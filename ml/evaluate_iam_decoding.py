"""Single validation-only beam comparison; never retrain or read the final test."""
import argparse
from datetime import datetime,timezone
import gc
import json
import os
from pathlib import Path
import time
from ml.artifacts import file_sha256,write_json_atomic
from ml.iam_protocol import LICENSE
from ml.manifest import read_manifest
from ml.metrics import evaluate


def validation_rows(rows):
    if not rows or any(r['split'] not in ('train','validation') for r in rows):
        raise ValueError('Only the selection manifest may enter decoding experiments')
    result=sorted((r for r in rows if r['split']=='validation'),key=lambda r:r['id'])
    if not result:raise ValueError('Validation data required')
    return result


def hashes(path):return {p.name:file_sha256(p) for p in sorted(Path(path).glob('*.safetensors'))}


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--corpus',type=Path,required=True);p.add_argument('--experiment',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True);p.add_argument('--policy',type=Path,required=True)
    a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve the previous decoding experiment')
    os.environ.setdefault('TOKENIZERS_PARALLELISM','false')
    os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK','1')
    a.output.mkdir(parents=True);report_path=a.output/'evaluation-report.json'
    policy=json.loads(a.policy.read_text())
    report=dict(status='running',stage='integrity-preflight',mode='validation-decoding',lines=881,numBeams=4,
                modelIndex=0,linesCompleted=0,totalPredictions=1762,completedPredictions=0,results=[],
                selectedStep=2000,trainingExecuted=False,weightsUpdated=False,finalTestRead=False,
                appReady=False,promotedToAndroid=False,policy=policy,policySha256=file_sha256(a.policy),
                startedAt=datetime.now(timezone.utc).isoformat(),sourceSha256=file_sha256(__file__),
                scope='One validation-only decoding comparison; existing v1 final test is not reused for selection')
    write_json_atomic(report_path,report)
    def event(e):
        with (a.output/'events.jsonl').open('a') as f:f.write(json.dumps(e)+'\n')
        print(json.dumps(e),flush=True)
    start=time.perf_counter()
    try:
        train=json.loads((a.experiment/'training-report.json').read_text())
        manifest=a.corpus/'selection-manifest.jsonl'
        if file_sha256(manifest)!=train['manifestSha256']:raise ValueError('Selection data changed')
        rows=validation_rows(read_manifest(manifest,required_splits=('train','validation'),allowed_licenses=(LICENSE,)))
        if len(rows)!=881:raise ValueError('Validation count changed')
        selected=Path(train['checkpoint']).resolve();baseline=Path(train['baselineCheckpoint']).resolve()
        for path in (baseline,selected):
            if not path.is_relative_to(a.experiment.resolve()):raise ValueError('Checkpoint path escapes experiment')
        frozen=[('original',baseline,hashes(baseline)),('fine-tuned',selected,hashes(selected))]
        if not all(h for _,_,h in frozen) or frozen[1][2]!=train['checkpointSha256']:raise ValueError('Checkpoint weights changed or missing')
        report.update(manifestSha256=file_sha256(manifest),frozenModels=[dict(model=k,path=str(v),sha256=h) for k,v,h in frozen],
                      greedyReferences=[dict(model='original',metrics=train['pretrainedValidation']),
                                        dict(model='fine-tuned',metrics=next(x['validation'] for x in train['training'] if x['step']==train['bestStep']))])
        import torch
        from PIL import Image
        from transformers import TrOCRProcessor,VisionEncoderDecoderModel
        device='cuda' if torch.cuda.is_available() else 'mps' if torch.backends.mps.is_available() else 'cpu'
        torch.manual_seed(20261015);report['device']=device
        references={r['id']:r['text'] for r in rows}
        for model_index,(label,path,pinned) in enumerate(frozen,1):
            report.update(stage='loading-'+label,model=label,modelIndex=model_index,linesCompleted=0)
            write_json_atomic(report_path,report)
            processor=TrOCRProcessor.from_pretrained(path,use_fast=False,local_files_only=True)
            model=VisionEncoderDecoderModel.from_pretrained(path,local_files_only=True).to(device).eval()
            model.requires_grad_(False);predictions={};begin=time.perf_counter()
            with torch.inference_mode():
                for i,row in enumerate(rows,1):
                    if len(processor.tokenizer(row['text']).input_ids)>128:raise ValueError('No reference truncation permitted')
                    with Image.open(row['image_path']) as image:
                        pixels=processor(images=image.convert('RGB'),return_tensors='pt').pixel_values.to(device)
                    tokens=model.generate(pixel_values=pixels,max_new_tokens=128,num_beams=4,length_penalty=1.,early_stopping=False)
                    predictions[row['id']]=processor.batch_decode(tokens,skip_special_tokens=True)[0]
                    if i%10==0 or i==len(rows):
                        report.update(stage='validating-'+label,linesCompleted=i,completedPredictions=(model_index-1)*len(rows)+i,
                                      elapsedSeconds=time.perf_counter()-start)
                        write_json_atomic(report_path,report)
                        event(dict(stage=report['stage'],model=label,linesCompleted=i,linesTotal=len(rows),numBeams=4))
            if hashes(path)!=pinned:raise ValueError('Frozen weights changed during decoding')
            write_json_atomic(a.output/(label+'-predictions.json'),predictions)
            result=dict(model=label,numBeams=4,metrics=evaluate(references,predictions),hostSecondsPerLine=(time.perf_counter()-begin)/len(rows))
            report['results'].append(result);write_json_atomic(report_path,report);event(result)
            del model,pixels,tokens;gc.collect()
            if device=='mps':torch.mps.empty_cache()
            elif device=='cuda':torch.cuda.empty_cache()
        candidates=[dict(x,numBeams=1) for x in report['greedyReferences']]+report['results']
        best=min(candidates,key=lambda x:(-x['metrics']['exactLineRate'],x['metrics']['cer']))
        report.update(status='completed',stage='completed',validationSelectedConfiguration=best,
                      validationExactLineTargetMet=best['metrics']['exactLineRate']>=policy['minimumExactLineRate'],
                      finalAcceptancePending=True,completedAt=datetime.now(timezone.utc).isoformat(),elapsedSeconds=time.perf_counter()-start)
        write_json_atomic(report_path,report);event(dict(stage='completed',best=best,appReady=False))
    except Exception as exc:
        report.update(status='failed',stage='failed',errorType=type(exc).__name__,error=str(exc))
        write_json_atomic(report_path,report);event(dict(stage='failed',error=str(exc)));raise


if __name__=='__main__':main()
