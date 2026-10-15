"""Evaluate a pinned English mobile recognizer on reviewed development lines only."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import time
import urllib.request
from ml.artifacts import file_sha256,write_json_atomic
from ml.evaluate_modern_validation import admitted_samples
from ml.metrics import evaluate

MODEL='PaddlePaddle/en_PP-OCRv5_mobile_rec'
REVISION='267c36e24c331595590fe7bd72bde2436fd286f2'
FILES={
    'config.json':(10455,'git-sha1','ee30b810753068b251de393eee66dff31f6d279c'),
    'inference.json':(217712,'git-sha1','fc20fb935854373220fbeff985cbd310f0450608'),
    'inference.pdiparams':(7772315,'sha256','3ec8a97ed6cefe8568d3e2ee90bb193299b566a7661aa4fd52d224b96b59f66b'),
    'inference.yml':(3964,'git-sha1','91a401a7220881921c249b92852a96f9dbf2132a')}


def verify_payload(payload,size,algorithm,digest):
    if algorithm not in ('sha256','git-sha1'):raise ValueError('Unknown pinned integrity algorithm')
    if len(payload)!=size:raise ValueError('Pinned model file length differs')
    actual=(hashlib.sha256(payload).hexdigest() if algorithm=='sha256'
            else hashlib.sha1(b'blob '+str(size).encode()+b'\0'+payload).hexdigest())
    if actual!=digest:raise ValueError('Pinned model file integrity differs')


def obtain_model(root):
    root.mkdir(parents=True,exist_ok=True);hashes={}
    for name,(size,algorithm,digest) in FILES.items():
        target=root/name
        if target.exists():payload=target.read_bytes()
        else:
            with urllib.request.urlopen(f'https://huggingface.co/{MODEL}/resolve/{REVISION}/{name}',timeout=30) as response:
                payload=response.read(size+1)
        verify_payload(payload,size,algorithm,digest)
        if not target.exists():
            temporary=target.with_suffix(target.suffix+'.partial')
            try:temporary.write_bytes(payload);temporary.replace(target)
            finally:temporary.unlink(missing_ok=True)
        hashes[name]=file_sha256(target)
    write_json_atomic(root/'provenance.json',dict(model=MODEL,revision=REVISION,license='Apache-2.0',sha256=hashes))
    return hashes


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,default=Path('ml/artifacts/mobile-english-baseline'))
    parser.add_argument('--manifest',type=Path,default=Path('ml/data/modern-validation/review-candidates.jsonl'))
    parser.add_argument('--review',type=Path)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError('Use a fresh evaluation output directory')
    manifest=args.manifest;review=args.review or manifest.with_name('visual-review.json')
    rows,_=admitted_samples(manifest,review)
    args.output.mkdir(parents=True);sources=args.output/'source';sources.mkdir()
    source_hashes={}
    for name in ('evaluate_mobile_baseline.py','evaluate_modern_validation.py','manifest.py','metrics.py','artifacts.py','requirements-mobile.txt'):
        payload=Path(__file__).with_name(name).read_bytes();(sources/name).write_bytes(payload)
        source_hashes[name]=hashlib.sha256(payload).hexdigest()
    model_dir=Path.home()/'.cache/folio-mobile-english-model'/REVISION
    hashes=obtain_model(model_dir)
    # Local pinned files avoid auto-selection of a different OCR model.
    os.environ.setdefault('PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK','True')
    from paddleocr import TextRecognition
    import paddle,paddleocr
    packages={distribution.metadata['Name']:distribution.version for distribution in importlib.metadata.distributions()}
    write_json_atomic(args.output/'runtime-packages.json',dict(sorted(packages.items())))
    model=TextRecognition(model_name='en_PP-OCRv5_mobile_rec',model_dir=str(model_dir),
                          device='cpu',enable_mkldnn=False,cpu_threads=2)
    predictions={};confidences={};start=time.perf_counter()
    for index,row in enumerate(rows,1):
        output=list(model.predict(input=str(row['image_path']),batch_size=1))
        if len(output)!=1 or not isinstance(output[0].get('rec_text'),str):raise ValueError('Unexpected recognition output')
        predictions[row['id']]=output[0]['rec_text'];confidences[row['id']]=float(output[0]['rec_score'])
        print(json.dumps(dict(linesCompleted=index,linesTotal=len(rows))),flush=True)
    seconds=time.perf_counter()-start;references={row['id']:row['text'] for row in rows}
    strict=evaluate(references,predictions)
    folded=evaluate({k:v.casefold() for k,v in references.items()},{k:v.casefold() for k,v in predictions.items()})
    folded['normalization']='NFC, collapse whitespace, casefold, preserve punctuation'
    write_json_atomic(args.output/'predictions.json',predictions)
    write_json_atomic(args.output/'confidences.json',confidences)
    report=dict(status='completed',model=MODEL,revision=REVISION,license='Apache-2.0',modelSha256=hashes,
                manifestSha256=file_sha256(manifest),reviewSha256=file_sha256(review),metrics=strict,
                casefoldedDiagnosticMetrics=folded,hostSecondsPerLine=seconds/len(rows),device='host-cpu',
                runtime=dict(python=platform.python_version(),paddle=paddle.__version__,paddleocr=paddleocr.__version__),
                evaluatorSha256=source_hashes['evaluate_mobile_baseline.py'],sourceHashes=source_hashes,
                runtimePackagesSha256=file_sha256(args.output/'runtime-packages.json'),trainingExecuted=False,promotedToAndroid=False,
                scope='Reviewed development validation only; no final-test, Android compatibility or device performance acceptance')
    write_json_atomic(args.output/'evaluation-report.json',report);print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
