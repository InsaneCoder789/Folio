"""IAM research-data adapter and fixed promotion gates; no dataset redistribution."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile
import xml.etree.ElementTree as ET

from ml.artifacts import write_json_atomic, file_sha256
from ml.metrics import evaluate

LICENSE = 'IAM-original-terms'
POLICY = {
    'schemaVersion': 1,
    'primaryMetric': 'case-sensitive corpus CER; NFC and collapsed whitespace',
    'maximumCer': .05,
    'maximumWer': .15,
    'minimumRelativeCerImprovement': .10,
    'minimumExactLineRate': .60,
    'minimumEvaluationLines': 500,
    'maximumExperimentsBeforeReview': 1,
    'testPolicy': 'No test decoding until validation-selected checkpoint is frozen',
    'foundationCaveat': 'TrOCR handwritten foundations already used IAM; this is adaptation, not an unseen-corpus claim',
    'readiness': 'Passing line gates is insufficient for full-page or Android release',
}


def writer_split(writer):
    """Custom reproducible writer split, explicitly not the official IAM protocol."""
    if not isinstance(writer, str) or not writer.strip():
        raise ValueError('Every IAM form needs an explicit writer ID')
    bucket = int(hashlib.sha256(('folio-iam-v1:' + writer).encode()).hexdigest()[:8], 16) % 100
    return 'train' if bucket < 80 else 'validation' if bucket < 90 else 'test'


def promotion(reference, prediction, baseline, writers_verified):
    candidate = evaluate(reference, prediction)
    original = evaluate(reference, baseline)
    improvement = (original['cer'] - candidate['cer']) / original['cer'] if original['cer'] else 0.
    checks = {
        'enoughLines': candidate['samples'] >= POLICY['minimumEvaluationLines'],
        'writerSeparationVerified': writers_verified is True,
        'cer': candidate['cer'] <= POLICY['maximumCer'],
        'wer': candidate['wer'] <= POLICY['maximumWer'],
        'exactLines': candidate['exactLineRate'] >= POLICY['minimumExactLineRate'],
        'improvesBaseline': improvement >= POLICY['minimumRelativeCerImprovement'],
    }
    return dict(candidate=candidate, baseline=original, relativeCerImprovement=improvement,
                checks=checks, lineGatePassed=all(checks.values()), appReady=False)


def annotations(archive):
    rows = {}
    with tarfile.open(archive, 'r:*') as source:
        for member in source:
            if not member.name.endswith('.xml'):
                continue
            if not member.isfile() or member.size > 4 * 1024**2:
                raise ValueError('Invalid or excessive XML member')
            root = ET.fromstring(source.extractfile(member).read())
            writer = root.attrib.get('writer-id', '').strip()
            document = root.attrib.get('id', '')
            if not writer or not re.fullmatch(r'[A-Za-z0-9]+-[A-Za-z0-9]+', document):
                raise ValueError('Invalid form identity or missing writer metadata')
            for line in root.findall('.//handwritten-part/line'):
                identity, text = line.attrib.get('id', ''), line.attrib.get('text', '')
                if not re.fullmatch(re.escape(document) + r'-[0-9]+', identity) or not text.strip():
                    raise ValueError('Invalid line identity or empty transcription')
                if identity in rows:
                    raise ValueError('Duplicate XML line ID')
                rows[identity] = dict(id=identity, document_id=document, writer_id=writer,
                                      split=writer_split(writer), text=text, license=LICENSE)
    if not rows:
        raise ValueError('No IAM line annotations found')
    return rows


def prepare(lines, xml, output):
    from PIL import Image
    import io
    from ml.manifest import read_manifest
    if output.exists():
        raise FileExistsError('Preserve earlier corpus; choose a new output directory')
    rows = annotations(xml)
    output.mkdir(parents=True)
    (output / 'images').mkdir()
    included = []
    seen = set()
    with tarfile.open(lines, 'r:*') as source:
        for member in source:
            identity = Path(member.name).stem
            if not member.name.endswith('.png') or identity not in rows:
                continue
            if identity in seen or not member.isfile() or member.size > 20 * 1024**2:
                raise ValueError('Duplicate or invalid line image')
            seen.add(identity)
            payload = source.extractfile(member).read()
            with Image.open(io.BytesIO(payload)) as image:
                if image.format != 'PNG' or not 0 < image.width * image.height <= 20_000_000:
                    raise ValueError('Invalid or excessive line image')
                image.verify()
            row = dict(rows[identity], image=f'images/{identity}.png', sha256=hashlib.sha256(payload).hexdigest())
            (output / row['image']).write_bytes(payload)
            included.append(row)
    missing = set(rows) - seen
    if missing:
        raise ValueError(f'Incomplete line archive: {len(missing)} annotated images missing; corpus not published')
    included.sort(key=lambda row: row['id'])
    manifest = output / 'manifest.jsonl'
    manifest.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in included))
    read_manifest(manifest, allowed_licenses=(LICENSE,))
    selection = output / 'selection-manifest.jsonl'
    selection.write_text(''.join(json.dumps(row, ensure_ascii=False) + '\n' for row in included if row['split'] != 'test'))
    write_json_atomic(output / 'preparation-report.json', {
        'status': 'passed', 'splitProtocol': 'folio-iam-v1 custom SHA256 writer partition 80/10/10',
        'officialBenchmarkSplit': False, 'writerSeparated': True,
        'counts': {split: sum(r['split'] == split for r in included) for split in ('train', 'validation', 'test')},
        'sourceHashes': {'lines': file_sha256(lines), 'xml': file_sha256(xml)},
        'manifestSha256': file_sha256(manifest), 'selectionManifestSha256': file_sha256(selection),
        'redistributeDataset': False, 'commercialReleaseCleared': False,
        'foundationCaveat': POLICY['foundationCaveat'], 'policy': POLICY,
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--lines', type=Path, required=True)
    parser.add_argument('--xml', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.lines, args.xml, args.output)


if __name__ == '__main__':
    main()
