"""Reproducible line-recognizer fine-tuning; held-out test is evaluated only after selection."""
import argparse
import gc
from datetime import datetime,timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import random
import shutil
import time


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--manifest',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--steps',type=int,default=300)
    parser.add_argument('--accumulation',type=int,default=4)
    parser.add_argument('--eval-every',type=int,default=100)
    parser.add_argument('--learning-rate',type=float,default=2e-5)
    parser.add_argument('--train-encoder',action='store_true',help='Adapt image features as well as the text decoder')
    parser.add_argument('--encoder-learning-rate',type=float,default=2e-6)
    parser.add_argument('--seed',type=int,default=20261015)
    parser.add_argument('--max-tokens',type=int,default=128)
    parser.add_argument('--device',choices=['auto','cpu','mps','cuda'],default='auto')
    parser.add_argument('--foundation',choices=('small','base'),default='small')
    parser.add_argument('--selection-only',action='store_true',help='Train/select with train+validation; defer the untouched final test')
    parser.add_argument('--source-license',choices=('CC-BY-4.0','CC-BY-SA-4.0','IAM-original-terms'),default='CC-BY-4.0')
    args=parser.parse_args()
    if min(args.steps,args.accumulation,args.eval_every,args.max_tokens)<1:parser.error('Training bounds must be positive')
    if args.learning_rate<=0 or args.encoder_learning_rate<=0:parser.error('Learning rates must be positive')
    # Imports stay lazy: metrics/manifest tests need no heavyweight ML installation.
    import torch
    from PIL import Image,ImageOps
    from transformers import TrOCRProcessor,VisionEncoderDecoderModel
    from ml.manifest import read_manifest
    from ml.metrics import evaluate
    from ml.seq2seq_training import training_loss
    from ml.artifacts import publish_checkpoint,write_json_atomic,file_sha256
    os.environ.setdefault('TOKENIZERS_PARALLELISM','false')
    required=('train','validation') if args.selection_only else ('train','validation','test')
    samples=read_manifest(args.manifest,required_splits=required,allowed_licenses=(args.source_license,))
    splits={key:[r for r in samples if r['split']==key] for key in required}
    random.seed(args.seed);torch.manual_seed(args.seed)
    device=args.device
    if device=='auto':device='cuda' if torch.cuda.is_available() else 'mps' if torch.backends.mps.is_available() else 'cpu'
    model_name,revision=(('microsoft/trocr-small-handwritten','b4648cfa171985a6745f37ddd637e98c0da958ac') if args.foundation=='small'
                         else ('microsoft/trocr-base-handwritten','eaacaf452b06415df8f10bb6fad3a4c11e609406'))
    processor=TrOCRProcessor.from_pretrained(model_name,revision=revision,use_fast=False)
    for row in samples:
        if len(processor.tokenizer(row['text']).input_ids)>args.max_tokens:
            raise ValueError(f'Transcription exceeds max-tokens: {row["id"]}; no silent truncation is allowed')
    model=VisionEncoderDecoderModel.from_pretrained(model_name,revision=revision).to(device)
    # The small checkpoint starts decoding with EOS (2), not the tokenizer's
    # CLS token (0). Preserve its generation contract for training label shifts.
    if model.config.decoder_start_token_id is None:
        model.config.decoder_start_token_id=model.generation_config.decoder_start_token_id
    if model.config.decoder_start_token_id is None:model.config.decoder_start_token_id=processor.tokenizer.cls_token_id
    model.config.pad_token_id=processor.tokenizer.pad_token_id
    model.generation_config.decoder_start_token_id=model.config.decoder_start_token_id
    model.generation_config.pad_token_id=processor.tokenizer.pad_token_id
    model.encoder.requires_grad_(args.train_encoder)
    encoder_parameters=list(model.encoder.parameters());encoder_ids={id(p) for p in encoder_parameters}
    groups=[{'params':[p for p in model.parameters() if p.requires_grad and id(p) not in encoder_ids],'lr':args.learning_rate}]
    if args.train_encoder:groups.append({'params':encoder_parameters,'lr':args.encoder_learning_rate})
    optimizer=torch.optim.AdamW(groups,weight_decay=.01)
    args.output.mkdir(parents=True,exist_ok=True)
    if (args.output/'training-report.json').exists():raise FileExistsError('Keep previous experiment results; choose a fresh output directory')
    checkpoint=None
    report={'startedAt':datetime.now(timezone.utc).isoformat(),'baseModel':model_name,'baseRevision':revision,
            'lossContract':'explicit aligned token cross-entropy; decoder inputs shifted once',
            'schemaVersion':2,'protocol':'validation-selection-only' if args.selection_only else 'train-validation-test',
            'datasetLicense':args.source_license,'testDeferred':args.selection_only,
            'manifestSha256':hashlib.sha256(args.manifest.read_bytes()).hexdigest(),'device':device,'seed':args.seed,
            'frozenEncoder':not args.train_encoder,'stepsRequested':args.steps,'splitCounts':{k:len(v) for k,v in splits.items()},
            'writerSeparated':all(bool(r.get('writer_id')) for r in samples),
            'scope':'Experimental handwritten English line recognition; not full-page Android OCR',
            'training':[],'promotedToAndroid':False}
    import transformers
    report['runtime']={'python':platform.python_version(),'torch':torch.__version__,
                       'transformers':transformers.__version__,'platform':platform.platform()}
    report['hyperparameters']={'learningRate':args.learning_rate,'accumulation':args.accumulation,
                              'encoderLearningRate':args.encoder_learning_rate if args.train_encoder else None,
                              'evalEvery':args.eval_every,'maxTokens':args.max_tokens,'weightDecay':.01,
                              'gradientClipNorm':1.,'generationBeams':1}
    report['tokenIds']={'decoderStart':model.config.decoder_start_token_id,'padding':model.config.pad_token_id,
                        'generationEnd':model.generation_config.eos_token_id}
    report['splitDocuments']={key:sorted({row['document_id'] for row in rows}) for key,rows in splits.items()}
    report['sourceHashes']={path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in
                           (Path(__file__),Path(__file__).with_name('requirements.txt'))}
    sources=args.output/'source';sources.mkdir()
    for path in sorted(Path(__file__).parent.glob('*.py')):
        shutil.copy2(path,sources/path.name);report['sourceHashes'][path.name]=file_sha256(sources/path.name)
    shutil.copy2(Path(__file__).with_name('requirements.txt'),sources/'requirements.txt')
    report['trainableParameters']=sum(p.numel() for p in model.parameters() if p.requires_grad)

    def train_mode():
        model.train()
        if not args.train_encoder:model.encoder.eval()

    def pixels(row):
        with Image.open(row['image_path']) as source:
            image=ImageOps.exif_transpose(source).convert('RGB')
            return processor(images=image,return_tensors='pt').pixel_values.to(device)

    @torch.inference_mode()
    def predict(rows,stage):
        model.eval();predictions={};start=time.perf_counter()
        for index,row in enumerate(rows,1):
            tokens=model.generate(pixels(row),max_new_tokens=args.max_tokens,num_beams=1)
            predictions[row['id']]=processor.batch_decode(tokens,skip_special_tokens=True)[0]
            if index%10==0 or index==len(rows):
                print(json.dumps({'stage':stage,'linesCompleted':index,'linesTotal':len(rows),
                                  'elapsedSeconds':time.perf_counter()-start}),flush=True)
        score=evaluate({r['id']:r['text'] for r in rows},predictions)
        score['secondsPerLine']=(time.perf_counter()-start)/len(rows)
        return score,predictions

    def save(step,predictions):
        nonlocal checkpoint
        def writer(path):
            model.save_pretrained(path,safe_serialization=True)
            processor.save_pretrained(path)
            write_json_atomic(path/'validation-predictions.json',predictions)
        parameter_bytes=sum(p.numel()*p.element_size() for p in model.parameters())
        checkpoint=publish_checkpoint(args.output,step,writer,minimum_free_bytes=max(1024**3,parameter_bytes+512*1024**2))
        report['checkpoint']=str(checkpoint)
        if step==0:report['baselineCheckpoint']=str(checkpoint)

    def complete(**extra):
        report.update(status='passed',stage='completed',completedAt=datetime.now(timezone.utc).isoformat(),
                      stepsCompleted=args.steps,bestStep=best_step,
                      selectedCheckpoint='fine-tuned' if best_step else 'pretrained-baseline-retained',
                      validationImproved=best_step>0,checkpoint=str(checkpoint),
                      checkpointSha256={p.name:file_sha256(p) for p in checkpoint.glob('*.safetensors')},**extra)
        write_json_atomic(report_path,report);print(json.dumps(report,indent=2),flush=True)

    report_path=args.output/'training-report.json'
    report.update(status='running',stage='gradient-preflight',stepsCompleted=0)
    write_json_atomic(report_path,report)
    # Fail early on unsupported device backward operations, before a lengthy
    # validation decode. No optimizer step or weight update occurs here.
    train_mode()
    first=splits['train'][0]
    labels=processor.tokenizer(first['text'],return_tensors='pt').input_ids.to(device)
    probe_loss=training_loss(model,labels,pixel_values=pixels(first))
    if not torch.isfinite(probe_loss):raise RuntimeError('Gradient preflight produced a non-finite loss')
    probe_loss.backward()
    gradients=[p.grad for p in model.parameters() if p.requires_grad and p.grad is not None]
    if not gradients or not all(torch.isfinite(gradient).all().item() for gradient in gradients):
        raise RuntimeError('Gradient preflight produced missing or non-finite gradients')
    report['gradientPreflight']={'loss':probe_loss.detach().item(),'gradientTensors':len(gradients),'optimizerSteps':0}
    optimizer.zero_grad(set_to_none=True);del probe_loss,gradients
    print(json.dumps({'stage':'gradient-preflight','result':report['gradientPreflight']}),flush=True)
    report['stage']='baseline-validation';write_json_atomic(report_path,report)
    baseline,baseline_predictions=predict(splits['validation'],'baseline-validation');report['pretrainedValidation']=baseline
    best=baseline['cer'];best_step=0;save(0,baseline_predictions)
    print(json.dumps({'stage':'baseline-validation','metrics':baseline}),flush=True)
    report['stage']='training'
    write_json_atomic(report_path,report)
    train=splits['train'];rng=random.Random(args.seed);order=list(range(len(train)));rng.shuffle(order);position=0
    optimizer.zero_grad(set_to_none=True)
    for step in range(1,args.steps+1):
        train_mode();loss_value=0.
        for _ in range(args.accumulation):
            if position>=len(order):rng.shuffle(order);position=0
            row=train[order[position]];position+=1
            labels=processor.tokenizer(row['text'],return_tensors='pt').input_ids
            if labels.shape[1]>args.max_tokens:raise ValueError('Training transcription exceeds max-tokens; no silent truncation is allowed')
            loss=training_loss(model,labels.to(device),pixel_values=pixels(row))
            if not torch.isfinite(loss):raise RuntimeError('Training produced a non-finite loss')
            loss_value+=loss.detach().item()/args.accumulation
            (loss/args.accumulation).backward()
        torch.nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad],1.)
        optimizer.step();optimizer.zero_grad(set_to_none=True)
        if step%10==0:print(json.dumps({'step':step,'loss':loss_value}),flush=True)
        if step%args.eval_every==0 or step==args.steps:
            score,validation_predictions=predict(splits['validation'],'validation')
            report['training'].append({'step':step,'loss':loss_value,'validation':score})
            if score['cer']<best:best=score['cer'];best_step=step;save(step,validation_predictions)
            report.update(bestStep=best_step,stepsCompleted=step)
            write_json_atomic(report_path,report)
            print(json.dumps({'stage':'validation','step':step,'metrics':score,'selectedStep':best_step}),flush=True)
    # Parameter groups also own the original weights; release them before a
    # checkpoint reload so the host does not retain two full training models.
    del optimizer,loss,model,groups,encoder_parameters
    gc.collect()
    if device=='mps':torch.mps.empty_cache()
    elif device=='cuda':torch.cuda.empty_cache()
    if args.selection_only:
        complete(testDeferred=True)
        return
    model=VisionEncoderDecoderModel.from_pretrained(checkpoint).to(device)
    # The test split was not used for gradients or checkpoint selection.
    report['stage']='held-out-test';write_json_atomic(report_path,report)
    test,predictions=predict(splits['test'],'selected-held-out-test')
    (args.output/'test-predictions.json').write_text(json.dumps(predictions,ensure_ascii=False,indent=2)+'\n')
    # Compare on the identical held-out set only after checkpoint selection is final.
    # These results never feed back into training or validation selection.
    if best_step:
        del model
        if device=='mps':torch.mps.empty_cache()
        elif device=='cuda':torch.cuda.empty_cache()
        model=VisionEncoderDecoderModel.from_pretrained(model_name,revision=revision).to(device)
        baseline_test,baseline_predictions=predict(splits['test'],'pretrained-held-out-test')
    else:
        baseline_test,baseline_predictions=test,predictions
    (args.output/'baseline-test-predictions.json').write_text(json.dumps(baseline_predictions,ensure_ascii=False,indent=2)+'\n')
    failures=[{'id':row['id'],'reference':row['text'],'prediction':predictions[row['id']],
               'baselinePrediction':baseline_predictions[row['id']],
               'cer':evaluate({row['id']:row['text']},{row['id']:predictions[row['id']]})['cer']}
              for row in splits['test'] if predictions[row['id']]!=row['text']]
    (args.output/'test-errors.json').write_text(json.dumps(sorted(failures,key=lambda row:row['cer'],reverse=True),ensure_ascii=False,indent=2)+'\n')
    complete(test=test,pretrainedTest=baseline_test,testCerImproved=test['cer']<baseline_test['cer'])


if __name__=='__main__':main()
