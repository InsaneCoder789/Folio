"""Compare fixed recognizers on visually admitted modern validation lines, without training."""
import argparse
import gc
import hashlib
import json
import os
from pathlib import Path
import re
import time
from ml.artifacts import write_json_atomic
from ml.manifest import read_manifest
from ml.metrics import evaluate


def admitted_samples(manifest,review,*,split='validation'):
    if split not in ('train','validation'):raise ValueError('Visual admission only supports declared train or validation data')
    rows=read_manifest(manifest,required_splits=(split,),allowed_licenses=('CC-BY-SA-4.0',))
    approval=json.loads(Path(review).read_text())
    if approval.get('candidateManifestSha256')!=hashlib.sha256(Path(manifest).read_bytes()).hexdigest():raise ValueError('Visual review belongs to a different candidate manifest')
    if not approval.get('reviewer') or approval.get('scope')!='English and supplied-transcription visual review':raise ValueError('Missing visual review provenance')
    boards=approval.get('evidenceBoardsSha256',{})
    expected={row['document_id']+'.png' for row in rows}
    if not isinstance(boards,dict) or set(boards)!=expected:raise ValueError('Visual review must retain every source-document board')
    board_root=(Path(manifest).parent/'review-boards').resolve()
    for name,digest in boards.items():
        path=(board_root/name).resolve()
        if path.parent!=board_root or not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest()!=digest:
            raise ValueError('Visual review board is missing, changed or outside its evidence directory')
    decisions=approval.get('decisions',[]);by_id={}
    for decision in decisions:
        identifier=decision.get('id')
        if identifier in by_id or decision.get('decision') not in ('accept','exclude') or not decision.get('reason'):raise ValueError('Invalid or duplicate visual review decision')
        by_id[identifier]=decision
    if set(by_id)!={row['id'] for row in rows}:raise ValueError('Every candidate must be reviewed before evaluation')
    accepted=[row for row in rows if by_id[row['id']]['decision']=='accept']
    if not accepted:raise ValueError('No visually accepted English lines')
    return accepted,approval


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--manifest',type=Path,default=Path('ml/data/modern-validation/review-candidates.jsonl'))
    parser.add_argument('--review',type=Path,default=Path('ml/data/modern-validation/visual-review.json'))
    parser.add_argument('--output',type=Path,default=Path('ml/artifacts/modern-validation-evaluation'))
    parser.add_argument('--checkpoint',type=Path,action='append',default=[])
    parser.add_argument('--foundation',choices=('small','base'),default='small')
    args=parser.parse_args();rows,review=admitted_samples(args.manifest,args.review)
    if (args.output/'evaluation-report.json').exists():raise FileExistsError('Use a fresh evaluation output directory')
    os.environ.setdefault('HF_HOME',str(Path.home()/'.cache/folio-handwriting-models'))
    os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK','1')
    os.environ.setdefault('TOKENIZERS_PARALLELISM','false')
    import torch
    from PIL import Image
    from transformers import TrOCRProcessor,VisionEncoderDecoderModel
    device='mps' if torch.backends.mps.is_available() else 'cpu'
    source,revision=(('microsoft/trocr-small-handwritten','b4648cfa171985a6745f37ddd637e98c0da958ac') if args.foundation=='small'
                     else ('microsoft/trocr-base-handwritten','eaacaf452b06415df8f10bb6fad3a4c11e609406'))
    models=[('pretrained' if args.foundation=='small' else 'pretrained-base',source,revision)]
    artifact_root=(Path(__file__).resolve().parent/'artifacts').resolve()
    for checkpoint in args.checkpoint:
        path=checkpoint.resolve()
        if not path.is_dir() or not path.is_relative_to(artifact_root):raise ValueError('Use a local Folio experimental checkpoint')
        label=path.parent.name
        if not re.fullmatch(r'[a-z0-9][a-z0-9_-]*',label):raise ValueError('Unsafe model label')
        models.append((label,str(path),None))
    if len({label for label,path,revision in models})!=len(models):raise ValueError('Duplicate model labels')
    args.output.mkdir(parents=True,exist_ok=True);results=[]
    references={row['id']:row['text'] for row in rows}
    for label,source,version in models:
        kwargs={'revision':version} if version else {}
        processor=TrOCRProcessor.from_pretrained(source,use_fast=False,**kwargs)
        for row in rows:
            if len(processor.tokenizer(row['text']).input_ids)>128:raise ValueError('Reference exceeds the declared generation bound')
        model=VisionEncoderDecoderModel.from_pretrained(source,**kwargs).to(device).eval()
        predictions={};start=time.perf_counter()
        with torch.inference_mode():
            for index,row in enumerate(rows,1):
                with Image.open(row['image_path']) as image:pixels=processor(images=image.convert('RGB'),return_tensors='pt').pixel_values.to(device)
                tokens=model.generate(pixels,max_new_tokens=128,num_beams=1)
                predictions[row['id']]=processor.batch_decode(tokens,skip_special_tokens=True)[0]
                if index%10==0:print(json.dumps({'model':label,'linesCompleted':index,'linesTotal':len(rows)}),flush=True)
        seconds=time.perf_counter()-start;score=evaluate(references,predictions)
        casefolded=evaluate({key:value.casefold() for key,value in references.items()},
                            {key:value.casefold() for key,value in predictions.items()})
        casefolded['normalization']='NFC, collapse whitespace, casefold, preserve punctuation'
        write_json_atomic(args.output/(label+'-predictions.json'),predictions)
        hashes={}
        if version is None:
            for path in Path(source).glob('*.safetensors'):
                with path.open('rb') as stream:hashes[path.name]=hashlib.file_digest(stream,'sha256').hexdigest()
        result={'model':label,'source':source,'revision':version,'checkpointSha256':hashes,'metrics':score,
                'casefoldedDiagnosticMetrics':casefolded,
                'hostSecondsPerLine':seconds/len(rows),'predictions':label+'-predictions.json'}
        results.append(result);print(json.dumps(result),flush=True)
        del model,pixels,tokens;gc.collect()
        if device=='mps':torch.mps.empty_cache()
    report={'status':'completed','split':'validation','license':'CC-BY-SA-4.0','lines':len(rows),
            'evaluationSourceSha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'documents':len({row['document_id'] for row in rows}),'manifestSha256':hashlib.sha256(args.manifest.read_bytes()).hexdigest(),
            'reviewSha256':hashlib.sha256(args.review.read_bytes()).hexdigest(),'device':device,'results':results,
            'trainingExecuted':False,'promotedToAndroid':False,'scope':'Visually admitted development-validation subset; not the full benchmark or an Android performance test'}
    write_json_atomic(args.output/'evaluation-report.json',report);print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
