# Testing and acceptance

## Portfolio Acceptance Audit - 16 October 2026

The existing tests already cover native capture (`NativeCameraTest`), crop/OCR and
both PDF screens (`NativeDocumentFlowTest`), activity recreation and failed-import
recovery (`NativeRecoveryTest`), and transactional repository failure handling
(`RepositoryPipelineTest`, `WorkflowPersistenceTest`). Do not duplicate those tests
or reinterpret browser-prototype evidence as native acceptance.

Added `RepositoryPipelineTest.correctedReviewedDocumentAndPdfSurviveRepositoryRestart`:
import a synthetic page, apply grayscale correction, run the app's ML Kit OCR,
save reviewed text, generate a real text PDF, close/reopen the disk database and
repository, then verify retained correction/text/export metadata, unchanged PDF
bytes and searchable reviewed text. Instrumentation compilation passed locally
on 16 October; device execution is pending. It does not measure recognition accuracy or prove Android
process-death recovery: database reopening and activity recreation are narrower.

From `android/`, with a configured SDK/JDK:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
# Requires an attached disposable test device/emulator:
./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.rohanc.ocrscanner.feature.NativeCameraTest,com.rohanc.ocrscanner.feature.NativeDocumentFlowTest,com.rohanc.ocrscanner.feature.NativeRecoveryTest,com.rohanc.ocrscanner.data.RepositoryPipelineTest,com.rohanc.ocrscanner.data.WorkflowPersistenceTest
```

No device was attached during this audit, so no fresh installed-app pass is claimed.
Archive `app/build/outputs/androidTest-results/connected/` and reports from the
actual run. A compiled APK or this command listing is not an executed-device result.
Physical camera quality, force-stop/relaunch recovery and cancellation during
export remain separate acceptance checks. Keep the existing ML Kit, custom TrOCR
development-quality and standalone mobile-parity evidence separately labelled;
the prior experimental CER/WER limitations are not improved by this storage test.

Native foundation source and tests have now been authored; native build/device evidence is recorded separately below. Browser design-prototype checks are recorded below. Existing architecture PDF pages were rendered and visually reviewed during creation.

## 8. Validation plan

Test examples: flat paper, angled paper, shadow, low contrast, blur, glare, clutter, multiple separated cards, mismatched front/back, and selected writing scripts. Use synthetic or consented samples for development.

- Geometry: corner error and correct crop coverage against reviewed references.
- OCR: character error rate and word error rate against manually typed text. Do not use model confidence as a substitute for measured accuracy.
- PDF: recognized text is searchable, copy order is correct, text aligns, fonts survive reopening, layouts do not overflow.
- Cards: missed/extra regions, card-type mistakes, user-confirmed pairing, placement coverage.
- Android: preview responsiveness, capture/processing times, memory peaks, process recreation, disk failures, cancellation, no-network behavior.

Performance and quality thresholds will be set from a representative device and sample baseline before acceptance gates are finalized.

## 12. Design handoff and acceptance

Deliver a token sheet, reusable component sheet, 15 primary screen frames, state variants, and a connected prototype. Use S01-S15 route IDs in frames and annotations. Show three complete journeys: multipage searchable scan, regenerated text PDF, and ID-card front/back plus multi-card layouts.

Design is ready for implementation when every primary action has a destination, Android Back is defined, drafts survive editor navigation, both output previews are represented, card overflow is visible, and no-data/failure/retry states exist. Validate large text and small phone widths before handoff.

## Frontend review checklist

- 15 primary screens: 14 product screens and the optional learning lab.
- Both PDF output modes show distinct, accurate previews.
- Front/back pairing and multiple-card photos have complete routes.
- Missing backs, unknown card types, and page overflow have clear states.
- Every primary action and Android Back path has a destination.
- Drafts survive editor navigation; stale OCR and exports are labeled.
- Permission denial, manual crop, OCR retry, export failure, and cancellation are covered.
- Small phone widths, enlarged text, contrast, labels, and touch targets are checked.

## Validation log

Record future checks with date, artifact/version, method, outcome, and unresolved issues. Do not mark planned tests as passed.


## Browser design verification - 14 October 2026

Flow under test: fresh library -> scan setup -> sample capture/import -> correction -> OCR review -> organizer -> layout -> export preview -> saved library item.

Method: in-app browser navigation and accessibility review; Playwright for stable screenshots and repeatable viewport/interaction checks because the in-app browser screenshot sizing was unreliable. JavaScript syntax checks and project whitespace checks supplement the browser evidence.

| Check | Result |
| --- | --- |
| 15 routes render at 390x844 without horizontal overflow | Passed |
| Two-page document flow: crop, filter, learning lab, OCR edit, reorder, both export formats, save/reload, search | Passed |
| Unfinished draft survives opening an unrelated saved document | Passed |
| Paired-card flow with missing-pair recovery | Passed |
| Multiple-card flow with six regions, four-card grid overflow, and two-sheet preview pagination | Passed |
| OCR and PDF failures recover through retry; camera denial has recovery | Passed |
| Cancelled processing cannot publish a stale result | Passed |
| Onboarding and returning-user skip; reduced-motion boot | Passed |
| Viewport checks at 320x700, 390x680, 390x844, 430x932; desktop at 1440x1000 | Passed |
| Local image import displays selected image, retake replaces region, pointer and keyboard crop adjustment | Passed |

Zero JavaScript runtime errors were recorded in the regression run. A favicon 404 observed during the first inspection was fixed by adding the local app icon.

### Corrections made during review

- Kept the active draft separate from saved-document visits.
- Retakes replace the selected region instead of adding an unwanted page.
- Card preview pagination counts sheets, not card regions.
- Imported images display the selected image rather than the sample document.
- Pair labels require exactly one front and one back; single-sided cards remain valid.
- Removed a duplicate camera exit and redundant capture toast.
- Enlarged small text and control targets; local selection changes preserve editor scroll.
- Removed duplicate text over the OCR highlight.

### Visual comparison ledger

| Reference / check | Prototype decision |
| --- | --- |
| Silk cool gray surface and opposing shadows | Retained #E8EAF0 and restrained raised/inset depth |
| Plus Jakarta Sans and indigo controls | Retained font family; muted indigo for a single controlled accent |
| Reference widgets and dense editor chrome | Intentionally reduced to task-relevant controls |
| Generated capture onboarding concept | Same capture-to-scan image role, short copy and primary CTA; independent artwork with native text |
| Generated text onboarding concept | Same searchable-paper image role and second-step CTA; left-aligned copy for consistency |
| Main app hierarchy | Consistent toolbar, 24 px content gutters, large image workspace, bottom primary action |
| Motion | One-shot boot reveal, no repeated pulsing; reduced-motion path verified |

These are intentional adaptations of references, not a claim of pixel-for-pixel copying. Generated concepts and latest browser screenshots were visually inspected.

### Evidence and limitations

- Structured verification results (local artifact: `output/design/verification.json`)
- Wireframe/prototype (local artifact: `design/index.html`)
- Reusable regression runner: `tools/verify-design.js` for the connected Playwright runtime against the local server.
- Browser prototypes do not prove Android device behavior, camera permissions, live CV/OCR accuracy, a real searchable PDF text layer, or Android sharing. These remain future native validation gates.
- No production backend requests, external sharing, or cloud storage were exercised.


### Permanent website launcher

`python3 tools/serve_design.py --check` successfully identifies the running design website. The Python launcher compiles, the macOS command passes `zsh -n`, local documentation links resolve, and `/docs` still contains exactly the six authorized Markdown files. Final screenshots and verification results remain under `output/design/`; temporary QA captures were removed without changing website source or assets.


## Final frontend interaction pass - 14 October 2026

User accepted the frontend before starting Kotlin work. Browser plugin not available; used the existing Playwright runtime in isolated browser contexts.

| Measured check | Outcome |
| --- | --- |
| 20 interaction scenario groups, 47 action branches, 96 control paths, 12 input families | Passed |
| 17 views across five viewports: 320x568, 360x640, 390x844, 430x932, 844x390 | 85 combinations passed layout checks |
| Overflow, viewport containment, reachable scroll content, decoded assets, named controls and touch targets | Passed automated checks |
| JavaScript runtime errors / console warnings in interaction suite | 0 / 0 |

Corrected cancelled OCR navigation, saved-preview/editor return paths, inherited enhancement values, per-region edits, export invalidation after edits/reorder, image import decoding and cancellation, card grouping/callers, malformed local state, onboarding clipping and OCR-highlight touch size. Shell navigation checks were interrupted when the user moved to backend work; no additional complete shell-audit claim is made. Temporary scripts and screenshots remain under `/tmp/ocr-scanner-ui-qa/` and may expire. Existing `output/design/` artifacts remain intact.

## Native foundation validation

Authored 13 JVM tests for revision checks, page permutation validation, missing/duplicate card sides, pair ordering and overflow. Authored six Android/Room tests for OCR/review round trips, stale edits/results, atomic reorder, cascade deletion and cancelled work. Authored is not passed.

`tools/verify_android.py` records actual build/lint/JVM results and instrumentation-APK compilation under `output/android/`. The first verification completed successfully: debug APK, all 13 JVM tests (zero failures/errors/skips), lint, and device-test APK compilation. Lint has zero errors and 13 warnings, mainly pinned dependency upgrades, kapt usage, backup-rule metadata and the initial missing launcher icon. This is not a warning-free build. No phone/emulator was connected during preflight. Device OCR accuracy, image orientation/limits, file cleanup failures, process death, full native layouts and real PDF behaviour remain unverified. Compiling device tests does not execute them.


## Folio device gate

Four new `ImagePipelineTest` cases exercise actual image import/orientation and the real ML Kit adapter. Together with the six Room cases, ten device tests are authored; execution remains pending. GitHub CI builds their APK but does not run them. The original 13 JVM tests and first lint/APK checks passed before the Folio display-name update.

The local native gate runs via `tools/verify_android_device.py` on the existing read-only Pixel_9_Pro AVD (API 37). Its structured report distinguishes test execution from APK compilation and activity launch; native UI interaction coverage and broader device/API coverage remain separate.

Initial GitHub CI stopped during SDK setup because the action requested the removed `tools` package. Updated setup to install `platform-tools` explicitly; the replacement run remains unverified until GitHub completes it.


## Native workflow gate - 14 October 2026

Previous gate outcome: APK, lint and all 13 JVM tests passed; **all ten device tests passed** on API 37. The overall gate failed only at the separate activity-launch command after connected-test teardown. Launch output had not been recorded, so app runtime failure versus target teardown cannot be concluded from the old report. The runner now explicitly installs the APK, records launch output, and checks that its process remains alive. Latest foundation GitHub CI also passed after the SDK setup correction.

Expanded sources author 23 JVM and 20 device tests. New checks cover convex/in-bounds geometry, aspect ratio, margin/overlap/pair placement, actual OpenCV correction and multi-card contours, Unicode PDF extraction, invisible searchable text rendered by Android PdfRenderer, multipage typesetting, rejected misaligned text, import-OCR-export revision behaviour, correction invalidation, disk reopening, v1-to-v2 migration and connected native onboarding/library/setup/settings/search. These new tests are **pending execution**, not passed based on source inspection.

`output/android/verification.json` and `device-verification.json` record the latest actual run. A fresh gate is required for this source revision. Android rendering plus PDFBox extraction is a useful compatibility probe; it does not replace checking search/copy in independent external readers. Camera hardware, external save/share UI, every native editor touch path, accessibility/large text, representative OCR accuracy, API 28-36 and physical-device behaviour remain explicit checks.


## Backend execution pass

`Backend.md` is the existing implementation document renamed at the user's request; it replaces Implementation.md, retaining six docs.

The first foreground API-34 gate passed the build, 23 JVM checks and all 25 device checks, including real CameraX capture, correction, WorkManager OCR, native searchable/regenerated PDF screens and cold activity launch. PDF save descriptor ownership and the migration fixture's optional index list were corrected. Atomic batch-import and retake regression checks also passed.

Independent pypdf reading then exposed missing logical word spaces in searchable output. The exporter now emits explicit separators; a regression check and independent exported-PDF verifier were added. A combined six-card/three-pair/two-sheet integration check is included in the expanded 27-device-test gate. This expanded gate is pending until its actual result is recorded below.


### Verified result - backend execution pass

| Check | Actual result |
| --- | --- |
| Debug APK build and Android lint | Passed; lint warnings remain, no errors |
| JVM tests | 23 passed, zero failures/errors/skips |
| Android 14 / API 34 device tests | 27 passed, zero failures/errors/skips |
| Independent cold app launch + process alive | Passed |
| Native CameraX capture -> private draft | Passed on emulated camera |
| Native correction -> OCR -> searchable PDF -> text review -> regenerated PDF | Passed through the actual Compose screens |
| Three multi-card photos -> six regions -> three pairs -> two searchable grid sheets | Passed |
| Atomic import failure/success, retake, migration and stale revisions | Passed |
| Independent pypdf phrase extraction from both native exports | Passed, including explicit searchable word spaces |
| Desktop PDFium rendering | Both exported pages rendered and visually inspected without clipping or missing glyphs |
| GitHub CI for native-code commit a2b150d | Passed |

Evidence: build/JVM report (local artifact: `output/android/verification.json`), device report (local artifact: `output/android/device-verification.json`), independent PDF report (local artifact: `output/android/desktop-pdf-verification.json`), native PDF-preview capture (local artifact: `output/android/acceptance/native-preview.png`). The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk` and is intentionally excluded from Git. Test PDFs and screenshots use synthetic content.

