#!/usr/bin/env python3
"""Run Folio's native gate on an existing, isolated read-only AVD instance."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--avd', required=True, help='An already installed AVD; nothing is downloaded')
args = parser.parse_args()
sdk = Path(os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT') or Path.home() / 'Library/Android/sdk')
adb = str(sdk / 'platform-tools/adb')
emulator = str(sdk / 'emulator/emulator')
output = root / 'output/android'
output.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
studio_jdk = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
if not env.get('JAVA_HOME') and studio_jdk.is_dir():
    env['JAVA_HOME'] = str(studio_jdk)
report = {'timestamp': datetime.now(timezone.utc).isoformat(), 'avd': args.avd, 'status': 'failed', 'deviceTests': {}, 'activityLaunched': False}
owned = None
log_handle = None
exit_code = 1

def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, **kwargs)

try:
    run([sys.executable, str(root / 'tools/verify_android.py')], cwd=root, env=env)
    available = run([emulator, '-list-avds'], capture_output=True).stdout.splitlines()
    if args.avd not in available:
        raise RuntimeError('Requested AVD is not installed.')
    # Reserve an unused emulator pair; never target or terminate another instance.
    ports = []
    try:
        for port in (5556, 5557):
            sock = socket.socket(); ports.append(sock); sock.bind(('127.0.0.1', port))
    finally:
        for sock in ports: sock.close()
    serial = 'emulator-5556'
    devices = run([adb, 'devices'], capture_output=True).stdout
    if serial in devices:
        raise RuntimeError('Reserved emulator serial is already in use.')
    log_handle = (output / 'device-emulator.log').open('w')
    owned = subprocess.Popen([emulator, '-avd', args.avd, '-port', '5556', '-read-only', '-no-window',
        '-no-audio', '-no-snapshot', '-gpu', 'swiftshader', '-memory', '2048'],
        stdout=log_handle, stderr=subprocess.STDOUT, env=env)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if owned.poll() is not None:
            raise RuntimeError('Emulator exited before boot; inspect device-emulator.log.')
        boot = subprocess.run([adb, '-s', serial, 'shell', 'getprop', 'sys.boot_completed'], text=True, capture_output=True, timeout=15)
        if boot.returncode == 0 and boot.stdout.strip() == '1': break
        time.sleep(2)
    else:
        raise RuntimeError('Emulator boot timed out.')
    report['sdkInt'] = run([adb, '-s', serial, 'shell', 'getprop', 'ro.build.version.sdk'], capture_output=True).stdout.strip()
    env['ANDROID_SERIAL'] = serial
    wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
    with (output / 'device-tests.log').open('w') as stream:
        result = subprocess.run([wrapper, '--no-daemon', '--console=plain', ':app:connectedDebugAndroidTest'],
            cwd=root / 'android', env=env, stdout=stream, stderr=subprocess.STDOUT)
    counts = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
    reports = root / 'android/app/build/outputs/androidTest-results/connected/debug'
    for path in reports.rglob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        for key in counts: counts[key] += int(suite.get(key, '0'))
    report['deviceTests'] = counts
    if result.returncode or counts['tests'] < 20 or counts['failures'] or counts['errors'] or counts['skipped']:
        raise RuntimeError('Device tests did not all pass; inspect device-tests.log and Gradle reports.')
    # Connected instrumentation may uninstall its target during teardown.
    # Install the app explicitly before the independent launch gate.
    run([adb, '-s', serial, 'install', '-r', str(root / 'android/app/build/outputs/apk/debug/app-debug.apk')], capture_output=True)
    launch = run([adb, '-s', serial, 'shell', 'am', 'start', '-W', '-n',
        'com.rohanc.ocrscanner/.MainActivity'], capture_output=True)
    report['launchOutput'] = launch.stdout
    if 'Status: ok' not in launch.stdout:
        raise RuntimeError('Activity launch was not confirmed.')
    report['activityLaunched'] = True
    time.sleep(3)
    process = run([adb, '-s', serial, 'shell', 'pidof', 'com.rohanc.ocrscanner'], capture_output=True)
    if not process.stdout.strip(): raise RuntimeError('App exited after launch.')
    with (output / 'device-library.png').open('wb') as stream:
        subprocess.run([adb, '-s', serial, 'exec-out', 'screencap', '-p'], stdout=stream, check=True)
    report['status'] = 'passed'; exit_code = 0
except Exception as error:
    report['error'] = str(error)
finally:
    if owned is not None and owned.poll() is None:
        owned.terminate()
        try: owned.wait(timeout=15)
        except subprocess.TimeoutExpired: owned.kill(); owned.wait()
    if log_handle is not None: log_handle.close()
    (output / 'device-verification.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2), flush=True)
sys.exit(exit_code)
