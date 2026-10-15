"""Explain validation failures without reusing final-test data or changing scores."""
import argparse
import json
import math
from pathlib import Path
import unicodedata
from ml.artifacts import file_sha256, write_json_atomic
from ml.metrics import evaluate, normalize


def compact(text, *, case=False, punctuation=False):
    text=normalize(text)
    if case:text=text.casefold()
    return ''.join(c for c in text if not c.isspace() and
                   not (punctuation and unicodedata.category(c).startswith('P')))


def category(reference,prediction):
    ref,pred=normalize(reference),normalize(prediction)
    if ref==pred:return 'exact'
    if compact(ref)==compact(pred):return 'whitespace-only'
    if ref.casefold()==pred.casefold():return 'case-only'
    if compact(ref,punctuation=True)==compact(pred,punctuation=True):return 'punctuation-and-spacing-only'
    if compact(ref,case=True,punctuation=True)==compact(pred,case=True,punctuation=True):return 'case-punctuation-spacing-only'
    return 'text-recognition-error'


def analyze(manifest,checkpoint,output):
    manifest,checkpoint,output=map(Path,(manifest,checkpoint,output))
    if output.exists():raise FileExistsError('Preserve earlier analysis')
    samples=[json.loads(line) for line in manifest.read_text().splitlines() if line.strip()]
    if not samples or any(r['split'] not in ('train','validation') for r in samples):
        raise ValueError('Only the train/validation selection manifest is admitted; final test is excluded')
    rows=[r for r in samples if r['split']=='validation']
    predictions=json.loads((checkpoint/'validation-predictions.json').read_text())
    references={r['id']:r['text'] for r in rows}
    score=evaluate(references,predictions)
    groups={};examples=[]
    for row in rows:
        pred=predictions[row['id']];kind=category(row['text'],pred)
        groups[kind]=groups.get(kind,0)+1
        if kind!='exact':examples.append(dict(id=row['id'],writer_id=row['writer_id'],reference=row['text'],
                                             prediction=pred,category=kind))
    exact=groups.get('exact',0)
    report=dict(status='completed',scope='Validation-only error diagnosis; unchanged strict headline metrics',
                sourceManifestSha256=file_sha256(manifest),predictionsSha256=file_sha256(checkpoint/'validation-predictions.json'),
                metrics=score,exclusiveCategories=groups,
                targetCounts={str(target):dict(requiredExactLines=math.ceil(target*len(rows)),
                                               additionalExactLinesNeeded=max(0,math.ceil(target*len(rows))-exact))
                              for target in (.75,.80)},
                diagnosticsAreNotAcceptanceScores=True,testDataRead=False)
    output.mkdir(parents=True)
    write_json_atomic(output/'analysis-report.json',report)
    write_json_atomic(output/'validation-errors.json',examples)
    print(json.dumps(report,indent=2))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--checkpoint',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args();analyze(a.manifest,a.checkpoint,a.output)


if __name__=='__main__':main()
