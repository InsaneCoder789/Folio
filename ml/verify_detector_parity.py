"""Compare pinned Paddle/ONNX probability maps for the 15 reviewed full photos."""
import argparse
import importlib.metadata
import json
import os
from pathlib import Path
from ml.artifacts import file_sha256,write_json_atomic
from ml.detector_codec import prepare_detector_tensor
from ml.detector_model import MODEL,REVISION,obtain_detector


def prepare(root):
    if root.exists():raise FileExistsError('Preserve earlier detector parity evidence')
    import numpy as np
    os.environ.setdefault('PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK','True')
    from paddleocr import TextDetection
    model_root,hashes=obtain_detector()
    pages=json.loads(Path('ml/artifacts/fullpage-mobile-handwriting/pages.json').read_text())
    root.mkdir(parents=True);(root/'tensors').mkdir();(root/'source').mkdir();rows=[];source_hashes={}
    for name in ('verify_detector_parity.py','detector_codec.py','detector_model.py','requirements-mobile.txt','requirements-export.txt'):
        payload=Path(__file__).with_name(name).read_bytes();(root/'source'/name).write_bytes(payload)
        source_hashes[name]=file_sha256(root/'source'/name)
    model=TextDetection(model_name='PP-OCRv5_mobile_det',model_dir=str(model_root),limit_side_len=1280,
                        limit_type='max',device='cpu',enable_mkldnn=False,cpu_threads=2)
    try:
        predictor=model.paddlex_predictor
        for index,page in enumerate(pages,1):
            candidates=[Path('ml/data')/folder/'pages'/(page['document']+'.jpg') for folder in ('modern-validation','modern-validation-expanded')]
            paths=[path for path in candidates if path.exists()]
            if len(paths)!=1 or file_sha256(paths[0])!=page['sourceSha256']:raise ValueError('Full photograph provenance changed')
            raw=predictor.pre_tfs['Read'](imgs=[str(paths[0])]);portable=prepare_detector_tensor(raw[0])
            resized,_=predictor.pre_tfs['Resize'](imgs=raw,limit_side_len=1280,limit_type='max',max_side_limit=4000)
            normalized=predictor.pre_tfs['Normalize'](imgs=resized)
            reference=predictor.pre_tfs['ToBatch'](imgs=predictor.pre_tfs['ToCHW'](imgs=normalized))
            if len(reference)!=1 or not np.array_equal(portable,reference[0]):raise ValueError('Detector preprocessing differs from PaddleX')
            probability=predictor.runner(x=reference)[0]
            if probability.shape!=(1,1,portable.shape[2],portable.shape[3]) or not np.isfinite(probability).all():raise ValueError('Unexpected Paddle detector map')
            item=dict(document=page['document'],sourceSha256=page['sourceSha256'])
            for role,tensor in (('input',portable),('reference',probability)):
                path=root/'tensors'/(page['document']+'-'+role+'.npy');np.save(path,tensor,allow_pickle=False)
                item[role]=str(path.relative_to(root));item[role+'Sha256']=file_sha256(path)
            rows.append(item);print(json.dumps(dict(pagesPrepared=index,pagesTotal=len(pages))),flush=True)
    finally:model.close()
    packages={d.metadata['Name']:d.version for d in importlib.metadata.distributions()}
    write_json_atomic(root/'paddle-runtime-packages.json',dict(sorted(packages.items())))
    write_json_atomic(root/'inputs-report.json',dict(status='passed-preprocessing-parity',model=MODEL,revision=REVISION,
                      modelSha256=hashes,rows=rows,codecSha256=file_sha256(Path(__file__).with_name('detector_codec.py')),
                      evaluatorSha256=file_sha256(Path(__file__)),fullpageResultsSha256=file_sha256(Path('ml/artifacts/fullpage-mobile-handwriting/pages.json')),
                      sourceHashes=source_hashes,runtimePackagesSha256=file_sha256(root/'paddle-runtime-packages.json'),
                      absoluteTolerance=.0005,relativeTolerance=.0005))


def evaluate(root):
    import numpy as np,onnxruntime as ort
    inputs=json.loads((root/'inputs-report.json').read_text());export=Path('ml/artifacts/mobile-detector-onnx')
    conversion=json.loads((export/'export-report.json').read_text())
    for name,digest in inputs['sourceHashes'].items():
        if file_sha256(root/'source'/name)!=digest:raise ValueError('Detector parity captured source changed')
    if inputs['modelSha256']!=conversion['sourceModelSha256'] or file_sha256(export/'model.onnx')!=conversion['onnxSha256']:
        raise ValueError('Detector export/input provenance disagrees')
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(export/'model.onnx'),sess_options=options,providers=['CPUExecutionProvider']);results=[]
    for row in inputs['rows']:
        tensors={}
        for role in ('input','reference'):
            path=(root/row[role]).resolve()
            if not path.is_relative_to(root.resolve()) or file_sha256(path)!=row[role+'Sha256']:raise ValueError('Detector parity tensor changed or escaped')
            tensors[role]=np.load(path,allow_pickle=False)
        actual=session.run(None,{session.get_inputs()[0].name:tensors['input']})[0];reference=tensors['reference']
        if actual.shape!=reference.shape or not np.isfinite(actual).all():raise ValueError('Invalid ONNX detector map')
        difference=np.abs(actual-reference)
        results.append(dict(document=row['document'],maximumAbsoluteError=float(difference.max()),meanAbsoluteError=float(difference.mean()),
                            thresholdMaskDifferences=int(np.count_nonzero((actual>.3)!=(reference>.3))),
                            allclose=bool(np.allclose(actual,reference,atol=inputs['absoluteTolerance'],rtol=inputs['relativeTolerance']))))
    passed=bool(results) and all(row['allclose'] for row in results)
    packages={d.metadata['Name']:d.version for d in importlib.metadata.distributions()}
    write_json_atomic(root/'onnx-runtime-packages.json',dict(sorted(packages.items())))
    report=dict(status='passed-map-parity' if passed else 'failed-map-parity',pages=len(results),results=results,
                inputsReportSha256=file_sha256(root/'inputs-report.json'),onnxSha256=conversion['onnxSha256'],
                runtimePackagesSha256=file_sha256(root/'onnx-runtime-packages.json'),
                absoluteTolerance=inputs['absoluteTolerance'],relativeTolerance=inputs['relativeTolerance'],androidVerified=False,
                scope='Host detector tensor/map equivalence; no box, full-page quality or Android acceptance')
    write_json_atomic(root/'parity-report.json',report);print(json.dumps(report,indent=2),flush=True)
    if not passed:raise RuntimeError('Detector probability-map parity failed')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=('prepare','evaluate'))
    parser.add_argument('--output',type=Path,default=Path('ml/artifacts/mobile-detector-parity'));args=parser.parse_args()
    if args.stage=='prepare':prepare(args.output)
    else:evaluate(args.output)


if __name__=='__main__':main()
