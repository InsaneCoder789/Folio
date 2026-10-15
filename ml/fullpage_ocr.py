"""Portable host full-page OCR: pinned ONNX detector + frozen TrOCR recognizer."""
import argparse
import json
from pathlib import Path
import time
from ml.artifacts import file_sha256,write_json_atomic
from ml.detector_codec import prepare_detector_tensor
from ml.fullpage_geometry import map_to_boxes,perspective_crop

DETECTOR_SHA256='cfcb87395c173a9f48d98d1d247b1006b7ed8224db0286709ce2b96fd38bee96'


class PageRecognizer:
    def __init__(self,detector,checkpoint,*,beams=1,device='cpu'):
        if beams not in (1,4) or device not in ('cpu','mps','cuda'):raise ValueError('Unsupported decoding contract')
        import onnxruntime as ort,torch
        from transformers import TrOCRProcessor,VisionEncoderDecoderModel
        if file_sha256(detector)!=DETECTOR_SHA256:raise ValueError('Detector hash mismatch')
        checkpoint=Path(checkpoint)
        weights=list(checkpoint.glob('*.safetensors'))
        if len(weights)!=1:raise ValueError('Expected one immutable TrOCR weight file')
        options=ort.SessionOptions();options.intra_op_num_threads=2;options.inter_op_num_threads=1
        self.session=ort.InferenceSession(str(detector),sess_options=options,providers=['CPUExecutionProvider'])
        torch.set_num_threads(2)
        self.processor=TrOCRProcessor.from_pretrained(checkpoint,use_fast=False,local_files_only=True)
        self.model=VisionEncoderDecoderModel.from_pretrained(checkpoint,local_files_only=True).to(device).eval()
        self.model.requires_grad_(False)
        self.beams,self.device=beams,device
        self.provenance=dict(detectorSha256=DETECTOR_SHA256,recognizerSha256=file_sha256(weights[0]),
                             checkpoint=str(checkpoint),numBeams=beams,maxNewTokens=128,device=device,
                             detectorThreshold=.3,boxThreshold=.6,unclipRatio=1.5)

    def read(self,path):
        import cv2,numpy as np,torch
        from PIL import Image,ImageOps
        path=Path(path)
        if path.stat().st_size>25*1024**2:raise ValueError('Input exceeds 25MiB')
        start=time.perf_counter()
        with Image.open(path) as source:
            if min(source.size)<32 or source.width*source.height>64_000_000:raise ValueError('Invalid source dimensions')
            image=ImageOps.exif_transpose(source).convert('RGB')
            oriented_size=image.size
            image.thumbnail((3000,3000),Image.Resampling.LANCZOS)
            bgr=cv2.cvtColor(np.asarray(image),cv2.COLOR_RGB2BGR)
        tensor=prepare_detector_tensor(bgr)
        probability=self.session.run(None,{self.session.get_inputs()[0].name:tensor})[0]
        if probability.shape!=(1,1,tensor.shape[2],tensor.shape[3]):raise ValueError('Detector output contract changed')
        boxes=map_to_boxes(probability[0,0],bgr.shape[1],bgr.shape[0])
        detection_seconds=time.perf_counter()-start
        lines=[]
        for box in boxes:
            if time.perf_counter()-start>300:raise TimeoutError('Page diagnostic exceeded the fixed five-minute limit')
            crop=perspective_crop(bgr,box['polygon'])
            rgb=Image.fromarray(cv2.cvtColor(crop,cv2.COLOR_BGR2RGB))
            pixels=self.processor(images=rgb,return_tensors='pt').pixel_values.to(self.device)
            with torch.inference_mode():
                tokens=self.model.generate(pixel_values=pixels,num_beams=self.beams,max_new_tokens=128,
                                           length_penalty=1.,early_stopping=False)
            text=self.processor.batch_decode(tokens,skip_special_tokens=True)[0]
            sx,sy=oriented_size[0]/bgr.shape[1],oriented_size[1]/bgr.shape[0]
            lines.append(dict(box,text=text,sourcePolygon=[[x*sx,y*sy] for x,y in box['polygon']],recognitionConfidence=None))
        return dict(status='completed',sourceSha256=file_sha256(path),orientedSourceSize=oriented_size,
                    workingSize=[bgr.shape[1],bgr.shape[0]],lines=lines,text='\n'.join(row['text'] for row in lines),
                    detectionSeconds=detection_seconds,hostSeconds=time.perf_counter()-start,
                    provenance=self.provenance,weightsUpdated=False,
                    scope='Host horizontal-English page inference; reading order is geometric, not semantic; no Android acceptance')


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--input',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--detector',type=Path,default=Path('ml/artifacts/mobile-detector-onnx/model.onnx'))
    p.add_argument('--checkpoint',type=Path,required=True);p.add_argument('--beams',type=int,choices=(1,4),default=1)
    p.add_argument('--device',choices=('cpu','mps','cuda'),default='cpu');a=p.parse_args()
    if a.output.exists():raise FileExistsError('Keep prior inference outputs')
    result=PageRecognizer(a.detector,a.checkpoint,beams=a.beams,device=a.device).read(a.input)
    write_json_atomic(a.output,result)
    print(json.dumps(dict(status=result['status'],lines=len(result['lines']),hostSeconds=result['hostSeconds'])))


if __name__=='__main__':main()
