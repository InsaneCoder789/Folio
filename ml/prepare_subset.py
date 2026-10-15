"""Materialize a bounded document-disjoint handwriting pilot from published line images and transcripts."""
import argparse
from collections import Counter,defaultdict
import hashlib
import json
from pathlib import Path
import random
import re
import zipfile
from ml.http_range import HttpRangeFile,stored_member
from ml.manifest import read_manifest

ARCHIVE='ICDAR-HTR-Competition-2015/TEST/12-pages-dataset.zip'


def labels_from_text(text):
    labels={};excluded=Counter();seen=set()
    for line in text.splitlines():
        if not line.strip():continue
        if '\t' not in line:raise ValueError('Expected published ID-tab-transcription rows')
        identifier,transcript=line.split('\t',1)
        transcript=transcript.strip()
        if not re.match(r'^\d{3}_\d{3}_\d{3}_',identifier):raise ValueError('Unexpected source line identifier')
        if identifier in seen:raise ValueError('Duplicate source transcription ID')
        seen.add(identifier)
        if not transcript:excluded['blank']+=1;continue
        if '<' in transcript or '>' in transcript or '^' in transcript or '_' in transcript or '\u00ad' in transcript:
            excluded['ambiguous-editorial-annotation']+=1;continue
        if len(transcript)>180:excluded['longer-than-pilot-bound']+=1;continue
        labels[identifier]=transcript
    return labels,dict(excluded)


def assign_documents(identifiers,seed):
    documents=sorted({'_'.join(identifier.split('_')[:3]) for identifier in identifiers})
    if len(documents)<8:raise ValueError('Pilot needs at least eight source documents')
    random.Random(seed).shuffle(documents)
    held=max(2,round(len(documents)*.17))
    result={}
    for index,document in enumerate(documents):
        result[document]='validation' if index<held else 'test' if index<held*2 else 'train'
    return result


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--source',type=Path,default=Path('ml/data/bentham'))
    parser.add_argument('--output',type=Path,default=Path('ml/data/bentham-pilot'))
    parser.add_argument('--max-lines-per-document',type=int,default=30)
    parser.add_argument('--seed',type=int,default=20261015)
    args=parser.parse_args()
    if args.max_lines_per_document<1:parser.error('Line bound must be positive')
    provenance=json.loads((args.source/'source-provenance.json').read_text())
    if provenance['id']!=248733 or provenance['metadata']['license']['id']!='cc-by-4.0':raise ValueError('Unexpected dataset identity or license')
    labels,excluded=labels_from_text((args.source/'12-pages-dataset-reference_v2.dat').read_text(encoding='utf-8-sig'))
    index=json.loads((args.source/'archive-index.json').read_text())[ARCHIVE]
    images={Path(row['name']).stem:row for row in index if row['name'].lower().endswith(('.png','.jpg','.jpeg','.tif','.tiff')) and Path(row['name']).stem in labels}
    if len(images)<len(labels)*.8:raise ValueError('Published line IDs do not match image filenames; refusing inferred labels')
    document_splits=assign_documents(images,args.seed)
    grouped=defaultdict(list)
    for identifier in sorted(images):grouped['_'.join(identifier.split('_')[:3])].append(identifier)
    selected=[];rng=random.Random(args.seed)
    for ids in grouped.values():
        selected.extend(rng.sample(ids,min(len(ids),args.max_lines_per_document)))
    selected.sort(key=lambda key:images[key]['offset'])
    args.output.mkdir(parents=True,exist_ok=True);(args.output/'images').mkdir(exist_ok=True)
    metadata=provenance['files'][0]
    remote=HttpRangeFile(metadata['links']['self'],metadata['size'],block_size=1024*1024,cache_blocks=8,cache_directory=args.source/'ranges',max_download_bytes=256*1024*1024)
    rows=[]
    from PIL import Image
    import io
    with zipfile.ZipFile(remote) as outer:
        with zipfile.ZipFile(stored_member(outer,outer.getinfo(ARCHIVE))) as archive:
            for position,identifier in enumerate(selected,1):
                info=archive.getinfo(images[identifier]['name'])
                if info.file_size>8*1024*1024:raise ValueError('Source line image exceeds the bounded decoder input')
                payload=archive.read(info)  # ZipFile checks this member's CRC.
                with Image.open(io.BytesIO(payload)) as source:
                    if source.width*source.height>4_000_000:raise ValueError('Source is not a bounded line image')
                    image=source.convert('RGB')
                    path=args.output/'images'/(identifier+'.png')
                    temporary=path.with_suffix('.partial')
                    image.save(temporary,format='PNG');temporary.replace(path)
                document='_'.join(identifier.split('_')[:3])
                rows.append({'id':identifier,'document_id':document,'writer_id':None,'split':document_splits[document],
                             'image':'images/'+path.name,'text':labels[identifier],'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),
                             'license':'CC-BY-4.0','source_member':info.filename,'source_crc32':info.CRC})
                if position%25==0:print(json.dumps({'materialized':position,'total':len(selected),'networkBytes':remote.bytes_downloaded}),flush=True)
    manifest=args.output/'manifest.jsonl'
    manifest.write_text('\n'.join(json.dumps(row,ensure_ascii=False) for row in rows)+'\n')
    validated=read_manifest(manifest)
    counts=Counter(row['split'] for row in validated)
    if counts['train']<50 or min(counts['validation'],counts['test'])<10:raise ValueError('Pilot split is too small for the planned experiment')
    report={'status':'passed','manifest':str(manifest),'splitCounts':dict(counts),'documents':document_splits,
            'excludedTranscriptions':excluded,'networkBytes':remote.bytes_downloaded,'license':'CC-BY-4.0',
            'source':'https://zenodo.org/records/248733','sourceTitle':provenance['metadata']['title'],
            'creators':provenance['metadata']['creators'],
            'splitProtocol':'Folio pilot document-disjoint partition of published line data; NOT the official competition train/test protocol',
            'writerSeparated':False,'writerBoundary':'Writer labels unavailable; do not claim writer-disjoint accuracy',
            'modernHandwritingValidated':False,'sourceArchiveMd5Verified':False}
    (args.output/'dataset-report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
