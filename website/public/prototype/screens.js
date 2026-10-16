export const screens = [
  ['01','Library','Find saved documents and resume drafts.','New scan → 02 · Document → 12 · Search → 13 · Settings → 14'],
  ['02','Scan setup','Choose document or card mode, then choose an image source.','Camera → 03 · Import → 04'],
  ['03','Camera','Capture a page or separated cards.','Capture → add to tray · Done → 04'],
  ['04','Boundaries','Adjust corners and select separate card regions.','Apply → 05 · Retake → 03'],
  ['05','Enhancement','Choose a readable image treatment.','Read text → 06 · Learning lab → 15'],
  ['06','OCR review','Compare and correct recognized text.','Document → 08 · Cards → 07'],
  ['07','Card pairing','Confirm sides and manually pair cards.','Confirm → 08 · Missing side → 02'],
  ['08','Organizer','Name, edit, reorder, or remove pages.','Add → 02 · Editors → 04–06 · Layout → 09'],
  ['09','Page layout','Fit pages or arrange cards without distortion.','Export settings → 10'],
  ['10','Export options','Choose searchable scan or regenerated text.','Generate preview → 11'],
  ['11','PDF preview','Inspect the chosen output before saving.','Save → 12 · Change format → 10'],
  ['12','Document details','Open a saved preview, edit, or share.','Open → 11 · Edit → 08 · Share → sheet'],
  ['13','Search','Search local document titles and OCR text.','Result → 12 · Back → 01'],
  ['14','Settings','Set supported scan and export defaults.','Save → 01 · Replay onboarding → intro'],
  ['15','Learning lab','Inspect original, edges, corners, and OCR geometry.','Return → 05; document remains unchanged'],
].map(([id,title,purpose,connections])=>({id,title,purpose,connections}));

export const lessonText = {
  Original:'The capture is a grid of color measurements. Keep it unchanged as your source.',
  Grayscale:'Color channels become intensity values. Shape and brightness remain.',
  Blur:'Neighboring pixels are averaged to suppress small variations. Too much loses detail.',
  Edges:'Rapid changes in intensity become boundary candidates—not necessarily the paper edge.',
  Corners:'Four ordered corners describe the flat surface we want to straighten.',
  Warp:'A projective transform maps the tilted surface to a rectangle.',
  'OCR boxes':'Word positions link recognized text to the image and the exported PDF.'
};
