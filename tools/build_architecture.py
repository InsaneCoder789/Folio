"""Render the design-baseline PDF. Never create Markdown or write to docs. No app code."""
from pathlib import Path
from html import escape
from reportlab.pdfgen import canvas
from reportlab.lib.colors import HexColor, white
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/pdf'
OUT.mkdir(parents=True, exist_ok=True)

# ID, title, purpose, controls/content, entry, exit, required variants.
SCREENS = [
('01', 'Document library', 'Find saved documents and resume drafts.', 'Thumbnails; title; page count; draft/export status; New scan; Search; Settings.', 'App launch; saved-document Back.', 'New scan -> 02; item -> 12; Search -> 13; Settings -> 14.', 'Empty library; populated library; draft with interrupted work; selection/delete confirmation.'),
('02', 'Scan setup', 'Choose the scan workflow and input source.', 'Document or ID cards; ID workflow: front/back or multiple cards; Camera or Import images.', '01 New scan; 08 Add pages.', 'Camera -> 03; system image picker -> 04; Back -> caller.', 'Document; front/back cards; multiple cards; import cancelled. Preserve the caller and current draft.'),
('03', 'Camera capture', 'Capture sharp pages or cards with boundary guidance.', 'Viewfinder; shutter; flash; capture count; thumbnail tray; Done; optional front/back cue.', '02 Camera; retake from 04.', 'Done -> 04; retake returns to its region; Back -> draft/discard sheet.', 'No permission; no boundary; blur/glare suggestion; captured images; missing back. Suggestions must not block manual capture.'),
('04', 'Boundary editor', 'Confirm the page or each card region before correction.', 'Four draggable corners; zoom; rotate; add/remove region in multiple-card mode; image switcher; Apply.', '03 Done; gallery import; 08 Crop.', 'Apply -> 05; Retake -> 03; Cancel -> caller.', 'Detected corners; no detection/manual corners; multiple cards; invalid/crossed corners. Keep edits per capture.'),
('05', 'Enhancement editor', 'Choose a readable appearance while preserving the original.', 'Original/color/grayscale/B&W thumbnails; brightness/contrast; rotate; before/after; Apply; Learning lab.', '04; 08 Enhance.', 'Continue -> OCR processing -> 06; Lab -> 15; Back -> 04 or caller.', 'Original; color; B&W; clipped highlights; processing failure. Apply per region; batch apply is explicit.'),
('06', 'OCR review', 'Review extracted text against the scan.', 'Image/Text tabs; word highlight; block editor; language; rerun; previous/next region; Continue.', '05 processing; 08 Text; 12 Review text.', 'Document Continue -> 08; ID Continue -> 07; selected word -> edit sheet.', 'Recognizing; text found; no text; unsupported script; edits; retry. Do not invent confidence percentages.'),
('07', 'Card review and pairing', 'Label card regions and confirm front/back relationships.', 'Card thumbnails; generic/unknown type label; Front/Back/Single; pair/unpair; capture missing side; Continue.', '06 in ID mode; 08 Pair cards.', 'Continue -> 08; capture missing side -> 02 with pending pair; edit text -> 06.', 'Unpaired; paired; single-sided; missing back; unknown type. Manual confirmation is authoritative.'),
('08', 'Document organizer', 'Manage the complete draft before export.', 'Title; page/card thumbnails; drag reorder; add; crop; enhance; text; duplicate; delete; pair cards in ID mode; Layout.', '06 document; 07 cards; 12 Edit.', 'Editors -> 04/05/06; pairing -> 07; Add -> 02; Layout -> 09.', 'One page; multipage; multiple card pairs; empty after deletion; stale OCR after crop change.'),
('09', 'Page layout', 'Place pages and cards on export sheets.', 'Page canvas; A4/Letter/Auto; margins; orientation; fit; ID templates: stacked, side-by-side, grid; overflow page count.', '08 Layout; 10 Adjust layout.', 'Continue -> 10; Back -> 08.', 'Document fit; card pair; card grid; overflow; unknown physical size. Preserve aspect ratio; use actual size only when known.'),
('10', 'Export options', 'Choose what the generated PDF contains.', 'Searchable scan or Regenerated text; filename; output summary; scan compression or text font/size; Generate preview.', '09; 11 Change output.', 'Generate -> progress state -> 11; Adjust layout -> 09; incomplete OCR -> 06.', 'Both output modes; pending OCR; failed OCR. Text mode says simple paragraphs; no exact-layout promise.'),
('11', 'PDF preview', 'Inspect the generated result before saving or sharing.', 'Page viewer; page counter; search; text selection; output-mode label; Save; change options; edit layout.', '10 successful generation; 12 Open PDF.', 'Save -> 12; Change options -> 10; layout -> 09; Back -> caller.', 'Generating overlay; preview ready; no search match; failed export; outdated preview. Shared reader UI for both formats.'),
('12', 'Saved document details', 'Open, edit, export, or share a saved document.', 'Title; cover; page count; exports with format/time; Open PDF; Edit; Review text; Share; rename/delete menu.', '01 item; 13 result; 11 Save.', 'Open -> 11 read-only saved mode; Edit -> 08; Text -> 06; Share -> system share sheet; Back -> 01.', 'Draft only; current export; older export clearly labeled; export missing; failed share. Regenerate only on request.'),
('13', 'Search results', 'Find documents using titles and recognized text.', 'Query field; text matches; highlighted snippets; document/page labels; recent query optional.', '01 Search.', 'Result -> 12 with matched page context; Back -> 01.', 'Typing; results; no matches; OCR not run. Searches local metadata and OCR only.'),
('14', 'Settings', 'Set scanning and export defaults.', 'Theme; OCR script choice; default paper size; scan quality; learning-lab visibility; local-storage summary.', '01 Settings.', 'Save defaults / Back -> 01.', 'Default values; changed defaults; unsupported language. Only expose supported OCR scripts; no model-training UI.'),
('15', 'CV learning lab', 'Inspect how image processing creates the scan.', 'Stage selector: original, grayscale, blur, edges, contours, corners, warp, OCR boxes; two-image comparison; short explanation.', '05 Learning lab when enabled.', 'Back -> 05 with edits preserved.', 'Stage result; detection failure; no OCR yet; comparison. Experiments never alter the document until explicitly applied.'),
]

