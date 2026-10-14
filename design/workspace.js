import { screens } from './prototype/screens.js';
const $=s=>document.querySelector(s);
const line='<div class="mini-line"></div>';
function mini(id){
 const page=`<div class="mini-page">${line.repeat(7)}</div>`;
 if(id==='03')return '<div class="mini-camera"><div></div></div>';
 if(['04','05','09','11','15'].includes(id))return page+'<div class="mini-tabs"></div>'+line;
 if(['01','08','12','13'].includes(id))return '<div class="mini-tabs"></div>'+Array(3).fill(`<div class="mini-block">${line.repeat(2)}</div>`).join('');
 if(id==='06')return `<div class="mini-block"></div>${line.repeat(9)}`;
 if(id==='07')return `<div class="mini-cards">${'<div class="mini-block"></div>'.repeat(4)}</div>${line.repeat(3)}`;
 return `${line}<div class="mini-block"></div><div class="mini-block"></div>${line.repeat(3)}`;
}
$('#screen-grid').innerHTML=screens.map(s=>`<button class="wire-card" data-open="${s.id}" aria-label="Explore S${s.id} ${s.title}"><div class="mini-phone"><div class="mini-status"><span>9:41</span><span>● ▰</span></div><div class="mini-title"></div>${mini(s.id)}<div class="mini-primary"></div></div><span>S${s.id} / ${s.title}</span><small>${s.purpose}</small></button>`).join('');
$('#routes').innerHTML=screens.map(s=>`<button data-open="${s.id}"><span>${s.id}</span>${s.title}</button>`).join('');
function mode(proto){$('#wireframe').hidden=proto;$('#prototype-view').hidden=!proto;$('#wire-tab').setAttribute('aria-pressed',!proto);$('#prototype-tab').setAttribute('aria-pressed',proto);window.scrollTo(0,0);}
function detail(id){const s=screens.find(s=>s.id===id);$('#screen-id').textContent=s?`S${s.id} / SCREEN`:'FIRST RUN';$('#screen-title').textContent=s?.title??(id==='boot'?'Boot animation':'Onboarding');$('#screen-purpose').textContent=s?.purpose??'A brief, skippable introduction to capture and searchable text.';$('#screen-connections').textContent=s?.connections??'Boot → first-run intro → Library. Returning users skip the intro.';$('#open-phone').href=`prototype/index.html#${id}`;document.querySelectorAll('#routes button').forEach(b=>b.classList.toggle('active',b.dataset.open===id));}
document.addEventListener('click',e=>{const b=e.target.closest('[data-open]');if(b){mode(true);$('#phone').src=`prototype/index.html${b.dataset.open==='07'?'?sample=cards':''}#${b.dataset.open}`;detail(b.dataset.open);}});
$('#wire-tab').onclick=()=>mode(false);$('#prototype-tab').onclick=()=>mode(true);
window.addEventListener('message',e=>{if(e.origin===location.origin&&e.data?.type==='screen')detail(e.data.id);});
detail('01');
