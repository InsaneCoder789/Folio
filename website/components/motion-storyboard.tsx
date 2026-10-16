'use client';
import Image from 'next/image';
import {useState} from 'react';
import {chapters} from '@/lib/content';
const movement=[
 'The source paper shifts a few pixels against the headline, with soft edge fading and no moving scanner line.',
 'The paper plane gently settles toward a rectified page. This is artwork; it does not perform browser correction.',
 'Handwritten fragments move into the review chapter. The text remains fixed and readable.',
 'A new pair of card specimens enters the spread. Native layout diagrams explain confirmed pairing separately.',
 'The scan and typeset document are shown together. Output controls change the explanatory text.',
 'The indexed archive closes the journey. The page continues into supporting features and measured research.'
];
export function MotionStoryboard(){const[progress,setProgress]=useState(0);const index=Math.min(5,Math.floor(progress/100*6));const chapter=chapters[index];
 function select(next:number){setProgress(Math.max(0,Math.min(5,next))*100/6+.1)}
 return <section className="storyboard section-shell" aria-label="Interactive editorial motion storyboard"><div className="story-stage"><div className="story-art"><Image src={chapter.image} alt={chapter.alt} fill sizes="(max-width:760px) 100vw, 75vw"/></div><h2>{chapter.title[0]}<br/><span>{chapter.title[1]}</span></h2><p>{chapter.lead}</p><div className="story-progress" style={{transform:`scaleX(${progress/100})`}} aria-hidden="true"/></div><div className="story-controls"><button disabled={index===0} onClick={()=>select(index-1)}>← Previous</button><label htmlFor="story-scrub">SCROLL POSITION<input id="story-scrub" type="range" min="0" max="100" step=".1" value={progress} onChange={event=>setProgress(Number(event.target.value))}/><output htmlFor="story-scrub">{Math.round(progress)}%</output></label><button disabled={index===5} onClick={()=>select(index+1)}>Next →</button></div><nav className="story-chapters" aria-label="Storyboard chapters">{chapters.map((item,i)=><button key={item.id} onClick={()=>select(i)} aria-pressed={i===index}>{item.label}</button>)}</nav><div className="story-notes" aria-live="polite"><div><h3>TRIGGER</h3><p>Desktop scroll through one sixth of the pinned workflow. Chapter controls support direct navigation.</p></div><div><h3>MOVEMENT</h3><p>{movement[index]}</p></div><div><h3>QUIET VERSION</h3><p>Reduced motion and mobile use a static, unpinned chapter with direct controls. Artwork and all information remain accessible.</p></div></div></section>
}
