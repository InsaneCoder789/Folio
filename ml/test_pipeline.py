import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from ml.metrics import evaluate,distance
from ml.manifest import read_manifest


class HandwritingPipelineTests(unittest.TestCase):
    def test_detector_rounding_matches_python_half_even_at_stride_boundaries(self):
        from ml.detector_codec import detector_dimensions
        self.assertEqual((384,448),detector_dimensions(400,432))
        self.assertEqual((1280,512),detector_dimensions(1600,621))
        self.assertEqual((32,32),detector_dimensions(32,32))
        with self.assertRaises(ValueError):detector_dimensions(0,400)

    def test_fullpage_matches_use_geometry_only_and_never_reuse_detections(self):
        from ml.evaluate_fullpage_handwriting import assign_matches
        pairs=[(0,0,.8),(1,0,.9),(0,1,.7),(2,2,.49)]
        self.assertEqual({1:(0,.9),0:(1,.7)},assign_matches(pairs))
        self.assertEqual(assign_matches(pairs),assign_matches(list(reversed(pairs))))
        with self.assertRaises(ValueError):assign_matches([(0,0,float('nan'))])

    def test_mobile_ctc_keeps_repeated_letters_separated_by_blanks(self):
        from ml.mobile_codec import decode_ctc
        alphabet=['blank','b','o','k',' ']
        self.assertEqual('book',decode_ctc([0,1,1,2,2,0,2,3,3,0],alphabet))
        self.assertEqual('b k',decode_ctc([1,4,4,3],alphabet))
        self.assertEqual('',decode_ctc([0,0],alphabet))
        for indices in ([-1],[5],[1.2]):
            with self.assertRaises((ValueError,TypeError)):decode_ctc(indices,alphabet)

    def test_corpus_errors_use_reference_denominators(self):
        result=evaluate({'a':'cat','b':'two words'},{'a':'cut','b':'two extra words'})
        self.assertEqual(1+len('extra '),result['characterErrors'])
        self.assertEqual(2,result['wordErrors'])
        self.assertEqual(12,result['characters'])
        self.assertAlmostEqual(2/3,result['wer'])

    def test_insertions_are_not_hidden_by_clamping(self):
        self.assertGreater(evaluate({'a':'a'},{'a':'abcdefgh'})['cer'],1)

    def test_missing_prediction_cannot_improve_score(self):
        with self.assertRaises(ValueError):evaluate({'a':'text','b':'more'},{'a':'text'})

    def test_unicode_normalization_preserves_case(self):
        self.assertEqual(0,evaluate({'a':'café'},{'a':'cafe\u0301'})['cer'])
        self.assertGreater(evaluate({'a':'Text'},{'a':'text'})['cer'],0)

    def test_alignment_distance_known_example(self):
        self.assertEqual(3,distance('kitten','sitting'))

    def test_manifest_blocks_document_and_image_leakage(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);rows=[]
            for split in ('train','validation','test'):
                content=split.encode();(root/(split+'.png')).write_bytes(content)
                rows.append(dict(id=split,document_id=split,split=split,image=split+'.png',text='A line',sha256=hashlib.sha256(content).hexdigest(),license='CC-BY-4.0'))
            path=root/'manifest.jsonl'
            def save():path.write_text('\n'.join(json.dumps(r) for r in rows))
            save();self.assertEqual(3,len(read_manifest(path)))
            rows[1]['document_id']='train';save()
            with self.assertRaises(ValueError):read_manifest(path)
            rows[1]['document_id']='validation';rows[1]['sha256']=rows[0]['sha256'];save()
            with self.assertRaises(ValueError):read_manifest(path,verify_files=False)

    def test_manifest_blocks_writer_leakage_and_path_escape(self):
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'manifest.jsonl'
            rows=[dict(id=s,document_id=s,writer_id='same',split=s,image=s+'.png',text='Text',sha256=s,license='CC-BY-4.0') for s in ('train','validation','test')]
            path.write_text('\n'.join(json.dumps(r) for r in rows))
            with self.assertRaises(ValueError):read_manifest(path,verify_files=False)
            rows[0]['image']='../outside.png';path.write_text('\n'.join(json.dumps(r) for r in rows))
            with self.assertRaises(ValueError):read_manifest(path,verify_files=False)



