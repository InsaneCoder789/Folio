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
