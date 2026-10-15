"""Materialize a reproducible subset of the published Bentham baseline partitions."""
import argparse
from collections import Counter,defaultdict
import hashlib
import io
import json
from pathlib import Path
import random
import re
import shutil
import zipfile
from ml.http_range import HttpRangeFile,stored_member
from ml.manifest import read_manifest

ARCHIVE='ICDAR-HTR-Competition-2015/TRAIN/contestHTRtS.zip'
PREFIX='contestHTRtS/BenthamData/1stBatch/'


def published_partitions(contents):
    partitions={};documents={};seen=set()
    for split,title in (('train','Train'),('validation','Validation'),('test','Test')):
        identifiers=contents[PREFIX+'Partitions/'+title+'Lines.lst'].splitlines()
        page_ids=contents[PREFIX+'Partitions/'+title+'.lst'].splitlines()
        if not identifiers or len(page_ids)!=len(set(page_ids)):raise ValueError('Invalid published page partition')
        for identifier in identifiers:
            if not re.fullmatch(r'\d{3}_\d{3}_\d{3}_\d+_\d+',identifier):raise ValueError('Unexpected published line ID')
            if identifier in seen:raise ValueError('Duplicate line across published partitions')
            document='_'.join(identifier.split('_')[:3])
            if document not in page_ids:raise ValueError('Line does not belong to its declared page partition')
            if document in documents and documents[document]!=split:raise ValueError('Published document leakage')
            documents[document]=split;seen.add(identifier)
        partitions[split]=identifiers
    combined=contents[PREFIX+'Partitions/TrainValidationLines.lst'].splitlines()
    if len(combined)!=len(set(combined)) or set(combined)!=set(partitions['train']+partitions['validation']):
        raise ValueError('Published train-validation union does not match its separate partitions')
    return partitions


