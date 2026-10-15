"""Prepare a review subset from the declared official HierText training or validation split."""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import random
import re
import shutil
import tarfile
import urllib.request
from ml.artifacts import write_json_atomic
from ml.inspect_modern_source import REVISION,SPLITS

COMMON={'the','and','of','to','in','is','for','this','with','my','your','you','are','a','on','we','our','be','that','it'}


def require_unprepared(root):
    if any((Path(root)/name).exists() for name in ('review-candidates.jsonl','preparation-report.json','visual-review.json')):
        raise FileExistsError('Preserve the existing prepared/reviewed corpus; use a separately versioned dataset')


def select_review_images(candidates,count,excluded=()):
    if len({row['imageId'] for row in candidates})!=len(candidates):raise ValueError('Duplicate source photograph ID')
    available=sorted((row for row in candidates if row['imageId'] not in set(excluded)),key=lambda row:row['imageId'])
    if count<1 or len(available)<count:raise ValueError('Not enough unused source images match the review sampling rule')
    return random.Random(20261015).sample(available,count)


def english_review_candidate(text):
    """A sampling heuristic only; actual English acceptance needs visual review."""
    words=re.findall(r'[a-z]+',text.lower())
    return len(words)>=5 and sum(word in COMMON for word in words)>=2


def crop_geometry(vertices,width,height):
    if len(vertices)!=4:raise ValueError('Need a four-corner line rectangle')
    points=[(float(x),float(y)) for x,y in vertices]
    if any(not math.isfinite(x) or not math.isfinite(y) or not 0<=x<=width or not 0<=y<=height for x,y in points):raise ValueError('Line polygon leaves the source image')
    cx=sum(x for x,y in points)/4;cy=sum(y for x,y in points)/4
    points.sort(key=lambda p:math.atan2(p[1]-cy,p[0]-cx))
    first=min(range(4),key=lambda i:sum(points[i]));points=points[first:]+points[:first]
    for i in range(4):
        a,b,c=points[i],points[(i+1)%4],points[(i+2)%4]
        if (b[0]-a[0])*(c[1]-b[1])-(b[1]-a[1])*(c[0]-b[0])<=0:raise ValueError('Line polygon is not a convex rectangle')
    angle=math.degrees(math.atan2(points[1][1]-points[0][1],points[1][0]-points[0][0]))
    if abs(angle)>35:raise ValueError('Line orientation is outside the initial review scope')
    length=lambda a,b:math.hypot(a[0]-b[0],a[1]-b[1])
    w=round((length(points[0],points[1])+length(points[3],points[2]))/2)
    h=round((length(points[0],points[3])+length(points[1],points[2]))/2)
    if w<16 or h<8 or w<h:raise ValueError('Line crop is too small or vertical')
    # Pillow QUAD uses upper-left, lower-left, lower-right, upper-right.
    quad=tuple(coordinate for index in (0,3,2,1) for coordinate in points[index])
    return (w,h),quad