class DatasetPartitionTests(unittest.TestCase):
    def test_expanded_review_sampling_excludes_all_previously_seen_photos(self):
        from ml.prepare_modern_corpus import select_review_images
        candidates=[dict(imageId=str(i)) for i in range(20)]
        first=select_review_images(candidates,8)
        excluded={row['imageId'] for row in first}
        second=select_review_images(candidates,12,excluded)
        self.assertFalse(excluded & {row['imageId'] for row in second})
        self.assertEqual(second,select_review_images(list(reversed(candidates)),12,excluded))
        with self.assertRaises(ValueError):select_review_images(candidates,13,excluded)
        with self.assertRaises(ValueError):select_review_images(candidates+[candidates[0]],1)

    def test_public_repository_blocks_model_weights_and_raw_images_outside_cache_folders(self):
        from tools.verify_repository import prohibited
        for name in ('model.safetensors','weights.ONNX','training.tgz','raw-page.jpg','inference.pdiparams'):
            self.assertTrue(prohibited('ml/experiment/'+name))
        for name in ('ml/artifacts.py','ml/requirements-mobile.txt','ml/evaluate_mobile_baseline.py',
                     'android/app/src/main/assets/fonts/NotoSans-Regular.ttf'):
            self.assertFalse(prohibited(name))

    def test_mobile_baseline_pin_rejects_tampering_and_partial_downloads(self):
        from ml.evaluate_mobile_baseline import verify_payload
        payload=b'pinned weights';size=len(payload)
        sha=hashlib.sha256(payload).hexdigest()
        blob=hashlib.sha1(b'blob '+str(size).encode()+b'\0'+payload).hexdigest()
        verify_payload(payload,size,'sha256',sha);verify_payload(payload,size,'git-sha1',blob)
        for data,length,algorithm,digest in ((payload[:-1],size,'sha256',sha),
                                           (b'changed bytes',size,'sha256',sha),
                                           (payload,size,'git-sha1','wrong'),
                                           (payload,size,'unknown',sha)):
            with self.assertRaises(ValueError):verify_payload(data,length,algorithm,digest)

    def test_corpus_preparation_cannot_replace_reviewed_or_prepared_evidence(self):
        from ml.prepare_modern_corpus import require_unprepared
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);require_unprepared(root)
            for name in ('review-candidates.jsonl','preparation-report.json','visual-review.json'):
                path=root/name;path.write_bytes(b'preserve evidence')
                with self.assertRaises(FileExistsError):require_unprepared(root)
                self.assertEqual(b'preserve evidence',path.read_bytes());path.unlink()

    def test_modern_source_photo_leakage_is_detected_across_distinct_ids(self):
        from ml.assemble_modern_experiment import require_separation
        training=[dict(id='first',document_id='first-page',split='train',sha256='first-crop',source_image_sha256='same-photo')]
        validation=[dict(id='second',document_id='second-page',split='validation',sha256='second-crop',source_image_sha256='same-photo')]
        with self.assertRaises(ValueError):require_separation(training,validation)
        validation[0]['source_image_sha256']='different-photo'
        require_separation(training,validation)

    def test_validation_only_license_policy_must_be_explicit(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);image=root/'line.png';image.write_bytes(b'evaluation-image-integrity-fixture')
            row=dict(id='line',document_id='page',split='validation',image='line.png',text='A line',
                     sha256=hashlib.sha256(image.read_bytes()).hexdigest(),license='CC-BY-SA-4.0')
            path=root/'manifest.jsonl';path.write_text(json.dumps(row)+'\n')
            with self.assertRaises(ValueError):read_manifest(path)
            with self.assertRaises(ValueError):read_manifest(path,required_splits=('validation',))
            self.assertEqual(1,len(read_manifest(path,required_splits=('validation',),allowed_licenses=('CC-BY-SA-4.0',))))

    def test_visual_review_must_cover_and_bind_the_exact_candidate_manifest(self):
        from ml.evaluate_modern_validation import admitted_samples
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);rows=[]
            for identifier in ('first','second'):
                image=root/(identifier+'.png');image.write_bytes(identifier.encode())
                rows.append(dict(id=identifier,document_id='page',split='validation',image=image.name,text='A reviewed line',
                                 sha256=hashlib.sha256(image.read_bytes()).hexdigest(),license='CC-BY-SA-4.0'))
            manifest=root/'manifest.jsonl';manifest.write_text('\n'.join(json.dumps(row) for row in rows))
            boards=root/'review-boards';boards.mkdir();board=boards/'page.png';board.write_bytes(b'review-board-fixture')
            data={'candidateManifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest(),'reviewer':'Unit test fixture',
                  'scope':'English and supplied-transcription visual review',
                  'evidenceBoardsSha256':{'page.png':hashlib.sha256(board.read_bytes()).hexdigest()},
                  'decisions':[{'id':'first','decision':'accept','reason':'Fixture accepted'},{'id':'second','decision':'exclude','reason':'Fixture excluded'}]}
            review=root/'review.json';review.write_text(json.dumps(data))
            self.assertEqual(['first'],[row['id'] for row in admitted_samples(manifest,review)[0]])
            data['decisions'].pop();review.write_text(json.dumps(data))
            with self.assertRaises(ValueError):admitted_samples(manifest,review)
            data['decisions'].append({'id':'second','decision':'exclude','reason':'Fixture excluded'})
            data['candidateManifestSha256']='changed';review.write_text(json.dumps(data))
            with self.assertRaisesRegex(ValueError,'different candidate manifest'):admitted_samples(manifest,review)

    def test_visual_review_rejects_missing_changed_and_escaped_boards(self):
        from ml.evaluate_modern_validation import admitted_samples
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);image=root/'line.png';image.write_bytes(b'line-fixture')
            row=dict(id='line',document_id='page',split='train',image=image.name,text='A line',
                     sha256=hashlib.sha256(image.read_bytes()).hexdigest(),license='CC-BY-SA-4.0')
            manifest=root/'manifest.jsonl';manifest.write_text(json.dumps(row)+'\n')
            boards=root/'review-boards';boards.mkdir();board=boards/'page.png';board.write_bytes(b'board-fixture')
            digest=hashlib.sha256(board.read_bytes()).hexdigest()
            data=dict(candidateManifestSha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),reviewer='Unit test fixture',
                      scope='English and supplied-transcription visual review',evidenceBoardsSha256={'page.png':digest},
                      decisions=[dict(id='line',decision='accept',reason='Fixture')])
            review=root/'review.json'
            def check():
                review.write_text(json.dumps(data));return admitted_samples(manifest,review,split='train')
            self.assertEqual(1,len(check()[0]))
            board.write_bytes(b'changed')
            with self.assertRaises(ValueError):check()
            board.unlink()
            with self.assertRaises(ValueError):check()
            data['evidenceBoardsSha256']={}
            with self.assertRaises(ValueError):check()
            # Even a matching hash cannot admit an evidence symlink outside the board directory.
            outside=root/'outside.png';outside.write_bytes(b'board-fixture');board.symlink_to(outside)
            data['evidenceBoardsSha256']={'page.png':digest}
            with self.assertRaises(ValueError):check()

    def test_modern_crop_uses_pillow_corner_order_and_preserves_dimensions(self):
        from ml.prepare_modern_corpus import crop_geometry
        points=[[100,100],[300,100],[300,140],[100,140]]
        size,quad=crop_geometry(points,1000,800)
        self.assertEqual((200,40),size)
        self.assertEqual((100.,100.,100.,140.,300.,140.,300.,100.),quad)
        self.assertEqual((size,quad),crop_geometry(list(reversed(points)),1000,800))

    def test_modern_crop_rejects_outside_degenerate_and_vertical_polygons(self):
        from ml.prepare_modern_corpus import crop_geometry
        for points in ([[-1,10],[40,10],[40,40],[0,40]],[[10,10]]*4,
                       [[10,10],[40,10],[40,300],[10,300]],[[10,10],[40,10],[40,40]]):
            with self.assertRaises(ValueError):crop_geometry(points,1000,800)

    def test_modern_candidate_filter_excludes_printed_vertical_and_illegible_words(self):
        import copy
        from ml.inspect_modern_source import handwriting_lines
        word={'handwritten':True,'legible':True,'vertical':False}
        line={'text':'A handwritten line','handwritten':True,'legible':True,'vertical':False,
              'words':[word],'vertices':[[0,0],[100,0],[100,20],[0,20]]}
        def annotation(value):return {'paragraphs':[{'lines':[value]}]}
        self.assertEqual(1,len(handwriting_lines(annotation(line))))
        for key,value in (('handwritten',False),('vertical',True),('legible',False)):
            changed=copy.deepcopy(line);changed['words'][0][key]=value
            self.assertEqual([],handwriting_lines(annotation(changed)))
        changed=copy.deepcopy(line);changed['text']='12345'
        self.assertEqual([],handwriting_lines(annotation(changed)))

    def test_streaming_annotation_budget_rejects_excess_bytes(self):
        import io
        from ml.inspect_modern_source import BoundedReader
        stream=BoundedReader(io.BytesIO(b'abcdefgh'),5)
        self.assertEqual(b'',stream.read(0))
        self.assertEqual(b'abcd',stream.read(4))
        with self.assertRaises(ValueError):stream.read(65536)
        self.assertEqual(6,stream.bytes_read)

    def test_published_transcriptions_are_used_without_inventing_labels(self):
        from ml.prepare_subset import labels_from_text
        labels,excluded=labels_from_text('002_393_001_01\tArt. 1.\n002_393_001_02\t<gap/>\n002_393_001_03\t\n')
        self.assertEqual({'002_393_001_01':'Art. 1.'},labels)
        self.assertEqual({'ambiguous-editorial-annotation':1,'blank':1},excluded)

    def test_excluded_duplicate_and_unstructured_labels_are_rejected(self):
        from ml.prepare_subset import labels_from_text
        for text in ('002_393_001_01\t<gap/>\n002_393_001_01\tGuess', 'invented text', 'wrong_id\tText'):
            with self.assertRaises(ValueError):labels_from_text(text)

    def test_document_partition_is_reproducible_and_never_splits_page_lines(self):
        from ml.prepare_subset import assign_documents
        identifiers=[f'002_{page:03}_001_{line:02}' for page in range(12) for line in range(5)]
        split=assign_documents(identifiers,20261015)
        self.assertEqual(split,assign_documents(reversed(identifiers),20261015))
        self.assertEqual(12,len(split))
        self.assertEqual({'train','validation','test'},set(split.values()))
        self.assertEqual(8,list(split.values()).count('train'))
        self.assertEqual(2,list(split.values()).count('validation'))
        self.assertEqual(2,list(split.values()).count('test'))

    def test_too_few_source_documents_are_rejected(self):
        from ml.prepare_subset import assign_documents
        with self.assertRaises(ValueError):assign_documents(['002_001_001_01'],1)

    def test_published_partitions_reject_overlap_and_wrong_union(self):
        from ml.prepare_training_corpus import published_partitions,PREFIX
        contents={}
        for title,page in (('Train','002_001_001'),('Validation','002_002_001'),('Test','002_003_001')):
            contents[PREFIX+'Partitions/'+title+'.lst']=page+'\n'
            contents[PREFIX+'Partitions/'+title+'Lines.lst']=page+'_01_01\n'
        union=PREFIX+'Partitions/TrainValidationLines.lst'
        contents[union]='002_001_001_01_01\n002_002_001_01_01\n'
        self.assertEqual(3,len(published_partitions(contents)))
        contents[union]='002_001_001_01_01\n'
        with self.assertRaises(ValueError):published_partitions(contents)
        contents[union]='002_001_001_01_01\n002_002_001_01_01\n'
        contents[PREFIX+'Partitions/TestLines.lst']='002_001_001_01_01\n'
        with self.assertRaises(ValueError):published_partitions(contents)

    def test_balanced_selection_spans_pages_and_is_order_independent(self):
        from ml.prepare_training_corpus import select_lines
        ids=[f'002_{page:03}_001_01_{line:02}' for page in range(10) for line in range(20)]
        chosen=select_lines(ids,25,42)
        self.assertEqual(chosen,select_lines(list(reversed(ids)),25,42))
        self.assertEqual(25,len(set(chosen)))
        self.assertEqual(10,len({'_'.join(i.split('_')[:3]) for i in chosen[:10]}))
        self.assertEqual(set(ids),set(select_lines(ids,1000,42)))


