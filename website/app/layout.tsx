import type {Metadata} from 'next';
import localFont from 'next/font/local';
import {MotionProvider} from '@/components/preferences';
import './globals.css';
const display=localFont({src:'../public/fonts/anton.ttf',weight:'400',display:'swap',variable:'--font-display'});
const body=localFont({src:[{path:'../public/fonts/jakarta-regular.ttf',weight:'400'},{path:'../public/fonts/jakarta-medium.ttf',weight:'500'},{path:'../public/fonts/jakarta-semibold.ttf',weight:'600'},{path:'../public/fonts/jakarta-bold.ttf',weight:'700'}],display:'swap',variable:'--font-body'});
export const metadata:Metadata={title:{default:'Folio — Keep what matters.',template:'%s — Folio'},description:'Capture, correct, review, organize and export. Explore the complete native Android scanner workflow and its ongoing handwriting OCR research.'};
export default function Layout({children}:{children:React.ReactNode}){return <html lang="en"><body className={`${display.variable} ${body.variable}`}><MotionProvider><a className="skip" href="#main">Skip to content</a>{children}</MotionProvider></body></html>}