# PDF uses explicit page composition and vector diagrams for readable exports.
W,H = 595.28,841.89
INK='#172532'; MUTED='#536575'; ACCENT='#087F8C'; LIGHT='#EAF4F4'; LINE='#CAD7DE'
c=canvas.Canvas(str(OUT/'Architecture.pdf'), pagesize=(W,H))
c.setTitle('OCR Scanner - Architecture and Frontend Design Baseline')
c.setAuthor('OCR Scanner')
PAGE=0
def text(x,y,s,size=10,color=INK,font='Helvetica'):
    c.setFillColor(HexColor(color)); c.setFont(font,size); c.drawString(x,y,s)
def para(x,y,s,width=499,size=10,color=INK):
    style=ParagraphStyle('p',fontName='Helvetica',fontSize=size,leading=size*1.4,textColor=HexColor(color))
    p=Paragraph(s,style); _,h=p.wrap(width,700); p.drawOn(c,x,y-h); return y-h
def page(title,sub):
    global PAGE
    if PAGE: c.showPage()
    PAGE+=1
    text(48,798,'OCR SCANNER / DESIGN BASELINE 1.0',9,ACCENT,'Helvetica-Bold')
    text(48,758,title,25,INK,'Helvetica-Bold')
    para(48,738,sub,size=10,color=MUTED)
    c.setStrokeColor(HexColor(LINE)); c.line(48,69,W-48,69)
    text(48,49,'14 OCT 2026  |  KOTLIN / ANDROID  |  DESIGN FIRST',8,MUTED)
    text(W-66,49,f'{PAGE:02}',9,MUTED)
