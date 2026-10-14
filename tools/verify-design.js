async (page) => {
  const root='/Users/rohanc/Documents/ChatGPT/OCR Scanner';
  const base='http://127.0.0.1:8770/prototype/index.html';
  const results=[];const errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  const assert=(value,message)=>{if(!value)throw new Error(message)};
  const route=async id=>{await page.waitForFunction(id=>document.querySelector('#app')?.dataset.route===id,id);};
  const click=async action=>{const modal=page.locator(`#overlay [data-action="${action}"]`);await (await modal.count()?modal:page.locator(`[data-action="${action}"]`)).first().click();};
  const reset=async hash=>{await page.goto(base+'#01');await page.evaluate(()=>localStorage.removeItem('ocr-design-v1'));await page.goto(base+'#'+hash);await page.reload();};
  await page.setViewportSize({width:390,height:844});
  await reset('01');
  for(let i=1;i<=15;i++){
    const id=String(i).padStart(2,'0');await page.goto(base+'#'+id);await route(id);
    const overflow=await page.evaluate(()=>({body:document.documentElement.scrollWidth>innerWidth+1,screen:document.querySelector('.screen')?.scrollWidth>document.querySelector('.screen')?.clientWidth+1}));
    assert(!overflow.body&&!overflow.screen,'Horizontal overflow on S'+id);
    await page.screenshot({path:root+`/tmp/design-qa/s${id}.png`});
  }
  results.push('15 screen routes render at 390 x 844 without horizontal overflow');
  await reset('01');
  await click('new-scan');await route('02');await click('camera');await route('03');
  assert(await page.locator('[data-action="done-capture"]').isDisabled(),'Done must be disabled before capture');
  await click('capture');await click('capture');await click('done-capture');await route('04');
  const corner=page.getByRole('button',{name:'Corner 1; use arrow keys to adjust'});
  await corner.press('ArrowRight');
  assert(await page.evaluate(()=>window.__design.state.captures[0].corners[0][0]===9),'Keyboard corner adjustment failed');
  await click('apply-crop');await route('05');
  await click('choose:filter:Grayscale');assert(await page.locator('[data-action="choose:filter:Grayscale"]').getAttribute('aria-pressed')==='true','Filter selection failed');
  await click('nav:15');await route('15');await page.locator('#lesson-select').selectOption('Edges');await click('nav:05');await route('05');
  await click('ocr');await route('06');await page.locator('#ocr-text').fill('Reviewed project notes.\n\nA clear document for the archive.');
  await click('ocr-continue');await route('08');await page.locator('#document-title').fill('Reviewed archive');await click('move-down:0');
  await click('nav:09');await route('09');await click('nav:10');await route('10');
  await click('generate');await route('11');assert(await page.locator('.pdf-image-page').count()===1,'Searchable mode must preserve scan');
  await click('save-document');await route('12');
  await page.reload();await route('12');assert(await page.locator('.detail-title').innerText()==='Reviewed archive','Saved document did not survive reload');
  await click('nav:11');await click('nav:10');await click('choose:output:text');await click('generate');await route('11');
  assert(await page.locator('.typeset-page').count()===1,'Regenerated text mode must use typeset page');
  assert((await page.locator('.typeset-page').innerText()).includes('Reviewed project notes'),'Reviewed text missing from regenerated preview');
  await page.screenshot({path:root+'/tmp/design-qa/regenerated.png'});
  await click('save-document');await route('12');await click('back');await route('01');await click('nav:13');
  await page.locator('#search-input').fill('Reviewed');assert(await page.locator('.search-result').count()===1,'Local OCR/title search failed');
  await page.locator('#search-input').fill('xyz-no-match');assert(await page.getByText('No matches yet').count()===1,'No results state missing');
  results.push('Two-page document journey, corner adjustment, filters, learning lab, text correction, reorder, both PDF modes, save/reload and search pass');
  // A saved-document visit must not destroy an unrelated draft.
  await page.goto(base+'#01');await click('new-scan');
  if(await page.locator('[data-action="replace-draft"]').count())await click('replace-draft');
  await click('import');await click('sample-import');
  await click('apply-crop');await click('ocr');await route('06');await click('ocr-continue');await route('08');
  await page.locator('#document-title').fill('Unfinished draft');await click('back');await route('01');
  await page.locator('[data-action="open:notes"]').click();await route('12');await click('back');await route('01');
  await click('resume-draft');await route('08');assert(await page.locator('#document-title').inputValue()==='Unfinished draft','Opening saved document destroyed draft');
  results.push('Unrelated draft survives opening a saved document');
  await reset('01');await click('new-scan');await click('choose:mode:cards');await click('camera');await click('capture');await click('capture');await click('done-capture');await click('apply-crop');await click('ocr');await route('06');await click('ocr-continue');await route('07');
  await click('confirm-pairs');assert(await page.getByRole('dialog').count()===1,'Unpaired sides must require review');await click('close-sheet');
  await page.locator('#pair-0').fill('Studio');await page.locator('#pair-1').fill('Studio');await click('confirm-pairs');await route('08');
  await click('nav:09');await click('choose:template:Side by side');await page.screenshot({path:root+'/tmp/design-qa/paired-cards.png'});
  assert(await page.locator('.layout-grid .id-paper').count()===2,'Both card sides must appear on page');
  results.push('Paired-card journey and missing-pair recovery pass');
  await reset('01');await click('new-scan');await click('choose:mode:cards');await click('choose:workflow:multiple');await click('camera');await click('capture');await click('capture');await click('done-capture');await click('apply-crop');await click('ocr');await route('06');await click('ocr-continue');await route('07');await click('confirm-pairs');await route('08');await click('nav:09');await click('choose:template:Grid');
  assert((await page.locator('.caption').innerText()).includes('2 sheets'),'Six cards must overflow four-card grid');
  await page.screenshot({path:root+'/tmp/design-qa/card-overflow.png'});
  await click('nav:10');await click('generate');await route('11');
  assert((await page.locator('.preview-toolbar').innerText()).includes('1/2'),'Card preview must count PDF sheets');
  assert(await page.locator('.layout-grid .id-paper').count()===4,'First grid sheet should hold four cards');
  await click('next-page');assert(await page.locator('.layout-grid .id-paper').count()===2,'Second grid sheet should hold remaining two cards');
  results.push('Multiple-card capture, single-sided confirmation, grid overflow and PDF sheet pagination pass');
  // Design failure scenarios are deliberate local simulation states.
  await page.goto(base+'?scenario=ocr-fail#05');await click('ocr');await page.getByText('Text could not be read').waitFor();await click('ocr');await route('06');
  await page.goto(base+'?scenario=pdf-fail#10');await click('generate');await page.getByText('Preview could not be created').waitFor();await click('generate');await route('11');
  await page.goto(base+'?scenario=permission-denied#02');await click('camera');await page.getByText('Camera permission needed').waitFor();await click('camera');await route('03');
  results.push('OCR failure/retry, PDF failure/retry and camera denial/retry pass');
  await page.goto(base+'#05');await click('ocr');await click('cancel-job');await page.waitForTimeout(750);assert(await page.locator('#app').getAttribute('data-route')==='05','Cancelled processing navigated later');
  results.push('Cancelled processing cannot publish a stale result');
  await reset('intro-1');await page.getByRole('button',{name:'Continue',exact:true}).click();await route('intro-2');await click('finish-intro');await route('01');await page.goto(base+'#boot');await route('01');
  results.push('First-run onboarding and returning-user skip pass');
  const narrow=[];
  for(const size of [{width:320,height:700},{width:390,height:680},{width:430,height:932}]){
    await page.setViewportSize(size);
    for(const id of ['01','02','04','05','06','07','08','09','10','14','intro-1','intro-2']){
      await page.goto(base+'#'+id);await route(id);
      const overflow=await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth+1||document.querySelector('.screen')?.scrollWidth>document.querySelector('.screen')?.clientWidth+1);
      assert(!overflow,`Overflow at ${size.width}x${size.height}, ${id}`);
    }narrow.push(`${size.width}x${size.height}`);
  }
  results.push('Narrow/short/tall viewport routes pass: '+narrow.join(', '));
  await page.emulateMedia({reducedMotion:'reduce'});await page.goto(base+'#boot');await route('01');
  assert(await page.evaluate(()=>matchMedia('(prefers-reduced-motion: reduce)').matches),'Reduced motion not enabled');
  results.push('Reduced-motion boot reaches returning-user library');
  await page.emulateMedia({reducedMotion:'no-preference'});await page.setViewportSize({width:1440,height:1000});await page.goto('http://127.0.0.1:8770/');
  assert(await page.locator('.wire-card').count()===15,'Wireframe inventory not 15 screens');await page.screenshot({path:root+'/tmp/design-qa/wireframe.png',fullPage:true});
  await page.locator('#prototype-tab').click();await page.screenshot({path:root+'/tmp/design-qa/workspace.png',fullPage:true});
  results.push('Desktop wireframe and interactive workspace render');
  assert(errors.length===0,'Browser runtime errors: '+errors.join('; '));
  const report={date:'2026-10-14',scope:'Frontend design prototype, not native Android or live OCR/PDF',results,runtimeErrors:errors};
  return report;
}
