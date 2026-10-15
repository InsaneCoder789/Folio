"""Targeted full-photo detection/recognition diagnostics, with incomplete page labels."""
import argparse
from collections import defaultdict
import importlib.metadata
import json
import math
import os
from pathlib import Path
import time
from ml.artifacts import file_sha256,write_json_atomic
from ml.detector_model import MODEL as DETECTOR,REVISION as DETECTOR_REVISION,obtain_detector
from ml.evaluate_mobile_baseline import MODEL,REVISION,obtain_model
from ml.evaluate_modern_validation import admitted_samples
from ml.metrics import evaluate


def assign_matches(pairs,threshold=.5):
    if not 0<threshold<=1:raise ValueError('Invalid overlap threshold')
    selected={};used=set()
    for target,prediction,overlap in sorted(pairs,key=lambda row:(-row[2],row[0],row[1])):
        if not math.isfinite(overlap) or not 0<=overlap<=1:raise ValueError('Invalid polygon overlap')
        if overlap>=threshold and target not in selected and prediction not in used:
            selected[target]=(prediction,overlap);used.add(prediction)
    return selected


def quad_iou(first,second):
    import cv2,numpy as np
    a=cv2.convexHull(np.asarray(first,dtype=np.float32));b=cv2.convexHull(np.asarray(second,dtype=np.float32))
    area_a=abs(cv2.contourArea(a));area_b=abs(cv2.contourArea(b))
    if min(area_a,area_b)<=0:return 0.
    intersection=float(cv2.intersectConvexConvex(a,b)[0]);union=area_a+area_b-intersection
    return min(1.,max(0.,intersection/union)) if union>0 else 0.


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=Path('ml/artifacts/fullpage-mobile-handwriting'));args=parser.parse_args()
    if args.output.exists():raise FileExistsError('Use a fresh full-page diagnostic directory')
    documents=defaultdict(list);provenance={}
    for folder in ('modern-validation','modern-validation-expanded'):
        root=Path('ml/data')/folder;manifest=root/'review-candidates.jsonl';review=root/'visual-review.json'
        rows,_=admitted_samples(manifest,review);provenance[folder]=dict(manifestSha256=file_sha256(manifest),reviewSha256=file_sha256(review))
        for row in rows:
            page=(root/row['source_image']).resolve()
            if not page.is_relative_to(root.resolve()) or file_sha256(page)!=row['source_image_sha256']:raise ValueError('Source photograph changed or escaped')
            documents[page].append(row)
    detector,detector_hashes=obtain_detector();recognizer=Path.home()/'.cache/folio-mobile-english-model'/REVISION;recognizer_hashes=obtain_model(recognizer)
    args.output.mkdir(parents=True);sources=args.output/'source';sources.mkdir();source_hashes={}
    for name in ('evaluate_fullpage_handwriting.py','detector_model.py','evaluate_mobile_baseline.py','evaluate_modern_validation.py','metrics.py','requirements-mobile.txt'):
        payload=Path(__file__).with_name(name).read_bytes();(sources/name).write_bytes(payload);source_hashes[name]=file_sha256(sources/name)
    os.environ.setdefault('PADDLE_PDX_DISABLE_MODEL_SOURCE_CHECK','True')
    from paddleocr import PaddleOCR
    pipeline=PaddleOCR(text_detection_model_name='PP-OCRv5_mobile_det',text_detection_model_dir=str(detector),
                       text_recognition_model_name='en_PP-OCRv5_mobile_rec',text_recognition_model_dir=str(recognizer),
                       use_doc_orientation_classify=False,use_doc_unwarping=False,use_textline_orientation=False,
                       text_det_limit_side_len=1280,text_det_limit_type='max',text_recognition_batch_size=1,
                       device='cpu',enable_mkldnn=False,cpu_threads=2)
    references={};predictions={};targets=[];pages=[];elapsed=0;recalled=0;recalled_loose=0
    for index,(page,rows) in enumerate(sorted(documents.items()),1):
        start=time.perf_counter();output=list(pipeline.predict(input=str(page)));elapsed+=time.perf_counter()-start
        if len(output)!=1:raise ValueError('Expected one page result')
        result=output[0];raw_polygons=result['rec_polys']
        polygons=raw_polygons.tolist() if hasattr(raw_polygons,'tolist') else [p.tolist() if hasattr(p,'tolist') else p for p in raw_polygons]
        texts=list(result['rec_texts']);scores=[float(value) for value in result['rec_scores']]
        if len(polygons)!=len(texts) or len(texts)!=len(scores):raise ValueError('Inconsistent full-page output')
        pairs=[(i,j,quad_iou(row['source_vertices'],polygon)) for i,row in enumerate(rows) for j,polygon in enumerate(polygons)]
        matches=assign_matches(pairs,.5);recalled+=len(matches);recalled_loose+=len(assign_matches(pairs,.3))
        for i,row in enumerate(rows):
            match=matches.get(i);prediction=texts[match[0]] if match else ''
            references[row['id']]=row['text'];predictions[row['id']]=prediction
            targets.append(dict(id=row['id'],reference=row['text'],prediction=prediction,matchedIoU=match[1] if match else None,
                                sourcePolygon=row['source_vertices'],predictedPolygon=polygons[match[0]] if match else None))
        pages.append(dict(document=rows[0]['document_id'],sourceSha256=file_sha256(page),polygons=polygons,texts=texts,scores=scores,
                          unscoredDetectedLines=len(texts)-len(matches)))
        print(json.dumps(dict(pagesCompleted=index,pagesTotal=len(documents),targetsRecalledAtHalfIoU=recalled)),flush=True)
    packages={d.metadata['Name']:d.version for d in importlib.metadata.distributions()}
    write_json_atomic(args.output/'runtime-packages.json',dict(sorted(packages.items())))
    write_json_atomic(args.output/'predictions.json',predictions);write_json_atomic(args.output/'targets.json',targets);write_json_atomic(args.output/'pages.json',pages)
    report=dict(status='completed',detector=DETECTOR,detectorRevision=DETECTOR_REVISION,detectorSha256=detector_hashes,
                recognizer=MODEL,recognizerRevision=REVISION,recognizerSha256=recognizer_hashes,datasets=provenance,
                pages=len(pages),reviewedTargetLines=len(targets),recalledAtIoUHalf=recalled,recalledAtIoUPointThree=recalled_loose,
                targetedLineMetrics=evaluate(references,predictions),hostSecondsPerPage=elapsed/len(pages),sourceHashes=source_hashes,
                runtimePackagesSha256=file_sha256(args.output/'runtime-packages.json'),promotedToAndroid=False,
                scope='Curated reviewed-line diagnostics within full photographs. Missing targets count as empty predictions. Other page text is unlabelled; no full-page precision, complete-page accuracy, writer separation or Android acceptance claim.')
    write_json_atomic(args.output/'evaluation-report.json',report);print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
