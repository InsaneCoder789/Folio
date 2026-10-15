#!/usr/bin/env python3
"""Compare detector maps on the existing Pixel using only owned temporary files."""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid
import zipfile
from android_toolchain import build_environment
from verify_android_onnx import digest


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[1];output=root/'output/android';output.mkdir(parents=True,exist_ok=True)
    env=build_environment();sdk=Path(env.get('ANDROID_HOME') or Path.home()/'Library/Android/sdk');adb=str(sdk/'platform-tools/adb')
    def run(*command,timeout=30,check=True):return subprocess.run([adb,'-s',args.serial,*command],check=check,capture_output=True,text=True,timeout=timeout)
    def free_bytes():return int(run('shell','df','-k','/data').stdout.splitlines()[-1].split()[3])*1024
    remote='/data/local/tmp/folio-detector-'+uuid.uuid4().hex;created=False
    report=dict(status='failed',timestamp=datetime.now(timezone.utc).isoformat(),serial=args.serial,
                appInstalled=False,appDataModified=False,emulatorStopped=False,
                scope='Standalone ART detector-map equivalence; no box, installed UI, Room or worker acceptance')
    try:
        avd=run('emu','avd','name').stdout.splitlines()[0].strip()
        if args.serial!='emulator-5554' or avd!='Pixel_9_Pro':raise RuntimeError('Use only the existing Pixel_9_Pro emulator-5554')
        if run('shell','getprop','ro.product.cpu.abi').stdout.strip()!='arm64-v8a':raise RuntimeError('Use the existing arm64 Pixel')
        apk=root/'android/app/build/outputs/apk/debug/app-debug.apk';build=json.loads((output/'verification.json').read_text())
        if build['status']!='passed' or digest(apk)!=build['apkSha256'] or build['unitTests']['tests']<33:
            raise RuntimeError('Use the exact APK from the new 33-test build gate')
        export=root/'ml/artifacts/mobile-detector-onnx';model=export/'model.onnx';conversion=json.loads((export/'export-report.json').read_text())
        if digest(model)!=conversion['onnxSha256']:raise RuntimeError('Detector export changed')
        parity=root/'ml/artifacts/mobile-detector-parity';inputs=json.loads((parity/'inputs-report.json').read_text());comparison=json.loads((parity/'parity-report.json').read_text())
        if comparison['status']!='passed-map-parity' or digest(parity/'inputs-report.json')!=comparison['inputsReportSha256']:
            raise RuntimeError('Require verified host probability-map parity first')
        if digest(root/'ml/artifacts/fullpage-mobile-handwriting/pages.json')!=inputs['fullpageResultsSha256']:
            raise RuntimeError('Full-page source provenance changed')
        if inputs['modelSha256']!=conversion['sourceModelSha256']:raise RuntimeError('Reference map uses another detector')
        import cv2,numpy as np
        with tempfile.TemporaryDirectory(prefix='detector-probe-',dir=output) as temporary:
            stage=Path(temporary);native=stage/'lib64';native.mkdir()
            with zipfile.ZipFile(apk) as source,zipfile.ZipFile(stage/'probe.apk','w',zipfile.ZIP_DEFLATED) as probe:
                for name in source.namelist():
                    if re.fullmatch(r'classes[0-9]*\.dex',name):probe.writestr(name,source.read(name))
                for name in ('libc++_shared.so','libopencv_java4.so','libonnxruntime.so','libonnxruntime4j_jni.so'):
                    (native/name).write_bytes(source.read('lib/arm64-v8a/'+name))
            shutil.copy2(model,stage/'model.onnx');cases=[]
            for row in inputs['rows']:
                document=row['document']
                if not re.fullmatch('[a-f0-9]{16}',document):raise RuntimeError('Invalid source photograph ID')
                pages=[root/'ml/data'/folder/'pages'/(document+'.jpg') for folder in ('modern-validation','modern-validation-expanded')]
                pages=[path for path in pages if path.exists()]
                if len(pages)!=1 or digest(pages[0])!=row['sourceSha256']:raise RuntimeError('Original photograph changed')
                image=cv2.imread(str(pages[0]),cv2.IMREAD_COLOR)
                if image is None:raise RuntimeError('Cannot decode original photograph')
                png=stage/(document+'.png')
                if not cv2.imwrite(str(png),image) or not np.array_equal(image,cv2.imread(str(png))):raise RuntimeError('Canonical page pixels changed')
                reference=(parity/row['reference']).resolve()
                if not reference.is_relative_to(parity.resolve()) or reference.stat().st_size>8*1024**2 or digest(reference)!=row['referenceSha256']:raise RuntimeError('Reference map changed, escaped or exceeded its bound')
                probability=np.load(reference,allow_pickle=False)
                if probability.ndim!=4 or probability.shape[:2]!=(1,1) or probability.dtype!=np.float32 or not all(32<=n<=1280 and n%32==0 for n in probability.shape[2:]) or not np.isfinite(probability).all():raise RuntimeError('Invalid reference map')
                binary=stage/(document+'.bin');binary.write_bytes(probability.astype('<f4').tobytes(order='C'))
                cases.append(dict(document=document,width=probability.shape[3],height=probability.shape[2],
                                  referenceSha256=digest(binary),pngSha256=digest(png)))
            if len(cases)!=15 or len({row['document'] for row in cases})!=15:raise RuntimeError('Require all 15 photographs')
            (stage/'cases.json').write_text(json.dumps(dict(cases=cases,absoluteTolerance=inputs['absoluteTolerance'],relativeTolerance=inputs['relativeTolerance'])))
            payload=sum(path.stat().st_size for path in stage.rglob('*') if path.is_file());available=free_bytes()
            report.update(avd=avd,apkSha256=digest(apk),modelSha256=digest(model),payloadBytes=payload,freeBytesBefore=available)
            if available<payload+256*1024**2:raise RuntimeError('Insufficient device space with the probe reserve')
            run('shell','mkdir',remote);created=True;run('push',str(stage)+'/.',remote,timeout=180)
            command=f'CLASSPATH={remote}/probe.apk LD_LIBRARY_PATH={remote}/lib64 app_process -Djava.library.path={remote}/lib64 /system/bin com.rohanc.ocrscanner.diagnostics.DetectorProbe {remote}'
            result=run('shell',command,timeout=240,check=False)
            (output/'detector-device-probe.log').write_text(result.stdout+'\n'+result.stderr)
            lines=[line[len('FOLIO_DETECTOR_RESULT '):] for line in result.stdout.splitlines() if line.startswith('FOLIO_DETECTOR_RESULT ')]
            if not lines:raise RuntimeError('Detector probe returned no structured result; inspect its log')
            report['runtime']=json.loads(lines[-1]);runtime=report['runtime']
            if result.returncode or runtime.get('status')!='passed' or runtime.get('pages')!=15 or runtime.get('mismatchPages')!=0:
                raise RuntimeError('Pixel detector-map parity failed')
            report['status']='passed'
    except Exception as failure:report['error']=str(failure)
    finally:
        if created:
            try:
                cleanup=run('shell','rm','-rf','--',remote,check=False)
                report['ownTemporaryFilesRemoved']=cleanup.returncode==0 and run('shell','test','!','-e',remote,check=False).returncode==0
                if not report['ownTemporaryFilesRemoved']:report.update(status='failed',error='Detector probe cleanup did not verify')
                report['freeBytesAfterCleanup']=free_bytes()
            except Exception as failure:report.update(status='failed',cleanupError=str(failure),ownTemporaryFilesRemoved=False,temporaryDeviceDirectory=remote)
        (output/'detector-device-verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
