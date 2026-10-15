"""Publish experiment metadata and immutable checkpoints without replacing a good partial save."""
import json
import os
from pathlib import Path
import shutil
import tempfile
import hashlib


def file_sha256(path):
    with Path(path).open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()


def write_json_atomic(path,value):
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    fd,name=tempfile.mkstemp(prefix='.'+path.name+'-',suffix='.partial',dir=path.parent)
    temporary=Path(name)
    try:
        with os.fdopen(fd,'w') as stream:
            json.dump(value,stream,indent=2,ensure_ascii=False,allow_nan=False);stream.write('\n')
            stream.flush();os.fsync(stream.fileno())
        temporary.replace(path)
    finally:temporary.unlink(missing_ok=True)


def publish_checkpoint(output,step,writer,minimum_free_bytes=1024**3):
    """Only rename a complete new checkpoint; earlier step directories are immutable."""
    output=Path(output);output.mkdir(parents=True,exist_ok=True)
    if step<0:raise ValueError('Checkpoint step cannot be negative')
    target=output/f'checkpoint-{step:06}'
    if target.exists():raise FileExistsError('Checkpoint already exists; choose a fresh experiment output')
    if shutil.disk_usage(output).free<minimum_free_bytes:raise RuntimeError('Not enough free disk to publish the checkpoint safely')
    temporary=Path(tempfile.mkdtemp(prefix='.checkpoint-',suffix='.partial',dir=output))
    try:
        writer(temporary)
        config=temporary/'config.json'
        if not config.is_file() or not isinstance(json.loads(config.read_text()),dict):raise ValueError('Missing or invalid model config')
        if not list(temporary.glob('*.safetensors')):raise ValueError('Missing safe model weights')
        for path in temporary.rglob('*'):
            if path.is_file():
                with path.open('rb') as stream:os.fsync(stream.fileno())
        temporary.rename(target)
        return target
    finally:
        if temporary.exists():shutil.rmtree(temporary)