class ArchiveSafetyTests(unittest.TestCase):
    def test_shared_request_budget_is_atomic_across_workers(self):
        from concurrent.futures import ThreadPoolExecutor
        from ml.http_range import RequestBudget
        budget=RequestBudget(32)
        def reserve(_):
            try:budget.reserve(4);return True
            except RuntimeError:return False
        with ThreadPoolExecutor(max_workers=8) as pool:results=list(pool.map(reserve,range(16)))
        self.assertEqual(8,sum(results));self.assertEqual(32,budget.reserved)

    def test_parallel_cache_writers_do_not_share_temporary_files(self):
        import io,threading
        from concurrent.futures import ThreadPoolExecutor
        from unittest.mock import patch
        from ml.http_range import HttpRangeFile
        barrier=threading.Barrier(2)
        class Response(io.BytesIO):
            status=206;headers={'Content-Range':'bytes 0-7/8'}
        def response(*a,**k):barrier.wait(timeout=5);return Response(b'abcdefgh')
        with tempfile.TemporaryDirectory() as tmp:
            def fetch(_):return HttpRangeFile('https://example.invalid/archive',8,block_size=8,cache_directory=tmp).read()
            with patch('urllib.request.urlopen',side_effect=response),ThreadPoolExecutor(max_workers=2) as pool:
                self.assertEqual([b'abcdefgh']*2,list(pool.map(fetch,range(2))))
            self.assertEqual(1,len(list(Path(tmp).rglob('*.bin'))))
            self.assertEqual([],list(Path(tmp).rglob('*.partial')))

    def test_prefetch_bounds_selected_entries_and_deduplicates_blocks(self):
        from ml.prefetch_training_ranges import entry_blocks
        rows=[{'name':'a','offset':0},{'name':'b','offset':100},{'name':'c','offset':250}]
        self.assertEqual([0,1,2],entry_blocks(rows,{'a','b'},50,128))
        with self.assertRaises(ValueError):entry_blocks(rows,{'c'},50,128)
        with self.assertRaises(ValueError):entry_blocks(rows,{'missing'},50,128)

    def test_stored_nested_archive_is_read_without_full_extraction(self):
        import io,zipfile
        from ml.http_range import stored_member
        inner=io.BytesIO()
        with zipfile.ZipFile(inner,'w') as archive:archive.writestr('line.txt','Actual line transcription')
        outer=io.BytesIO()
        with zipfile.ZipFile(outer,'w',compression=zipfile.ZIP_STORED) as archive:archive.writestr('nested.zip',inner.getvalue())
        outer.seek(0)
        with zipfile.ZipFile(outer) as archive:
            with zipfile.ZipFile(stored_member(archive,archive.getinfo('nested.zip'))) as nested:
                self.assertEqual(b'Actual line transcription',nested.read('line.txt'))

    def test_whole_archive_response_is_rejected(self):
        from unittest.mock import patch
        from ml.http_range import HttpRangeFile
        import io
        class Response(io.BytesIO):
            status=200;headers={}
        with patch('urllib.request.urlopen',return_value=Response(b'not a ranged response')):
            with self.assertRaises(RuntimeError):HttpRangeFile('https://example.invalid/archive',100).read(1)

    def test_read_budget_rejects_oversized_fetch(self):
        from ml.http_range import HttpRangeFile
        with self.assertRaises(ValueError):HttpRangeFile('https://example.invalid/archive',100*1024*1024).read()
        with self.assertRaises(RuntimeError):HttpRangeFile('https://example.invalid/archive',100,block_size=16,max_download_bytes=8).read(1)

    def test_truncated_ranges_retry_without_caching_partial_bytes(self):
        import io
        from unittest.mock import patch
        from ml.http_range import HttpRangeFile
        class Response(io.BytesIO):
            status=206;headers={'Content-Range':'bytes 0-7/8'}
        source=HttpRangeFile('https://example.invalid/archive',8,block_size=8,max_download_bytes=16)
        with patch('urllib.request.urlopen',side_effect=[Response(b'abc'),Response(b'abcdefgh')]) as request,patch('ml.http_range.time.sleep'):
            self.assertEqual(b'abcdefgh',source.read())
            self.assertEqual(2,request.call_count)
            self.assertEqual(11,source.bytes_downloaded)
            self.assertEqual(b'abcdefgh',source.cache[0])

    def test_retries_keep_transfer_budget_and_attempt_bound(self):
        import io
        from unittest.mock import patch
        from ml.http_range import HttpRangeFile
        class Response(io.BytesIO):
            status=206;headers={'Content-Range':'bytes 0-7/8'}
        for budget,expected_calls in ((8,1),(64,3)):
            source=HttpRangeFile('https://example.invalid/archive',8,block_size=8,max_download_bytes=budget)
            with patch('urllib.request.urlopen',side_effect=lambda *a,**k:Response(b'abc')) as request,patch('ml.http_range.time.sleep'):
                with self.assertRaises(RuntimeError):source.read()
                self.assertEqual(expected_calls,request.call_count)
                self.assertFalse(source.cache)


