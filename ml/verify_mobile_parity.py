"""Prepare in the Paddle runtime, then independently execute in the ONNX runtime."""
import argparse
import json
from pathlib import Path
from ml.artifacts import file_sha256,write_json_atomic
from ml.evaluate_mobile_baseline import MODEL,REVISION,obtain_model
from ml.evaluate_modern_validation import admitted_samples
from ml.mobile_codec import decode_ctc,prepare_tensor


def prepare(root):
    if root.exists():raise FileExistsError('Preserve existing parity evidence')
    import cv2,numpy as np
    from paddlex.inference.models.text_recognition.processors import OCRReisizeNormImg
    import paddlex.inference.models.text_recognition.processors as upstream
    model_root=Path.home()/'.cache/folio-mobile-english-model'/REVISION;hashes=obtain_model(model_root)
    config=json.loads((model_root/'config.json').read_text())
    alphabet=['blank']+config['PostProcess']['character_dict']+[' ']
    reference_processor=OCRReisizeNormImg(rec_image_shape=[3,48,320])
    root.mkdir(parents=True);(root/'inputs').mkdir();rows=[];ids=set()
    for folder,experiment in [('modern-validation','mobile-english-baseline'),('modern-validation-expanded','mobile-english-expanded-baseline')]:
        manifest=Path('ml/data')/folder/'review-candidates.jsonl';review=manifest.with_name('visual-review.json')
        samples,_=admitted_samples(manifest,review)
        baseline=Path('ml/artifacts')/experiment
        report=json.loads((baseline/'evaluation-report.json').read_text());predictions=json.loads((baseline/'predictions.json').read_text())
        if report['model']!=MODEL or report['revision']!=REVISION or report['modelSha256']!=hashes:
            raise ValueError('Baseline uses different pinned weights or configuration')
        if report['manifestSha256']!=file_sha256(manifest) or report['reviewSha256']!=file_sha256(review):
            raise ValueError('Baseline data changed')
        if set(predictions)!={row['id'] for row in samples}:raise ValueError('Incomplete baseline predictions')
        for row in samples:
            if row['id'] in ids:raise ValueError('Repeated parity line')
            ids.add(row['id']);image=cv2.imread(str(row['image_path']),cv2.IMREAD_COLOR)
            if image is None:raise ValueError('Cannot read admitted crop')
            tensor=prepare_tensor(image);reference=reference_processor([image])[0][None]
            if not np.array_equal(tensor,reference):raise ValueError('Portable preprocessing differs from PaddleX')
            path=root/'inputs'/(row['id']+'.npy');np.save(path,tensor,allow_pickle=False)
            rows.append(dict(id=row['id'],input='inputs/'+path.name,sha256=file_sha256(path),
                             reference=row['text'],paddlePrediction=predictions[row['id']],baselineReportSha256=file_sha256(baseline/'evaluation-report.json')))
    write_json_atomic(root/'inputs-report.json',dict(status='passed-preprocessing-parity',model=MODEL,revision=REVISION,
                      sourceModelSha256=hashes,alphabet=alphabet,rows=rows,upstreamProcessorSha256=file_sha256(Path(upstream.__file__)),
                      portableProcessorSha256=file_sha256(Path(__file__).with_name('mobile_codec.py')),
                      scope='Exact preprocessing-array equality on admitted real lines; inference parity pending'))
    print(json.dumps(dict(status='passed-preprocessing-parity',lines=len(rows))),flush=True)


def evaluate(root,export):
    import numpy as np,onnxruntime as ort
    report=json.loads((root/'inputs-report.json').read_text());conversion=json.loads((export/'export-report.json').read_text())
    if report['status']!='passed-preprocessing-parity' or report['sourceModelSha256']!=conversion['sourceModelSha256']:
        raise ValueError('Input and export provenance disagree')
    if report['portableProcessorSha256']!=file_sha256(Path(__file__).with_name('mobile_codec.py')):raise ValueError('Portable codec changed')
    model=export/'model.onnx'
    if file_sha256(model)!=conversion['onnxSha256']:raise ValueError('ONNX model changed')
    options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
    session=ort.InferenceSession(str(model),sess_options=options,providers=['CPUExecutionProvider'])
    predictions={};mismatches=[]
    for row in report['rows']:
        path=(root/row['input']).resolve()
        if not path.is_relative_to(root.resolve()) or file_sha256(path)!=row['sha256']:raise ValueError('Parity input changed or escaped')
        tensor=np.load(path,allow_pickle=False);output=session.run(None,{session.get_inputs()[0].name:tensor})[0]
        if output.ndim!=3 or output.shape[0]!=1 or output.shape[2]!=len(report['alphabet']) or not np.isfinite(output).all():
            raise ValueError('Unexpected ONNX recognition output')
        prediction=decode_ctc(output.argmax(axis=2)[0],report['alphabet']);predictions[row['id']]=prediction
        if prediction!=row['paddlePrediction']:mismatches.append(dict(id=row['id'],paddle=row['paddlePrediction'],onnx=prediction))
    write_json_atomic(root/'onnx-predictions.json',predictions)
    result=dict(status='passed-prediction-parity' if not mismatches else 'failed-prediction-parity',
                lines=len(predictions),mismatches=mismatches,onnxSha256=conversion['onnxSha256'],
                inputsReportSha256=file_sha256(root/'inputs-report.json'),
                scope='Host preprocessing and decoded-text parity only; no numeric-logit, Android or general accuracy acceptance')
    write_json_atomic(root/'parity-report.json',result);print(json.dumps(result,indent=2),flush=True)
    if mismatches:raise RuntimeError('ONNX decoded predictions differ from Paddle')


def main():
    parser=argparse.ArgumentParser();parser.add_argument('stage',choices=('prepare','evaluate'))
    parser.add_argument('--output',type=Path,default=Path('ml/artifacts/mobile-english-parity'))
    parser.add_argument('--export',type=Path,default=Path('ml/artifacts/mobile-english-onnx'))
    args=parser.parse_args()
    if args.stage=='prepare':prepare(args.output)
    else:evaluate(args.output,args.export)


if __name__=='__main__':main()
