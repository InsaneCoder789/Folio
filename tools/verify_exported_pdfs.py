#!/usr/bin/env python3
"""Independently read/render synthetic PDFs exported by the native acceptance test."""
from pathlib import Path
import hashlib
import json
import re
import pypdf
import pypdfium2 as pdfium

root=Path(__file__).resolve().parents[1]
source=root/'output/android/acceptance'
rendered=root/'tmp/pdfs'
rendered.mkdir(parents=True,exist_ok=True)
results=[]
for name,phrases in [('searchable',['FOLIO BACKEND','A working document.']),('regenerated',['Folio reviewed text.','Ready for export.'])]:
    path=source/(name+'.pdf')
    reader=pypdf.PdfReader(path)
    text=re.sub(r'\s+',' ',' '.join(page.extract_text() or '' for page in reader.pages)).strip()
    passed=all(phrase in text for phrase in phrases)
    pdf=pdfium.PdfDocument(path)
    try:
        for index in range(len(pdf)):
            page=pdf[index]
            try:page.render(scale=1.2).to_pil().save(rendered/f'folio-{name}-{index+1}.png')
            finally:page.close()
    finally:pdf.close()
    results.append({'file':str(path.relative_to(root)),'pages':len(reader.pages),'passed':passed,'text':text,
        'sha256':hashlib.sha256(path.read_bytes()).hexdigest()})
report={'engine':'pypdf '+pypdf.__version__,'renderer':'PDFium','results':results,'passed':all(result['passed'] for result in results)}
(root/'output/android/desktop-pdf-verification.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
if not report['passed']:raise SystemExit(1)
