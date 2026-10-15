"""Prepare an isolated training runtime and inspect licensed source data without downloading a full archive."""
import argparse
from datetime import datetime,timezone
import json
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.request
import venv
import zipfile
from ml.http_range import HttpRangeFile,stored_member


def inspect_source(root):
    with urllib.request.urlopen('https://zenodo.org/api/records/248733',timeout=30) as response:metadata=json.load(response)
    if metadata['metadata']['license']['id']!='cc-by-4.0':raise RuntimeError('Source license differs from the selected dataset contract')
    archive=metadata['files'][0]
    source=root/'ml/data/bentham';source.mkdir(parents=True,exist_ok=True)
    (source/'source-provenance.json').write_text(json.dumps(metadata,indent=2)+'\n')
    remote=HttpRangeFile(archive['links']['self'],archive['size'],block_size=1024*1024,cache_blocks=8,cache_directory=source/'ranges',max_download_bytes=64*1024*1024)
    indexes={}
    with zipfile.ZipFile(remote) as outer:
        for key in ('ICDAR-HTR-Competition-2015/TRAIN/contestHTRtS.zip','ICDAR-HTR-Competition-2015/TEST/12-pages-dataset.zip'):
            with zipfile.ZipFile(stored_member(outer,outer.getinfo(key))) as nested:
                entries=[dict(name=f.filename,bytes=f.file_size,compressedBytes=f.compress_size,crc=f.CRC,offset=f.header_offset,compression=f.compress_type) for f in nested.infolist()]
                indexes[key]=entries
                print(json.dumps({'archive':key,'entries':len(entries),'images':sum(e['name'].lower().endswith(('.png','.jpg','.tif','.tiff')) for e in entries)}),flush=True)
        for key in ('ICDAR-HTR-Competition-2015/TEST/12-pages-dataset-reference_v2.dat','ICDAR-HTR-Competition-2015/TEST/38-pages-dataset-reference_v2.dat'):
            data=outer.read(key)
            (source/Path(key).name).write_bytes(data)
    (source/'archive-index.json').write_text(json.dumps(indexes,indent=2)+'\n')
    return {'source':'https://zenodo.org/records/248733','license':'CC-BY-4.0','archiveBytes':archive['size'],
            'downloadedBytes':remote.bytes_downloaded,'index':str(source/'archive-index.json'),
            'integrity':'ZIP CRC for read entries; source metadata retained; whole-archive MD5 not verified',
            'scope':'Historical English handwriting; modern handwriting still needs separate evaluation'}


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--environment',type=Path,default=Path.home()/'.cache/folio-handwriting-venv')
    args=parser.parse_args();root=Path(__file__).resolve().parents[1]
    output=root/'ml/artifacts';output.mkdir(parents=True,exist_ok=True)
    report={'startedAt':datetime.now(timezone.utc).isoformat(),'status':'failed','trainingExecuted':False}
    try:
        free=shutil.disk_usage(root).free
        if free<5*1024**3:raise RuntimeError('Training preparation needs at least 5 GB free space')
        print('Inspecting source archive with bounded byte ranges.',flush=True)
        report['dataset']=inspect_source(root)
        if not (args.environment/'bin/python').exists():venv.EnvBuilder(with_pip=True,symlinks=True).create(args.environment)
        python=args.environment/'bin/python'
        subprocess.run([str(python),'-m','pip','install','--no-cache-dir','-r',str(root/'ml/requirements.txt')],check=True,timeout=1800)
        probe=subprocess.check_output([str(python),'-c','import torch,transformers,json;print(json.dumps(dict(torch=torch.__version__,transformers=transformers.__version__,mps=torch.backends.mps.is_available(),cuda=torch.cuda.is_available())))'],text=True,timeout=30)
        report.update(status='passed',environment=str(args.environment),runtime=json.loads(probe),completedAt=datetime.now(timezone.utc).isoformat())
    except Exception as failure:report['error']=str(failure)
    (output/'preparation-report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)
    return 0 if report['status']=='passed' else 1


if __name__=='__main__':raise SystemExit(main())