class ExperimentIntegrityTests(unittest.TestCase):
    def fixture(self,root):
        from ml.metrics import evaluate
        rows=[]
        for split in ('train','validation','test'):
            data=split.encode();(root/(split+'.png')).write_bytes(data)
            rows.append(dict(id=split,document_id=split,split=split,image=split+'.png',text='cat',
                             sha256=hashlib.sha256(data).hexdigest(),license='CC-BY-4.0'))
        manifest=root/'manifest.jsonl';manifest.write_text('\n'.join(json.dumps(row) for row in rows))
        experiment=root/'experiment';checkpoint=experiment/'best';checkpoint.mkdir(parents=True)
        weights=checkpoint/'model.safetensors';weights.write_bytes(b'unit-test-checkpoint-integrity-fixture')
        selected={'test':'cat'};baseline={'test':'cut'}
        (experiment/'test-predictions.json').write_text(json.dumps(selected))
        (experiment/'baseline-test-predictions.json').write_text(json.dumps(baseline))
        report={'status':'passed','stage':'completed','manifestSha256':hashlib.sha256(manifest.read_bytes()).hexdigest(),
                'splitCounts':{'train':1,'validation':1,'test':1},'test':evaluate({'test':'cat'},selected),
                'pretrainedTest':evaluate({'test':'cat'},baseline),'pretrainedValidation':{'cer':.5},
                'training':[{'step':100,'validation':{'cer':.4}},{'step':200,'validation':{'cer':.6}}],
                'bestStep':100,'stepsRequested':200,'stepsCompleted':200,'checkpoint':str(checkpoint),
                'checkpointSha256':{'model.safetensors':hashlib.sha256(weights.read_bytes()).hexdigest()},
                'selectedCheckpoint':'fine-tuned','validationImproved':True,'testCerImproved':True}
        (experiment/'training-report.json').write_text(json.dumps(report))
        return manifest,experiment,report

    def test_artifact_verification_recomputes_scores_and_detects_tampering(self):
        from ml.validate_experiment import validate
        with tempfile.TemporaryDirectory() as tmp:
            manifest,experiment,report=self.fixture(Path(tmp))
            self.assertTrue(validate(manifest,experiment)['checkpointHashesVerified'])
            with self.assertRaises(ValueError):validate(manifest,experiment,allow_selection=True)
            report['test']['cer']=.1
            (experiment/'training-report.json').write_text(json.dumps(report))
            with self.assertRaises(ValueError):validate(manifest,experiment)
            report['test']['cer']=0.
            (experiment/'training-report.json').write_text(json.dumps(report))
            (experiment/'best/model.safetensors').write_bytes(b'changed')
            with self.assertRaises(ValueError):validate(manifest,experiment)

    def test_selection_and_improvement_claims_cannot_override_evidence(self):
        from ml.validate_experiment import validate
        for field,value in (('bestStep',200),('testCerImproved',False),('validationImproved',False),
                            ('selectedCheckpoint','pretrained-baseline-retained'),('stepsCompleted',199)):
            with tempfile.TemporaryDirectory() as tmp:
                manifest,experiment,report=self.fixture(Path(tmp));report[field]=value
                (experiment/'training-report.json').write_text(json.dumps(report))
                with self.assertRaises(ValueError,msg=field):validate(manifest,experiment)

    def test_selection_integrity_cannot_be_presented_as_final_test_verification(self):
        from ml.validate_experiment import validate
        from ml.metrics import evaluate
        with tempfile.TemporaryDirectory() as tmp:
            manifest,experiment,report=self.fixture(Path(tmp))
            rows=[json.loads(line) for line in manifest.read_text().splitlines() if json.loads(line)['split']!='test']
            manifest.write_text('\n'.join(json.dumps(row) for row in rows))
            baseline=experiment/'baseline';baseline.mkdir()
            base_prediction={'validation':'cut'};selected_prediction={'validation':'cat'}
            (baseline/'validation-predictions.json').write_text(json.dumps(base_prediction))
            (experiment/'best/validation-predictions.json').write_text(json.dumps(selected_prediction))
            base_score=evaluate({'validation':'cat'},base_prediction);selected_score=evaluate({'validation':'cat'},selected_prediction)
            for key in ('test','pretrainedTest','testCerImproved'):report.pop(key)
            report.update(schemaVersion=2,protocol='validation-selection-only',testDeferred=True,
                          datasetLicense='CC-BY-4.0',baselineCheckpoint=str(baseline),
                          manifestSha256=hashlib.sha256(manifest.read_bytes()).hexdigest(),
                          splitCounts={'train':1,'validation':1},pretrainedValidation=base_score,sourceHashes={})
            report['training'][0]['validation']=selected_score
            source=experiment/'source';source.mkdir()
            for name in ('train_handwriting.py','requirements.txt','metrics.py','manifest.py','artifacts.py'):
                path=source/name;path.write_text('unit-test source-integrity fixture\n')
                report['sourceHashes'][name]=hashlib.sha256(path.read_bytes()).hexdigest()
            (experiment/'training-report.json').write_text(json.dumps(report))
            with self.assertRaises(ValueError):validate(manifest,experiment)
            result=validate(manifest,experiment,allow_selection=True)
            self.assertEqual('passed-selection-integrity',result['status'])
            self.assertFalse(result['heldOutTestVerified']);self.assertFalse(result['qualityAcceptedForAndroid'])
            (experiment/'best/validation-predictions.json').write_text(json.dumps({'validation':'changed'}))
            with self.assertRaises(ValueError):validate(manifest,experiment,allow_selection=True)


