"""Verified local cache of the official Apache-2.0 mobile text detector."""
import hashlib
from pathlib import Path
import urllib.request
from ml.artifacts import file_sha256,write_json_atomic
from ml.evaluate_mobile_baseline import verify_payload

MODEL='PaddlePaddle/PP-OCRv5_mobile_det'
REVISION='0d63e78e2b680928f6b1747d76a08db6e645efb7'
FILES={
    'config.json':(2871,'git-sha1','4fc190d78e4425094b31a2d3a4744a3623d00b50'),
    'inference.json':(229777,'git-sha1','6cd678f39460a27372f8fe570e4e12e7a383418f'),
    'inference.pdiparams':(4692937,'sha256','afa1820cb16c1fd0dad589d0f8b389139061c1ef6d68019685fd07be997dda5b'),
    'inference.yml':(903,'git-sha1','579d10695d2dd6e85c5ecba02d151ae5c077aa49')}


def obtain_detector():
    root=Path.home()/'.cache/folio-mobile-detector'/REVISION;root.mkdir(parents=True,exist_ok=True);hashes={}
    for name,(size,algorithm,expected) in FILES.items():
        target=root/name
        if target.exists():payload=target.read_bytes()
        else:
            with urllib.request.urlopen(f'https://huggingface.co/{MODEL}/resolve/{REVISION}/{name}',timeout=30) as response:payload=response.read(size+1)
        verify_payload(payload,size,algorithm,expected)
        if not target.exists():
            temporary=target.with_suffix(target.suffix+'.partial')
            try:temporary.write_bytes(payload);temporary.replace(target)
            finally:temporary.unlink(missing_ok=True)
        hashes[name]=file_sha256(target)
    write_json_atomic(root/'provenance.json',dict(model=MODEL,revision=REVISION,license='Apache-2.0',sha256=hashes))
    return root,hashes