The previous five failing device checks are resolved for the supported API-34 gate: PDF file sync uses an open descriptor, the migration fixture handles tables without indexes, and Espresso runs on a supported stable Android system image. This does not establish API-37 UI-test compatibility.

Scope still unverified: physical camera/photo quality, real ID-card classification accuracy, manual external save/share interaction, all native accessibility/large-text paths, broader API/device coverage, and search/copy in Acrobat or other external viewers. Current evidence proves the tested native MVP workflows, not release readiness or zero possible bugs.


## Completion-goal pass - 14 October 2026

Flow: editor with pending changes -> activity recreation -> same changes remain available -> apply/save -> persisted values match.

- Debug build/lint and 23 JVM tests passed after the recovery changes.
- Expanded API-34 gate: **30 passed**, zero failures/errors/skips. Added pending enhancement restoration, pending reviewed-text restoration and saved editor-return coverage.
- Independent PDF extraction/rendering passed again for both actual native exports.
- Route-keyed composition gives each screen its own scroll state. Crop corners and enhancement settings now use saveable state; the crop gesture itself still needs a dedicated recreation interaction check.
- Six-file documentation rule and permanent design website availability passed.
- A visible Folio_API34 emulator displayed Folio onboarding; the native gate can now retain its successful emulator using `--visible --keep-open`.

Activity recreation is not process death. The separate `tools/verify_android_recovery.py --serial emulator-5556` probe backgrounds Folio and kills only its debuggable process under its own UID, retaining the Android task. It must observe the old process stop, a new PID and the restored setup route/card mode before passing. This bounded setup probe does not prove persisted-document, OCR-job or export process recovery.

Optimized build verification is separate from the debug gate. `release-verification.json` records the actual candidate result, measured APK size/ABI, test totals and signing boundary. A failed or interrupted optimized gate does not inherit a debug pass. Physical-device, all-route large-text/accessibility, external save/share, disk-full/large-batch and broader API checks remain open.


## Pixel 9 visible testing - 15 October 2026

The user requires the existing Pixel_9_Pro emulator as the only tester. The agent-owned background API-34 run was stopped and its emulator closed. Pixel_9_Pro was retained. Current visible-device evidence belongs in `output/android/visible-device-verification.json`; API-34 results do not prove API-37 compatibility.

Use `tools/verify_android_visible.py --serial emulator-5554`. It never starts/stops an emulator, resizes the display, wipes data or uninstalls the app. Direct instrumentation keeps the run visible and avoids Gradle's target-uninstall teardown. The native review activity is debug-only and uses fictional fixtures; production library data remains separate.

### Current Pixel 9 result

- Existing Pixel_9_Pro, emulator-5554, API 37, unchanged display: **34 device tests passed**, zero failures/errors.
- Debug build, **23 JVM tests** and lint passed; lint passing does not mean warning-free.
- Four UI test groups cover 15 screens plus two onboarding steps, document/card navigation, 200% text and an actual 320 dp content width at 200% text.
- Device report (local artifact: `output/android/visible-device-verification.json`), build report (local artifact: `output/android/verification.json`), native screen board (local artifact: `output/android/ui-parity-pixel9/screen-board.png`).
- The same emulator remains open in the debug UI review activity. No other emulator is used. The arm64-only local debug build fits its available storage.

This verifies the tested flows, not every possible state or backend completeness. Physical-camera quality, representative OCR accuracy, external save/share interaction, additional device coverage and final user acceptance remain separate. Backend completion follows page by page after this UI review.

The same Pixel run produced searchable and regenerated PDFs; independent pypdf extraction and PDFium rendering passed for both synthetic exports. See PDF report (local artifact: `output/android/desktop-pdf-verification.json`).

## Non-ML backend increment - pending verification

The next full suite expects 42 device tests (previous 34 plus eight new checks): persisted paired workflows after disk reopening, atomic/stale pair edits, read-only lab derivatives, v2 migration, pending labels through recreation, overflow-sheet navigation, document-name Back persistence, and captured-file retention after failed import. The new repository tests deliberately do not call OCR. Existing OCR/PDF regressions remain in the full suite.

Run only on the existing Pixel: `python3 tools/verify_android_visible.py --serial emulator-5554 --launch-app`. This finishes on the actual app rather than review fixtures. Record the actual outcome before committing/pushing the backend increment. Prior pass counts remain historical evidence.

### Pixel workflow validation follow-up

The first expanded run executed all 42 tests: 41 passed, one settings-navigation test failed because it hard-coded A4 despite a persistent Letter default. The debug build, 23 JVM checks and lint passed. The test now reads the current default, verifies a change and restores the prior setting. A new pending-settings recreation test brings the next full suite to 43 checks; pending settings now use saveable state. The runner distinguishes test assertion failures from instrumentation errors.

Manual external export validation on the same Pixel used only a synthetic page imported through Android's picker. Both searchable and regenerated PDFs were saved through Android Files and opened in the external Google PDF reader. Search for WORKFLOW showed 1 of 1 matches in the searchable PDF. Independent pypdf extraction found the phrase in both saved files; the scan PDF embeds an image and the regenerated PDF does not. The share chooser opened and was dismissed without selecting a recipient. These checks do not prove delivery to a share recipient or every external reader.

Local-only evidence: `output/android/external-export-verification.json`, `external-reader-search.xml`, `external-share-sheet.xml`, `external-reader-search.png` and `acceptance/external-saved-*.pdf`. The external-reader test exposed UUID display names; the next build supplies the document filename in FileProvider metadata. That naming change awaits device recheck.

The next command adds a real task-restoration check after the suite: `python3 tools/verify_android_visible.py --serial emulator-5554 --launch-app --recovery`. It backgrounds and kills only Folio's debuggable process, retains its task/data, then checks restored setup mode and workflow. It never stops the Pixel emulator. This recovery gate remains pending until an actual passing report exists.

