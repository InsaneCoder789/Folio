import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import uuid
from tools.run_visible_job import ROOT,validate_plan


class VisibleJobTests(unittest.TestCase):
    def test_invalid_shell_strings_and_duplicate_stages_are_rejected(self):
        good=dict(id='visible-test',stages=[dict(id='first',name='First',argv=[sys.executable,'-c','print(1)'])])
        self.assertEqual(good,validate_plan(good))
        with self.assertRaises(ValueError):validate_plan(dict(good,stages=[dict(id='first',name='First',argv='echo unsafe')]))
        with self.assertRaises(ValueError):validate_plan(dict(good,stages=good['stages']*2))
        with self.assertRaises(ValueError):validate_plan(dict(good,id='../escape'))

    def execute(self,stages):
        identity='monitor-test-'+uuid.uuid4().hex
        folder=ROOT/'output/training-monitor/jobs'/identity
        self.addCleanup(lambda:shutil.rmtree(folder) if folder.exists() else None)
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'plan.json';path.write_text(json.dumps(dict(id=identity,name='Synthetic visibility test',stages=stages)))
            process=subprocess.run([sys.executable,str(ROOT/'tools/run_visible_job.py'),'--plan',str(path)],cwd=ROOT,capture_output=True,text=True,timeout=15)
        return process,json.loads((folder/'status.json').read_text()),folder

    def test_progress_and_success_are_durable(self):
        code='import json;print(json.dumps(dict(linesCompleted=2,linesTotal=2)))'
        process,status,folder=self.execute([dict(id='work',name='Synthetic work',argv=[sys.executable,'-c',code])])
        self.assertEqual(0,process.returncode,process.stderr)
        self.assertEqual('completed',status['status'])
        self.assertEqual(dict(completed=2,total=2,unit='lines'),status['progress'])
        self.assertEqual('completed',status['stages'][0]['status'])
        self.assertTrue((folder/'work.log').is_file())

    def test_failure_stops_later_stages_and_keeps_failure_visible(self):
        process,status,_=self.execute([
            dict(id='fail',name='Expected test failure',argv=[sys.executable,'-c','raise SystemExit(7)']),
            dict(id='never',name='Must not run',argv=[sys.executable,'-c','raise SystemExit(99)'])])
        self.assertEqual(7,process.returncode)
        self.assertEqual('failed',status['status'])
        self.assertEqual(7,status['stages'][0]['exitCode'])
        self.assertEqual('queued',status['stages'][1]['status'])
        self.assertIn('exit 7',status['error'])


if __name__=='__main__':unittest.main()
