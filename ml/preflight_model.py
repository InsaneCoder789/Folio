"""Cache the pinned recognizer and verify one real line can execute before training."""
import argparse
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import time


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--image',type=Path,required=True)
    args=parser.parse_args()
    if not args.image.is_file():parser.error('Use an extracted source line image')
    os.environ.setdefault('HF_HOME',str(Path.home()/'.cache/folio-handwriting-models'))
    os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK','1')
    os.environ.setdefault('TOKENIZERS_PARALLELISM','false')
    import torch
    import transformers
    from PIL import Image
    from transformers import TrOCRProcessor,VisionEncoderDecoderModel
    name='microsoft/trocr-small-handwritten'
    revision='b4648cfa171985a6745f37ddd637e98c0da958ac'
    processor=TrOCRProcessor.from_pretrained(name,revision=revision,use_fast=False)
    model=VisionEncoderDecoderModel.from_pretrained(name,revision=revision)
    device='mps' if torch.backends.mps.is_available() else 'cpu'
    model.to(device).eval()
    with Image.open(args.image) as image:
        pixels=processor(images=image.convert('RGB'),return_tensors='pt').pixel_values.to(device)
    start=time.perf_counter()
    with torch.inference_mode():
        output=model.generate(pixels,max_new_tokens=128,num_beams=1)
    prediction=processor.batch_decode(output,skip_special_tokens=True)[0]
    report={'status':'passed','completedAt':datetime.now(timezone.utc).isoformat(),'baseModel':name,
            'baseRevision':revision,'torch':torch.__version__,'transformers':transformers.__version__,
            'device':device,'input':str(args.image),'imageSha256':hashlib.sha256(args.image.read_bytes()).hexdigest(),
            'pixelShape':list(pixels.shape),'prediction':prediction,'seconds':time.perf_counter()-start,
            'trainingExecuted':False,'benchmark':False,'scope':'One-line runtime smoke check; no accuracy or improvement claim'}
    path=Path('ml/artifacts/model-preflight.json');path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