### Platform PDF integration gate - pending

The follow-up suite completed all 43 device checks with zero failures/errors, plus 23 JVM checks and lint. Its overall runner failed in the subsequent real process-death probe: the old process stopped, but the expected Import images setup control did not return. Recovery remains incomplete; the 43 device passes do not override that failure.

The user-selected PdfDocument API is now the authoring path for visible scan/card sheets and regenerated text. Two new tests bring the suite to 45: ensure each of 200 Unicode paragraphs is extracted exactly once across pages without rasterized text, and render every native text page to verify visible content stays within margins. Existing searchable-layer, card-sheet, PDF output and UI tests remain unchanged in scope. The upcoming run omits the known-failing process-death probe while isolating the new PDF integration; recovery is a separate open gate.

### Backend audit and history normalization

The user explicitly requested all existing commit messages renamed. Thirteen commits were recreated with the same trees, author/committer identities, original dates and parent graph; the first is initial commit. The index bytes and pending tracked diff were identical before/after. A local Git bundle and mapping remain under .git. The normalized main was pushed with an exact force-with-lease expectation. Existing local test reports retain their original source IDs; no result is fabricated by updating their historical hashes.

The latest PDF integration run failed only at lint WrongConstant for Layout.BREAK_STRATEGY_SIMPLE. Debug/test APK compilation and 23 JVM checks passed; device tests were not executed. The writer now uses LineBreaker.BREAK_STRATEGY_SIMPLE behind an API-29 guard, with the platform default on API 28. No lint suppression/baseline was added. A fresh 45-test run is required.

Manual recovery diagnosis: the top Folio activity initially remained PAUSED with mHaveState=false after Home, despite an older activity having saved state. Once the top activity was observed STOPPED with mHaveState=true, killing Folio and reopening through its launcher restored card setup/workflow with a distinct PID. The automated probe now waits for that actual lifecycle state instead of one second. Parser checks reject a paused top activity even if an older activity is saved; full automated recovery remains pending.

The audit also corrected export publication to invalidate the documents flow without incrementing the export's source revision; its existing pipeline test now awaits a library update. Failed first imports now remove an unused empty draft while retaining captured files for retry, preventing a mode-locked empty scan; the existing recovery test also checks library IDs remain unchanged. These changes await the new device gate.

### Handwriting pipeline source verification

`python3 -m unittest ml.test_pipeline`: 10 checks passed. Coverage includes corpus error denominators, insertion CER above one, missing predictions, Unicode/case behavior, known edit distance, document/image/writer leakage, path escape, stored nested ZIP access, whole-archive rejection and transfer/read budgets. Python compilation also passed. These are pipeline tests, not training or OCR accuracy results.

Runtime inspection: current host is arm64 with 16 GB RAM and approximately 11 GB free disk; torch/transformers were absent. Preparation uses a dedicated cache environment outside this project, pinned requirements and a minimum free-space guard. Selective archive inspection read roughly 9 MB to enumerate the nested training/test archives; no 6.8 GB bulk archive was downloaded. Source metadata declares CC-BY-4.0. Dataset subset materialization, baseline predictions, actual gradient updates and device model evaluation remain unexecuted.

### Latest completed gate and next regression suite

The completed backend gate passed all **45 device tests**, **23 JVM checks** and lint. Its overall status failed because the subsequent process-death restoration probe did not return the setup controls, despite observing saved state and stopping the old process. Recovery is still open.

Activity inspection found duplicate MainActivity instances. The manifest now uses singleTop, with a new test asserting that a launcher intent reuses the active scan and its selected workflow. This change has not yet passed the device gate.

The next suite expects **49 device tests**. Three other additions exercise the 48-region import limit without overflow file leakage, storage deletion and reversed ordering, preservation of a completed PDF after write failure, and 48 native scan pages with intermediate-file cleanup. Scan authoring closes each native sheet before disk-backed merge. These use bounded synthetic images; a high-resolution memory stress test remains outstanding.

The ML pipeline now passes **14 source-level tests**, including strict published-label parsing, rejection of duplicate excluded IDs, deterministic document-separated partitioning and minimum-document enforcement. No model accuracy is implied. The new subset adapter and training orchestration remain unexecuted; the bounded pilot is explicitly separate from the official competition benchmark and modern/writer-separated validation.

Runtime/data preparation completed successfully: PyTorch 2.8.0 and Transformers 4.57.1 import in the isolated environment, with Apple MPS available. Archive inspection transferred 8,799,750 bytes and indexed 12,219 training images plus 331 test images. Exact filename matching found 246 usable published test-line transcriptions; 72 editorial annotations and one blank label are excluded. The source archive's whole-file MD5 is not verified by selective access. Pixel verification continues separately; no training accuracy or checkpoint result exists yet.

The optimized-runtime verifier now selects the same compatible installed JDK as debug verification and builds the benchmark/test APKs before direct instrumentation. It targets only the existing Pixel_9_Pro and expects 45 production-flow tests; the four debug-only gallery tests are excluded. Its separate benchmark application ID preserves the debug app's files. The script compiles, but the revised optimized build/runtime gate has not yet run. It does not configure a production signing key.

### 49-test result and recovery checker correction

The expanded Pixel run passed **all 49 device tests**, **23 JVM tests** and lint. This includes launcher re-entry, maximum region count/storage deletion, failed PDF write preservation and 48-page native scan export cleanup. The exact debug APK SHA-256 is a60492ac1697f6fc07b73f062afaa1dbd98b158b73732740e6b7c6f1eaeb60a4. High-resolution peak memory remains unmeasured.

Its subsequent recovery checker failed at scan-mode selection. The captured XML actually contains ID cards with checkable=true/checked=true and Multiple cards with selected=true. The checker only recognized selected, so it falsely rejected the restored radio choice. It now accepts Android's checked state only on checkable controls, retains the selected-state check for tabs, and asserts both choices before killing the process. Pure parser checks cover checked radio, selected tab, unselected and missing controls. A fresh full recovery probe is required; XML alone is not recorded as a probe pass.

### Accessible crop increment - pending

The crop editor now exposes four virtual corner handles with coordinate descriptions and directional accessibility actions. Each action moves one percent of the source dimension (at least one pixel) within the same bounds as dragging. The visible Silk controls remain unchanged. Two debug-only component tests check all four action menus, retained coordinates/descriptions and source-boundary rejection. They are authored, not yet executed; the next full debug suite expects 51 checks. The optimized suite still expects 45 production-flow checks, excluding six debug gallery/component checks.

The pinned pretrained TrOCR-small model executed one real extracted training line on MPS. Its transcription was poor and execution took about 17.9 seconds including preparation/decode in that smoke check. This is not a benchmark or evidence of usable handwriting accuracy. No gradient updates have run. The runtime report remains local at ml/artifacts/model-preflight.json.

The corrected real process-death probe **passed** on the installed 49-test build: setup route 02, CARDS mode and multiple-card workflow survived saved-state capture and termination, with PID changing from 17130 to 17507. It asserted both selected choices before termination. No emulator stop, data reset or document import/deletion occurred. Local before/after XML and process-recovery-verification.json retain the evidence. This proves that setup-restoration case, not WorkManager resumption or every editor's process-death state.

Handwriting subset extraction stopped after more than 100 lines because a source response contained fewer bytes than its declared range. No manifest/training pass was published. The range reader now retries transient failures at most three times, charges received partial bytes against its transfer budget and never caches incomplete ranges. The verified cache is reused on restart. All **16 ML pipeline tests** pass, including truncated-response recovery and retry/budget exhaustion. Actual fine-tuning remains pending until extraction and manifest validation finish.

The accessible-crop build passed compilation, 23 JVM checks and lint, but device installation failed with INSTALL_FAILED_INSUFFICIENT_STORAGE. No device tests from this build ran. The existing Pixel reports 424 MB free on a 5.8 GB data partition; Folio's own private data totals only 1.5 MB and generated Download evidence about 14 MB. Android's disposable-cache trim freed no space. A forced-internal replacement also failed. No app was uninstalled, no document/database was cleared, and storage thresholds or emulator size were not changed. The user has been asked to free storage; model work can continue independently.

### Completed handwritten-English pilot

The user selected continuing model work while device storage remains constrained. Data extraction recovered from its verified cache and completed 238 lines: 167 train / 31 validation / 40 test, separated by document. The 300-step decoder fine-tune completed on MPS. Validation CER selected step 200; step 300 was worse and was not selected.

| Held-out metric | Pretrained | Selected fine-tune |
| --- | --- | --- |
| Character errors / 1,308 reference characters | 1,205 / 92.13% CER | 998 / 76.30% CER |
| Word errors / 253 reference words | 314 / 124.11% WER | 292 / 115.42% WER |
| Exact lines / 40 | 0 | 0 |

These scores are poor: no Android promotion or usable-recognition claim. Source predictions include hallucinated/repeated words. Historical filtered lines from two held-out pages do not prove modern handwriting or writer generalization. Timing around 0.19 seconds per line is a host experiment, not Android latency.

`python3 -m ml.validate_experiment --manifest ml/data/bentham-pilot/manifest.jsonl --experiment ml/artifacts/handwriting-pilot` passed. It independently recalculates saved prediction metrics, verifies validation-only selection and completed steps, and hashes the checkpoint (model.safetensors: 4dda753b1fcc3b91bbb0a49e55f39a20f9b3a4bc446a50a2d940295470cd707e). Local reports/checkpoints remain ignored.

Published training-protocol inspection also completed. The supplied baseline partitions contain 9,198 train / 1,415 validation / 860 test lines, with no pilot-page overlap. Source instructions explicitly distinguish extracted-line IDs from PAGE XML IDs; the new adapter matches the provided line images with their exact same-name transcript files. It preserves the supplied punctuation spacing. Its document-balanced bounded subset is not the full official benchmark. All 18 ML source-level tests pass, including partition-union/leakage checks and deterministic page coverage. Larger subset materialization and further quality diagnosis remain pending.

