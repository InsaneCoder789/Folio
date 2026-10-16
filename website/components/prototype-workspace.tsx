'use client';
import Link from 'next/link';
import {useEffect,useRef,useState} from 'react';
import {prototypeScreens} from '@/lib/prototype-screens';

function playerUrl(id:string){return `/prototype-player${id==='07'?'?sample=cards':''}#${id}`}

export function PrototypeWorkspace(){
  const [active,setActive]=useState('01');
  const [source,setSource]=useState(playerUrl('01'));
  const [revision,setRevision]=useState(0);
  const [ready,setReady]=useState(false);
  const [appearance,setAppearance]=useState('Dark');
  const [phoneScale,setPhoneScale]=useState(1);
  const frame=useRef<HTMLDivElement>(null);
  const phone=useRef<HTMLIFrameElement>(null);
  const screen=prototypeScreens.find(item=>item.id===active);
  const title=screen?.title??(active==='boot'?'Boot animation':'Onboarding');
  function syncAppearance(){
    const value=phone.current?.contentDocument?.documentElement.dataset.appearance;
    if(value==='Light'||value==='Dark')setAppearance(value);
  }
  useEffect(()=>{
    setReady(true);
    const observer=new ResizeObserver(([entry])=>setPhoneScale(entry.contentRect.width/360));
    if(frame.current)observer.observe(frame.current);
    function receive(event:MessageEvent){
      if(event.origin!==location.origin||event.source!==phone.current?.contentWindow)return;
      if(event.data?.type==='appearance'&&['Light','Dark'].includes(event.data.value)){setAppearance(event.data.value);return}
      if(event.data?.type!=='screen')return;
      const id=event.data.id;
      if(prototypeScreens.some(item=>item.id===id)||['boot','intro-1','intro-2'].includes(id))setActive(id);
    }
    addEventListener('message',receive);
    syncAppearance();
    return()=>{observer.disconnect();removeEventListener('message',receive)};
  },[]);
  function select(id:string){setActive(id);setSource(playerUrl(id));setRevision(value=>value+1)}

  return <div className="prototype-page prototype-workspace" data-ready={ready} data-appearance={appearance}>
    <header className="workspace-header">
      <Link className="workspace-brand" href="/" aria-label="Folio home"><span className="brand">folio<span>.</span></span><small>Design workspace</small></Link>
      <span className="workspace-current">Prototype</span>
      <Link className="workspace-return" href="/">Editorial / Folio ↗</Link>
    </header>
    <main id="main" className="workspace-columns">
      <aside className="workspace-explorer" aria-label="Screen explorer">
        <h1>Screen explorer</h1>
        <nav aria-label="Prototype screens">{prototypeScreens.map(item=><button key={item.id} disabled={!ready} aria-current={active===item.id?'step':undefined} onClick={()=>select(item.id)}><span>{item.id}</span>{item.title}</button>)}</nav>
        <div className="workspace-entry"><button disabled={!ready} onClick={()=>select('boot')}>Replay boot animation</button><button disabled={!ready} onClick={()=>select('intro-1')}>View onboarding</button></div>
      </aside>
      <label className="workspace-mobile-picker">Screen explorer<select disabled={!ready} value={screen?.id??active} onChange={event=>select(event.target.value)}>{prototypeScreens.map(item=><option key={item.id} value={item.id}>{item.id} / {item.title}</option>)}<option value="boot">Boot animation</option><option value="intro-1">Onboarding</option><option value="intro-2">Onboarding / 2</option></select></label>
      <section className="workspace-device-stage" aria-label="Interactive phone preview">
        <div ref={frame} className="workspace-device"><iframe key={revision} ref={phone} onLoad={syncAppearance} className="prototype-frame" style={{width:360,height:800,transform:`scale(${phoneScale})`,transformOrigin:"top left"}} src={source} title="Folio interactive app prototype" aria-describedby="prototype-note"/></div>
        <p id="prototype-note">20:9 phone preview · simulated processing</p>
      </section>
      <aside className="workspace-details" aria-label="Selected screen details">
        <p className="workspace-screen-id">{screen?`S${screen.id} / SCREEN`:'FIRST RUN'}</p>
        <h2>{title}</h2>
        <p>{screen?.purpose??'A brief, skippable introduction to capture and searchable text.'}</p>
        <h3>Connections</h3>
        <p>{screen?.connections??'Boot → first-run intro → Library. Returning users skip the intro.'}</p>
        <h3>Design decisions</h3>
        <p>One primary next action. Generous preview space. Soft depth with the editorial palette and readable contrast.</p>
        <p>Every visible control changes state or leads somewhere useful.</p>
        <a href={playerUrl(active)} target="_blank" rel="noopener">Open phone view ↗</a>
      </aside>
    </main>
  </div>;
}
