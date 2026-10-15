#!/usr/bin/env python3
"""Verify task restoration on the user's existing visible Pixel; never reset its data."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
args=parser.parse_args()
serial=args.serial
adb=str(Path(os.environ.get('ANDROID_HOME') or Path.home()/'Library/Android/sdk')/'platform-tools/adb')
pkg='com.rohanc.ocrscanner'
report={'timestamp':datetime.now(timezone.utc).isoformat(),'status':'failed','serial':args.serial}
output=root/'output/android'
output.mkdir(parents=True,exist_ok=True)
def shell(*args):
    return subprocess.check_output([adb,'-s',serial,'shell',*args],text=True,timeout=20).strip()

def nodes():
    shell('uiautomator','dump','/sdcard/folio-recovery-window.xml')
    return list(ET.fromstring(shell('cat','/sdcard/folio-recovery-window.xml')).iter('node'))
def is_selected(label):
    current=nodes()
    parents={child:parent for parent in current for child in parent}
    for node in current:
        if node.get('text')!=label and node.get('content-desc')!=label:continue
        while node is not None:
            # Android maps Role.RadioButton to checked; Role.Tab uses selected.
            if node.get('selected')=='true' or (node.get('checkable')=='true' and node.get('checked')=='true'):return True
            node=parents.get(node)
    return False
def wait_for(text):
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        found=[n for n in nodes() if (n.get('text')==text or n.get('content-desc')==text)]
        if found:return found[0]
        time.sleep(.5)
    raise RuntimeError('Expected visible control: '+text)
def tap(text):
    n=wait_for(text);left,top,right,bottom=map(int,re.findall(r'\d+',n.get('bounds')))
    shell('input','tap',str((left+right)//2),str((top+bottom)//2))
def stopped_with_saved_state(dump):
    # Inspect the top Folio activity, not an older stopped copy underneath it.
    activity=re.search(r'\* Hist[^\n]*ActivityRecord\{[^\n]*'+re.escape(pkg)+r'/\.MainActivity[^\n]*\}',dump)
    if activity is None:return False
    block=re.split(r'\n\s*\* (?:Hist|Task)',dump[activity.end():],maxsplit=1)[0]
    return 'mHaveState=true' in block and 'state=STOPPED' in block
def launch():
    return shell('am','start','-W','-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-f','0x10200000','-n',pkg+'/.MainActivity')
try:
    if shell('getprop','ro.kernel.qemu')!='1':raise RuntimeError('Use the existing Pixel emulator for this task-restoration probe')
    avd=subprocess.check_output([adb,'-s',serial,'emu','avd','name'],text=True,timeout=20).splitlines()[0].strip()
    if avd!='Pixel_9_Pro':raise RuntimeError('This project uses only the existing Pixel_9_Pro emulator')
    report.update(avd=avd,usesExistingEmulator=True,emulatorStopped=False,dataReset=False)
    launch()
    # The probe only opens an unpersisted setup state; it imports/deletes no documents.
    current=nodes()
    if any(n.get('text')=='Skip' for n in current):tap('Skip')
    for _ in range(6):
        if any(n.get('text')=='Your library' for n in nodes()):break
        shell('input','keyevent','KEYCODE_BACK')
    wait_for('Your library')
    tap('New scan');tap('ID cards');tap('Multiple cards')
    if not is_selected('ID cards') or not is_selected('Multiple cards'):
        raise RuntimeError('Setup choices were not selected before the process-death probe')
    report['choicesSelectedBeforeKill']=True
    (output/'process-recovery-before.xml').write_text(shell('cat','/sdcard/folio-recovery-window.xml'))
    before=shell('pidof',pkg)
    if not before.isdigit():raise RuntimeError('Expected one Folio test process')
    shell('input','keyevent','KEYCODE_HOME')
    deadline=time.monotonic()+15
    while time.monotonic()<deadline:
        if stopped_with_saved_state(shell('dumpsys','activity','activities')):break
        time.sleep(.25)
    else:raise RuntimeError('Android did not finish saving the stopped activity; process-death probe cannot proceed')
    report['savedActivityStateBeforeKill']=True
    # Kill under the debuggable app's own UID. Unlike force-stop, this retains
    # the Android task and its saved state; no data is reset or deleted.
    shell('run-as',pkg,'kill','-9',before)
    deadline=time.monotonic()+10
    while time.monotonic()<deadline:
        probe=subprocess.run([adb,'-s',args.serial,'shell','pidof',pkg],capture_output=True,text=True,timeout=10)
        if not probe.stdout.strip():break
        time.sleep(.5)
    else:raise RuntimeError('Process did not stop; task-restoration proof is invalid')
    report['oldProcessStopped']=True
    launch()
    wait_for('Import images')
    wait_for('ID cards')
    (output/'process-recovery-after.xml').write_text(shell('cat','/sdcard/folio-recovery-window.xml'))
    if not is_selected('ID cards'):raise RuntimeError('Scan mode was not restored')
    wait_for('Multiple cards')
    if not is_selected('Multiple cards'):raise RuntimeError('Card workflow was not restored')
    after=shell('pidof',pkg)
    if not after or after==before:raise RuntimeError('A distinct restored process was not confirmed')
    report.update(status='passed',beforePid=before,afterPid=after,restoredRoute='02',restoredMode='CARDS',restoredWorkflow='multiple')
    tap('Back')
except Exception as failure:report['error']=str(failure)
finally:
    (output/'process-recovery-verification.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
raise SystemExit(0 if report['status']=='passed' else 1)