The next dataset job requests up to 2,048 / 192 / 192 lines from the supplied partitions, then transparently filters unusable transcripts. Its manifest is only accepted after image hashes, split coverage and document/image leakage checks. It has a bounded network budget and stops extraction if local free space falls below 1 GB. Completion is not yet claimed.

The trainer now supports optional encoder adaptation with a separately recorded encoder learning rate, and records source/requirements hashes. This capability compiles; it is not evidence that an encoder-training experiment has run. CPU/MPS decoding comparison uses only three training images and records processor pixels, token sequences and outputs; it is a runtime diagnostic, not an independent accuracy benchmark.

The CPU/MPS comparison completed with exactly identical generated token IDs for all three training images. Both outputs were poor. This rules out a device-specific decoding difference for these cases; it does not establish parity for every input/operator or explain all recognition errors. The next pilot keeps the same data, seed, step count and decoder learning rate while enabling encoder adaptation. Its results must remain separate from the earlier frozen-encoder pilot. The already-inspected 40-line pilot test is development evidence; the larger published-partition test remains untouched by training/selection.

The larger source extraction has accepted 2,336 exact transcriptions and excluded 96 with editorial annotations; image extraction and final manifest verification remain in progress. Encoder adaptation passed backward preflight with 360 finite gradient tensors and began optimizer steps. No final score is available yet.

All **20 ML pipeline tests** now pass. New artifact-integrity checks reject altered saved metrics, checkpoint bytes, validation selection, improvement flags, selected-checkpoint labels and incomplete step counts. Independent verification of the first completed pilot still passes these stricter checks. Integrity passing does not grant recognition-quality acceptance.

### Encoder adaptation completed

The second 300-step pilot completed. Validation selected step 300 (72.61% CER). On the same development test, CER is 936 / 1,308 = **71.56%**, WER 269 / 253 = **106.32%**, exact lines 0 / 40. It improves on the frozen-encoder pilot's 76.30% CER but remains unsuitable for app integration. Independent metric/selection/hash verification passed. The checkpoint SHA-256 is 15efce8d927a260ade6c7aebf2de9036e74979daeb6c9b5fe1ce1185c451f6ea. No result is yet available on the larger published-partition test.

Future training uses staged, immutable step directories for checkpoint publication and atomic JSON reports. A failed new save leaves earlier checkpoints intact; existing experiment reports cannot be overwritten by rerunning into the same output directory. Source files are copied with the experiment for reproducibility. Unit failure injection and immutability tests pass; this revised publication path has not yet completed a real model run.

All **24 ML pipeline tests** pass, including the three artifact-publication regressions and a separate modern-data candidate filter. The pinned HierText validation-label inspector records original Git-blob and SHA-256 integrity, selects only annotated legible horizontal handwritten words/lines, and excludes non-ASCII labels. This is Latin-script filtering, not proof of English. Inspection is pending; it downloads no scene images and performs no recognition/training.

### Streaming source inspection and extraction acceleration

The first modern-label inspection correctly stopped when its expanded JSON exceeded the 192 MiB whole-file limit. The pinned compressed file expands to 216,449,832 bytes. Inspection now uses pinned ijson 3.4.0 to stream one image annotation at a time, with a 512 MiB total decompression allowance. The completed inspection verifies 1,724 validation images and identifies **3,732 candidate handwritten lines across 257 images**. Compressed source SHA-256: ce7085d8bf24c2f13df852715c34494e6748cafcabb4bfc7a6e2bb320f58d23a. English-language review, scene-image admission and recognition evaluation remain undone; no images or training data were imported from this source.

The historical corpus extractor remains active. A separate prefetch tool plans byte blocks only for its selected image entries and warms the same cache using four independent readers. It never changes the extractor's position or restarts it. A shared request allowance caps reservations at 2 GiB, including retry requests; a disk reserve is checked before fetching. Cache writes now use unique temporary filenames so concurrent readers cannot collide with the active extractor's earlier writer. Image decode, ZIP CRC and manifest verification remain the extractor's responsibility.

All **28 source-level ML checks** pass. New coverage checks streaming expansion limits, shared-budget atomicity, concurrent cache writes, and deduplicated entry bounds. Cache prefetch itself remains pending until its real run completes.

Modern image preparation is now authored for an eight-image deterministic validation-review subset. It uses the official HierText validation archive (HEAD size verified: 577,088,394 bytes), checks response length/ETag, records a local archive hash, matches source image dimensions to annotations and preserves source polygons. Line crops use Pillow's documented QUAD corner order, with bounds/convexity/orientation guards. The English-word heuristic only chooses review candidates; English acceptance stays pending until visual inspection. No full-benchmark or recognition result is implied. All **30 ML source tests** pass, including crop geometry/order and invalid-polygon rejection. Actual image preparation remains pending.

The separate modern evaluator now requires an explicit visual review decision for every candidate and binds that review to the exact candidate-manifest hash. It reads a validation-only manifest under an explicit CC BY-SA policy; the training reader still requires train/validation/test and CC BY by default. Missing decisions, altered manifests and implicit license/split changes are rejected. A review-board renderer places each crop beside its exact source transcription. All **32 ML pipeline checks** pass; rendering, actual visual admissions and modern recognition evaluation still require completed source images.

Modern source preparation completed: eight source images yielded 31 candidate crops from seven images; one selected image's sole candidate was outside the initial crop-geometry scope. All seven crop boards were rendered and visually inspected. **13 lines from six images** were admitted for English/transcription comparison; 18 were excluded with individual reasons (unverifiable faint/blurred fragments, label typos, punctuation mismatch or ambiguous source capitalization). Decisions bind the exact manifest and rendered-board hashes. Labels were not corrected. This is a small curated development subset; exclusions prevent a representative/full-benchmark accuracy claim.

The upcoming comparison runs the pretrained recognizer and both completed pilots on precisely those admitted lines. Strict case/punctuation scores remain primary; a separately labeled casefolded diagnostic will show the effect of capitalization. The evaluation performs no training and cannot authorize Android promotion. Its results remain pending.

### Modern development comparison completed

All three recognizers completed inference on the same 13 admitted lines / six source images (411 reference characters, 85 words). Saved predictions independently reproduce the strict metrics.

| Recognizer | Strict CER | Casefolded diagnostic CER | Strict WER |
| --- | --- | --- | --- |
| Pretrained TrOCR-small | 288.56% | 268.61% | 323.53% |
| Frozen-encoder pilot | 100.97% | 82.73% | 127.06% |
| Encoder-adaptation pilot | 87.59% | 77.62% | 110.59% |

All exact-line rates are zero. Values above 100% reflect insertion/hallucination errors and are not clamped. The poor result persists after casefolding. None meets app acceptance; the small curated development set does not establish general handwriting accuracy. The next diagnostic compares a pinned TrOCR-base foundation on exactly these same admitted inputs, with no training. Its official model API identifies revision eaacaf452b06415df8f10bb6fad3a4c11e609406 and a 1,333,384,464-byte safetensors file. This is a host quality baseline; mobile memory, latency, export and packaging remain separate gates.

Two inspected samples from the in-progress larger corpus have source-matched grayscale handwriting and exact line transcripts; the inspection used cached ranges with zero additional network bytes. This supports sample correspondence, not completion of the remaining corpus integrity checks.

### Stronger foundation baseline completed

Pinned TrOCR-base completed the same 13-line modern comparison without training. Strict CER is **284 / 411 = 69.10%**, strict WER **77 / 85 = 90.59%**, with one exact line. The separately labeled casefolded diagnostic is **40 / 411 = 9.73% CER**, **23 / 85 = 27.06% WER**, with three exact lines. Host time was about 0.71 seconds per line; Android timing is unmeasured.

Inspection confirms substantial lowercase normalization and abbreviation expansion (for example, STDNTS becomes students and V. becomes very), plus errors on blur and punctuation. This is a stronger word-reading baseline, not faithful transcription acceptance. Strict scores remain primary; no acceptance rule was relaxed after seeing the results.

Modern data tooling now supports the explicitly declared source train/validation roles. The image-preparation entry point is `ml.prepare_modern_corpus`; the earlier validation-only filename was renamed before publication. The official modern training archive HEAD reports 2,798,458,845 bytes. A separate source-training inspection and 32-image review subset are next; validation images/reviews are preserved and no validation line will be used for gradients. All 32 existing pipeline checks still pass after the role extension. Actual training-source admission remains pending.

### Modern training admission and selection protocol

Training-source preparation completed: 32 selected photographs yielded 114 crop candidates from 31 photographs; seven additional lines failed geometry guards. All 31 boards were rendered and visually inspected. Thirty unchanged English labels were admitted; 84 were excluded for transcription/case/punctuation mismatches, blur, clipping or upside-down crops. Review records bind candidate-manifest and board hashes and identify the assistant reviewer, without implying human approval. Model predictions did not determine admission.

`ml.assemble_modern_experiment` passed actual corpus assembly/integrity with 30 train / 13 validation lines. IDs, source documents, crop hashes and original-photo hashes cannot cross roles. Writer separation is unknown. This small curated subset supports a bounded adaptation experiment, not general accuracy claims.

All **34 pipeline tests passed**, including explicit train/validation-only protocol validation, immutable checkpoint publication, source snapshot hashes, stored validation-prediction rescoring, license admission and original-photo leakage rejection. The earlier encoder pilot independently revalidated at 936/1308 character errors (71.56% CER), without quality acceptance.

