"""Package reusable OCR artifacts locally; release mode requires verified acceptance."""
import argparse
import json
from pathlib import Path
import tempfile
import zipfile
from ml.artifacts import file_sha256

MODEL_FILES=('model.safetensors','config.json','generation_config.json','preprocessor_config.json',
             'tokenizer_config.json','special_tokens_map.json','vocab.json','merges.txt')
SOURCE_FILES=('fullpage_ocr.py','fullpage_geometry.py','detector_codec.py','artifacts.py',
              'requirements.txt','requirements-fullpage.txt','metrics.py')


def release_gate(acceptance,policy,weights_sha):
    if acceptance.get('status')!='verified' or acceptance.get('freshHoldoutVerified') is not True:
        raise ValueError('Release requires independent acceptance on a new locked holdout')
    if acceptance.get('modelSha256')!=weights_sha:raise ValueError('Acceptance belongs to different model weights')
    if acceptance.get('redistributionCleared') is not True:raise ValueError('Source/model redistribution requirements remain unresolved')
    score=acceptance.get('metrics',{})
    import math
    for key in ('samples','exactLineRate','cer','wer'):
        value=score.get(key)
        if isinstance(value,bool) or not isinstance(value,(int,float)) or not math.isfinite(value) or value<0:
            raise ValueError('Acceptance contains an invalid metric')
    if not isinstance(score['samples'],int) or score['exactLineRate']>1:
        raise ValueError('Invalid sample count or exact-line rate')
    if score.get('samples',0)<policy['minimumEvaluationLines'] or score.get('exactLineRate',0)<policy['minimumExactLineRate']:
        raise ValueError('Exact-line acceptance target not met')
    if score.get('cer',float('inf'))>policy['maximumCer'] or score.get('wer',float('inf'))>policy['maximumWer']:
        raise ValueError('Character/word error thresholds not met')
    return True


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--checkpoint',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--policy',type=Path,default=Path('ml/iam-exact-line-policy-v2.json'))
    p.add_argument('--licenses-dir',type=Path);p.add_argument('--acceptance',type=Path);p.add_argument('--experimental',action='store_true')
    p.add_argument('--detector',type=Path,default=Path('ml/artifacts/mobile-detector-onnx/model.onnx'));a=p.parse_args()
    if a.output.exists():raise FileExistsError('Never replace an earlier bundle')
    policy=json.loads(a.policy.read_text());files={}
    for name in MODEL_FILES:
        path=a.checkpoint/name
        if not path.is_file():raise ValueError('Missing recognizer artifact: '+name)
        files['recognizer/'+name]=path
    from ml.fullpage_ocr import DETECTOR_SHA256
    if file_sha256(a.detector)!=DETECTOR_SHA256:raise ValueError('Detector integrity mismatch')
    files['detector/model.onnx']=a.detector
    for name in SOURCE_FILES:files['ml/'+name]=Path(__file__).with_name(name)
    files['policy.json']=a.policy
    weights_sha=file_sha256(files['recognizer/model.safetensors'])
    if not a.experimental:
        if not a.acceptance:raise ValueError('Provide verified acceptance or label the local bundle experimental')
        release_gate(json.loads(a.acceptance.read_text()),policy,weights_sha)
        if not a.licenses_dir or not all((a.licenses_dir/n).is_file() for n in ('FOUNDATION-LICENSE.txt','DETECTOR-LICENSE.txt','DATASET-TERMS.txt')):
            raise ValueError('Include verified foundation/detector licenses and dataset terms before release')
        for n in ('FOUNDATION-LICENSE.txt','DETECTOR-LICENSE.txt','DATASET-TERMS.txt'):files['licenses/'+n]=a.licenses_dir/n
    elif a.acceptance:raise ValueError('Experimental bundles must not imply an acceptance certificate')
    a.output.parent.mkdir(parents=True,exist_ok=True)
    checksums={name:dict(sha256=file_sha256(path),bytes=path.stat().st_size) for name,path in sorted(files.items())}
    metadata=dict(schemaVersion=1,status='experimental-not-accepted' if a.experimental else 'accepted-line-recognizer',
                  acceptedForAndroid=False,publicUploadPerformed=False,modelSha256=weights_sha,
                  policySha256=file_sha256(a.policy),files=checksums,
                  sourceDataset='IAM; original terms must be respected',
                  detectorLicense='Apache-2.0',redistributionCleared=not a.experimental,
                  limitations=['Line accuracy is separate from full-page accuracy.','Android runtime not accepted.'])
    fd,name=tempfile.mkstemp(prefix='.bundle-',suffix='.partial',dir=a.output.parent)
    import os
    os.close(fd);temporary=Path(name)
    try:
        with zipfile.ZipFile(temporary,'w',compression=zipfile.ZIP_STORED,allowZip64=True) as out:
            for name,path in sorted(files.items()):out.write(path,name)
            out.writestr('bundle-manifest.json',json.dumps(metadata,indent=2)+'\n')
            if a.acceptance:out.write(a.acceptance,'acceptance-report.json')
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:raise ValueError('Bundle CRC verification failed')
        temporary.rename(a.output)
    finally:temporary.unlink(missing_ok=True)
    print(json.dumps(dict(status=metadata['status'],output=str(a.output),sha256=file_sha256(a.output),bytes=a.output.stat().st_size)))


if __name__=='__main__':main()
