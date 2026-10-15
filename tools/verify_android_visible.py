#!/usr/bin/env python3
"""Test Folio on the user's already-running visible emulator. Never launch, reset, stop or resize it."""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
import re
import subprocess
import sys
from android_toolchain import build_environment

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--test-class',help='Optional instrumentation class; otherwise run the complete native suite')
parser.add_argument('--launch-app',action='store_true',help='Finish on the real app library rather than the debug review fixture')
parser.add_argument('--recovery',action='store_true',help='After passing device tests, verify real process-death restoration on the same Pixel')
args=parser.parse_args()
if args.recovery:args.launch_app=True
env=build_environment()
sdk=Path(env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT') or Path.home()/'Library/Android/sdk')
adb=str(sdk/'platform-tools/adb')
output=root/'output/android';output.mkdir(parents=True,exist_ok=True)
report={'timestamp':datetime.now(timezone.utc).isoformat(),'serial':args.serial,'status':'failed','testClass':args.test_class,'usesExistingEmulator':True,'emulatorStopped':False}
report['sourceRevision']=subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip()
report['sourceDirty']=bool(subprocess.check_output(['git','status','--porcelain','--','android','tools'],cwd=root,text=True).strip())

def command(parts,**kw):return subprocess.run(parts,check=True,text=True,timeout=kw.pop('timeout',60),**kw)
def shell(*parts):return command([adb,'-s',args.serial,'shell',*parts],capture_output=True).stdout.strip()
try:
    if shell('getprop','ro.kernel.qemu')!='1':raise RuntimeError('Select the already-running Pixel emulator')
    report['avd']=command([adb,'-s',args.serial,'emu','avd','name'],capture_output=True).stdout.splitlines()[0].strip()
    report['sdkInt']=shell('getprop','ro.build.version.sdk')
    report['displaySize']=shell('wm','size');report['displayDensity']=shell('wm','density')
    report['abi']=shell('getprop','ro.product.cpu.abi')
    command([sys.executable,str(root/'tools/verify_android.py'),'--abi',report['abi']],cwd=root,env=env,timeout=900)
    apk=root/'android/app/build/outputs/apk/debug/app-debug.apk'
    tests=root/'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    command([adb,'-s',args.serial,'install','-r',str(apk)],capture_output=True,timeout=120)
    command([adb,'-s',args.serial,'install','-r','-t',str(tests)],capture_output=True,timeout=120)
    shell('input','keyevent','KEYCODE_WAKEUP')
    instrumentation=[adb,'-s',args.serial,'shell','am','instrument','-w','-r']
    if args.test_class:instrumentation+=['-e','class',args.test_class]
    instrumentation+=['com.rohanc.ocrscanner.test/androidx.test.runner.AndroidJUnitRunner']
    log=output/'visible-device-tests.log'
    with log.open('w') as stream:result=subprocess.run(instrumentation,env=env,stdout=stream,stderr=subprocess.STDOUT,text=True,timeout=900)
    content=log.read_text();report['log']=str(log.relative_to(root))
    passed=re.search(r'OK \((\d+) tests?\)',content)
    failures=re.search(r'Tests run:\s*(\d+),\s*Failures:\s*(\d+)',content)
    report['deviceTests']={'tests':int(passed.group(1)) if passed else int(failures.group(1)) if failures else 0,'failures':int(failures.group(2)) if failures else 0,'errors':0 if passed or failures else 1}
    # Collect partial captures too, so a failed test can still be diagnosed visually.
    ui=output/'ui-parity-pixel9';ui.mkdir(exist_ok=True)
    for route in [f'{i:02}' for i in range(1,16)]+['intro-1','intro-2']:
        for suffix in ('','-large','-narrow-large'):
            subprocess.run([adb,'-s',args.serial,'pull',f'/sdcard/Download/FolioUiParity/{route}{suffix}.png',str(ui/f'{route}{suffix}.png')],capture_output=True,timeout=10)
    if result.returncode or not passed:raise RuntimeError('Native instrumentation did not all pass; inspect visible-device-tests.log')
    expected=4 if args.test_class and args.test_class.endswith('NativeUiParityTest') else 51 if not args.test_class else 1
    if int(passed.group(1))<expected:raise RuntimeError('Expected native checks were not all executed')
    report['status']='passed'
except Exception as failure:
    report['error']=str(failure)
    if isinstance(failure,subprocess.CalledProcessError):report['commandError']=str(failure.stdout or '')+str(failure.stderr or '')
finally:
    # Leave the same emulator visible in the native review activity. No uninstall or data reset.
    try:
        activity='com.rohanc.ocrscanner/.MainActivity' if args.launch_app else 'com.rohanc.ocrscanner/.feature.ui.UiGalleryActivity'
        launch=command([adb,'-s',args.serial,'shell','am','start','-W','-n',activity,*([] if args.launch_app else ['--es','screen','01'])],capture_output=True,timeout=30)
        report['launchOutput']=launch.stdout
    except Exception as failure:report['launchError']=str(failure)
    if report['status']=='passed' and args.recovery:
        try:
            recovery=command([sys.executable,str(root/'tools/verify_android_recovery.py'),'--serial',args.serial],cwd=root,capture_output=True,timeout=120)
            report['processRecovery']=json.loads(recovery.stdout)
        except Exception as failure:
            report['status']='failed';report['error']='Process-death restoration did not pass.'
            report['recoveryError']=str(failure)
            if isinstance(failure,subprocess.CalledProcessError):report['recoveryOutput']=str(failure.stdout or '')+str(failure.stderr or '')
    (output/'visible-device-verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)
raise SystemExit(0 if report['status']=='passed' else 1)