The next base adaptation is configured for 100 steps, accumulation four, frozen encoder, decoder learning rate 5e-6 and strict validation selection every 25 steps. Baseline retention is required if no validation improvement occurs. Its checkpoint publication and selection-only report require real-run verification after completion. No final test corpus is read or final-test score fabricated in selection-only mode.

Latest gate: **35 pipeline tests pass**. A new regression covers changed/missing/omitted boards and evidence symlinks outside the review directory. Both actual reviewed datasets passed board/hash verification. The repository CI workflow now runs the lightweight ML pipeline suite using Python 3.12 without model/data downloads; this is source-authored CI coverage, not a claim of a hosted run.

### Modern base adaptation completed and independently verified

The frozen-encoder modern pilot completed all 100 steps. Validation selected step 100. The new immutable checkpoint publication and schema-2 selection-only protocol passed their first real-model verification: captured sources, model SHA, complete steps, baseline/selected saved predictions and strict metric rescoring all passed. Selected weights SHA-256: `d2f25d6604957b7baa8d8b5e32481c273917a195ba8696c66a67c478e4df227b`. This verifies artifact consistency, not a fresh inference reload or final-test accuracy.

| Validation metric | Pretrained base | Selected step 100 |
| --- | --- | --- |
| Strict character errors / 411 | 284 (69.10%) | 232 (56.45%) |
| Strict word errors / 85 | 77 (90.59%) | 72 (84.71%) |
| Casefolded diagnostic CER | 9.73% | 23.60% |
| Exact lines / 13 | 1 | 1 |

Saved per-line diagnostics show omitted/repeated words and corrupted words alongside increased uppercase output. Therefore strict CER improvement is insufficient for app promotion. No final test was read. The next controlled run changes encoder adaptation while preserving the other training choices; encoder LR is 1e-6.

All **36 source tests pass** after adding prepared-corpus replacement protection. Both real prepared datasets reject replacement before annotation/archive/crop writes. Review artifacts remain intact. The larger historical image extraction and bounded cache prefetch remain separate live jobs.

### Fresh checkpoint reload verification

A separate evaluator loaded the pinned pretrained base and the saved step-100 base decoder checkpoint, decoded all 13 admitted validation lines again and saved a new evaluation report. Both complete prediction dictionaries exactly match their original saved validation predictions. This establishes fresh reload/inference consistency for these inputs, beyond hash verification. It does not establish final accuracy or Android runtime compatibility. The encoder comparison remains a separate run with no result claimed yet.

### Encoder comparison completed; mobile baseline prepared

The 100-step base encoder+decoder experiment completed and independent selection verification passed. Step 100: strict CER 225/411 = 54.74%, WER 69/85 = 81.18%, one exact line. Casefolded diagnostic CER 108/411 = 26.28%, worse than the pretrained base's 9.73%. Missing/repeated words remain. Selected model SHA: `164cc9ef34911e9e58929e0d56fb518c046d5cd0f778950598c9e740be3567eb`. This checkpoint is rejected for app promotion.

A read-only Pixel storage check reports 524,288 KiB available (512 MiB). No installation, unrelated deletion or emulator replacement was attempted.

The official English PP-OCRv5 mobile baseline is now source-prepared, with a pinned model revision, exact file lengths/upstream hashes and separate runtime. All **37 lightweight pipeline tests pass**, including truncated/altered model-file rejection and unknown integrity-algorithm rejection. The evaluator compiles; its real dependency installation and inference have not yet run. Publisher capability/accuracy claims are not Folio measurements.

### Mobile English baseline completed

The isolated PaddlePaddle 3.3.1 / PaddleOCR 3.7.0 runtime installed, `pip check` passed, and all 13 reviewed lines completed CPU inference. The exact pinned upstream model/config hashes verified. Independent saved-prediction rescoring reproduces strict CER **17/411 = 4.14%**, WER **19/85 = 22.35%**, three exact lines; casefolded CER **14/411 = 3.41%**. All predictions were inspected: missing spaces/commas, letter substitutions and a spurious letter remain. Host time is about 0.23 seconds/line and does not establish Android latency. The recorded evaluator source was recovered and exactly matched to its execution SHA; the dependency snapshot is saved locally.

This is a pretrained baseline on a small curated development set, not Folio fine-tuning or an untouched final test. Publisher pretraining overlap with this public corpus has not been established. No Android promotion follows from this result.

All **39 lightweight tests pass**. New checks enforce model/image exclusion anywhere inside public ML source and deterministic review sampling with complete prior-photograph exclusion. The next preparation uses a fresh directory for 24 additional validation photographs, excludes the complete earlier eight-photograph preparation, and re-verifies the cached archive. Its actual candidates and English visual admission are pending. Existing prepared/reviewed data refuses replacement. Future evaluation source/package snapshots are authored and compiled; their real run is pending.

### Expanded development data admitted

Preparation and rendering completed for 24 additional validation photographs: 50 crop candidates across 22 boards, plus 12 geometry exclusions. All 22 boards were visually inspected before model inference. The review admits 15 unchanged English lines from nine photographs and excludes 35 candidates with recorded reasons. The exact candidate manifest and every board hash verify. All eight prior photographs were excluded before deterministic sampling; exact original-photo hashes independently verify separation. Admitted source/crop/document hashes also pass separation from training.

The next isolated mobile baseline evaluation will use precisely these 15 lines. They are additional curated development data, not an untouched final test or representative accuracy sample. Publisher pretraining overlap remains unknown. Actual recognition metrics and the newer source/package snapshot path remain pending until execution.

### Expanded mobile evaluation verified

All 15 admitted lines completed: strict CER **29/509 = 5.70%**, WER **23/105 = 21.90%**, three exact lines. Casefolded diagnostic CER is **24/509 = 4.72%**. Independent saved-prediction rescoring, manifest/review integrity, captured source hashes and runtime package snapshot all passed. All 15 reference/prediction pairs were inspected. Missing/substituted letters, spaces and abbreviated words remain. Host time was about 0.17 seconds/line; Android timing remains unmeasured.

`ml/export_mobile_onnx.py` is authored and compiles. It uses pinned source files, a fresh output, source/runtime snapshots, ONNX structural checks, external-tensor rejection and finite host-CPU smoke output. `ml/requirements-export.txt` isolates conversion dependencies from both completed recognition/training runtimes. Actual conversion, smoke and real-line parity remain pending. The 39 lightweight pipeline tests still pass.

### Larger historical corpus completed

The existing extraction and bounded prefetch jobs both completed successfully. The finalized manifest contains **1,966 train / 183 validation / 187 test = 2,336 lines** after 96 editorial exclusions from the selected limits. An independent reader verified every image SHA and required-role/document/exact-image separation. Source transcripts preserve their punctuation spacing. Writer separation and contemporary handwriting coverage remain unknown. Test predictions have not been generated for this corpus. Cache prefetch stayed within its 2 GiB request reservation. Selective source-entry checks do not establish the full outer archive MD5.

### ONNX host export passed; real-line parity pending

The isolated conversion job completed. ONNX structural/full checking, self-contained tensor storage and finite CPU smoke output passed. Model size **7,865,035 bytes**, SHA `a27234082983fb2e22a905c400e321a800bff75ee76d85a10219327e242c1c75`, opset 19. Input is dynamic batch/width, 3 channels, height 48; output has 438 classes. Independent artifact, captured-source and runtime-package hashes verify. The converter's optimization stage installed symbolic-inference dependencies; their exact versions are captured in the runtime snapshot.

All **40 lightweight tests pass**, including repeated CTC labels separated by blanks, adjacent duplicate collapse, spaces, empty output and invalid labels. New parity tooling compiles. It prepares exact BGR normalized arrays using the mobile environment, compares them to PaddleX 3.7.2, then runs those fixed arrays in the isolated ONNX environment. All 28 saved decoded predictions must agree. Actual real-line parity and Android behavior remain pending.

### Real-line ONNX parity passed

Both runtime stages completed. All **28 preprocessing tensors** exactly match PaddleX's BGR resize/normalization output. ONNX decoded predictions match all 28 saved Paddle predictions with zero mismatches. Independent verification checked the input-report hash, every tensor file hash and exact prediction dictionaries. This preserves the previously measured line outputs; no new Android or general-accuracy claim follows.

The cached larger-corpus tokenizer preflight passed: all 2,336 transcriptions fit, maximum 31 tokens versus limit 128. A corrected cache-location invocation was required; the first read-only attempt looked in the default cache and failed before model/data mutation. The next actual custom run uses 500 steps, accumulation four, decoder LR 1e-5, encoder LR 1e-6, validation every 100 steps and the published-partition subset. Its 187 test lines are evaluated after selection. Full-protocol runtime after the parameter-reference release fix remains pending. All 40 lightweight pipeline tests pass.

### Kotlin recognizer component authored

Added a bounded BGR/CTC codec and a pinned-file ONNX line recognizer using ONNX Runtime Android 1.31.0. Model/config length+SHA checks precede session creation; input/output tensor contracts, finite output, synchronized close/inference and native resource cleanup are explicit. This component returns line text/confidence; full-page detection and geometry are not inferred. It does not replace the current ML Kit OcrEngine.

Six JVM tests were authored for dimensions, BGR/padding, repeated CTC characters/spaces, invalid sizes/pixel lengths and nonfinite scores. The verifier now requires at least **29 JVM tests**, so stale 23-test output cannot satisfy the new gate. Actual build/JVM/lint results are pending. No model is bundled and Pixel execution remains pending while storage is insufficient.

### Kotlin component build gate passed

The debug APK, device-test APK compilation, all **29 JVM tests** (zero failures/errors/skips) and lint passed. APK SHA `1fd923998b34c76246c5771e59b909704730a095e7aa0b8f523609e04736ac54`. The installed Pixel app was not changed and no instrumentation execution is claimed.

