'use client';
import Image from 'next/image';
import {useEffect,useRef,useState} from 'react';
import type {SceneEngine} from '@/lib/scene-engine';
import {useMotionPreference} from './preferences';
export function DocumentScene({image,alt,mode='process',chapter=0}:{image:string;alt:string;mode?:'hero'|'process';chapter?:number}){
 const host=useRef<HTMLDivElement>(null),root=useRef<HTMLDivElement>(null);const[ready,setReady]=useState(false);const{reduced}=useMotionPreference();
 useEffect(()=>{
  const element=host.current,container=root.current;if(!element||!container)return;setReady(false);
  if(reduced||new URLSearchParams(location.search).has('no-webgl')||(mode==='process'&&matchMedia('(max-width:760px)').matches))return;
  const abort=new AbortController();let engine:SceneEngine|null=null,visible=false,loading=false,frame=0,frames=0,pointer:[number,number]=[0,0];
  const clamp=(v:number)=>Math.max(0,Math.min(1,v));
  function render(){frame=0;if(!engine||!visible||document.hidden||abort.signal.aborted)return;
   const track=container!.closest('[data-scene-track]') as HTMLElement|null;
   const rect=(track??container!).getBoundingClientRect();const total=clamp(-rect.top/Math.max(1,rect.height-innerHeight));
   engine.render(mode==='hero'?total*.4:clamp(total*6-chapter),pointer);container!.dataset.frames=String(++frames);setReady(true);
  }
  const request=()=>{if(!frame&&visible&&!document.hidden)frame=requestAnimationFrame(render)};
  async function initialize(){if(engine||loading)return;loading=true;try{const module=await import('@/lib/scene-engine');if(abort.signal.aborted)return;const next=await module.createDocumentScene(element!,image,mode,abort.signal);if(abort.signal.aborted){next?.dispose();return}engine=next;request()}catch{if(!abort.signal.aborted)container!.dataset.webgl='fallback'}finally{loading=false}}
  const observer=new IntersectionObserver(([entry])=>{visible=entry.isIntersecting;if(visible){void initialize();request()}},{rootMargin:'100px'});observer.observe(container);
  const resize=new ResizeObserver(()=>{engine?.resize();request()});resize.observe(container);
  const move=(event:PointerEvent)=>{if(mode!=='hero'||!visible)return;pointer=[event.clientX/innerWidth-.5,-event.clientY/innerHeight+.5];request()};
  window.addEventListener('scroll',request,{passive:true});window.addEventListener('pointermove',move,{passive:true});document.addEventListener('visibilitychange',request);
  return()=>{abort.abort();cancelAnimationFrame(frame);observer.disconnect();resize.disconnect();window.removeEventListener('scroll',request);window.removeEventListener('pointermove',move);document.removeEventListener('visibilitychange',request);engine?.dispose()};
 },[image,mode,chapter,reduced]);
 return <div ref={root} className={`document-scene ${mode==='hero'?'hero-scene':''}`} data-webgl={ready?'ready':'fallback'} data-artwork={image}><Image className="scene-image" src={image} alt={alt} fill sizes={mode==='hero'?'100vw':'(max-width:760px) 100vw, 75vw'} priority={mode==='hero'}/><div ref={host} className="scene-canvas" aria-hidden="true"/></div>
}