def select_lines(identifiers,limit,seed):
    """Round-robin over documents so one long page cannot dominate the subset."""
    if limit<1:raise ValueError('Line limit must be positive')
    if len(identifiers)!=len(set(identifiers)):raise ValueError('Duplicate source line')
    groups=defaultdict(list)
    for identifier in sorted(identifiers):groups['_'.join(identifier.split('_')[:3])].append(identifier)
    rng=random.Random(seed);documents=sorted(groups);rng.shuffle(documents)
    for group in groups.values():rng.shuffle(group)
    selected=[]
    while documents and len(selected)<limit:
        remaining=[]
        for document in documents:
            selected.append(groups[document].pop())
            if groups[document]:remaining.append(document)
            if len(selected)==limit:break
        documents=remaining
    return selected


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,default=Path('ml/data/bentham-training'))
    parser.add_argument('--train-lines',type=int,default=2048)
    parser.add_argument('--validation-lines',type=int,default=192)
    parser.add_argument('--test-lines',type=int,default=192)
    parser.add_argument('--seed',type=int,default=20261015)
    args=parser.parse_args()
    if min(args.train_lines,args.validation_lines,args.test_lines)<1:parser.error('Split limits must be positive')
    if shutil.disk_usage(Path.cwd()).free<4*1024**3:raise RuntimeError('Need at least 4 GB free for bounded source extraction')
    source=Path('ml/data/bentham')
    provenance=json.loads((source/'source-provenance.json').read_text())
    if provenance['id']!=248733 or provenance['metadata']['license']['id']!='cc-by-4.0':raise ValueError('Unexpected source identity or license')
    inspection=json.loads(Path('ml/artifacts/training-source-inspection.json').read_text())
    if inspection['archive']!=ARCHIVE:raise ValueError('Wrong source protocol')
    partitions=published_partitions(inspection['contents'])
    limits={'train':args.train_lines,'validation':args.validation_lines,'test':args.test_lines}
    selected={identifier:split for split,identifiers in partitions.items() for identifier in select_lines(identifiers,limits[split],args.seed)}
    rows=json.loads((source/'archive-index.json').read_text())[ARCHIVE]
    entries={row['name']:row for row in rows}
    for identifier in selected:
        for suffix in ('Images/Lines/'+identifier+'.png','Transcriptions/'+identifier+'.txt'):
            if PREFIX+suffix not in entries:raise ValueError('Published line has no matching image/transcript file')
    metadata=provenance['files'][0]
    remote=HttpRangeFile(metadata['links']['self'],metadata['size'],block_size=1024*1024,cache_blocks=8,
                         cache_directory=args.output/'ranges',max_download_bytes=1024*1024*1024)
    labels={};excluded={};manifest=[]
    from PIL import Image
    args.output.mkdir(parents=True,exist_ok=True);(args.output/'images').mkdir(exist_ok=True)
    with zipfile.ZipFile(remote) as outer:
        with zipfile.ZipFile(stored_member(outer,outer.getinfo(ARCHIVE))) as archive:
            # Read the small exact transcripts before fetching their much larger images.
            for identifier in sorted(selected,key=lambda key:entries[PREFIX+'Transcriptions/'+key+'.txt']['offset']):
                info=archive.getinfo(PREFIX+'Transcriptions/'+identifier+'.txt')
                if info.file_size>8192:raise ValueError('Unexpected transcript size')
                text=archive.read(info).decode('utf-8-sig').strip()
                reason='blank' if not text else 'multiline' if len(text.splitlines())!=1 else 'editorial-annotation' if any(c in text for c in '<>^_\u00ad') else 'too-long' if len(text)>180 else None
                if reason:excluded[identifier]=reason
                else:labels[identifier]=text
            print(json.dumps({'stage':'transcriptions','usable':len(labels),'excluded':dict(Counter(excluded.values()))}),flush=True)
            for index,identifier in enumerate(sorted(labels,key=lambda key:entries[PREFIX+'Images/Lines/'+key+'.png']['offset']),1):
                if index%50==1 and shutil.disk_usage(args.output).free<1024**3:
                    raise RuntimeError('Source extraction stopped with less than 1 GB free; verified cache is retained')
                info=archive.getinfo(PREFIX+'Images/Lines/'+identifier+'.png')
                if info.file_size>8*1024*1024:raise ValueError('Source line exceeds the image input bound')
                payload=archive.read(info)
                with Image.open(io.BytesIO(payload)) as image:
                    if image.width*image.height>4_000_000:raise ValueError('Source is not a bounded line image')
                    path=args.output/'images'/(identifier+'.png');temporary=path.with_suffix('.partial')
                    image.convert('RGB').save(temporary,format='PNG');temporary.replace(path)
                manifest.append({'id':identifier,'document_id':'_'.join(identifier.split('_')[:3]),'writer_id':None,
                                 'split':selected[identifier],'image':'images/'+path.name,'text':labels[identifier],
                                 'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'license':'CC-BY-4.0',
                                 'source_member':info.filename,'source_crc32':info.CRC})
                if index%50==0:print(json.dumps({'stage':'images','completed':index,'total':len(labels),'networkBytes':remote.bytes_downloaded}),flush=True)
    path=args.output/'manifest.jsonl'
    path.write_text('\n'.join(json.dumps(row,ensure_ascii=False) for row in manifest)+'\n')
    validated=read_manifest(path);counts=Counter(row['split'] for row in validated)
    if counts['train']<min(500,args.train_lines*.8) or min(counts['validation'],counts['test'])<min(50,args.validation_lines*.5,args.test_lines*.5):
        raise ValueError('Too few valid lines remain after transparent filtering')
    report={'status':'passed','manifest':str(path),'source':inspection['source'],'license':'CC-BY-4.0',
            'creators':provenance['metadata']['creators'],'splitCounts':dict(counts),'requestedLimits':limits,
            'publishedLineCounts':{k:len(v) for k,v in partitions.items()},'seed':args.seed,'excluded':excluded,
            'protocol':'Subset of published first-batch baseline partitions; not the full official benchmark',
            'transcriptionPolicy':'Exact provided line transcripts; preserve their punctuation spacing',
            'writerSeparated':False,'modernHandwritingValidated':False,'networkBytes':remote.bytes_downloaded,
            'sourceArchiveMd5Verified':False}
    (args.output/'dataset-report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v for k,v in report.items() if k!='excluded'},indent=2),flush=True)


if __name__=='__main__':main()