A debug-only ART probe plus `tools/verify_android_onnx.py` is authored to test the runtime on the existing Pixel without APK installation. It checks the exact passed APK/model/data hashes, stages DEX plus four required native libraries and the 28 public line fixtures in a unique temporary directory, and retains a free-space reserve. Only its own temporary directory is removed, with absence verified. The probe reports exact text parity, per-line inference time and maximum observed process PSS; these are emulator diagnostics, not physical-device or installed-app acceptance. Source compiles at the Python level; debug-probe compilation and execution are pending.

### Existing Pixel standalone recognition passed

The rebuilt probe passed on **Pixel_9_Pro / emulator-5554** without APK installation. All 28 actual Kotlin/Android decoded strings exactly match their host baseline, independently checked from the saved device dictionary. The 29 JVM tests, lint and debug/device-test APK compilation also passed. Source APK SHA: `55d6818bc49e4ed56180ccc6633b60319264a90f273d2933a2874622b522a260`.

Median line-call time: **44.35 ms**, minimum **21.09 ms**, maximum **336.18 ms**. These emulator times include preprocessing and bitmap cleanup, exclude model loading/file decode, and do not establish physical-phone performance. Maximum observed process PSS is **186,984 KiB / 182.60 MiB**; sampling is not a peak-memory measurement. The uniquely staged temporary directory was removed and its absence verified. No installed-app data or emulator configuration was changed. UI/Room/worker and full-page acceptance remain separate.

The full-photo evaluator is now authored and compiles. It pins the mobile detector/recognizer, preserves all original-photo hashes, disables optional automatic orientation/unwarping model downloads and bounds detection input to side 1,280. Fixed geometry-only matching at IoU 0.5 measures recovery of the 28 reviewed target lines; IoU 0.3 is separately diagnostic. Unmatched targets count as empty strings; unrelated page text is unlabelled and cannot establish precision/full-page accuracy. All **41 pipeline tests pass**, including one-to-one geometry assignment and invalid-overlap rejection. Real evaluation remains pending.

### Full-photo diagnostic completed and inspected

The pinned detector/recognizer ran all **15 source photographs**. It recovered **21/28 reviewed target lines at IoU 0.5** and 23/28 at diagnostic IoU 0.3. Targeted CER **244/920 = 26.52%**, WER **76/190 = 40.00%**, three exact target lines. Independent target-metric rescoring, match counts, captured-source and runtime hashes passed. Host CPU time was about 6.20 seconds/page. This is a targeted diagnostic with incomplete page annotations, not complete-page accuracy/precision.

All seven IoU-0.5 misses were inspected against closest predictions. Five original-photo overlay boards were also viewed. Two correctly decoded lines had expanded predicted boxes (IoU about 0.46 and 0.41). Others were missed or fragmented, including faint cursive, diagonal fabric handwriting, menu text and green small lettering. None of the five source photos carried EXIF orientation; orientation metadata does not explain these cases. Thresholds/results remain unchanged.

The exporter now supports the pinned detector with a 512x512 host smoke input and its own output directory. Existing recognizer files remain intact. All **41 pipeline tests pass** and export source compiles; actual detector conversion and probability-map parity remain pending.

### Detector export integrity and map gate

Detector export completed and independently verifies at **4,819,576 bytes**, SHA `cfcb87395c173a9f48d98d1d247b1006b7ed8224db0286709ce2b96fd38bee96`. ONNX full structural checks and finite 1x1x512x512 smoke output passed; model/source/runtime snapshot hashes verify. Dynamic batch/height/width inputs remain. This establishes conversion execution, not probability-map or Android equivalence.

The new detector parity workflow first compares portable BGR/stride-32 tensors exactly against installed PaddleX, retaining actual Paddle probability maps for all 15 source photographs. A separate ONNX runtime compares those maps at absolute/relative tolerances **0.0005 / 0.0005**, fixed before measuring. Pixel threshold-mask differences at 0.3 are recorded. Source/tensor/runtime snapshots are retained. All **42 tests pass**, including ties-to-even resize rounding at half-stride boundaries. Actual map parity remains pending.

Twelve deterministic training-only historical samples were rendered on two boards and visually inspected. Image/text correspondence broadly holds in this limited spot check, with faint/crossed-out/clipped text, numeric and punctuation-only samples, and publisher punctuation-spacing conventions. Source labels/dataset were not altered during the live run; no test examples were inspected. These observations are not whole-corpus certification or a proven cause of the regression.

### Detector map parity passed; Kotlin map component authored

The two-runtime detector gate completed on all **15 full photographs**. Exact preprocessing equality passed. All maps meet predeclared atol/rtol **0.0005/0.0005**; largest reported absolute error **0.0001143813**. There are **zero changed pixels in masks thresholded at 0.3** across all pages. Artifact verification checked page coverage, input-report hash, all input/reference tensor files and captured source/runtime package hashes. These checks did not independently rerun the numerical inference.

New Kotlin source ports stride rounding, unsigned BGR normalization and bounded ONNX probability maps with model/session checks and native resource cleanup. Four JVM tests are authored, increasing the required gate to **33**. The Android source has not yet compiled or run. The previous 29-test build and 28-line Pixel recognition result remain historical evidence for the earlier build. Detector boxes, ordering, full-page recognition and installed app behavior are still open.

### Detector build and larger training completed

The detector-component gate passed the debug APK, device-test compilation, lint and all **33 JVM tests**, with zero failures/errors/skips. APK SHA `59d2a38651d948972e587081f34161995338fb0989e6d2af7f7480506c84108d`. Actual Pixel detector execution is still pending. A new debug ART probe and Python verifier are authored for all 15 pages. Canonical lossless page pixels avoid conflating JPEG-decoder differences with model parity; fixed map tolerances remain 0.0005/0.0005. Source/ref-map hashes, model/APK hashes, a free-space reserve and owned-directory cleanup are enforced. This is not an installed-app/UI/camera/Room test.

The larger training job completed all **500 steps** and independently verified saved test/validation scores, complete steps, baseline-retention selection, model SHA and captured sources. Validation CER after adaptation was 48.34%, 50.72%, 41.01%, 43.18% and 49.11% at steps 100-500, all worse than the pretrained 17.15%. The retained baseline test has **1,387/8,683 character errors = 15.97% CER**, **527/1,727 word errors = 30.52% WER**, **16/187 exact lines**. Because the selected model is the pretrained baseline, its comparison predictions are identical by construction; no independent second baseline decode is claimed. No trained candidate is promoted. This establishes experiment integrity and a historical benchmark result, not successful quality improvement or contemporary handwriting generalization.


## IAM handwriting experiment - 15 October 2026

Status: protocol and adapter implemented; fine-tuning has **not started** because authenticated dataset archives are unavailable. Publisher requests timed out; Microsoft's published archive link returned access errors. Obtain `lines.tgz` and `xml.tgz` through registered IAM access. Never put credentials, archives, or images in Git.

The adapter `ml/iam_protocol.py` reads line PNGs and original XML transcriptions/writer IDs, verifies image hashes, rejects cross-role writer/document/image leakage and incomplete archives, and publishes a separate train/validation manifest excluding test rows. No archive paths are extracted directly. Existing output directories cannot be overwritten. Original dataset terms remain recorded as `IAM-original-terms`, separate from Creative Commons policies; commercial release is not cleared by this adapter.

Partition protocol: deterministic SHA256 writer assignment using `folio-iam-v1` salt and nominal 80/10/10 buckets. Actual sample counts depend on writers. This is a custom writer-separated partition, **not the official IAM benchmark split**. TrOCR handwritten foundations already used IAM; their prior writer exposure is not disproved by our partition. IAM adaptation results cannot establish unseen-corpus or phone-photo generalization.

Frozen initial engineering targets (`ml/iam-experiment-policy.json`): case-sensitive CER <=5%, WER <=15%, exact-line rate >=60%, at least 500 evaluation lines, and >=10% relative CER improvement against the identical pretrained baseline on the same references. NFC/whitespace normalization preserves case and punctuation. These are acceptance targets, not measured results or promised accuracy. Validation selects the checkpoint; test decoding is deferred until selection is frozen. Run one bounded experiment before reviewing results; do not expand automatically into repeated tuning. A failed improvement gate retains the foundation.

Passing these line-level gates does not make the app ready. Full-photo localization/recognition, reading order, Android resource behavior, review/storage and both PDF flows require independent acceptance. In particular, prior full-photo CER of 26.52% remains unresolved.

Preparation command, after registered archives are available:

```sh
/Users/rohanc/.cache/folio-handwriting-venv/bin/python -m ml.iam_protocol --lines ml/data/iam-source/lines.tgz --xml ml/data/iam-source/xml.tgz --output ml/data/iam-v1
```

Training must use `selection-manifest.jsonl` with `--selection-only --source-license IAM-original-terms`, never the full manifest during checkpoint selection. Before committing to a long run, diagnose the prior training regressions and demonstrate that a small training-only set can be learned; that diagnostic is not a validation accuracy claim.

Verification: 48 pipeline tests pass in the existing pinned ML environment. System Python passes 47 and skips the Pillow-dependent archive integration test. The integration test checks real synthetic PNG/XML archives, writer partitioning, label preservation, test-role exclusion and overwrite rejection. No IAM images have been imported and no new model checkpoint has been trained.


### IAM archive preparation update - 15 October 2026

The metadata-access blocker is resolved for the supplied `/Users/rohanc/Downloads/archive.zip`: downloaded `forms.txt` and `lines.txt` from the Kaggle `maxicode/iam-handwriting` listing match all 1,539 source page IDs and provide 13,353 annotated lines. Original metadata hashes are pinned in `ml/prepare_iam_forms.py`; original terms remain distinct from mirror license labels. XML is no longer required for this ASCII-based import.

