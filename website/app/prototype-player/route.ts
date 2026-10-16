// Isolate the original DOM-based prototype from the editorial's React tree.
// Its interaction modules are preserved verbatim; editorial.css only changes presentation.
const document=`<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="theme-color" content="#101110">
<base href="/prototype/">
<title>Folio · Interactive prototype</title>
<link rel="icon" href="assets/logo.png">
<link rel="stylesheet" href="style.css">
<link rel="stylesheet" href="editorial.css">
</head><body>
<div id="app"></div><div id="overlay"></div><div id="toast" role="status" aria-live="polite"></div>
<script type="module" src="app.js"></script>
</body></html>`;

export function GET(){
  return new Response(document,{headers:{
    'Content-Type':'text/html; charset=utf-8',
    'X-Content-Type-Options':'nosniff',
    'X-Robots-Tag':'noindex',
    'Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data: blob:; connect-src 'none'; frame-ancestors 'self'; base-uri 'self'; form-action 'none'",
  }});
}
