"""Read the published training protocol and sample labels without downloading its full archive."""
import json
from pathlib import Path
import zipfile
from ml.http_range import HttpRangeFile,stored_member


def main():
    source=Path('ml/data/bentham')
    provenance=json.loads((source/'source-provenance.json').read_text())
    if provenance['metadata']['license']['id']!='cc-by-4.0':raise ValueError('Source license changed')
    member='ICDAR-HTR-Competition-2015/TRAIN/contestHTRtS.zip'
    rows=json.loads((source/'archive-index.json').read_text())[member]
    names={row['name']:row for row in rows}
    prefix='contestHTRtS/BenthamData/1stBatch/'
    wanted=['contestHTRtS/README.txt',prefix+'README.txt',prefix+'Transcriptions/115_067_003_02_26.txt']
    wanted.extend(name for name in names if name.startswith(prefix+'Partitions/') and name.endswith('.lst'))
    metadata=provenance['files'][0]
    remote=HttpRangeFile(metadata['links']['self'],metadata['size'],block_size=1024*1024,cache_blocks=8,
                         cache_directory='ml/data/bentham-training-inspection/ranges',max_download_bytes=32*1024*1024)
    contents={}
    with zipfile.ZipFile(remote) as outer:
        with zipfile.ZipFile(stored_member(outer,outer.getinfo(member))) as archive:
            for name in sorted(wanted,key=lambda name:names[name]['offset']):
                info=archive.getinfo(name)
                if info.file_size>1024*1024:raise ValueError('Unexpectedly large protocol file')
                contents[name]=archive.read(info).decode('utf-8-sig')
    report={'source':'https://zenodo.org/records/248733','license':'CC-BY-4.0','archive':member,
            'networkBytes':remote.bytes_downloaded,'contents':contents,'trainingExecuted':False}
    output=Path('ml/artifacts/training-source-inspection.json');output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,indent=2)+'\n')
    for name,text in contents.items():
        print(json.dumps({'file':name,'lines':len(text.splitlines()),'excerpt':text[:1800]}),flush=True)
    print(json.dumps({'report':str(output),'networkBytes':remote.bytes_downloaded}),flush=True)


if __name__=='__main__':main()
