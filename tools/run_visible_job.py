"""Run an explicit finite argv plan with durable, HUD-readable stage progress."""
import argparse
from datetime import datetime,timezone
import json
import os
from pathlib import Path
import re
import subprocess
import signal
import sys

ROOT=Path(__file__).resolve().parent.parent


def validate_plan(plan):
    if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,80}',plan.get('id','')):raise ValueError('Invalid visible job ID')
    stages=plan.get('stages',[])
    if not stages or len(stages)>20:raise ValueError('Use 1..20 finite stages')
    ids=set()
    for stage in stages:
        if not re.fullmatch(r'[a-z0-9][a-z0-9-]{0,50}',stage.get('id','')) or stage['id'] in ids:raise ValueError('Invalid or duplicate stage ID')
        ids.add(stage['id'])
        argv=stage.get('argv')
        if not isinstance(argv,list) or not argv or not all(isinstance(x,str) and x and '\x00' not in x for x in argv):raise ValueError('Each stage needs literal argv; shell strings are not supported')
        if not isinstance(stage.get('name'),str) or not stage['name'].strip():raise ValueError('Stage needs a user-facing name')
    return plan


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--plan',type=Path,required=True);a=p.parse_args()
    plan=validate_plan(json.loads(a.plan.read_text()))
    folder=ROOT/'output/training-monitor/jobs'/plan['id']
    folder.mkdir(parents=True,exist_ok=False)
    sys.path.insert(0,str(ROOT))
    from ml.artifacts import write_json_atomic
    status=dict(id=plan['id'],name=plan.get('name',plan['id']),status='running',startedAt=datetime.now(timezone.utc).isoformat(),
                stageIndex=0,stages=[dict(id=s['id'],name=s['name'],status='queued') for s in plan['stages']],
                events=[],progress=None,error=None)
    def publish():
        status['updatedAt']=datetime.now(timezone.utc).isoformat();write_json_atomic(folder/'status.json',status)
    publish()
    def interrupted(signum,frame):raise SystemExit(128+signum)
    signal.signal(signal.SIGTERM,interrupted)
    try:
        for index,stage in enumerate(plan['stages']):
            status.update(stageIndex=index,stage=stage['name'],progress=None)
            status['stages'][index]['status']='running';publish()
            env=os.environ.copy();env.update(stage.get('env',{}))
            with (folder/(stage['id']+'.log')).open('w') as logfile:
                child=subprocess.Popen(stage['argv'],cwd=ROOT,env=env,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
                try:
                    for line in child.stdout:
                        logfile.write(line);logfile.flush();print(line,end='',flush=True)
                        status['events']=(status['events']+[line.rstrip()])[-12:]
                        try:
                            event=json.loads(line)
                            if isinstance(event,dict):
                                for completed,total,unit in [('linesCompleted','linesTotal','lines'),('pagesCompleted','pagesTotal','pages'),('bytes','total','bytes')]:
                                    c,t=event.get(completed),event.get(total)
                                    if isinstance(c,(int,float)) and isinstance(t,(int,float)) and 0<=c<=t and t>0:
                                        status['progress']=dict(completed=c,total=t,unit=unit);break
                        except json.JSONDecodeError:pass
                        publish()
                    code=child.wait()
                finally:
                    if child.poll() is None:child.terminate();child.wait()
            status['stages'][index].update(status='completed' if code==0 else 'failed',exitCode=code)
            if code:
                status.update(status='failed',error=stage['name']+' failed; exit '+str(code));publish();return code
            publish()
        status.update(status='completed',completedAt=datetime.now(timezone.utc).isoformat());publish();return 0
    except BaseException as exc:
        status.update(status='failed',error=type(exc).__name__+': '+str(exc));publish();raise


if __name__=='__main__':sys.exit(main() or 0)