The new bounded ZIP-to-line adapter checks writer folders, page dimensions, crop bounds, archive CRCs, image hashes and cross-split leakage. It publishes atomically, preserves source labels (converting IAM `|` separators to spaces), and generates training-only review boards. Fifty pipeline tests pass in the pinned ML environment, including synthetic end-to-end archive import and failed-publication cleanup. Actual corpus preparation and visual review are pending; no IAM training or model-readiness result is claimed yet. The next required gate is visual crop/label review, followed by a small training-only learning diagnostic before longer fine-tuning.


### IAM prepared corpus and bounded learning diagnostic - 15 October 2026

Preparation completed: 13,353 line crops from 1,539 pages, with 11,444 training lines (545 writers), 881 validation lines (53 writers), and 1,028 test lines (59 writers). Archive CRCs, coordinate bounds, metadata writer matches and cross-role leakage checks passed. This is the custom writer partition, not the official IAM benchmark.

The assistant viewed all three training-only boards (24 crop/transcription pairs) and recorded a sampled visual review bound to the board and corpus hashes. Neighbouring ink and unusual source punctuation remain documented; no transcription edits were made. This does not certify the complete corpus.

`ml/iam_learning_check.py` prepares one diagnostic on eight explicitly reviewed TRAINING lines, using the pinned TrOCR-base foundation, frozen encoder, decoder AdamW learning rate 5e-6, accumulation 4, maximum 160 updates, evaluation every 20, and a 45-minute runtime check. It requires training CER <=2%, no CER regression against the same-line baseline, and at least 20% loss reduction before allowing consideration of longer fine-tuning. Validation/test decoding is prohibited. It saves no promotable checkpoint, and cannot establish generalization, Android compatibility, or app readiness. A failure stops the next training stage; no automated experiment loop is scheduled.

All 52 pipeline tests pass in the pinned ML environment, including diagnostics selection and failure-gate checks. Diagnostic execution/results are tracked separately; this entry does not claim it has passed.


### Training loss alignment correction - 15 October 2026

The first eight-line IAM learning check completed 160 updates but failed its fixed gate: CER rose from 20/323 (6.19%) to 49/323 (15.17%) even while loss fell. No checkpoint was saved/promoted and no longer training run was launched.

Source inspection and a tiny real VisionEncoderDecoder regression reproduction confirmed that installed Transformers 4.57.1 shifted decoder inputs once but dispatched a generic causal-LM loss that shifted target labels again. Thus the optimized token targets were misaligned with generation. `ml/seq2seq_training.py` now supplies explicit decoder inputs shifted once and computes same-position cross-entropy independently of the library's loss dispatcher. Both the main trainer and IAM diagnostic use it; site-packages and dataset labels are unchanged.

All 54 pipeline tests pass in the pinned environment. New tests cover perfect aligned token predictions, ignored padding, actual ViT/TrOCR wrapper forward equivalence to manual CE, and finite gradients. The previous custom training runs used the faulty objective, so their poor scores cannot establish that the architecture cannot learn these data; their checkpoints remain unpromoted. Pretrained inference baselines are unaffected by this training-code correction.

A single corrected eight-line check is prepared with the same data, learning rate, 160-step cap and unchanged gate. Its result is pending; confirming the loss defect does not itself establish successful learning or app readiness.


### Corrected learning check passed; one IAM adaptation run prepared - 15 October 2026

The corrected eight-line learning diagnostic passed at update 40: training CER/WER 0%, eight exact lines, versus 6.19% CER before adaptation; mean training loss decreased from 4.3822 to 0.00389. No validation/test data were decoded and no checkpoint was promoted. This demonstrates memorization capability with the corrected objective, not generalization.

Next bounded experiment: one complete pass over the 11,444 training lines (2,861 optimizer steps, accumulation 4), frozen encoder, decoder learning rate 2e-6, pinned TrOCR-base foundation. Baseline and checkpoints are compared on the identical 881-line validation set, evaluated at updates 1,000, 2,000 and 2,861. The 1,028 test lines are excluded from the selection manifest and deferred until checkpoint selection is frozen. One run is prepared; no automatic hyperparameter search or repeated tuning is scheduled. Dataset source, custom writer split and prior IAM foundation exposure caveats still apply. Successful execution alone is not acceptance.


### IAM single-pass training result - 15 October 2026

The run completed all 2,861 updates on 11,444 training lines. Validation selected update 2,000, not the final update. Saved prediction rescoring, selected checkpoint SHA256 and captured source integrity passed independently. Training execution success does not mean quality promotion.

On the identical 881 validation lines (39,760 characters): pretrained CER 2.6685% (1,061 errors), selected CER 2.6610% (1,058 errors); WER 6.8119% to 6.5080%; exact-line rate 64.8127% to 64.0182%. Thus only three fewer character errors, a 0.2828% relative CER reduction, versus the fixed 10% relative-improvement target. Absolute CER/WER/exact-line thresholds pass, but the combined validation promotion gate fails on improvement. No claim of material model superiority or statistical significance is made.

The 1,028 final-test lines remain unevaluated; this custom writer-separated IAM validation is not an unseen-corpus claim because the foundation already used IAM. The selected checkpoint is retained for evaluation, not promoted to Android; Folio still uses ML Kit. Full-page phone-photo recognition, mobile deployment/resource acceptance and integrated review/storage/PDF tests remain separate requirements. No additional training experiment is automatically scheduled.

Evidence: `ml/artifacts/iam-adaptation-v1/training-report.json`, `artifact-verification.json` and `validation-quality-gate.json` (local ignored artifacts).


### Frozen IAM final-test launch preparation - 15 October 2026

Prepared `ml/evaluate_iam_frozen.py` to evaluate the original saved checkpoint and validation-selected update 2,000, once each on the same 1,028 reserved test lines (2,056 predictions total). Parameters remain greedy decoding, one beam and maximum 128 new tokens. No optimizer/gradients, weight updates, checkpoint reselection or automatic repeat is permitted. Existing evaluation output cannot be overwritten. Preflight binds the full/selection manifest hashes to the completed training run, verifies source files via manifest hashes, checks selected weight hashes and freezes both models' hashes; each model's hashes are verified again after inference. Saved predictions permit independent rescoring after completion. IAM foundation prior exposure remains a caveat; this is not an unseen-corpus claim.

The local viewport-fit HUD is configured for this test: total predictions, current model lines, frozen decoding settings, event stream and final CER/WER/exact-line comparison. It reports the conjunction of the original validation gate and final-test gate, with Android acceptance separate. All 55 pipeline tests pass in the pinned ML environment. Test execution/results are pending and must not be inferred from this preparation record.


### Frozen IAM final-test result - 15 October 2026

The original and frozen update-2,000 checkpoints completed all 2,056 predictions on the same 1,028 reserved test lines in 1,073 seconds, with no weight updates. Independent saved-prediction rescoring, model weight hashes and the full corpus manifest hash verified.

Across 48,078 reference characters and 9,549 words: original CER 2.1091% (1,014 character errors), WER 6.4405% (615 word errors), exact-line rate 64.9805% (668 lines). Fine-tuned CER 2.0654% (993 errors), WER 5.9483% (568 errors), exact-line rate 66.1479% (680 lines). This is 21 fewer character errors, 47 fewer word errors and 12 additional exact lines. Relative CER improvement is 2.071%, below the fixed 10% promotion target. Both absolute quality thresholds pass; combined validation/test promotion remains false and app readiness remains false. Statistical significance has not been established.

Measured host inference was approximately 0.494s/line for the original and 0.534s/line for the fine-tuned model; these are Mac inference measurements, not Android benchmarks. Foundation prior IAM exposure limits claims: this is our reserved custom writer-separated scan-line test, not proof of performance on unseen phone photos or full-page recognition. The frozen candidate is retained; Folio still uses ML Kit. Next evaluation needs full-page camera data and mobile feasibility/resource checks. No additional IAM training or test rerun was launched.

Local evidence: `ml/artifacts/iam-frozen-test-v1/evaluation-report.json`, model prediction files and `artifact-verification.json`. The HUD retains the completed comparison.


### ML-only scope and stricter exact-line target - 15 October 2026

User direction: keep work focused on ML and publish the model/source to GitHub once complete. The 75–80% goal is interpreted conservatively as strict case-sensitive completely correct lines: 75% minimum, 80% stretch. Character error rate, word error rate and exact-line rate remain separate. Historical policy/results are preserved; `ml/iam-exact-line-policy-v2.json` records the new target and validation selection rule (highest exact-line rate, tie-break lower CER). Current candidate has 66.15% exact lines on the consumed v1 final test and is not accepted for publication. No commit/push or weight upload has occurred for this stage.

Validation-only diagnosis on 881 lines: 564 exact, 242 text-recognition errors, 49 punctuation/spacing-only mismatches, 11 case-only, nine whitespace-only and six combined formatting-only. Reaching 75% needs 97 additional exact validation lines; reaching 80% needs 141. These diagnostic categories never replace strict headline scores. Even correcting all formatting-only failures is insufficient for 75%.

One bounded next experiment is prepared: compare beam-4 decoding for the frozen original and update-2,000 checkpoints against their saved greedy validation results, with identical source labels and maximum 128 new tokens. This changes decoding, not weights, and selects by the new exact-line criterion using validation only. The consumed final test is excluded from this stage; fresh final acceptance needs another locked holdout or explicitly disclosed benchmark reuse. No 75–80% success is promised. HUD configuration tracks this validation comparison. All 57 pipeline tests pass in the pinned environment.

