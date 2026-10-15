"""Copy visually admitted train/validation lines into a source-separated selection dataset."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import shutil
from ml.artifacts import file_sha256,write_json_atomic
from ml.evaluate_modern_validation import admitted_samples
from ml.manifest import read_manifest


def require_separation(training,validation):
    ids=set();documents={};images={};photos={}
    for role,rows in (('train',training),('validation',validation)):
        for row in rows:
            if row['split']!=role or row['id'] in ids:raise ValueError('Unexpected or duplicate sample role/ID')
            ids.add(row['id'])
            for identifier,registry in ((row['document_id'],documents),(row['sha256'],images),(row['source_image_sha256'],photos)):
                if identifier in registry and registry[identifier]!=role:raise ValueError('Document, crop or original-photo leakage across roles')
                registry[identifier]=role


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--output',type=Path,default=Path('ml/data/modern-selection'))
    args=parser.parse_args();inputs={};review_hashes={};manifest_hashes={};groups={}
    for role,folder in (('train','modern-training'),('validation','modern-validation')):
        root=Path('ml/data')/folder;manifest=root/'review-candidates.jsonl';review=root/'visual-review.json'
        rows,_=admitted_samples(manifest,review,split=role)
        prep=json.loads((root/'preparation-report.json').read_text())
        if prep['split']!=role or prep['license']!='CC-BY-SA-4.0':raise ValueError('Input preparation role/license mismatch')
        groups[role]=rows;inputs[role]=root;review_hashes[role]=file_sha256(review);manifest_hashes[role]=file_sha256(manifest)
    require_separation(groups['train'],groups['validation'])
    manifest=args.output/'manifest.jsonl'
    if manifest.exists():raise FileExistsError('Keep the existing dataset; choose a fresh output directory')
    args.output.mkdir(parents=True,exist_ok=True);images=args.output/'images';images.mkdir(exist_ok=True)
    records=[];checked_photos={}
    for role,rows in groups.items():
        root=inputs[role].resolve()
        for row in rows:
            if not re.fullmatch('[0-9a-f]{16}-[0-9]{2}',row['id']):raise ValueError('Unexpected source line ID')
            photo=(root/row['source_image']).resolve()
            if not photo.is_relative_to(root) or not photo.is_file():raise ValueError('Source photo escapes its dataset or is missing')
            if photo not in checked_photos:checked_photos[photo]=file_sha256(photo)
            if checked_photos[photo]!=row['source_image_sha256']:raise ValueError('Source photo changed after preparation')
            destination=images/(row['id']+'.png');shutil.copy2(row['image_path'],destination)
            record={key:value for key,value in row.items() if key not in ('image_path','source_image')}
            record.update(image='images/'+destination.name,englishVisualReview='accepted',
                          sourceManifestSha256=manifest_hashes[role],visualReviewSha256=review_hashes[role])
            records.append(record)
    manifest.write_text('\n'.join(json.dumps(row,ensure_ascii=False) for row in records)+'\n')
    validated=read_manifest(manifest,required_splits=('train','validation'),allowed_licenses=('CC-BY-SA-4.0',))
    report={'status':'passed-selection-data-integrity','manifest':str(manifest),'license':'CC-BY-SA-4.0',
            'splitCounts':dict(Counter(row['split'] for row in validated)),
            'sourceManifestSha256':manifest_hashes,'visualReviewSha256':review_hashes,
            'source':'https://github.com/google-research-datasets/hiertext','testDeferred':True,
            'documentAndExactPhotoSeparated':True,'writerSeparated':False,'trainingExecuted':False,
            'scope':'Admitted development training/validation data; final held-out testing remains separate'}
    write_json_atomic(args.output/'dataset-report.json',report);print(json.dumps(report,indent=2),flush=True)


if __name__=='__main__':main()