class ArtifactPublicationTests(unittest.TestCase):
    def test_invalid_report_does_not_replace_previous_completed_metadata(self):
        from ml.artifacts import write_json_atomic
        with tempfile.TemporaryDirectory() as tmp:
            path=Path(tmp)/'report.json';write_json_atomic(path,{'status':'completed'})
            with self.assertRaises(ValueError):write_json_atomic(path,{'loss':float('nan')})
            self.assertEqual({'status':'completed'},json.loads(path.read_text()))
            self.assertEqual(['report.json'],[p.name for p in Path(tmp).iterdir()])

    def test_checkpoint_write_failure_preserves_earlier_checkpoint(self):
        from ml.artifacts import publish_checkpoint
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);old=root/'checkpoint-000000';old.mkdir();(old/'weights').write_bytes(b'previous')
            def failing_writer(path):
                (path/'partial-weights').write_bytes(b'incomplete')
                raise OSError('Simulated storage write failure')
            with self.assertRaises(OSError):publish_checkpoint(root,100,failing_writer)
            self.assertEqual(b'previous',(old/'weights').read_bytes())
            self.assertEqual([old],list(root.iterdir()))

    def test_published_checkpoint_is_immutable(self):
        from ml.artifacts import publish_checkpoint
        with tempfile.TemporaryDirectory() as tmp:
            def writer(path):
                (path/'config.json').write_text('{"model_type":"unit-test-fixture"}')
                (path/'model.safetensors').write_bytes(b'checkpoint-publication-unit-fixture')
            root=Path(tmp);path=publish_checkpoint(root,100,writer)
            self.assertEqual('checkpoint-000100',path.name)
            before=(path/'model.safetensors').read_bytes()
            with self.assertRaises(FileExistsError):publish_checkpoint(root,100,writer)
            self.assertEqual(before,(path/'model.safetensors').read_bytes())

