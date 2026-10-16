'use client';
import {createContext,useContext,useEffect,useState} from 'react';
const MotionContext=createContext({reduced:false,toggle:()=>{}});
export function MotionProvider({children}:{children:React.ReactNode}){
 const[reduced,setReduced]=useState(false);
 useEffect(()=>{const media=matchMedia('(prefers-reduced-motion: reduce)');const sync=()=>{let local=false;try{local=sessionStorage.getItem('folio-next-motion')==='reduce'}catch{}setReduced(media.matches||local)};sync();media.addEventListener('change',sync);return()=>media.removeEventListener('change',sync)},[]);
 useEffect(()=>{document.documentElement.dataset.motion=reduced?'reduce':'full'},[reduced]);
 function toggle(){setReduced(value=>{try{sessionStorage.setItem('folio-next-motion',!value?'reduce':'full')}catch{}return !value})}
 return <MotionContext.Provider value={{reduced,toggle}}>{children}</MotionContext.Provider>
}
export function useMotionPreference(){return useContext(MotionContext)}
export function MotionToggle(){const{reduced,toggle}=useMotionPreference();return <button className="motion-toggle" onClick={toggle} aria-pressed={reduced}>{reduced?'Enable motion':'Reduce motion'}</button>}
