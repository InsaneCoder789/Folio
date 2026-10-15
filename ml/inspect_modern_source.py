"""Inspect pinned HierText labels by their declared training/validation role."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import urllib.request

REVISION='70b6620b2b112597d8219e11eee9773a1403827c'
SOURCE='https://github.com/google-research-datasets/hiertext'
SPLITS={'validation':{'annotationBytes':12274736,'images':1724,'archiveBytes':577088394,'expandedBudget':512*1024*1024},
        'train':{'annotationBytes':59214216,'images':8281,'archiveBytes':2798458845,'expandedBudget':2*1024**3}}


class BoundedReader:
    def __init__(self,source,maximum):self.source=source;self.maximum=maximum;self.bytes_read=0
    def read(self,size=-1):
        size=65536 if size<0 else size
        data=self.source.read(min(size,self.maximum-self.bytes_read+1))
        self.bytes_read+=len(data)
        if self.bytes_read>self.maximum:raise ValueError('Annotation expansion exceeds the total streaming budget')
        return data


def handwriting_lines(annotation):
    result=[]
    for paragraph in annotation.get('paragraphs',[]):
        for line in paragraph.get('lines',[]):
            words=line.get('words',[]);text=line.get('text','')
            if line.get('handwritten') is not True or line.get('legible') is not True or line.get('vertical') is not False:continue
            if not words or any(word.get('handwritten') is not True or word.get('legible') is not True or word.get('vertical') is not False for word in words):continue
            if not isinstance(text,str) or not 2<=len(text)<=180 or not all(32<=ord(c)<=126 for c in text) or not any(c.isalpha() for c in text):continue
            result.append({'text':text,'vertices':line['vertices']})
    return result


def download(url,maximum):
    request=urllib.request.Request(url,headers={'User-Agent':'Folio-handwriting-research/1.0'})
    with urllib.request.urlopen(request,timeout=30) as response:
        data=response.read(maximum+1)
    if len(data)>maximum:raise ValueError('Source exceeds the configured read limit')
    return data


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--split',choices=tuple(SPLITS),default='validation');args=parser.parse_args()
    split=args.split;config=SPLITS[split]
    root=Path('ml/data/hiertext-inspection');root.mkdir(parents=True,exist_ok=True)
    metadata=json.loads(download(f'https://api.github.com/repos/google-research-datasets/hiertext/contents/gt/{split}.jsonl.gz?ref={REVISION}',64*1024))
    if metadata['size']!=config['annotationBytes']:raise ValueError('Pinned annotation size differs from inspected source')
    path=root/(split+'.jsonl.gz')
    payload=path.read_bytes() if path.exists() else download(f'https://raw.githubusercontent.com/google-research-datasets/hiertext/{REVISION}/gt/{split}.jsonl.gz',64*1024*1024)
    digest=hashlib.sha1(b'blob '+str(len(payload)).encode()+b'\0'+payload).hexdigest()
    if digest!=metadata['sha']:raise ValueError('Annotation bytes do not match the pinned Git blob')
    if not path.exists():path.write_bytes(payload)
    import ijson
    candidates=[];line_count=0;image_count=0
    with gzip.open(path,'rb') as stream:
        bounded=BoundedReader(stream,config['expandedBudget'])
        for annotation in ijson.items(bounded,'annotations.item',use_float=True):
            image_count+=1
            lines=handwriting_lines(annotation);line_count+=len(lines)
            if lines:candidates.append({'imageId':annotation['image_id'],'width':annotation['image_width'],
                                        'height':annotation['image_height'],'lines':lines})
    if image_count!=config['images']:raise ValueError('Pinned image count differs from the published split protocol')
    report={'status':'completed','source':SOURCE,'revision':REVISION,'license':'CC-BY-SA-4.0',
            'split':split,'annotationSha256':hashlib.sha256(payload).hexdigest(),'gitBlobSha1':digest,
            'compressedBytes':len(payload),'expandedBytesRead':bounded.bytes_read,'imageCount':image_count,
            'eligibleHandwrittenImages':len(candidates),'eligibleHandwrittenLines':line_count,'candidates':candidates,
            'languageBoundary':'ASCII Latin-script candidates; English is not verified by this filter',
            'imagesDownloaded':False,'trainingExecuted':False,'accuracyMeasured':False,
            'scope':'Separate modern-scene handwriting '+split+' candidate; language and image admission pending'}
    output=Path('ml/artifacts')/('modern-source-inspection.json' if split=='validation' else 'modern-training-source-inspection.json');output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({key:value for key,value in report.items() if key!='candidates'},indent=2),flush=True)


if __name__=='__main__':main()