class IamProtocolTests(unittest.TestCase):
    def test_writer_partition_is_deterministic_and_requires_identity(self):
        from ml.iam_protocol import writer_split
        self.assertEqual(writer_split('writer-001'), writer_split('writer-001'))
        self.assertEqual({'train', 'validation', 'test'}, {writer_split(str(i)) for i in range(200)})
        with self.assertRaises(ValueError): writer_split('')

    def test_zero_baseline_and_tiny_evaluation_cannot_pass(self):
        from ml.iam_protocol import promotion
        result = promotion({'a': 'correct'}, {'a': 'correct'}, {'a': 'correct'}, True)
        self.assertFalse(result['lineGatePassed'])
        self.assertFalse(result['appReady'])

    def test_promotion_requires_verified_writer_separation_and_improvement(self):
        from ml.iam_protocol import promotion
        reference = {str(i): 'the handwritten sentence' for i in range(500)}
        candidate = dict(reference)
        baseline = {key: 'tha handwritten sentence' for key in reference}
        self.assertTrue(promotion(reference, candidate, baseline, True)['lineGatePassed'])
        self.assertFalse(promotion(reference, candidate, baseline, False)['lineGatePassed'])
        self.assertFalse(promotion(reference, baseline, baseline, True)['lineGatePassed'])

    def test_iam_annotation_requires_writer_metadata(self):
        import io, tarfile
        from ml.iam_protocol import annotations
        with tempfile.TemporaryDirectory() as tmp:
            archive = Path(tmp) / 'xml.tgz'
            payload = b'<form id="a01-000"><handwritten-part><line id="a01-000-00" text="Text"/></handwritten-part></form>'
            with tarfile.open(archive, 'w:gz') as out:
                info = tarfile.TarInfo('a.xml'); info.size = len(payload)
                out.addfile(info, io.BytesIO(payload))
            with self.assertRaises(ValueError): annotations(archive)

    def test_iam_archive_import_preserves_labels_and_seals_test_out_of_selection(self):
        import io, tarfile
        try:
            from PIL import Image
        except ImportError:
            self.skipTest('Archive image integration requires the pinned ML environment with Pillow')
        from ml.iam_protocol import prepare, writer_split, LICENSE
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); xml = root / 'xml.tgz'; lines = root / 'lines.tgz'
            writers = {role: next(str(i) for i in range(1000) if writer_split(str(i)) == role)
                       for role in ('train', 'validation', 'test')}
            with tarfile.open(xml, 'w:gz') as out_xml, tarfile.open(lines, 'w:gz') as out_lines:
                for i, role in enumerate(writers):
                    form = f'a01-00{i}'; identity = form + '-00'
                    payload = (f'<form id="{form}" writer-id="{writers[role]}"><handwritten-part>'
                               f'<line id="{identity}" text="Case &amp; punctuation!"/>'
                               '</handwritten-part></form>').encode()
                    info = tarfile.TarInfo(form+'.xml'); info.size = len(payload)
                    out_xml.addfile(info, io.BytesIO(payload))
                    pixels = io.BytesIO(); Image.new('RGB', (20+i, 12), (i*50, 0, 0)).save(pixels, 'PNG')
                    payload = pixels.getvalue(); info = tarfile.TarInfo('nested/'+identity+'.png'); info.size = len(payload)
                    out_lines.addfile(info, io.BytesIO(payload))
            output = root / 'prepared'; prepare(lines, xml, output)
            rows = read_manifest(output/'manifest.jsonl', allowed_licenses=(LICENSE,))
            self.assertEqual(3, len(rows)); self.assertTrue(all(r['text'] == 'Case & punctuation!' for r in rows))
            selected = read_manifest(output/'selection-manifest.jsonl', required_splits=('train','validation'), allowed_licenses=(LICENSE,))
            self.assertEqual(2, len(selected)); self.assertFalse(any(r['split'] == 'test' for r in selected))
            report = json.loads((output/'preparation-report.json').read_text())
            self.assertTrue(report['writerSeparated']); self.assertFalse(report['officialBenchmarkSplit'])
            with self.assertRaises(FileExistsError): prepare(lines, xml, output)

    def test_iam_original_terms_do_not_become_creative_commons(self):
        from ml.iam_protocol import LICENSE
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'manifest.jsonl'
            rows = [dict(id=s, document_id=s, writer_id=s, split=s, image=s+'.png', text='Text', sha256=s, license=LICENSE)
                    for s in ('train', 'validation', 'test')]
            path.write_text('\n'.join(json.dumps(row) for row in rows))
            with self.assertRaises(ValueError): read_manifest(path, verify_files=False)
            self.assertEqual(3, len(read_manifest(path, verify_files=False, allowed_licenses=(LICENSE,))))


