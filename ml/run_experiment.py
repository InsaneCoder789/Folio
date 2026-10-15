"""Run data materialization and a bounded training experiment after preparation passes."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--steps',type=int,default=300)
    args=parser.parse_args();root=Path(__file__).resolve().parents[1]
    preparation=json.loads((root/'ml/artifacts/preparation-report.json').read_text())
    if preparation.get('status')!='passed':raise RuntimeError('Dataset/runtime preparation has not passed')
    if shutil.disk_usage(root).free<4*1024**3:raise RuntimeError('At least 4 GB free space is needed before the experiment')
    python=Path(preparation['environment'])/'bin/python'
    subprocess.run([str(python),'-m','ml.prepare_subset'],cwd=root,check=True,timeout=3600)
    env=os.environ.copy();env['HF_HOME']=str(Path.home()/'.cache/folio-handwriting-models');env['PYTORCH_ENABLE_MPS_FALLBACK']='1'
    subprocess.run([str(python),'-m','ml.train_handwriting','--manifest','ml/data/bentham-pilot/manifest.jsonl',
                    '--output','ml/artifacts/handwriting-pilot','--steps',str(args.steps)],cwd=root,env=env,check=True,timeout=14400)


if __name__=='__main__':main()