def box(x,y,w,h,title,detail='',accent=False):
    c.setFillColor(HexColor(LIGHT if accent else '#F3F6F8')); c.setStrokeColor(HexColor(LINE))
    c.roundRect(x,y,w,h,7,fill=1,stroke=1)
    para(x+12,y+h-11,escape(title),w-24,11,INK)
    if detail: para(x+12,y+h-31,escape(detail),w-24,9,MUTED)
def arrow(x1,y1,x2,y2,label=''):
    from math import atan2, cos, sin, pi
    c.setStrokeColor(HexColor(ACCENT)); c.setLineWidth(1.2); c.line(x1,y1,x2,y2)
    a=atan2(y2-y1,x2-x1)
    p=c.beginPath();p.moveTo(x2,y2);p.lineTo(x2-6*cos(a-pi/6),y2-6*sin(a-pi/6));p.lineTo(x2-6*cos(a+pi/6),y2-6*sin(a+pi/6));p.close()
    c.setFillColor(HexColor(ACCENT));c.drawPath(p,fill=1,stroke=0)
    if label: text((x1+x2)/2+5,(y1+y2)/2+5,label,8,MUTED)
def note(y,title,body):
    text(48,y,title,11,ACCENT,'Helvetica-Bold'); return para(48,y-13,body,size=10)-20
def chain(labels,top=690,details=None):
    for i,title in enumerate(labels):
        y=top-i*76
        box(130,y-52,335,52,title,details[i] if details else '',i==0)
        if i<len(labels)-1: arrow(297,y-52,297,y-76)

page('What we are designing','15 screens: 14 product screens + 1 optional computer vision learning lab.')
box(174,622,247,64,'OCR Scanner','A local document and ID-card workspace',True)
for x,title,detail in [(48,'Capture + correct','Paper boundaries and perspective'),(310,'Recognize text','On-device OCR and review')]:
    box(x,502,237,74,title,detail);arrow(297,622,x+118,576)
for x,title,detail in [(48,'Searchable scan','Original appearance + text layer'),(310,'Regenerated text','Newly typeset paragraphs')]:
    box(x,382,237,74,title,detail);arrow(x+118,502,x+118,456)
note(334,'Confirmed scope','Both PDF formats. Front/back card pairing. Multiple cards in one photograph. Arrange cards on the same PDF page.')
note(256,'Design baseline','Kotlin + Compose. CameraX + OpenCV. Bundled ML Kit OCR. Room and private files. Original captures and editable drafts retained.')
note(178,'Later scope','Cloud sync, handwriting, curved-page flattening, automatic card pairing, and exact reconstruction of complex documents.')

page('Screen connections','Main routes below. System dialogs, sheets, and processing states do not add screens.')
rows=[('01 Library','02 Scan setup','03 Camera'),('04 Boundaries','05 Enhancement','06 OCR review'),('07 Card pairing','08 Organizer','09 Page layout'),('10 Export options','11 PDF preview','12 Saved document')]
for r,row in enumerate(rows):
    y=642-r*112
    for col,t in enumerate(row): box(48+col*171,y,157,56,t,accent=(r==0 and col==0))
    arrow(205,y+28,219,y+28);arrow(376,y+28,390,y+28)
    if r<3:
        c.setStrokeColor(HexColor(ACCENT));c.line(469,y,469,y-20);c.line(469,y-20,126,y-20);arrow(126,y-20,126,y-56)
para(48,274,'Document mode skips 07. Import goes from 02 directly to 04. Saved items open 12; Edit returns to 08. Opening a saved PDF reuses 11.',size=10)
for i,t in enumerate(['13 Search','14 Settings','15 Learning lab']): box(48+i*171,153,157,54,t)
para(48,135,'01 opens Search and Settings. 05 opens Learning lab and returns with edits preserved.',size=10)

