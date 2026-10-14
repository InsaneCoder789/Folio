# Testing and acceptance

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

- [Structured verification results](../output/design/verification.json)
- [Wireframe/prototype](../design/index.html)
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

Evidence: [build/JVM report](../output/android/verification.json), [device report](../output/android/device-verification.json), [independent PDF report](../output/android/desktop-pdf-verification.json), [native PDF-preview capture](../output/android/acceptance/native-preview.png). The debug APK is generated at `android/app/build/outputs/apk/debug/app-debug.apk` and is intentionally excluded from Git. Test PDFs and screenshots use synthetic content.

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
- [Device report](../output/android/visible-device-verification.json), [build report](../output/android/verification.json), [native screen board](../output/android/ui-parity-pixel9/screen-board.png).
- The same emulator remains open in the debug UI review activity. No other emulator is used. The arm64-only local debug build fits its available storage.

This verifies the tested flows, not every possible state or backend completeness. Physical-camera quality, representative OCR accuracy, external save/share interaction, additional device coverage and final user acceptance remain separate. Backend completion follows page by page after this UI review.

The same Pixel run produced searchable and regenerated PDFs; independent pypdf extraction and PDFium rendering passed for both synthetic exports. See [PDF report](../output/android/desktop-pdf-verification.json).