def obtain_archive(root,split):
    expected_bytes=SPLITS[split]['archiveBytes'];url=f'https://open-images-dataset.s3.amazonaws.com/ocr/{split}.tgz'
    target=root/(split+'.tgz');record=root/'archive-provenance.json'
    if target.exists() and record.exists():
        metadata=json.loads(record.read_text())
        with target.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
        if target.stat().st_size==expected_bytes and digest==metadata['sha256'] and metadata['url']==url:return target
        raise ValueError('Cached modern image archive failed its recorded integrity check')
    if shutil.disk_usage(root).free<max(2*1024**3,expected_bytes*2+1024**3):raise RuntimeError('Need sufficient free disk for the source archive and preparation reserve')
    request=urllib.request.Request(url,method='HEAD')
    with urllib.request.urlopen(request,timeout=30) as response:
        size=int(response.headers['Content-Length']);etag=response.headers.get('ETag');modified=response.headers.get('Last-Modified')
    if size!=expected_bytes:raise ValueError('Official image archive size changed; inspect before proceeding')
    headers={'If-Match':etag} if etag else {}
    partial=target.with_suffix('.partial');total=0;digest=hashlib.sha256()
    try:
        with urllib.request.urlopen(urllib.request.Request(url,headers=headers),timeout=30) as response,partial.open('wb') as out:
            if int(response.headers.get('Content-Length','0'))!=size:raise ValueError('Unexpected archive response size')
            while True:
                chunk=response.read(min(256*1024,size-total+1))
                if not chunk:break
                total+=len(chunk)
                if total>size:raise ValueError('Archive transfer exceeded its bound')
                out.write(chunk);digest.update(chunk)
                if total%(32*1024*1024)==0:print(json.dumps({'stage':'archive','bytes':total,'total':size}),flush=True)
        if total!=size:raise ValueError('Incomplete modern image archive')
        partial.replace(target)
        write_json_atomic(record,{'url':url,'split':split,'bytes':size,'etag':etag,'lastModified':modified,'sha256':digest.hexdigest(),
                                  'upstreamCryptographicChecksumVerified':False,'license':'CC-BY-SA-4.0'})
        return target
    finally:partial.unlink(missing_ok=True)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--images',type=int);parser.add_argument('--split',choices=tuple(SPLITS),default='validation')
    parser.add_argument('--output',type=Path,help='Fresh versioned directory inside ml/data')
    parser.add_argument('--archive-root',type=Path,help='Existing same-role archive cache; files are reverified')
    parser.add_argument('--exclude-preparation',type=Path,action='append',default=[],help='Exclude every photograph in an earlier same-role preparation')
    args=parser.parse_args()
    if args.images is None:args.images=8 if args.split=='validation' else 32
    if not 1<=args.images<=(24 if args.split=='validation' else 64):parser.error('Image count exceeds the declared initial review scope')
    root=args.output or Path('ml/data')/('modern-validation' if args.split=='validation' else 'modern-training')
    data_root=(Path(__file__).resolve().parent/'data').resolve()
    if not root.resolve().is_relative_to(data_root) or root.resolve()==data_root:raise ValueError('Keep versioned prepared data inside ml/data')
    archive_root=args.archive_root or root
    if not archive_root.resolve().is_relative_to(data_root):raise ValueError('Archive cache must remain inside ml/data')
    require_unprepared(root)
    inspection_path=Path('ml/artifacts')/('modern-source-inspection.json' if args.split=='validation' else 'modern-training-source-inspection.json')
    inspection=json.loads(inspection_path.read_text())
    if inspection['revision']!=REVISION or inspection['split']!=args.split or inspection['license']!='CC-BY-SA-4.0':raise ValueError('Unexpected modern annotation provenance')
    candidates=[]
    for image in inspection['candidates']:
        lines=[line for line in image['lines'] if english_review_candidate(line['text'])]
        if lines:candidates.append(dict(image,lines=lines))
    excluded_photos=set();exclusion_hashes={}
    for previous in args.exclude_preparation:
        preparation=json.loads(previous.read_text())
        if preparation.get('status')!='prepared-for-review' or preparation.get('split')!=args.split or preparation.get('revision')!=REVISION or preparation.get('license')!='CC-BY-SA-4.0':
            raise ValueError('Exclude only completed preparations from the same pinned source role')
        ids=preparation.get('selectedImageIds',[])
        if not ids or any(not re.fullmatch('[0-9a-f]{16}',identifier) for identifier in ids):raise ValueError('Invalid excluded source photographs')
        excluded_photos.update(ids);exclusion_hashes[str(previous)]=hashlib.sha256(previous.read_bytes()).hexdigest()
    selected=select_review_images(candidates,args.images,excluded_photos)
    by_id={row['imageId']:row for row in selected}
    if any(not re.fullmatch('[0-9a-f]{16}',key) for key in by_id):raise ValueError('Unsafe source image ID')
    root.mkdir(parents=True,exist_ok=True)
    for folder in ('pages','lines'):(root/folder).mkdir(exist_ok=True)
    archive=obtain_archive(archive_root,args.split);rows=[];found=set();excluded=[]
    from PIL import Image
    with tarfile.open(archive,'r:gz') as source:
        for member in source:
            identifier=Path(member.name).stem
            if identifier not in by_id or identifier in found or not member.isfile() or not member.name.lower().endswith('.jpg'):continue
            if member.size>20*1024*1024:raise ValueError('Source image exceeds input bound')
            stream=source.extractfile(member)
            if stream is None:raise ValueError('Cannot read selected image entry')
            with stream:payload=stream.read(20*1024*1024+1)
            if len(payload)!=member.size:raise ValueError('Incomplete source image entry')
            metadata=by_id[identifier]
            with Image.open(io.BytesIO(payload)) as original:
                if original.size!=(metadata['width'],metadata['height']) or original.width*original.height>4_000_000:raise ValueError('Image and annotation dimensions disagree')
                image=original.convert('RGB');image.load()
            page=root/'pages'/(identifier+'.jpg');page.write_bytes(payload);found.add(identifier)
            for position,line in enumerate(metadata['lines'][:10]):
                try:size,quad=crop_geometry(line['vertices'],image.width,image.height)
                except ValueError as error:excluded.append({'imageId':identifier,'text':line['text'],'reason':str(error)});continue
                identifier_line=identifier+f'-{position:02}'
                path=root/'lines'/(identifier_line+'.png')
                with image.transform(size,Image.Transform.QUAD,quad,resample=Image.Resampling.BICUBIC) as crop:crop.save(path)
                rows.append({'id':identifier_line,'document_id':identifier,'split':args.split,'image':'lines/'+path.name,
                             'text':line['text'],'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'license':'CC-BY-SA-4.0',
                             'source_image':'pages/'+page.name,'source_image_sha256':hashlib.sha256(payload).hexdigest(),
                             'source_vertices':line['vertices'],'englishVisualReview':'pending'})
            image.close()
            if found==set(by_id):break
    if found!=set(by_id) or not rows:raise ValueError('Selected image entries or usable line crops are missing')
    manifest=root/'review-candidates.jsonl';manifest.write_text('\n'.join(json.dumps(row,ensure_ascii=False) for row in rows)+'\n')
    report={'status':'prepared-for-review','source':inspection['source'],'revision':REVISION,'license':'CC-BY-SA-4.0',
            'sourceAnnotationSha256':inspection['annotationSha256'],'split':args.split,'seed':20261015,
            'selectedImageIds':sorted(found),'candidateLines':len(rows),'excluded':excluded,'manifest':str(manifest),
            'excludedPreparationSha256':exclusion_hashes,'excludedSourcePhotographs':len(excluded_photos),
            'englishVerified':False,'accuracyMeasured':False,'trainingExecuted':False,
            'scope':'Separate deterministic '+args.split+' review subset; not the official full benchmark'}
    write_json_atomic(root/'preparation-report.json',report);print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