page('How scanning works','Expose each stage in the learning lab. Use full-resolution captures for the final correction.')
chain(['Pixels -> grayscale','Blur -> edges','Contours -> four corners','Review corners / map to source','Perspective correction','Display image + OCR input'],details=['Color measurements become intensity values.','Noise suppression, then intensity-change detection.','Boundaries become geometric candidates.','Manual correction remains available.','A homography straightens a flat surface.','Separate visual styling from recognition preparation.'])
note(202,'Camera strategy','Analyze reduced preview frames for guidance. Capture at higher resolution. Close analysis frames promptly; keep full OCR out of the live preview loop.')
note(132,'Failure behavior','Glare, blur, clutter, and shadows can hide boundaries or text. Offer recapture and manual corners; filters cannot recreate missing detail.')

page('OCR and PDF outputs','OCR produces words and positions. Export decides how they appear on the page.')
box(172,630,251,58,'Corrected image','Retained original appearance',True)
box(172,527,251,58,'OCR + review','Blocks, lines, words, geometry, reading order');arrow(297,630,297,585)
box(48,365,237,94,'Searchable scan PDF','Image + aligned invisible text layer. Search and copy while preserving the scan.')
box(310,365,237,94,'Regenerated text PDF','Reviewed text -> paragraph layout -> embedded fonts. Newly typeset output.')
arrow(260,527,166,459);arrow(334,527,429,459)
note(318,'Alignment contract','Use a shared transform for image and text: rotation -> crop -> corrected region -> PDF placement. Keep text revisions tied to image revisions.')
note(235,'Editing contract','Short word edits preserve geometry where possible. Large rewrites require alignment review for searchable scans. Regenerated text can reflow.')
note(153,'PDF-engine gate','Verify invisible text, Unicode mapping, font embedding, extraction order, and Android compatibility in two readers before choosing a library. Android PdfDocument is a candidate, not a proven searchable exporter.')

page('ID cards: capture to page','Detect regions, read text, confirm labels and pairs, then compose the export sheet.')
for i,(title,detail) in enumerate([('Find each card','Separate card regions; user can add missed regions.'),('Correct and recognize','Warp and OCR each card independently.'),('Label and pair','Front / back / single; user-confirmed pairing.')]):
    y=632-i*86;box(98,y,399,59,title,detail,i==0)
    if i<2: arrow(297,y,297,y-27)
text(48,416,'PAGE TEMPLATES',10,ACCENT,'Helvetica-Bold')
for x,t in [(48,'Paired sides'),(310,'Multiple-card grid')]:
    c.setFillColor(white);c.setStrokeColor(HexColor(LINE));c.rect(x,204,237,185,fill=1,stroke=1);text(x+12,369,t,11,INK,'Helvetica-Bold')
box(92,292,149,48,'Front');box(92,226,149,48,'Back')
for x,y,t in [(327,292,'Card 1'),(435,292,'Card 2'),(327,226,'Card 3'),(435,226,'Card 4')]: box(x,y,96,48,t)
para(48,179,'Preserve aspect ratio. Use physical size only when known. Overflow creates another page. Card imagery belongs to scan layouts; regenerated output uses a separate text layout.',size=10)
para(48,116,'Initial capture target: fully visible, separated cards. Unknown document types remain valid; no automatic identity or authenticity verification.',size=9,color=MUTED)

page('Kotlin application architecture','One app module initially. Keep processing, presentation, and persistence behind clear interfaces.')
box(172,638,251,54,'Compose screens','Render state; emit user actions',True)
box(172,548,251,54,'ViewModels + StateFlow','Retain screen state and coordinate work');arrow(297,638,297,602)
box(172,458,251,54,'Use cases','Scan / recognize / arrange / export');arrow(297,548,297,512)
for i,(t,d) in enumerate([('VisionProcessor','OpenCV adapter'),('OcrEngine','ML Kit adapter'),('PdfExporter','Validated engine')]):
    x=48+i*171;box(x,338,157,68,t,d);arrow(297,458,x+78,406)