class IamFormsAdapterTests(unittest.TestCase):
    def test_ascii_labels_preserve_case_punctuation_and_token_internal_spaces(self):
        from ml.prepare_iam_forms import parse_metadata
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); forms = root/'forms.txt'; lines = root/'lines.txt'
            forms.write_text('a01-000u 000 1 all 1 1 2 2\n')
            lines.write_text('a01-000u-00 err 154 2 1 2 10 4 M Ps|tomorrow|.\n')
            _, grouped = parse_metadata(forms, lines)
            row = grouped['a01-000u'][0]
            self.assertEqual('M Ps tomorrow .', row['text'])
            self.assertEqual([1, 2, 10, 4], row['sourceBox'])
            lines.write_text(lines.read_text()*2)
            with self.assertRaises(ValueError): parse_metadata(forms, lines)

    def test_forms_zip_preparation_is_atomic_and_writer_separated(self):
        import io, zipfile
        try:
            from PIL import Image
        except ImportError:
            self.skipTest('Requires Pillow in pinned ML environment')
        from ml.prepare_iam_forms import prepare
        from ml.iam_protocol import writer_split, LICENSE
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp); archive = root/'source.zip'; forms = root/'forms.txt'; lines = root/'lines.txt'
            form_rows = []; line_rows = []
            with zipfile.ZipFile(archive, 'w') as out:
                for i, role in enumerate(('train', 'validation', 'test')):
                    writer = next(str(n) for n in range(1000) if writer_split(str(n)) == role)
                    document = f'a01-00{i}'
                    form_rows.append(f'{document} {writer} 1 all 1 1 2 2')
                    line_rows.append(f'{document}-00 ok 154 2 1 2 10 4 Case|{role}!')
                    data = io.BytesIO(); Image.new('RGB', (25, 20), (i*60, 20, 30)).save(data, 'PNG')
                    out.writestr(f'data/{writer}/{document}.png', data.getvalue())
            forms.write_text('\n'.join(form_rows)); lines.write_text('\n'.join(line_rows))
            output = root/'prepared'; prepare(archive, forms, lines, output, verify_pins=False)
            rows = read_manifest(output/'manifest.jsonl', allowed_licenses=(LICENSE,))
            self.assertEqual(3, len(rows))
            with Image.open(rows[0]['image_path']) as crop:
                self.assertEqual((10, 4), crop.size)
            selected = read_manifest(output/'selection-manifest.jsonl', required_splits=('train','validation'), allowed_licenses=(LICENSE,))
            self.assertEqual(2, len(selected))
            report = json.loads((output/'preparation-report.json').read_text())
            self.assertFalse(report['trainingStarted']); self.assertFalse(report['visualReviewPassed'])
            self.assertTrue(all(set(b['ids']) <= {r['id'] for r in rows if r['split']=='train'} for b in report['reviewBoards']))
            with self.assertRaises(FileExistsError): prepare(archive, forms, lines, output, verify_pins=False)
            lines.write_text(lines.read_text().replace('1 2 10 4', '1000 2 10 4'))
            with self.assertRaises(ValueError): prepare(archive, forms, lines, root/'invalid', verify_pins=False)
            self.assertFalse((root/'invalid').exists())
            self.assertFalse(list(root.glob('.iam-preparing-*')))

class IamLearningDiagnosticTests(unittest.TestCase):
    def test_memorization_gate_requires_low_training_error_and_decreasing_loss(self):
        from ml.iam_learning_check import diagnostic_passed
        base = {'cer': .08}
        self.assertTrue(diagnostic_passed(base, {'cer': .01}, 1., .5, 20))
        self.assertFalse(diagnostic_passed(base, {'cer': .03}, 1., .5, 20))
        self.assertFalse(diagnostic_passed(base, {'cer': .01}, 1., .9, 20))
        self.assertFalse(diagnostic_passed({'cer': .005}, {'cer': .01}, 1., .5, 20))
        self.assertFalse(diagnostic_passed(base, {'cer': .01}, 1., float('nan'), 20))
        self.assertFalse(diagnostic_passed(base, {'cer': .01}, 1., .5, 10))

    def test_learning_diagnostic_cannot_use_unreviewed_or_held_out_lines(self):
        from ml.iam_learning_check import select_rows
        rows = [dict(id='a', split='train'), dict(id='b', split='validation')]
        review = dict(status='sample-review-passed', reviewer='assistant', reviewedIds=['a','b'])
        self.assertEqual([rows[0]], select_rows(rows, review, ids=['a']))
        with self.assertRaises(ValueError): select_rows(rows, review, ids=['b'])
        with self.assertRaises(ValueError): select_rows(rows, review, ids=['missing'])
        with self.assertRaises(ValueError): select_rows(rows, review, ids=['a','a'])
        review['reviewedIds'] = []
        with self.assertRaises(ValueError): select_rows(rows, review, ids=['a'])

