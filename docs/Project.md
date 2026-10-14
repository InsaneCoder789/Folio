# Folio - project reference

Folio is a native Android scanner built in Kotlin and Jetpack Compose. Supports document correction, on-device OCR, searchable scan PDFs, regenerated text PDFs, front/back card pairing, and multiple-card page layouts.

Current phase: working native backend MVP; core workflows verified on Android 14. The permanent design website remains the visual reference.

## Documentation rules

- `/docs` contains exactly the six Markdown files listed below, and nothing else.
- All project documentation changes must update these six existing files.
- Do not create additional Markdown files anywhere in this project, including README, AGENTS, changelog, task notes, or generated Markdown exports.
- Put new topics under the appropriate existing file. Add sections instead of files.
- Generated PDFs remain outside `/docs`; they are reference snapshots, not editable documentation sources.
- Scripts must not generate Markdown files or add files to `/docs`.

## Six-file map

| File | Owns |
| --- | --- |
| [Project.md](Project.md) | Scope, project status, documentation rules, file map |
| [Architecture.md](Architecture.md) | System architecture, data flow, screen map, technical decisions |
| [Frontend.md](Frontend.md) | Frontend design prompt, screen behavior, prototype handoff |
| [Fundamentals.md](Fundamentals.md) | Computer vision concepts and learning exercises |
| [Backend.md](Backend.md) | Kotlin implementation sequence and technical validation gates |
| [Testing.md](Testing.md) | Quality checks, acceptance criteria, validation evidence |

## Source ownership

Use Architecture for system decisions and Frontend for detailed screen design. Fundamentals explains concepts; Backend tracks engineering work; Testing owns verification. Update affected existing files together when decisions change.

## Existing artifact

[Architecture PDF](../output/pdf/Architecture.pdf): 11-page design-baseline snapshot from 14 October 2026. Markdown is the current source; regenerate the PDF after substantive architecture changes when requested.


## Current design handoff

- [Wireframe and interactive prototype](../design/index.html)
- [Frontend design specification](Frontend.md)
- [Prototype verification](Testing.md)

The prototype contains 15 functional screens plus logo boot and two first-run onboarding states. It is a browser review artifact. Native persistence/import/OCR integration has now been authored under `android/`; build and device verification are tracked in Testing.md. Camera correction, real PDF generation and full native screen parity remain unfinished.


## Permanent design website

The user explicitly requested retaining the website on 14 October 2026. Keep `design/`, its prototype, assets, concepts, and reference screens in this project alongside the future Android app. Do not remove or replace the website when Kotlin implementation starts.

Double-click `Start Scanner Design.command` to start/open the website. Alternatively run `python3 tools/serve_design.py --open` from the project root. Default URL: `http://127.0.0.1:8770`. The launcher reuses an existing matching preview. Closing the server does not remove any website files or assets; use the launcher to reopen it.


## Public repository

Product/repository name: **Folio**. GitHub: https://github.com/InsaneCoder789/Folio. The local workspace remains `OCR Scanner`; no project files were moved. Initial Android application ID remains `com.rohanc.ocrscanner` to preserve install identity. Historical screen artwork and architecture PDF retain their original working title. Credentials, local SDK paths, build outputs, signing keys, logs and local integration metadata stay out of Git.


## Native workflow expansion

The next native increment authors all 15 functional routes plus first-run entry, CameraX capture, manual/automatic boundaries, OpenCV correction and enhancement, text review, manual card pairing, PDF placement, actual searchable/text PDF export, preview, save/share, local search/settings and an optional processing lab. WorkManager handles resumable OCR batches. These expanded sources require a fresh build and device gate before being treated as working acceptance evidence. The permanent website remains alongside the Android app.

The current backend gate passed build/lint, 23 JVM checks and 27 API-34 device checks. The working debug APK and verification evidence are listed in [Backend.md](Backend.md) and [Testing.md](Testing.md). Physical-phone and release validation are next.
