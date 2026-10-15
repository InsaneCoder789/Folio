#!/usr/bin/env python3
"""Build an optimized device-specific test candidate; never sign a production release."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
from android_toolchain import build_environment

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--serial', required=True, help='Existing test emulator/device; it is not reset or terminated')
args = parser.parse_args()
env = build_environment()
sdk = Path(env.get('ANDROID_HOME') or env.get('ANDROID_SDK_ROOT') or Path.home() / 'Library/Android/sdk')
adb = str(sdk / 'platform-tools/adb')
env['ANDROID_SERIAL'] = args.serial
output = root / 'output/android'
output.mkdir(parents=True,exist_ok=True)
report = {'timestamp': datetime.now(timezone.utc).isoformat(), 'sourceRevision': subprocess.check_output(['git','rev-parse','HEAD'], cwd=root,text=True).strip(),
          'sourceDirty': bool(subprocess.check_output(['git','status','--porcelain','--','android','tools'], cwd=root,text=True).strip()),
          'status': 'failed', 'serial': args.serial, 'productionSigned': False}
try:
    avd=subprocess.check_output([adb,'-s',args.serial,'emu','avd','name'],text=True,timeout=20).splitlines()[0].strip()
    if args.serial!='emulator-5554' or avd!='Pixel_9_Pro':
        raise RuntimeError('Use only the existing Pixel_9_Pro emulator-5554')
    report.update(avd=avd,javaHome=env['JAVA_HOME'],usesExistingEmulator=True,emulatorStopped=False,appUninstalled=False)
    abi = subprocess.check_output([adb, '-s', args.serial, 'shell', 'getprop', 'ro.product.cpu.abi'], text=True, timeout=20).strip()
    if abi not in ('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'):
        raise RuntimeError('Unsupported or unavailable target ABI')
    report['abi'] = abi
    command = ['./gradlew', '--no-daemon', '--console=plain', '-PfolioTestBuildType=benchmark', '-PfolioAbis='+abi,
               ':app:assembleBenchmark', ':app:lintBenchmark', ':app:assembleBenchmarkAndroidTest']
    report['command'] = command
    with (output / 'release-build.log').open('w') as stream:
        subprocess.run(command, cwd=root / 'android', env=env, stdout=stream, stderr=subprocess.STDOUT, check=True,timeout=1200)
    apk = root/'android/app/build/outputs/apk/benchmark/app-benchmark.apk'
    with zipfile.ZipFile(apk) as archive:
        packaged = sorted({name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')})
    if packaged != [abi]: raise RuntimeError('APK ABI contents do not match the device')
    report.update(apk=str(apk.relative_to(root)), apkBytes=apk.stat().st_size, apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(), packagedAbis=packaged)
    # Direct instrumentation avoids connected-test teardown uninstalling its target.
    # The separate application ID preserves the user's debug library and preferences.
    subprocess.run([adb,'-s',args.serial,'install','-r',str(apk)],check=True,capture_output=True,timeout=90)
    tests=root/'android/app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk'
    subprocess.run([adb,'-s',args.serial,'install','-r','-t',str(tests)],check=True,capture_output=True,timeout=90)
    subprocess.run([adb,'-s',args.serial,'shell','input','keyevent','KEYCODE_WAKEUP'],check=True,capture_output=True,timeout=20)
    log=output/'release-device-tests.log'
    with log.open('w') as stream:
        result=subprocess.run([adb,'-s',args.serial,'shell','am','instrument','-w','-r',
                               'com.rohanc.ocrscanner.benchmark.test/androidx.test.runner.AndroidJUnitRunner'],
                              env=env,stdout=stream,stderr=subprocess.STDOUT,text=True,timeout=1200)
    content=log.read_text();passed=re.search(r'OK \((\d+) tests?\)',content)
    failures=re.search(r'Tests run:\s*(\d+),\s*Failures:\s*(\d+)',content)
    report['deviceTests']={'tests':int(passed.group(1)) if passed else int(failures.group(1)) if failures else 0,
                           'failures':int(failures.group(2)) if failures else 0,'errors':0 if passed or failures else 1}
    report['deviceLog']=str(log.relative_to(root))
    # Six debug-only gallery/component tests are excluded from the release variant.
    if result.returncode or not passed or int(passed.group(1))<45:
        raise RuntimeError('Optimized runtime suite did not execute and pass all 45 production-flow checks')
    launch = subprocess.check_output([adb,'-s',args.serial,'shell','am','start','-W','-n','com.rohanc.ocrscanner.benchmark/com.rohanc.ocrscanner.MainActivity'],text=True,timeout=30)
    if 'Status: ok' not in launch: raise RuntimeError('Optimized app did not launch')
    subprocess.run([adb,'-s',args.serial,'shell','pidof','com.rohanc.ocrscanner.benchmark'],check=True,capture_output=True,timeout=90)
    report.update(status='passed', launchOutput=launch)
except Exception as failure:
    report['error'] = str(failure)
finally:
    (output/'release-verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2),flush=True)
raise SystemExit(0 if report['status']=='passed' else 1)
