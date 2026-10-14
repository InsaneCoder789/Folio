#!/usr/bin/env python3
"""Finite Android verification; no emulator launch and no Markdown generation."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[1]
project = root / 'android'
output = root / 'output' / 'android'
output.mkdir(parents=True, exist_ok=True)
env = os.environ.copy()
studio_jdk = Path('/Applications/Android Studio.app/Contents/jbr/Contents/Home')
if not env.get('JAVA_HOME') and studio_jdk.is_dir():
    env['JAVA_HOME'] = str(studio_jdk)
tasks = [':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', ':app:assembleDebugAndroidTest']
wrapper = 'gradlew.bat' if os.name == 'nt' else './gradlew'
command = [wrapper, '--no-daemon', '--console=plain', *tasks]
print('Android verification: debug APK, JVM tests, lint, device-test APK compilation.', flush=True)
log = output / 'build.log'
with log.open('w') as stream:
    process = subprocess.run(command, cwd=project, env=env, stdout=stream, stderr=subprocess.STDOUT, check=False)
unit = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for report in (project / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'):
    node = ET.parse(report).getroot()
    for field in unit:
        unit[field] += int(node.get(field, 0))
if process.returncode == 0 and (unit['tests'] < 23 or unit['failures'] or unit['errors'] or unit['skipped']):
    print('Verification failed: expected all 23 JVM tests to execute and pass.', flush=True)
    process.returncode = 1
apk = project / 'app/build/outputs/apk/debug/app-debug.apk'
result = {
    'timestamp': datetime.now(timezone.utc).isoformat(),
    'sourceRevision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
    'command': command, 'exitCode': process.returncode,
    'status': 'passed' if process.returncode == 0 else 'failed',
    'unitTests': unit,
    'instrumentationExecuted': False,
    'instrumentationNote': 'Device tests are compiled only. Connect an emulator/phone to execute them.',
    'buildLog': str(log.relative_to(root)),
    'apk': str(apk.relative_to(root)) if apk.exists() and process.returncode == 0 else None,
    'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest() if apk.exists() and process.returncode == 0 else None,
}
(output / 'verification.json').write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2), flush=True)
if process.returncode:
    print('\nLast build output:\n' + '\n'.join(log.read_text().splitlines()[-100:]), flush=True)
sys.exit(process.returncode)
