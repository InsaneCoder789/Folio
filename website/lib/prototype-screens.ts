export const prototypeScreens = [
  {
    "id": "01",
    "title": "Library",
    "purpose": "Find saved documents and resume drafts.",
    "connections": "New scan \u2192 02 \u00b7 Document \u2192 12 \u00b7 Search \u2192 13 \u00b7 Settings \u2192 14"
  },
  {
    "id": "02",
    "title": "Scan setup",
    "purpose": "Choose document or card mode, then choose an image source.",
    "connections": "Camera \u2192 03 \u00b7 Import \u2192 04"
  },
  {
    "id": "03",
    "title": "Camera",
    "purpose": "Capture a page or separated cards.",
    "connections": "Capture \u2192 add to tray \u00b7 Done \u2192 04"
  },
  {
    "id": "04",
    "title": "Boundaries",
    "purpose": "Adjust corners and select separate card regions.",
    "connections": "Apply \u2192 05 \u00b7 Retake \u2192 03"
  },
  {
    "id": "05",
    "title": "Enhancement",
    "purpose": "Choose a readable image treatment.",
    "connections": "Read text \u2192 06 \u00b7 Learning lab \u2192 15"
  },
  {
    "id": "06",
    "title": "OCR review",
    "purpose": "Compare and correct recognized text.",
    "connections": "Document \u2192 08 \u00b7 Cards \u2192 07"
  },
  {
    "id": "07",
    "title": "Card pairing",
    "purpose": "Confirm sides and manually pair cards.",
    "connections": "Confirm \u2192 08 \u00b7 Missing side \u2192 02"
  },
  {
    "id": "08",
    "title": "Organizer",
    "purpose": "Name, edit, reorder, or remove pages.",
    "connections": "Add \u2192 02 \u00b7 Editors \u2192 04\u201306 \u00b7 Layout \u2192 09"
  },
  {
    "id": "09",
    "title": "Page layout",
    "purpose": "Fit pages or arrange cards without distortion.",
    "connections": "Export settings \u2192 10"
  },
  {
    "id": "10",
    "title": "Export options",
    "purpose": "Choose searchable scan or regenerated text.",
    "connections": "Generate preview \u2192 11"
  },
  {
    "id": "11",
    "title": "PDF preview",
    "purpose": "Inspect the chosen output before saving.",
    "connections": "Save \u2192 12 \u00b7 Change format \u2192 10"
  },
  {
    "id": "12",
    "title": "Document details",
    "purpose": "Open a saved preview, edit, or share.",
    "connections": "Open \u2192 11 \u00b7 Edit \u2192 08 \u00b7 Share \u2192 sheet"
  },
  {
    "id": "13",
    "title": "Search",
    "purpose": "Search local document titles and OCR text.",
    "connections": "Result \u2192 12 \u00b7 Back \u2192 01"
  },
  {
    "id": "14",
    "title": "Settings",
    "purpose": "Set supported scan and export defaults.",
    "connections": "Save \u2192 01 \u00b7 Replay onboarding \u2192 intro"
  },
  {
    "id": "15",
    "title": "Learning lab",
    "purpose": "Inspect original, edges, corners, and OCR geometry.",
    "connections": "Return \u2192 05; document remains unchanged"
  }
] as const;
