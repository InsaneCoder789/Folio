"""One fixed IAM test comparison; no gradients, selection, tuning or promotion."""
import argparse
from datetime import datetime, timezone
import gc
import json
import os
from pathlib import Path
import time
from ml.artifacts import file_sha256, write_json_atomic
from ml.iam_protocol import LICENSE, POLICY, promotion
from ml.manifest import read_manifest
from ml.metrics import evaluate


def test_rows(rows):
    selected = [r for r in rows if r['split'] == 'test']
    if not selected or not all(r.get('writer_id') for r in selected):
        raise ValueError('Need nonempty test data with writer identities')
    return sorted(selected, key=lambda r:r['id'])


def model_hashes(path):
    files = sorted(Path(path).glob('*.safetensors'))
    if not files:
        raise ValueError('Missing frozen weights')
    return {p.name:file_sha256(p) for p in files}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--experiment', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        raise FileExistsError('Never overwrite or silently repeat a final test')
    os.environ.setdefault('TOKENIZERS_PARALLELISM','false')
    os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK','1')
    args.output.mkdir(parents=True)
    report_path = args.output/'evaluation-report.json'
    report = dict(status='running', stage='integrity-preflight', startedAt=datetime.now(timezone.utc).isoformat(),
                  mode='test', lines=1028, modelsTotal=2, modelIndex=0, linesCompleted=0,
                  totalPredictions=2056, completedPredictions=0, results=[],
                  trainingExecuted=False, weightsUpdated=False, checkpointSelectionFrozen=True,
                  policy=POLICY, promotedToAndroid=False, appReady=False,
                  scope='Our reserved custom writer-separated IAM test; foundation prior IAM exposure remains a caveat',
                  evaluationSourceSha256=file_sha256(__file__))
    write_json_atomic(report_path,report)

    def event(value):
        with (args.output/'events.jsonl').open('a') as stream:
            stream.write(json.dumps(value)+'\n')
        print(json.dumps(value),flush=True)

    started=time.perf_counter()
    try:
        train=json.loads((args.experiment/'training-report.json').read_text())
        gate=json.loads((args.experiment/'validation-quality-gate.json').read_text())
        preparation=json.loads((args.corpus/'preparation-report.json').read_text())
        if train['status']!='passed' or not train['testDeferred']:
            raise ValueError('Require a completed validation-selected run with final test deferred')
        if file_sha256(args.corpus/'manifest.jsonl') != preparation['manifestSha256']:
            raise ValueError('Full corpus manifest changed')
        if file_sha256(args.corpus/'selection-manifest.jsonl') != train['manifestSha256']:
            raise ValueError('Selection manifest changed')
        rows=test_rows(read_manifest(args.corpus/'manifest.jsonl',allowed_licenses=(LICENSE,)))
        if len(rows)!=1028:
            raise ValueError('Reserved test count changed')
        selected=Path(train['checkpoint']).resolve();baseline=Path(train['baselineCheckpoint']).resolve()
        for path in (selected,baseline):
            if not path.is_relative_to(args.experiment.resolve()):
                raise ValueError('Checkpoint outside selected experiment')
        frozen=[('original',baseline,model_hashes(baseline)),('fine-tuned',selected,model_hashes(selected))]
        if frozen[1][2]!=train['checkpointSha256']:
            raise ValueError('Selected weights changed after validation selection')
        report.update(lines=len(rows),totalPredictions=2*len(rows),selectedStep=train['bestStep'],
                      trainingReportSha256=file_sha256(args.experiment/'training-report.json'),
                      testManifestSha256=preparation['manifestSha256'],validationGatePassed=gate['lineGatePassed'],
                      frozenModels=[dict(model=label,path=str(path),sha256=hashes) for label,path,hashes in frozen])
        write_json_atomic(report_path,report)
        import torch
        from PIL import Image
        from transformers import TrOCRProcessor, VisionEncoderDecoderModel
        device='cuda' if torch.cuda.is_available() else 'mps' if torch.backends.mps.is_available() else 'cpu'
        torch.manual_seed(20261015)
        references={r['id']:r['text'] for r in rows};all_predictions={}
        report['device']=device
        for model_index,(label,path,hashes) in enumerate(frozen,1):
            report.update(stage='loading-'+label,model=label,modelIndex=model_index,linesCompleted=0)
            write_json_atomic(report_path,report)
            event(dict(stage=report['stage'],model=label,linesCompleted=0,linesTotal=len(rows)))
            processor=TrOCRProcessor.from_pretrained(path,use_fast=False,local_files_only=True)
            model=VisionEncoderDecoderModel.from_pretrained(path,local_files_only=True).to(device).eval()
            model.requires_grad_(False)
            for row in rows:
                if len(processor.tokenizer(row['text']).input_ids)>128:
                    raise ValueError('Test reference exceeds frozen generation bound')
            predictions={};begin=time.perf_counter()
            with torch.inference_mode():
                for index,row in enumerate(rows,1):
                    with Image.open(row['image_path']) as image:
                        pixels=processor(images=image.convert('RGB'),return_tensors='pt').pixel_values.to(device)
                    tokens=model.generate(pixel_values=pixels,max_new_tokens=128,num_beams=1)
                    predictions[row['id']]=processor.batch_decode(tokens,skip_special_tokens=True)[0]
                    if index%10==0 or index==len(rows):
                        report.update(stage='testing-'+label,linesCompleted=index,
                                      completedPredictions=(model_index-1)*len(rows)+index,
                                      elapsedSeconds=time.perf_counter()-started)
                        write_json_atomic(report_path,report)
                        event(dict(stage=report['stage'],model=label,linesCompleted=index,linesTotal=len(rows),
                                   completedPredictions=report['completedPredictions'],totalPredictions=2*len(rows)))
            score=evaluate(references,predictions)
            if model_hashes(path)!=hashes:
                raise ValueError('Frozen checkpoint changed during evaluation')
            write_json_atomic(args.output/(label+'-predictions.json'),predictions)
            result=dict(model=label,metrics=score,hostSecondsPerLine=(time.perf_counter()-begin)/len(rows),
                        checkpointHashesVerified=True,weightsUpdated=False)
            report['results'].append(result);all_predictions[label]=predictions
            write_json_atomic(report_path,report);event(result)
            del model,pixels,tokens;gc.collect()
            if device=='mps':torch.mps.empty_cache()
            elif device=='cuda':torch.cuda.empty_cache()
        comparison=promotion(references,all_predictions['fine-tuned'],all_predictions['original'],True)
        report.update(status='completed',stage='completed',comparison=comparison,
                      combinedLineGatePassed=gate['lineGatePassed'] and comparison['lineGatePassed'],
                      completedAt=datetime.now(timezone.utc).isoformat(),elapsedSeconds=time.perf_counter()-started)
        write_json_atomic(report_path,report);event(dict(stage='completed',comparison=comparison,appReady=False))
    except Exception as exc:
        report.update(status='failed',stage='failed',errorType=type(exc).__name__,error=str(exc))
        write_json_atomic(report_path,report);event(dict(stage='failed',errorType=type(exc).__name__,error=str(exc)))
        raise


if __name__=='__main__':main()
