"""Independently verify saved scores, checkpoint selection and checkpoint integrity."""
import argparse
import hashlib
import json
import math
from pathlib import Path
from ml.manifest import read_manifest
from ml.metrics import evaluate
from ml.artifacts import file_sha256


def compare_score(stored,score):
    for key,value in score.items():
        actual=stored.get(key)
        if isinstance(value,float):
            if not isinstance(actual,(int,float)) or not math.isclose(value,actual,rel_tol=1e-12,abs_tol=1e-12):raise ValueError('Reported score differs from saved predictions: '+key)
        elif actual!=value:raise ValueError('Reported metric differs: '+key)


def validate(manifest_path,experiment,*,allow_selection=False):
    manifest_path=Path(manifest_path);experiment=Path(experiment)
    report=json.loads((experiment/'training-report.json').read_text())
    if report.get('status')!='passed' or report.get('stage')!='completed':raise ValueError('Experiment did not complete')
    version=report.get('schemaVersion',1)
    if version not in (1,2):raise ValueError('Unsupported training report schema')
    protocol=report.get('protocol','train-validation-test')
    if protocol not in ('train-validation-test','validation-selection-only'):raise ValueError('Unknown experiment protocol')
    selection=protocol=='validation-selection-only'
    if selection and not allow_selection:raise ValueError('Final held-out test is deferred; explicitly request selection-only integrity verification')
    if allow_selection and not selection:raise ValueError('This report contains a test phase; use the full verification mode')
    if version>=2 and bool(report.get('testDeferred'))!=selection:raise ValueError('Test-deferred flag contradicts protocol')
    required=('train','validation') if selection else ('train','validation','test')
    rows=read_manifest(manifest_path,required_splits=required,allowed_licenses=(report.get('datasetLicense','CC-BY-4.0'),))
    if hashlib.sha256(manifest_path.read_bytes()).hexdigest()!=report['manifestSha256']:raise ValueError('Dataset manifest changed')
    counts={split:sum(row['split']==split for row in rows) for split in required}
    if counts!=report['splitCounts']:raise ValueError('Split counts changed')
    references={row['id']:row['text'] for row in rows if row['split']=='test'}
    recomputed={}
    for score_key,file in (() if selection else (('test','test-predictions.json'),('pretrainedTest','baseline-test-predictions.json'))):
        prediction=json.loads((experiment/file).read_text())
        score=evaluate(references,prediction)
        compare_score(report[score_key],score)
        recomputed[score_key]=score
    candidates=[(report['pretrainedValidation']['cer'],0)]+[(row['validation']['cer'],row['step']) for row in report['training']]
    if not all(math.isfinite(score) and score>=0 for score,step in candidates):raise ValueError('Invalid validation metric')
    steps=[row['step'] for row in report['training']]
    if steps!=sorted(set(steps)) or any(step<=0 for step in steps):raise ValueError('Invalid training evaluation order')
    best=min(candidates,key=lambda item:(item[0],item[1]))[1]
    if best!=report['bestStep']:raise ValueError('Checkpoint was not chosen by validation CER')
    if report['validationImproved']!=(best>0):raise ValueError('Validation improvement flag contradicts selection')
    if not selection and report['testCerImproved']!=(recomputed['test']['cer']<recomputed['pretrainedTest']['cer']):raise ValueError('Test improvement flag contradicts saved predictions')
    if selection and any(key in report for key in ('test','pretrainedTest','testCerImproved')):raise ValueError('Selection-only report must not claim final test results')
    if report['selectedCheckpoint']!=('fine-tuned' if best else 'pretrained-baseline-retained'):raise ValueError('Selected checkpoint label contradicts selection')
    if report['stepsCompleted']!=report['stepsRequested'] or not report['training'] or report['training'][-1]['step']!=report['stepsCompleted']:
        raise ValueError('Requested training steps did not finish')
    checkpoint=Path(report['checkpoint']).resolve()
    if not checkpoint.is_relative_to(experiment.resolve()):raise ValueError('Checkpoint escapes experiment directory')
    if not report['checkpointSha256']:raise ValueError('Missing checkpoint integrity hashes')
    for name,digest in report['checkpointSha256'].items():
        path=(checkpoint/name).resolve()
        if not path.is_relative_to(checkpoint) or not path.is_file() or file_sha256(path)!=digest:
            raise ValueError('Checkpoint integrity mismatch')
    if version>=2:
        validation={row['id']:row['text'] for row in rows if row['split']=='validation'}
        baseline=Path(report['baselineCheckpoint']).resolve()
        if not baseline.is_relative_to(experiment.resolve()):raise ValueError('Baseline checkpoint escapes experiment directory')
        selected_metrics=report['pretrainedValidation'] if best==0 else next(row['validation'] for row in report['training'] if row['step']==best)
        for label,path,stored in (('pretrainedValidation',baseline,report['pretrainedValidation']),('selectedValidation',checkpoint,selected_metrics)):
            score=evaluate(validation,json.loads((path/'validation-predictions.json').read_text()))
            compare_score(stored,score);recomputed[label]=score
        if not {'train_handwriting.py','requirements.txt','metrics.py','manifest.py','artifacts.py'}<=set(report.get('sourceHashes',{})):
            raise ValueError('Missing captured core training-source provenance')
        for name,digest in report['sourceHashes'].items():
            path=(experiment/'source'/name).resolve()
            if not path.is_relative_to((experiment/'source').resolve()) or not path.is_file() or file_sha256(path)!=digest:raise ValueError('Captured source snapshot changed')
    return {'status':'passed-selection-integrity' if selection else 'passed','manifest':str(manifest_path),'experiment':str(experiment),'stepsVerified':report['stepsCompleted'],
            'selectedStep':best,'scores':recomputed,'checkpointHashesVerified':True,
            'heldOutTestVerified':not selection,'validationPredictionsVerified':version>=2,
            'capturedSourceVerified':version>=2,
            'qualityAcceptedForAndroid':False,'scope':'Artifact consistency and integrity; no mobile or generalization acceptance'}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--experiment',type=Path,required=True)
    parser.add_argument('--selection-only',action='store_true',help='Verify selection integrity without claiming an untouched final test')
    args=parser.parse_args();result=validate(args.manifest,args.experiment,allow_selection=args.selection_only)
    (args.experiment/'artifact-verification.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2),flush=True)


if __name__=='__main__':main()
