"""Warm only selected image-entry ranges while the serial extractor keeps running."""
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import shutil
import zipfile
from ml.http_range import HttpRangeFile,RequestBudget,stored_member
from ml.prepare_training_corpus import ARCHIVE,PREFIX,published_partitions,select_lines


def entry_blocks(rows,names,base,block_size):
    ordered=sorted(rows,key=lambda row:row['offset'])
    ends={row['name']:following['offset'] for row,following in zip(ordered,ordered[1:])}
    indices=set()
    for row in ordered:
        if row['name'] not in names:continue
        if row['name'] not in ends or ends[row['name']]<=row['offset']:raise ValueError('Cannot bound source image entry')
        start=base+row['offset'];end=base+ends[row['name']]-1
        indices.update(range(start//block_size,end//block_size+1))
    if len([row for row in ordered if row['name'] in names])!=len(names):raise ValueError('Selected image entry is missing')
    return sorted(indices)


def main():
    root=Path('ml/data/bentham-training');source=Path('ml/data/bentham')
    provenance=json.loads((source/'source-provenance.json').read_text())
    if provenance['metadata']['license']['id']!='cc-by-4.0':raise ValueError('Unexpected source license')
    parts=published_partitions(json.loads(Path('ml/artifacts/training-source-inspection.json').read_text())['contents'])
    names={PREFIX+'Images/Lines/'+identifier+'.png' for split,limit in (('train',2048),('validation',192),('test',192))
           for identifier in select_lines(parts[split],limit,20261015)}
    rows=json.loads((source/'archive-index.json').read_text())[ARCHIVE]
    metadata=provenance['files'][0];block_size=1024*1024;budget=RequestBudget(2*1024**3)
    def reader():return HttpRangeFile(metadata['links']['self'],metadata['size'],block_size=block_size,cache_blocks=1,
                                     cache_directory=root/'ranges',max_download_bytes=32*1024*1024,request_budget=budget)
    probe=reader()
    with zipfile.ZipFile(probe) as archive:base=stored_member(archive,archive.getinfo(ARCHIVE)).offset
    blocks=entry_blocks(rows,names,base,block_size)
    def cached(index):
        path=probe.directory/f'{block_size}-{index}.bin'
        return path.is_file() and path.stat().st_size==min(block_size,metadata['size']-index*block_size)
    missing=[index for index in blocks if not cached(index)]
    if len(missing)*(block_size+1)>budget.maximum-budget.reserved:raise ValueError('Selected range plan exceeds the shared 2 GB request allowance')
    if shutil.disk_usage(root).free<len(missing)*block_size+2*1024**3:raise RuntimeError('Not enough free disk for the planned cache and extraction reserve')
    print(json.dumps({'stage':'plan','selectedImages':len(names),'totalBlocks':len(blocks),'missingBlocks':len(missing),
                      'plannedMiB':len(missing),'workers':4}),flush=True)
    def fetch(index):
        remote=reader();remote._block(index)
        return remote.bytes_downloaded
    transferred=0
    with ThreadPoolExecutor(max_workers=4) as pool:
        # Bound outstanding work to four requests. A failure never leaves a queue
        # of hundreds of downloads running after the caller has raised.
        for start in range(0,len(missing),4):
            futures=[pool.submit(fetch,index) for index in missing[start:start+4]]
            transferred+=sum(future.result() for future in futures)
            if start%100==0:print(json.dumps({'stage':'prefetch','completed':min(start+4,len(missing)),
                                             'total':len(missing),'downloadedBytes':transferred}),flush=True)
    report={'status':'passed','selectedImages':len(names),'blocks':len(blocks),'prefetchedBlocks':len(missing),
            'downloadedBytes':transferred,'requestBytesReserved':budget.reserved,'maximumRequestBytes':budget.maximum,
            'scope':'Verified byte-range cache only; image decode/CRC/manifest gates remain with the active extractor'}
    path=Path('ml/artifacts/training-prefetch.json');path.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