box(48,217,237,66,'DocumentRepository','Room metadata + private image/PDF files')
box(310,217,237,66,'PageLayoutEngine','Placement, scaling, overflow, templates')
c.setStrokeColor(HexColor(ACCENT))
c.line(172,485,30,485);c.line(30,485,30,250);arrow(30,250,48,250)
c.line(423,485,565,485);c.line(565,485,565,250);arrow(565,250,547,250)
note(175,'Execution','CameraX captures images. Coroutines keep interactive work off the UI thread. WorkManager handles durable OCR/export batches, not preview frames.')
para(48,103,'Packages: feature/{library,capture,editor,ocr,cards,export}, core/{vision,ocr,pdf}, data.',size=9,color=MUTED)

page('Data and revision lifecycle','Image files are separate from metadata. Each result belongs to a specific document revision.')
chain(['Document -> captures','Capture -> regions','Region -> OCR blocks','PDF pages -> region placements','Document -> exports'],top=686,details=['One original photo can contain several cards.','Corners, rotation, filter recipe, revision.','Text and geometry reference the same revision.','Page size, margins, destination rectangles.','Format, source revision, file URI, status.'])
note(273,'Edit invalidation','Crop or rotation change -> rerun OCR and regenerate PDF. Layout-only change -> regenerate PDF while retaining OCR. Never let an old job overwrite a newer edit.')
note(194,'Draft and export lifecycle','Captured -> corrected -> recognized -> reviewed -> arranged -> exported. Failures preserve the last valid draft and saved export; users can retry or cancel.')
note(115,'Storage','Room: documents, recipes, OCR, pairs, placements. Private files: originals, derivatives, PDFs. Deleting a document removes app-owned files; external shared copies are separate.')

for group in range(3):
    page(f'Screen specifications / {group+1} of 3','Stable route IDs connect the design frames, prototype, and future Kotlin navigation.')
    y=700
    for sid,title,purpose,content,entry,exit_,states in SCREENS[group*5:group*5+5]:
        text(48,y,f'S{sid}  {title}',13,ACCENT,'Helvetica-Bold')
        y=para(48,y-10,escape(purpose),size=10)-6
        y=para(48,y,'<b>Show:</b> '+escape(content),size=9)-4
        y=para(48,y,'<b>Connect:</b> '+escape(exit_),size=9)-4
        y=para(48,y,'<b>Variants:</b> '+escape(states),size=8.5,color=MUTED)-16
        if y<78: raise RuntimeError(f'Screen specification page overflow: {sid} at {y}')

page('Design handoff and gates','Finalize visual design before implementing the application.')
note(695,'Design deliverables','15 primary frames + named state variants. Design tokens and reusable components. Connected prototype covering both PDF formats and both card workflows. Android Back and editor return paths annotated.')
note(594,'Shared states, not extra screens','Camera permission, image picker, share sheet, progress, word editor, rename, delete confirmation, draft/discard, and failure/retry sheets.')
note(505,'Acceptance checks','No dead-end primary actions. Drafts survive navigation. No clipped content or distorted cards. Empty/error states have recovery actions. Both previews show the correct output type. Test compact widths and enlarged text.')
note(402,'Build sequence after design','Pixels and filters -> document geometry -> OCR -> PDF compatibility prototype -> document workflow -> card layouts -> Android reliability.')
note(306,'Decisions requiring implementation validation','Exact PDF engine, target Android/device baseline, additional writing scripts, named card families, physical-size printing, and measured quality/performance targets. These do not block the generic frontend design.')
text(48,206,'OFFICIAL REFERENCES',10,ACCENT,'Helvetica-Bold')
refs=[('Android architecture','https://developer.android.com/topic/architecture/recommendations'),('CameraX','https://developer.android.com/media/camera/camerax/analyze'),('OpenCV Android','https://opencv.org/android/'),('ML Kit OCR','https://developers.google.com/ml-kit/vision/text-recognition/v2/android'),('Android PDF pages','https://developer.android.com/reference/android/graphics/pdf/PdfDocument')]
y=186
for label,url in refs:
    text(48,y,label,9,MUTED);c.linkURL(url,(48,y-2,300,y+11),relative=0);y-=19
c.save()
print(f'Created {PAGE}-page Architecture.pdf; Markdown documentation is maintained only in the six existing docs files.')
