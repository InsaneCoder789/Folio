"""Export a pinned mobile OCR component; host smoke does not authorize Android use."""
import argparse
import importlib.metadata
import json
from pathlib import Path
import subprocess
import sys
from ml.artifacts import file_sha256,write_json_atomic
from ml.evaluate_mobile_baseline import MODEL,REVISION,obtain_model
from ml.detector_model import MODEL as DETECTOR,REVISION as DETECTOR_REVISION,obtain_detector


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--kind',choices=('recognizer','detector'),default='recognizer')
    parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    if args.output is None:args.output=Path('ml/artifacts')/('mobile-english-onnx' if args.kind=='recognizer' else 'mobile-detector-onnx')
    if args.output.exists():raise FileExistsError('Preserve existing exports; choose a fresh directory')
    if args.kind=='recognizer':
        model_root=Path.home()/'.cache/folio-mobile-english-model'/REVISION
        hashes=obtain_model(model_root);model_name=MODEL;revision=REVISION;smoke_shape=(1,3,48,320)
    else:
        model_root,hashes=obtain_detector();model_name=DETECTOR;revision=DETECTOR_REVISION;smoke_shape=(1,3,512,512)
    args.output.mkdir(parents=True);sources=args.output/'source';sources.mkdir();source_hashes={}
    for name in ('export_mobile_onnx.py','evaluate_mobile_baseline.py','detector_model.py','requirements-export.txt'):
        payload=Path(__file__).with_name(name).read_bytes();(sources/name).write_bytes(payload)
        source_hashes[name]=file_sha256(sources/name)
    temporary=args.output/'model.partial.onnx';complete=args.output/'model.onnx'
    converter=Path(sys.executable).with_name('paddle2onnx')
    command=[str(converter),'--model_dir',str(model_root),'--model_filename','inference.json',
             '--params_filename','inference.pdiparams','--save_file',str(temporary),
             '--opset_version','19','--enable_onnx_checker','True']
    try:
        subprocess.run(command,check=True,timeout=300)
        import numpy as np
        import onnx,onnxruntime as ort
        graph=onnx.load(str(temporary),load_external_data=False)
        # Keep one self-contained artifact for later mobile admission.
        if any(tensor.data_location==onnx.TensorProto.EXTERNAL for tensor in graph.graph.initializer):
            raise ValueError('Export requires external tensor files; mobile packaging is not self-contained')
        onnx.checker.check_model(str(temporary),full_check=True)
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
        session=ort.InferenceSession(str(temporary),sess_options=options,providers=['CPUExecutionProvider'])
        inputs=session.get_inputs()
        if len(inputs)!=1 or inputs[0].type!='tensor(float)':raise ValueError('Unexpected OCR input contract')
        smoke=session.run(None,{inputs[0].name:np.zeros(smoke_shape,dtype=np.float32)})
        if not smoke or any(not np.isfinite(output).all() for output in smoke):raise ValueError('Non-finite exported-model output')
        packages={distribution.metadata['Name']:distribution.version for distribution in importlib.metadata.distributions()}
        write_json_atomic(args.output/'runtime-packages.json',dict(sorted(packages.items())))
        temporary.replace(complete)
        result=dict(status='passed-host-export-smoke',kind=args.kind,model=model_name,revision=revision,license='Apache-2.0',
                    sourceModelSha256=hashes,onnxSha256=file_sha256(complete),onnxBytes=complete.stat().st_size,
                    opsets=[dict(domain=opset.domain,version=opset.version) for opset in graph.opset_import],
                    inputs=[dict(name=item.name,shape=item.shape,type=item.type) for item in inputs],
                    outputs=[dict(name=item.name,shape=item.shape,type=item.type) for item in session.get_outputs()],
                    smokeOutputShapes=[list(output.shape) for output in smoke],sourceHashes=source_hashes,
                    runtimePackagesSha256=file_sha256(args.output/'runtime-packages.json'),
                    predictionParityVerified=False,androidVerified=False,promotedToAndroid=False,
                    scope='ONNX structure and finite host-CPU smoke only; component-output parity and Android checks pending')
        write_json_atomic(args.output/'export-report.json',result);print(json.dumps(result,indent=2),flush=True)
    finally:temporary.unlink(missing_ok=True)


if __name__=='__main__':main()
