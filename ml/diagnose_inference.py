"""Compare CPU and Apple GPU decoding on training lines before blaming training data."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import time
from ml.manifest import read_manifest
from ml.metrics import evaluate


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--manifest',type=Path,default=Path('ml/data/bentham-pilot/manifest.jsonl'))
    args=parser.parse_args()
    os.environ.setdefault('HF_HOME',str(Path.home()/'.cache/folio-handwriting-models'))
    os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK','1')
    import torch
    from PIL import Image,ImageOps
    from transformers import TrOCRProcessor,VisionEncoderDecoderModel
    rows=sorted((row for row in read_manifest(args.manifest) if row['split']=='train'),key=lambda row:row['id'])[:3]
    if len(rows)!=3:raise ValueError('Need three actual training images for the device comparison')
    name='microsoft/trocr-small-handwritten';revision='b4648cfa171985a6745f37ddd637e98c0da958ac'
    processor=TrOCRProcessor.from_pretrained(name,revision=revision,use_fast=False)
    model=VisionEncoderDecoderModel.from_pretrained(name,revision=revision).eval()
    pixels=[];cases=[]
    for row in rows:
        with Image.open(row['image_path']) as source:
            image=ImageOps.exif_transpose(source).convert('RGB')
            tensor=processor(images=image,return_tensors='pt').pixel_values
            pixels.append(tensor)
            cases.append({'id':row['id'],'reference':row['text'],'imageWidth':image.width,'imageHeight':image.height,
                          'pixelMin':tensor.min().item(),'pixelMax':tensor.max().item(),
                          'pixelSha256':hashlib.sha256(tensor.numpy().tobytes()).hexdigest()})
    results={};devices=['cpu']+(['mps'] if torch.backends.mps.is_available() else [])
    for device in devices:
        model.to(device);predictions={};tokens_by_id={};start=time.perf_counter()
        with torch.inference_mode():
            for row,tensor in zip(rows,pixels):
                tokens=model.generate(tensor.to(device),num_beams=1,max_new_tokens=128)
                tokens_by_id[row['id']]=tokens.cpu().tolist()[0]
                predictions[row['id']]=processor.batch_decode(tokens,skip_special_tokens=True)[0]
        results[device]={'predictions':predictions,'tokenIds':tokens_by_id,'seconds':time.perf_counter()-start,
                         'diagnosticTrainingScores':evaluate({row['id']:row['text'] for row in rows},predictions)}
        print(json.dumps({'device':device,'predictions':predictions}),flush=True)
    report={'status':'completed','baseModel':name,'baseRevision':revision,'cases':cases,'devices':results,
            'imageProcessor':type(processor.image_processor).__name__,
            'encoderAttention':model.encoder.config._attn_implementation,'decoderAttention':model.decoder.config._attn_implementation,
            'generationConfig':model.generation_config.to_dict(),
            'exactDeviceTokenParity':results['cpu']['tokenIds']==results['mps']['tokenIds'] if 'mps' in results else None,
            'trainingExecuted':False,'scope':'Training-line runtime diagnostic; not a held-out benchmark or checkpoint selection'}
    path=Path('ml/artifacts/inference-diagnosis.json');path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
