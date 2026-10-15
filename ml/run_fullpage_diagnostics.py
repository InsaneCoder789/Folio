"""Targeted development-photo checks for the portable full-page OCR pipeline."""
import argparse
import json
import os
from pathlib import Path
import resource
import sys
import time
from collections import defaultdict
from ml.artifacts import file_sha256,write_json_atomic
from ml.metrics import evaluate
from ml.evaluate_fullpage_handwriting import assign_matches,quad_iou
from ml.evaluate_modern_validation import admitted_samples
from ml.fullpage_ocr import PageRecognizer


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output',type=Path,required=True)
    p.add_argument('--checkpoint',type=Path,required=True)
    p.add_argument('--device',choices=('cpu','mps','cuda'),default='cpu')
    p.add_argument('--max-pages',type=int,default=15);a=p.parse_args()
    if a.output.exists():raise FileExistsError('Preserve previous diagnostics')
    if not 1<=a.max_pages<=15:raise ValueError('Diagnostic scope is 1..15 existing reviewed pages')
    os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK','1')
    groups=defaultdict(list)
    for name in ('modern-validation','modern-validation-expanded'):
        root=Path('ml/data')/name
        rows,_=admitted_samples(root/'review-candidates.jsonl',root/'visual-review.json')
        for row in rows:
            page=(root/row['source_image']).resolve()
            if not page.is_relative_to(root.resolve()) or file_sha256(page)!=row['source_image_sha256']:
                raise ValueError('Source photo integrity changed')
            groups[page].append(row)
    a.output.mkdir(parents=True);report_path=a.output/'diagnostic-report.json'
    report=dict(status='running',stage='load-pipeline',pagesTotal=min(len(groups),a.max_pages),pagesCompleted=0,
                fullPageAccuracyMeasured=False,newHoldout=False,promotedToAndroid=False,appReady=False,
                scope='Previously reviewed development photos with partial labels; targeted recall/CER, not full-page precision or final accuracy')
    write_json_atomic(report_path,report)
    begin=time.perf_counter()
    try:
        engine=PageRecognizer('ml/artifacts/mobile-detector-onnx/model.onnx',a.checkpoint,device=a.device)
        refs={};preds={};targets=[];pages=[];matched=0
        for index,(path,rows) in enumerate(sorted(groups.items())[:a.max_pages],1):
            if time.perf_counter()-begin>1800:raise TimeoutError('Development-photo diagnostic reached its 30-minute limit')
            from PIL import Image
            with Image.open(path) as image:
                if image.getexif().get(274,1)!=1:raise ValueError('Benchmark coordinates require identity EXIF orientation')
            page=engine.read(path);lines=page['lines']
            pairs=[(i,j,quad_iou(row['source_vertices'],line['sourcePolygon'])) for i,row in enumerate(rows) for j,line in enumerate(lines)]
            matches=assign_matches(pairs,.5);matched+=len(matches)
            for i,row in enumerate(rows):
                hit=matches.get(i);text=lines[hit[0]]['text'] if hit else ''
                refs[row['id']]=row['text'];preds[row['id']]=text
                targets.append(dict(id=row['id'],reference=row['text'],prediction=text,matchedIoU=hit[1] if hit else None))
            write_json_atomic(a.output/(rows[0]['document_id']+'-page.json'),page)
            pages.append(dict(document=rows[0]['document_id'],sourceSha256=page['sourceSha256'],detectedLines=len(lines),
                              targetedLabels=len(rows),matchedTargets=len(matches),hostSeconds=page['hostSeconds']))
            report.update(stage='full-page-diagnostics',pagesCompleted=index,targetsMatched=matched)
            write_json_atomic(report_path,report);print(json.dumps(report),flush=True)
        max_rss=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        report.update(status='completed',stage='completed',targetedMetrics=evaluate(refs,preds),
                      targetRecallAtIoUHalf=matched/len(refs),pages=pages,provenance=engine.provenance,
                      maximumHostProcessRssBytes=max_rss if sys.platform=='darwin' else max_rss*1024,
                      sourceHashes={n:file_sha256(Path(__file__).with_name(n)) for n in ('fullpage_geometry.py','fullpage_ocr.py','run_fullpage_diagnostics.py','requirements-fullpage.txt')})
        write_json_atomic(a.output/'target-predictions.json',preds);write_json_atomic(a.output/'targets.json',targets)
        write_json_atomic(report_path,report);print(json.dumps(report,indent=2),flush=True)
    except Exception as exc:
        report.update(status='failed',errorType=type(exc).__name__,error=str(exc));write_json_atomic(report_path,report);raise


if __name__=='__main__':main()
