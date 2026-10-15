"""Prepare bounded IAM line crops from the user's ZIP and matching ASCII metadata."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import tempfile
import zipfile

from ml.artifacts import file_sha256, write_json_atomic
from ml.iam_protocol import LICENSE, POLICY, writer_split
from ml.manifest import read_manifest

EXPECTED_METADATA = {
    'forms.txt': '0f69f235ce649252709caa699bcfb93c9091e6bce39cc09c135b08672eac9dd0',
    'lines.txt': '76b09da2ac25a12a70dc129e8a368a5906a5f458de3349df5a037a4064192da2',
}


def parse_metadata(forms_path, lines_path):
    forms = {}
    for raw in Path(forms_path).read_text().splitlines():
        if not raw.strip() or raw.startswith('#'):
            continue
        parts = raw.split()
        if len(parts) != 8 or not re.fullmatch(r'[A-Za-z0-9]+-[A-Za-z0-9]+', parts[0]):
            raise ValueError('Invalid IAM form metadata')
        if parts[0] in forms:
            raise ValueError('Duplicate form ID')
        forms[parts[0]] = parts[1]
    rows = {}; seen = set()
    for raw in Path(lines_path).read_text().splitlines():
        if not raw.strip() or raw.startswith('#'):
            continue
        fields = raw.split(maxsplit=8)
        if len(fields) != 9 or fields[1] not in ('ok', 'err'):
            raise ValueError('Invalid IAM line metadata')
        identity = fields[0]; document = identity.rsplit('-', 1)[0]
        if document not in forms or not re.fullmatch(re.escape(document) + r'-\d+', identity) or identity in seen:
            raise ValueError('Duplicate line or invalid parent form')
        seen.add(identity)
        x, y, width, height = map(int, fields[4:8])
        if min(x, y) < 0 or min(width, height) < 1:
            raise ValueError('Invalid line rectangle')
        # IAM ASCII uses | as token separators. Preserve case, punctuation and
        # any within-token spaces exactly; never replace labels with OCR output.
        text = fields[8].replace('|', ' ')
        if not text.strip():
            raise ValueError('Blank transcription')
        writer = forms[document]
        row = dict(id=identity, document_id=document, writer_id=writer,
                   split=writer_split(writer), text=text, license=LICENSE,
                   sourceBox=[x, y, width, height], wordSegmentation=fields[1])
        rows.setdefault(document, []).append(row)
    if not rows:
        raise ValueError('No line annotations')
    return forms, rows


def training_boards(root, rows):
    from PIL import Image, ImageDraw, ImageFont
    # Only training examples are exposed to visual review; no test selection.
    chosen = sorted((r for r in rows if r['split'] == 'train'),
                    key=lambda r: hashlib.sha256(('review-v1:' + r['id']).encode()).hexdigest())[:24]
    boards = []
    try:
        font = ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf', 18)
    except OSError:
        font = ImageFont.load_default()
    for offset in range(0, len(chosen), 8):
        batch = chosen[offset:offset+8]
        canvas = Image.new('RGB', (1400, 220 * len(batch)), 'white'); draw = ImageDraw.Draw(canvas)
        for index, row in enumerate(batch):
            y = index * 220
            draw.text((15, y+8), row['id']+' / writer '+row['writer_id'], fill='black', font=font)
            with Image.open(root / row['image']) as image:
                image.thumbnail((1360, 125))
                canvas.paste(image, (15, y+38))
            draw.text((15, y+169), row['text'], fill='black', font=font)
            draw.line((0, y+219, 1400, y+219), fill='gray')
        path = root / f'training-review-{offset//8+1}.png'; canvas.save(path)
        boards.append(dict(path=path.name, sha256=file_sha256(path), ids=[r['id'] for r in batch]))
    return boards


def prepare(archive, forms_path, lines_path, output, verify_pins=True):
    from PIL import Image
    archive, forms_path, lines_path, output = map(Path, (archive, forms_path, lines_path, output))
    if output.exists():
        raise FileExistsError('Prepared corpus exists; do not overwrite it')
    if verify_pins:
        for name, path in [('forms.txt', forms_path), ('lines.txt', lines_path)]:
            if file_sha256(path) != EXPECTED_METADATA[name]:
                raise ValueError('Metadata differs from the inspected source pin: ' + name)
    forms, grouped = parse_metadata(forms_path, lines_path)
    output.parent.mkdir(parents=True, exist_ok=True)
    if shutil.disk_usage(output.parent).free < 12 * 1024**3:
        raise RuntimeError('Reserve at least 12 GiB free before preparation')
    temporary = Path(tempfile.mkdtemp(prefix='.iam-preparing-', dir=output.parent))
    try:
        (temporary / 'images').mkdir()
        rows = []; pages = []; used = set(); crop_bytes = 0
        with zipfile.ZipFile(archive) as source:
            members = [m for m in source.infolist() if m.filename.endswith('.png')]
            if len(members) > 3000:
                raise ValueError('Unexpected page count')
            for index, member in enumerate(members, 1):
                document = Path(member.filename).stem
                if document in used or document not in grouped or member.file_size > 120 * 1024**2:
                    raise ValueError('Duplicate, unannotated or excessive page')
                if Path(member.filename).parent.name != forms[document]:
                    raise ValueError('Archive writer folder differs from metadata')
                used.add(document)
                payload = source.read(member)  # ZIP verifies each member CRC.
                with Image.open(io.BytesIO(payload)) as page:
                    if page.format != 'PNG' or not 0 < page.width * page.height <= 25_000_000:
                        raise ValueError('Invalid or excessive page dimensions')
                    page.load()
                    page_hash = hashlib.sha256(payload).hexdigest()
                    pages.append(dict(document_id=document, member=member.filename, sha256=page_hash,
                                      width=page.width, height=page.height, writer_id=forms[document]))
                    for row in grouped[document]:
                        x, y, width, height = row['sourceBox']
                        if x + width > page.width or y + height > page.height:
                            raise ValueError('Line rectangle exceeds page: ' + row['id'])
                        relative = 'images/' + row['id'] + '.png'
                        crop = page.crop((x, y, x+width, y+height)).convert('RGB')
                        crop.save(temporary / relative)
                        crop_bytes += (temporary / relative).stat().st_size
                        if crop_bytes > 8 * 1024**3:
                            raise ValueError('Crop storage budget exceeded')
                        rows.append(dict(row, image=relative, sha256=file_sha256(temporary / relative),
                                         sourcePageSha256=page_hash))
                if index % 100 == 0 or index == len(members):
                    print(json.dumps(dict(stage='crop-validation', pages=index, totalPages=len(members), lines=len(rows))), flush=True)
        if set(grouped) != used:
            raise ValueError('Some annotated forms are absent from the supplied archive')
        rows.sort(key=lambda r: r['id'])
        manifest = temporary / 'manifest.jsonl'
        manifest.write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in rows))
        read_manifest(manifest, allowed_licenses=(LICENSE,))
        selection = temporary / 'selection-manifest.jsonl'
        selection.write_text(''.join(json.dumps(r, ensure_ascii=False)+'\n' for r in rows if r['split'] != 'test'))
        read_manifest(selection, required_splits=('train', 'validation'), allowed_licenses=(LICENSE,))
        boards = training_boards(temporary, rows)
        report = dict(status='prepared-awaiting-visual-review', trainingStarted=False, policy=POLICY,
                      source='Kaggle user ZIP + maxicode/iam-handwriting ASCII metadata',
                      sourceHashes=dict(archive=file_sha256(archive), forms=file_sha256(forms_path), lines=file_sha256(lines_path)),
                      writerSeparated=True, officialBenchmarkSplit=False,
                      splitProtocol='folio-iam-v1 SHA256 writer buckets 80/10/10',
                      counts={s: sum(r['split']==s for r in rows) for s in ('train','validation','test')},
                      writers={s: len({r['writer_id'] for r in rows if r['split']==s}) for s in ('train','validation','test')},
                      sourcePages=len(pages), cropBytes=crop_bytes,
                      sourceCodeSha256={n: file_sha256(Path(__file__).with_name(n)) for n in ('prepare_iam_forms.py', 'iam_protocol.py', 'manifest.py')},
                      manifestSha256=file_sha256(manifest), selectionManifestSha256=file_sha256(selection),
                      normalization='IAM ASCII token separators replaced by spaces; case/punctuation unchanged',
                      wordSegmentationErrors='Included: publisher says whole-line extraction/transcription is unaffected',
                      reviewBoards=boards, visualReviewPassed=False, commercialReleaseCleared=False,
                      foundationCaveat=POLICY['foundationCaveat'])
        write_json_atomic(temporary/'pages.json', pages)
        write_json_atomic(temporary/'preparation-report.json', report)
        temporary.rename(output)
        print(json.dumps(report, indent=2), flush=True)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--forms', type=Path, required=True)
    parser.add_argument('--lines', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    prepare(args.archive, args.forms, args.lines, args.output)


if __name__ == '__main__':
    main()
