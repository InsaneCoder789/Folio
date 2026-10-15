"""One bounded training-only memorization diagnostic; never an accuracy benchmark."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import random
import time

from ml.artifacts import file_sha256, write_json_atomic
from ml.iam_protocol import LICENSE
from ml.manifest import read_manifest
from ml.metrics import evaluate
from ml.seq2seq_training import training_loss

MODEL = 'microsoft/trocr-base-handwritten'
REVISION = 'eaacaf452b06415df8f10bb6fad3a4c11e609406'
IDS = ('h02-040-06', 'b01-057-02', 'c06-047-04', 'k02-036-01',
       'h07-060a-00', 'a01-014x-02', 'm01-125-06', 'f07-101a-07')


def diagnostic_passed(baseline, current, first_loss, last_loss, updates):
    """Low training error plus decreasing loss, not generalization or promotion."""
    import math
    if updates < 20 or not all(math.isfinite(v) for v in (baseline['cer'], current['cer'], first_loss, last_loss)):
        return False
    return current['cer'] <= .02 and last_loss <= .8 * first_loss and current['cer'] <= baseline['cer']


def select_rows(rows, review, ids=IDS):
    if review.get('status') != 'sample-review-passed' or review.get('reviewer') != 'assistant':
        raise ValueError('Require an explicit recorded crop-label review')
    reviewed = set(review['reviewedIds'])
    by_id = {row['id']: row for row in rows}
    if len(set(ids)) != len(ids):
        raise ValueError('Duplicate diagnostic line')
    result = []
    for identity in ids:
        row = by_id.get(identity)
        if row is None or row['split'] != 'train' or identity not in reviewed:
            raise ValueError('Diagnostic lines must be reviewed training examples')
        result.append(row)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--steps', type=int, default=160)
    parser.add_argument('--learning-rate', type=float, default=5e-6)
    args = parser.parse_args()
    if not 20 <= args.steps <= 200 or not 0 < args.learning_rate <= 2e-5:
        parser.error('Diagnostic bound: 20..200 steps and learning rate <=2e-5')
    if args.output.exists():
        raise FileExistsError('Choose a fresh diagnostic directory; never overwrite a run')
    os.environ.setdefault('HF_HOME', str(Path.home()/'.cache/folio-handwriting-models'))
    os.environ.setdefault('PYTORCH_ENABLE_MPS_FALLBACK', '1')
    os.environ.setdefault('TOKENIZERS_PARALLELISM', 'false')
    prep = json.loads((args.corpus/'preparation-report.json').read_text())
    review = json.loads((args.corpus/'visual-review.json').read_text())
    manifest = args.corpus/'selection-manifest.jsonl'
    if file_sha256(manifest) != prep['selectionManifestSha256'] or review['manifestSha256'] != prep['manifestSha256']:
        raise ValueError('Corpus/review identity changed')
    for board in review['boards']:
        if file_sha256(args.corpus/board['path']) != board['sha256']:
            raise ValueError('Reviewed board changed')
    rows = select_rows(read_manifest(manifest, required_splits=('train','validation'), allowed_licenses=(LICENSE,)), review)
    args.output.mkdir(parents=True)
    path = args.output/'diagnostic-report.json'
    report = dict(status='running', stage='load-model', startedAt=datetime.now(timezone.utc).isoformat(),
                  baseModel=MODEL, baseRevision=REVISION, manifestSha256=file_sha256(manifest),
                  reviewSha256=file_sha256(args.corpus/'visual-review.json'),
                  scope='Eight reviewed TRAINING lines only; memorization diagnostic, not held-out accuracy',
                  validationDecoded=False, testDecoded=False, appReady=False, promotedToAndroid=False,
                  seed=20261015, stepsRequested=args.steps, accumulation=4, learningRate=args.learning_rate,
                  frozenEncoder=True, lossContract='explicit aligned token cross-entropy; decoder inputs shifted once',
                  lossSourceSha256=file_sha256(Path(__file__).with_name('seq2seq_training.py')), rows=[dict(id=r['id'], sha256=r['sha256'], writer_id=r['writer_id']) for r in rows],
                  evaluations=[], sourceSha256=file_sha256(__file__))
    write_json_atomic(path, report)
    started = time.monotonic()
    try:
        import torch
        from PIL import Image
        from transformers import TrOCRProcessor, VisionEncoderDecoderModel
        from transformers.modeling_outputs import BaseModelOutput
        random.seed(report['seed']); torch.manual_seed(report['seed'])
        device = 'cuda' if torch.cuda.is_available() else 'mps' if torch.backends.mps.is_available() else 'cpu'
        processor = TrOCRProcessor.from_pretrained(MODEL, revision=REVISION, use_fast=False, local_files_only=True)
        model = VisionEncoderDecoderModel.from_pretrained(MODEL, revision=REVISION, local_files_only=True).to(device)
        # Preserve the checkpoint's generation/training shift contract.
        start_token = model.config.decoder_start_token_id
        if start_token is None:
            start_token = model.generation_config.decoder_start_token_id
        if start_token is None:
            raise ValueError('Checkpoint has no decoder start token')
        model.config.decoder_start_token_id = start_token
        model.config.pad_token_id = processor.tokenizer.pad_token_id
        model.generation_config.decoder_start_token_id = start_token
        model.generation_config.pad_token_id = processor.tokenizer.pad_token_id
        model.encoder.requires_grad_(False)
        parameters = [p for p in model.parameters() if p.requires_grad]
        optimizer = torch.optim.AdamW(parameters, lr=args.learning_rate, weight_decay=.01)
        report.update(device=device, torchVersion=torch.__version__, decoderStartToken=start_token,
                      trainableParameters=sum(p.numel() for p in parameters), stage='baseline')
        pixels = {}; labels = {}; features = {}
        model.eval()
        for row in rows:
            identity = row['id']
            with Image.open(row['image_path']) as image:
                pixels[identity] = processor(images=image.convert('RGB'), return_tensors='pt').pixel_values.to(device)
            labels[identity] = processor.tokenizer(row['text'], return_tensors='pt').input_ids.to(device)
            if labels[identity].shape[1] > 128:
                raise ValueError('No transcription truncation permitted')
            with torch.no_grad():
                features[identity] = model.encoder(pixel_values=pixels[identity]).last_hidden_state.detach()
        references = {r['id']: r['text'] for r in rows}

        @torch.inference_mode()
        def predict():
            model.eval()
            predictions = {}
            for row in rows:
                identity = row['id']
                tokens = model.generate(pixel_values=pixels[identity], max_new_tokens=128, num_beams=1)
                predictions[identity] = processor.batch_decode(tokens, skip_special_tokens=True)[0]
            return evaluate(references, predictions), predictions

        baseline, base_predictions = predict()
        report['baselineTrainingMetrics'] = baseline
        write_json_atomic(args.output/'baseline-training-predictions.json', base_predictions)
        write_json_atomic(path, report)
        print(json.dumps(dict(stage='baseline-training-only', metrics=baseline)), flush=True)
        losses = []; passed = False
        for step in range(1, args.steps+1):
            if time.monotonic()-started > 45*60:
                raise TimeoutError('Diagnostic reached the fixed 45-minute limit')
            model.train(); model.encoder.eval(); optimizer.zero_grad(set_to_none=True)
            value = 0.
            for offset in range(4):
                row = rows[((step-1)*4+offset) % len(rows)]; identity = row['id']
                loss = training_loss(model, labels[identity], encoder_outputs=BaseModelOutput(last_hidden_state=features[identity]))
                if not torch.isfinite(loss):
                    raise RuntimeError('Non-finite training loss')
                value += loss.detach().item()/4
                (loss/4).backward()
            norm = torch.nn.utils.clip_grad_norm_(parameters, 1.)
            if not torch.isfinite(norm):
                raise RuntimeError('Non-finite gradients')
            optimizer.step(); losses.append(value)
            if step % 10 == 0:
                print(json.dumps(dict(stage='training-only-diagnostic', step=step, loss=value)), flush=True)
            if step % 20 == 0 or step == args.steps:
                metrics, predictions = predict()
                first = sum(losses[:10])/len(losses[:10]); last = sum(losses[-10:])/len(losses[-10:])
                passed = diagnostic_passed(baseline, metrics, first, last, step)
                report['evaluations'].append(dict(step=step, trainingMetrics=metrics, firstMeanLoss=first,
                                                   lastMeanLoss=last, diagnosticPassed=passed))
                report.update(stage='training-only-diagnostic', stepsCompleted=step, elapsedSeconds=time.monotonic()-started)
                write_json_atomic(path, report)
                write_json_atomic(args.output/'latest-training-predictions.json', predictions)
                print(json.dumps(report['evaluations'][-1]), flush=True)
                if passed:
                    break
        report.update(status='passed' if passed else 'failed-learning-gate', stage='completed',
                      diagnosticPassed=passed, completedAt=datetime.now(timezone.utc).isoformat(),
                      elapsedSeconds=time.monotonic()-started,
                      checkpointSaved=False, nextStep='Review diagnostic before any longer fine-tuning')
        write_json_atomic(path, report)
        if not passed:
            raise RuntimeError('Training-only memorization gate failed; do not launch longer training')
    except Exception as exc:
        report.update(status=report['status'] if report['status']=='failed-learning-gate' else 'failed',
                      errorType=type(exc).__name__, error=str(exc), elapsedSeconds=time.monotonic()-started)
        write_json_atomic(path, report)
        raise


if __name__ == '__main__':
    main()
