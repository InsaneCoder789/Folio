import type {Metadata} from 'next';
import Link from 'next/link';
import {Header,Footer} from '@/components/navigation';
import {MotionStoryboard} from '@/components/motion-storyboard';
export const metadata:Metadata={title:'Motion storyboard',description:'Scrub through the six editorial workflow chapters and review their motion, triggers and reduced-motion alternatives.'};
export default function MotionPage(){return <><Header/><main id="main"><section className="story-intro section-shell"><span className="chapter-index">EDITORIAL MOTION / INTERACTIVE PLAN</span><h1>SCROLL.<br/><span>WITH INTENT.</span></h1><p>A digital storyboard for the website. Scrub the six chapters to see their artwork and motion direction. The native app wireframe remains in its local development test bench.</p><Link className="line-link" href="/#workflow">Experience the workflow →</Link></section><MotionStoryboard/></main><Footer/></>}
