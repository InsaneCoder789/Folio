"""Validate local line-level data and reject document/image leakage across splits."""
import hashlib
import json
from pathlib import Path


def read_manifest(path,verify_files=True,*,required_splits=('train','validation','test'),allowed_licenses=('CC-BY-4.0',)):
    path=Path(path).resolve();root=path.parent
    required=set(required_splits);licenses=set(allowed_licenses)
    if not required or not required<={'train','validation','test'}:raise ValueError('Invalid required splits')
    if not licenses or not licenses<={'CC-BY-4.0','CC-BY-SA-4.0','IAM-original-terms'}:raise ValueError('Unknown source license policy')
    samples=[];ids=set();documents={};writers={};hashes={}
    for index,line in enumerate(path.read_text().splitlines(),1):
        if not line.strip():continue
        row=json.loads(line)
        for field in ('id','document_id','split','image','text','sha256','license'):
            if not isinstance(row.get(field),str) or not row[field].strip():raise ValueError(f'Row {index} needs {field}')
        if row['id'] in ids:raise ValueError('Duplicate sample ID')
        if row['split'] not in required:raise ValueError('Unexpected split for this dataset role')
        if row['license'] not in licenses:raise ValueError('Source license does not match this adapter policy')
        image=(root/row['image']).resolve()
        if not image.is_relative_to(root):raise ValueError('Image path escapes the dataset directory')
        for identifier,registry in ((row['document_id'],documents),(row['sha256'],hashes)):
            if identifier in registry and registry[identifier]!=row['split']:raise ValueError('Document or duplicate-image leakage across splits')
            registry[identifier]=row['split']
        writer=row.get('writer_id')
        if writer:
            if writer in writers and writers[writer]!=row['split']:raise ValueError('Writer leakage across splits')
            writers[writer]=row['split']
        if verify_files:
            if not image.is_file() or hashlib.sha256(image.read_bytes()).hexdigest()!=row['sha256']:raise ValueError(f'Image integrity failed: {row["id"]}')
        ids.add(row['id']);row['image_path']=image;samples.append(row)
    if not samples or {r['split'] for r in samples}!=required:raise ValueError('Every explicitly required split must be nonempty')
    return samples
