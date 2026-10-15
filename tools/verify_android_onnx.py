#!/usr/bin/env python3
"""Run a private ART line probe on the existing Pixel, without APK installation."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import uuid
import zipfile
from android_toolchain import build_environment


def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);args=parser.parse_args()
    root=Path(__file__).resolve().parents[1];output=root/'output/android';output.mkdir(parents=True,exist_ok=True)
    env=build_environment();sdk=Path(env.get('ANDROID_HOME') or Path.home()/'Library/Android/sdk');adb=str(sdk/'platform-tools/adb')
    def run(*command,timeout=30,check=True):
        return subprocess.run([adb,'-s',args.serial,*command],check=check,capture_output=True,text=True,timeout=timeout)
    def free_bytes():return int(run('shell','df','-k','/data').stdout.splitlines()[-1].split()[3])*1024
    remote='/data/local/tmp/folio-onnx-'+uuid.uuid4().hex;created=False
    report=dict(timestamp=datetime.now(timezone.utc).isoformat(),status='failed',serial=args.serial,
                appInstalled=False,appDataModified=False,emulatorStopped=False,
                scope='Standalone ART runtime parity only; not installed UI or repository acceptance')
    try:
        avd=run('emu','avd','name').stdout.splitlines()[0].strip()
        if args.serial!='emulator-5554' or avd!='Pixel_9_Pro':raise RuntimeError('Use only the existing Pixel_9_Pro emulator-5554')
        if run('shell','getprop','ro.product.cpu.abi').stdout.strip()!='arm64-v8a':raise RuntimeError('This probe requires the existing arm64 Pixel')
        apk=root/'android/app/build/outputs/apk/debug/app-debug.apk'
        build=json.loads((output/'verification.json').read_text())
        if build['status']!='passed' or digest(apk)!=build['apkSha256']:raise RuntimeError('Use the exact APK from the latest passed build gate')
        export=root/'ml/artifacts/mobile-english-onnx';model=export/'model.onnx'
        export_report=json.loads((export/'export-report.json').read_text())
        if digest(model)!=export_report['onnxSha256']:raise RuntimeError('Model export changed')
        parity=root/'ml/artifacts/mobile-english-parity';inputs=json.loads((parity/'inputs-report.json').read_text())
        parity_report=json.loads((parity/'parity-report.json').read_text())
        if parity_report['status']!='passed-prediction-parity' or digest(parity/'inputs-report.json')!=parity_report['inputsReportSha256']:
            raise RuntimeError('Require verified host parity first')
        config=Path.home()/'.cache/folio-mobile-english-model'/export_report['revision']/'config.json'
        if digest(config)!=export_report['sourceModelSha256']['config.json']:raise RuntimeError('Model configuration changed')
        samples={};baseline_hashes=set()
        for folder,experiment in (('modern-validation','mobile-english-baseline'),('modern-validation-expanded','mobile-english-expanded-baseline')):
            source=root/'ml/data'/folder
            baseline_path=root/'ml/artifacts'/experiment/'evaluation-report.json'
            baseline=json.loads(baseline_path.read_text());baseline_hashes.add(digest(baseline_path))
            if digest(source/'review-candidates.jsonl')!=baseline['manifestSha256'] or digest(source/'visual-review.json')!=baseline['reviewSha256']:
                raise RuntimeError('Reviewed source changed after baseline evaluation')
            for line in (source/'review-candidates.jsonl').read_text().splitlines():
                row=json.loads(line);path=(source/row['image']).resolve()
                if not path.is_relative_to(source.resolve()) or digest(path)!=row['sha256']:raise RuntimeError('Source crop integrity failed')
                if row['id'] in samples:raise RuntimeError('Repeated source ID')
                samples[row['id']]=path
        with tempfile.TemporaryDirectory(prefix='onnx-probe-',dir=output) as temporary:
            stage=Path(temporary);native=stage/'lib64';native.mkdir()
            libraries={'libc++_shared.so','libopencv_java4.so','libonnxruntime.so','libonnxruntime4j_jni.so'}
            with zipfile.ZipFile(apk) as source,zipfile.ZipFile(stage/'probe.apk','w',zipfile.ZIP_DEFLATED) as probe:
                for name in source.namelist():
                    if re.fullmatch(r'classes[0-9]*\.dex',name):probe.writestr(name,source.read(name))
                for name in libraries:(native/name).write_bytes(source.read('lib/arm64-v8a/'+name))
            shutil.copy2(model,stage/'model.onnx');shutil.copy2(config,stage/'config.json')
            cases=[]
            for row in inputs['rows']:
                identifier=row['id']
                if row['baselineReportSha256'] not in baseline_hashes:raise RuntimeError('Parity input belongs to another baseline')
                if not re.fullmatch('[a-f0-9]{16}-[0-9]{2}',identifier):raise RuntimeError('Invalid probe sample ID')
                shutil.copy2(samples[identifier],stage/(identifier+'.png'))
                cases.append(dict(id=identifier,expected=row['paddlePrediction']))
            if len(cases)!=28:raise RuntimeError('Require all 28 reviewed lines')
            (stage/'cases.json').write_text(json.dumps(cases))
            payload=sum(path.stat().st_size for path in stage.rglob('*') if path.is_file())
            available=free_bytes();report.update(avd=avd,apkSha256=digest(apk),modelSha256=digest(model),payloadBytes=payload,freeBytesBefore=available)
            if available<payload+256*1024**2:raise RuntimeError('Insufficient device space while retaining the probe reserve')
            run('shell','mkdir',remote);created=True
            run('push',str(stage)+'/.',remote,timeout=120)
            command=f'CLASSPATH={remote}/probe.apk LD_LIBRARY_PATH={remote}/lib64 app_process -Djava.library.path={remote}/lib64 /system/bin com.rohanc.ocrscanner.diagnostics.LineRecognizerProbe {remote}'
            result=run('shell',command,timeout=180,check=False)
            (output/'onnx-device-probe.log').write_text(result.stdout+'\n'+result.stderr)
            lines=[line[len('FOLIO_ONNX_RESULT '):] for line in result.stdout.splitlines() if line.startswith('FOLIO_ONNX_RESULT ')]
            if not lines:raise RuntimeError('ART probe returned no structured result; inspect its retained log')
            report['runtime']=json.loads(lines[-1])
            if result.returncode or report['runtime'].get('status')!='passed' or report['runtime'].get('lines')!=28 or report['runtime'].get('mismatches')!=0:
                raise RuntimeError('Pixel line-recognition runtime/parity gate failed')
            report['status']='passed'
    except Exception as failure:report['error']=str(failure)
    finally:
        if created:
            try:
                cleanup=run('shell','rm','-rf','--',remote,check=False)
                report['ownTemporaryFilesRemoved']=cleanup.returncode==0 and run('shell','test','!','-e',remote,check=False).returncode==0
                if not report['ownTemporaryFilesRemoved']:report.update(status='failed',error='Probe cleanup did not verify')
                report['freeBytesAfterCleanup']=free_bytes()
            except Exception as failure:report.update(status='failed',cleanupError=str(failure),ownTemporaryFilesRemoved=False,temporaryDeviceDirectory=remote)
        (output/'onnx-device-verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