For later accepted publication, source changes will be batched on main. Dataset archives, reference frames and raw samples remain excluded. Large model weights should be distributed as a versioned GitHub Release asset with tokenizer/processor configuration, checksums and an honest evaluation summary, after redistribution requirements are verified. Model acceptance and publication are not yet complete.


### Full-page ML engineering and release tools - 15 October 2026

Implemented `ml/fullpage_geometry.py`: bounded DB probability-map thresholding, polygon scoring/expansion, duplicate suppression, perspective crops, deterministic whitespace-based column/heading reading order. Seven geometry tests pass in the existing OpenCV runtime, including permutations, two columns, spanning headings, same-row order, crop endpoints, threshold rejection and input bounds. This is horizontal-English geometric layout handling, not arbitrary semantic document understanding.

Implemented `ml/fullpage_ocr.py`: hash-pinned ONNX detector, frozen TrOCR recognizer, EXIF orientation handling, bounded source/working pixels, source-coordinate polygons and portable JSON output. `ml/run_fullpage_diagnostics.py` is prepared to run the 15 previously reviewed development photos, reporting matched-label recall/CER, host processing time and peak host RSS. Other page text lacks labels; this cannot establish complete-page precision, new-holdout accuracy or Android acceptance. Runtime execution is pending. CPU is selected to avoid scheduling a second GPU workload alongside the decoding comparison. Dependencies are pinned in `requirements-fullpage.txt`.

Implemented `ml/lock_holdout.py`: a new labelled test set must have at least 500 lines and writer IDs; it rejects prior line/document/writer/image/source-photo overlaps. A genuinely new locked set has not been supplied or created. The old final IAM test remains consumed and is not silently reset.

Implemented `ml/build_ml_bundle.py`: allowlisted weights, detector, tokenizer/configuration, portable inference sources and SHA256/size manifest, CRC-verified atomic ZIP publication. No raw training images, source archive, UI references or prediction samples enter the bundle. Experimental local packages remain explicitly unaccepted. Release mode rejects missing new-holdout evidence, wrong model identity, quality below the 75% target, non-finite metrics and unresolved redistribution requirements. Model weight size is 1,273.87 MiB; detector is 4.60 MiB. Mobile packaging/runtime feasibility is still unverified.

The existing pipeline suite now passes 59 tests; geometry adds seven separately passing tests (66 total across the existing runtimes). A unified-runtime verification, full-page development diagnostics and experimental local bundle are prepared as the next finite job. The 75–80% exact-line target is not achieved; no GitHub publication is claimed or performed.


### Separate evaluation-source review

The CVL publisher describes a separate 310-writer English/German source with XML word boxes and non-commercial research terms: https://cvl.tuwien.ac.at/research/cvl-databases/an-off-line-database-for-writer-retrieval-writer-identification-and-word-spotting/. No archive URL was exposed in the retrieved page; no source download or new holdout lock has happened. Word metadata/punctuation cannot be assumed to provide exact line transcriptions. This is a candidate source review, not completed data acquisition, model evaluation or release-rights clearance.


### Standing backend-job visibility requirement - 15 October 2026

Every backend/ML job started for this project must have visible progress in the existing HUD (http://127.0.0.1:63299/) or explicit progress updates in chat. Register the job/interface before launch, show the actual current stage and measured item counts when available, retain logs and completion/failure, and never invent overall percentages or ETAs. The viewport must fit the page without document scrolling; logs/tables may scroll internally.

`tools/run_visible_job.py` runs finite, explicit argv stages under Codex Process Jobs while publishing per-stage state, counts and logs to the HUD registry. It never executes output as instructions. Use a unique plan ID; completed/failed records remain available. Three tests verify plan rejection, durable measured progress, success and stopping on first failure. Existing jobs are connected through read-only status/report adapters, with a HUD selector for active jobs and retained results. Current full-page diagnostics and completed beam validation are registered; this changes monitoring only, not model weights.

Completed beam-4 comparison: best strict exact-line configuration was the original model with four beams, 65.4938% on validation, below the 75% target. The fine-tuned four-beam result was 64.6992% exact lines, CER 2.4899%, WER 6.1661%. No fresh final-test result or Android/model acceptance follows from this result.


### Completed full-page diagnostic and experimental bundle - 15 October 2026

The finite runtime/test/diagnostic/package job completed successfully. All 66 ML tests passed. The custom TrOCR pipeline processed 15/15 development photographs and matched 26/28 partial-label targets at IoU 0.5. Targeted CER = 441/920 = 47.93%, WER = 139/190 = 73.16%, exact lines = 1/28 = 3.57%. These results fail practical full-page recognition expectations despite strong target detection; they do not measure complete-page accuracy. Maximum host process RSS was 1,968,111,616 bytes. The local experimental bundle is 1,342,049,381 bytes, SHA256 `f45db2902b1bef50024ea84046e4770ff92fae0194dee0f8db1663d25527597c`; no acceptance or Android promotion is claimed.

Existing Pixel standalone detector evidence also passed all 15 map comparisons with zero mismatched pages. This and the 28-line recognizer parity probe test runtime equivalence, not installed-app integration or custom TrOCR quality. The latest recorded Android build/lint/33-JVM-test gate passed; the installed full-app baseline remains the earlier 49-device-test gate. Public README and aggregate benchmark JSON preserve these boundaries.


### Editorial redesign verification - 16 October 2026

The revised local Folio editorial passed 12 interaction/health checks in installed Google Chrome through bundled Playwright. The in-app browser displayed the page but returned inconsistent viewport sizes and screenshot captures, so reliable visual verification used the documented fallback. Checked desktop 1536x1024, mobile 390x844 and 320x844, and 1280x800 reduced-motion/fallback states. No page/shader errors or failed desktop assets were recorded.

Verified primary-anchor navigation; all four scroll/direct process controls; idle WebGL rendering cessation; measured research caveats; manual and OS reduced motion; static no-WebGL fallback; storyboard next/range/chapter controls; no horizontal overflow; and separate existing wireframe/prototype behavior. Editorial and storyboard contain no app/device embeds. Original app test-bench file hashes remain unchanged.

Visual comparison inspected the new generated opening/process/research studies and actual Chrome screenshots: locked headline/nav copy, condensed uppercase hierarchy, black/bone/vermilion palette, asymmetric image/type composition, and separate destinations. Mobile overflow was fixed by clipping the artwork stage within the viewport; the mobile supporting copy gained a black reading surface and the artwork was moved below the main headline. Intentional differences: local Anton type, additional factual research caveats, and illustrative shader movement rather than real OCR. Browser comparisons cover the stated Chrome viewports, not Safari, Firefox or physical-device GPU performance. Temporary screenshots and the execution report remain outside the repository.


### Distinct editorial artwork verification - 16 October 2026

All 12 editorial interaction/health checks passed again in Chrome after separating chapter artwork. Added assertions verify different hero/process WebGL texture sources, correct process artwork in the storyboard and mobile fallback, four process controls, idle process rendering cessation, reduced motion, no-WebGL behavior, no device embeds, no horizontal overflow at 390/320px and no page/shader/asset errors. Desktop and process screenshots were inspected. Existing app test-bench hashes remain unchanged.

### Next.js editorial verification — prepared 16 October 2026

New local source: `design/folio-web/`. Preflight `scripts/check-content.mjs` passed: seven distinct nontruncated artwork files, saved prompt provenance, 17 unchanged original test-bench files and exactly six existing `/docs/*.md` files. `scripts/browser-check.mjs` passed Node syntax checking; this does not establish browser success.

The finite validation pipeline installs pinned dependencies, checks protected content, typechecks, builds production output, then runs installed-Chrome verification through bundled Playwright. The browser runner owns a temporary foreground Next server on port 8771 and shuts it down when complete. It checks all six direct workflow controls, idle WebGL, three card layouts, mouse/keyboard PDF tabs, supporting disclosures, research boundaries, the six-stage process guide and fifteen-screen map, process anchors, storyboard range/chapter/next controls, separate test-bench navigation, 390/320px mobile route layouts, reduced motion, no-WebGL fallback, and browser/asset errors. Screenshots and the result record go to `/tmp/folio-next-qa/` for visual inspection. A passing runner still needs comparison of rendered screenshots with generated studies.

**Pending:** dependency installation, production build, execution of this browser matrix and rendered visual review. Earlier static-editorial checks do not transfer automatically to the Next.js implementation. This work does not run or promote the Android app or custom OCR model.


### Next.js results and scanner-line refinement — 16 October 2026

The earlier production job completed successfully: dependency installation, content protection, strict TypeScript, production compilation and all 16 installed-Chrome browser checks passed. Result: `/tmp/folio-next-qa/report.json`.

After removing the animated scan-line shader, targeted installed-Chrome verification passed on the live development preview at `127.0.0.1:8771`: WebGL initialization, direct Correct-chapter navigation, meaningful desktop/mobile content, no page/console errors, and no horizontal document overflow at 390px. The first targeted run exposed a 13px mobile library-art overflow, fixed by clipping the library composition. Desktop and mobile screenshots were inspected. TypeScript and artwork/protected-file checks passed again. This targeted refinement has not been production-rebuilt or retested across the entire earlier matrix. The Browser plugin/skill is not available; installed Chrome through bundled Playwright supplied deterministic viewport/console checks. The in-app preview was refreshed separately.

### Website publication preflight — 16 October 2026

Prepared standalone publishable source under `website/` with the same approved editorial UI and scanner-line removal. Strict TypeScript passed. Public links no longer point to localhost. Dependencies, build cache and Vercel local state are ignored; only application source, lockfile, licensed fonts and production artwork are intended for Git. Local design references and the native wireframe remain ignored. Production build, Git connection and public-route/browser checks are pending Vercel confirmation; prior local checks are not a deployed-site acceptance result.