class AlignedSeq2SeqLossTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            import torch
            cls.torch = torch
        except ImportError:
            raise unittest.SkipTest('Loss regression requires the pinned PyTorch environment')

    def test_perfect_same_position_predictions_do_not_receive_second_target_shift(self):
        from ml.seq2seq_training import aligned_token_loss
        torch = self.torch
        labels = torch.tensor([[0, 4, 7, 2, -100]])
        logits = torch.full((1, 5, 9), -20.)
        for index, token in enumerate(labels[0]):
            if token >= 0: logits[0, index, token] = 20.
        self.assertLess(aligned_token_loss(logits, labels).item(), 1e-6)
        shifted = torch.nn.functional.pad(labels, (0, 1), value=-100)[:, 1:]
        wrong = torch.nn.functional.cross_entropy(logits.reshape(-1,9), shifted.reshape(-1), ignore_index=-100)
        self.assertGreater(wrong.item(), 20.)
        with self.assertRaises(ValueError): aligned_token_loss(logits[:, :-1], labels)

    def test_real_vision_encoder_decoder_forward_matches_unshifted_manual_loss(self):
        from transformers import ViTConfig, TrOCRConfig, VisionEncoderDecoderConfig, VisionEncoderDecoderModel
        from ml.seq2seq_training import training_loss
        torch = self.torch; torch.manual_seed(123)
        encoder = ViTConfig(image_size=16, patch_size=8, hidden_size=16, num_hidden_layers=1,
                            num_attention_heads=2, intermediate_size=32)
        decoder = TrOCRConfig(vocab_size=13, d_model=16, decoder_layers=1, decoder_attention_heads=2,
                              decoder_ffn_dim=32, max_position_embeddings=32, is_decoder=True, add_cross_attention=True)
        config = VisionEncoderDecoderConfig.from_encoder_decoder_configs(encoder, decoder)
        config.decoder_start_token_id = 2; config.pad_token_id = 1
        model = VisionEncoderDecoderModel(config).eval()
        pixels = torch.randn(1,3,16,16); labels = torch.tensor([[0,4,7,2,-100]])
        expected_inputs = torch.tensor([[2,0,4,7,2]])
        self.assertTrue(torch.equal(expected_inputs, model.prepare_decoder_input_ids_from_labels(labels)))
        output = model(pixel_values=pixels, decoder_input_ids=expected_inputs, use_cache=False)
        manual = torch.nn.functional.cross_entropy(output.logits.reshape(-1,13), labels.reshape(-1), ignore_index=-100)
        corrected = training_loss(model, labels, pixel_values=pixels)
        self.assertTrue(torch.allclose(corrected, manual))
        corrected.backward()
        gradients = [p.grad for p in model.parameters() if p.grad is not None]
        self.assertTrue(gradients)
        self.assertTrue(all(torch.isfinite(g).all() for g in gradients))

class FrozenIamEvaluationTests(unittest.TestCase):
    def test_final_evaluation_excludes_training_and_validation_rows(self):
        from ml.evaluate_iam_frozen import test_rows
        rows=[dict(id='train', split='train', writer_id='1'), dict(id='validation', split='validation', writer_id='2'),
              dict(id='z', split='test', writer_id='3'), dict(id='a', split='test', writer_id='4')]
        self.assertEqual(['a','z'], [r['id'] for r in test_rows(rows)])
        with self.assertRaises(ValueError): test_rows(rows[:2])
        rows[-1]['writer_id']=''
        with self.assertRaises(ValueError): test_rows(rows)

class ExactLineImprovementTests(unittest.TestCase):
    def test_format_diagnostics_never_change_strict_accuracy(self):
        from ml.analyze_validation_errors import category
        self.assertEqual('exact',category('A word.', 'A word.'))
        self.assertEqual('whitespace-only',category('A word.', 'Aword.'))
        self.assertEqual('case-only',category('A word.', 'a word.'))
        self.assertEqual('punctuation-and-spacing-only',category('A word.', 'A word'))
        self.assertEqual('text-recognition-error',category('A word.', 'A ward.'))
        self.assertEqual(0, evaluate({'a':'A word.'},{'a':'a word'})['exactLineRate'])

    def test_decoding_selection_rejects_final_test_rows(self):
        from ml.evaluate_iam_decoding import validation_rows
        rows=[dict(id='train',split='train'),dict(id='val',split='validation')]
        self.assertEqual([rows[1]],validation_rows(rows))
        with self.assertRaises(ValueError):validation_rows(rows+[dict(id='test',split='test')])
        with self.assertRaises(ValueError):validation_rows(rows[:1])

class MlCompletionGateTests(unittest.TestCase):
    def test_holdout_rejects_previously_seen_writers_pages_and_images(self):
        from ml.lock_holdout import overlap_check
        candidate=[dict(id='new-line',document_id='new-page',writer_id='new-writer',sha256='new-image',split='test')]
        previous=[dict(id='old-line',document_id='old-page',writer_id='old-writer',sha256='old-image')]
        self.assertTrue(overlap_check(candidate,previous))
        for key in ('id','document_id','writer_id','sha256'):
            changed=[dict(candidate[0],**{key:previous[0][key]})]
            with self.assertRaises(ValueError):overlap_check(changed,previous)
        with self.assertRaises(ValueError):overlap_check([dict(candidate[0],writer_id='')],previous)
        with self.assertRaises(ValueError):overlap_check([dict(candidate[0],split='validation')],previous)

    def test_release_rejects_unaccepted_quality_wrong_model_and_nonfinite_metrics(self):
        from ml.build_ml_bundle import release_gate
        policy=dict(minimumEvaluationLines=500,minimumExactLineRate=.75,maximumCer=.05,maximumWer=.15)
        good=dict(status='verified',freshHoldoutVerified=True,modelSha256='expected',redistributionCleared=True,
                  metrics=dict(samples=500,exactLineRate=.8,cer=.02,wer=.06))
        self.assertTrue(release_gate(good,policy,'expected'))
        for key,value in [('freshHoldoutVerified',False),('modelSha256','other'),('redistributionCleared',False)]:
            with self.assertRaises(ValueError):release_gate(dict(good,**{key:value}),policy,'expected')
        for key,value in [('exactLineRate',.66),('cer',float('nan')),('wer',float('inf')),('samples',499)]:
            bad=dict(good,metrics=dict(good['metrics'],**{key:value}))
            with self.assertRaises(ValueError):release_gate(bad,policy,'expected')

if __name__=='__main__':unittest.main()
