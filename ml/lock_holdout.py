"""Lock a new writer-separated labelled holdout after rejecting prior exposure."""
import argparse
import json
from pathlib import Path
from ml.artifacts import file_sha256,write_json_atomic
from ml.manifest import read_manifest


def overlap_check(candidate,previous):
    if not candidate or any(r.get('split')!='test' or not r.get('writer_id') for r in candidate):
        raise ValueError('Every new holdout line needs test role and an explicit writer ID')
    for field in ('id','document_id','writer_id','sha256','source_image_sha256','sourcePageSha256'):
        used={r[field] for r in previous if r.get(field)}
        overlap={r[field] for r in candidate if r.get(field)} & used
        if overlap:raise ValueError('Holdout overlaps prior data by '+field)
    return True


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--manifest',type=Path,required=True)
    p.add_argument('--previous-manifest',type=Path,action='append',required=True)
    p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    if a.output.exists():raise FileExistsError('A locked holdout must not be replaced')
    candidates=read_manifest(a.manifest,required_splits=('test',),allowed_licenses=('CC-BY-4.0','CC-BY-SA-4.0','IAM-original-terms'))
    previous=[];provenance={}
    for path in a.previous_manifest:
        # Earlier corpora can contain multiple roles; this registry is for
        # overlap rejection only, not admitting their files to a new test.
        rows=[json.loads(l) for l in path.read_text().splitlines() if l.strip()]
        previous.extend(rows);provenance[str(path)]=file_sha256(path)
    overlap_check(candidates,previous)
    if len(candidates)<500:raise ValueError('At least 500 lines are required by the acceptance policy')
    write_json_atomic(a.output,dict(status='locked',manifest=str(a.manifest.resolve()),manifestSha256=file_sha256(a.manifest),
                      previousManifestSha256=provenance,lines=len(candidates),writers=len({r['writer_id'] for r in candidates}),
                      writerSeparationVerified=True,sourceFilesVerified=True,modelPredictionsViewed=False,
                      foundationTrainingOverlap='Unknown unless verified independently; cannot infer from local separation'))


if __name__=='__main__':main()
