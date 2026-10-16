import type {Metadata} from 'next';
import {Header} from '@/components/navigation';

export const metadata:Metadata={
  title:'Interactive prototype',
  description:'Explore the original 15-screen Folio design prototype, styled with the editorial typography and palette. Camera, OCR and PDF generation are simulated.',
};

export default function PrototypePage(){
  return <div className="prototype-page">
    <Header/>
    <main id="main" className="prototype-main">
      <div className="prototype-caption">
        <h1>App prototype<span>.</span></h1>
        <p id="prototype-note">Interactive design preview. Camera, OCR and PDF generation are simulated.</p>
      </div>
      <div className="prototype-stage">
        <iframe className="prototype-frame" src="/prototype-player" title="Folio interactive app prototype" aria-describedby="prototype-note"/>
      </div>
    </main>
  </div>;
}
