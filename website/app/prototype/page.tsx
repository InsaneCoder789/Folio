import type {Metadata} from 'next';
import {PrototypeWorkspace} from '@/components/prototype-workspace';

export const metadata:Metadata={
  title:'Interactive prototype',
  description:'Explore the original 15-screen Folio design prototype, styled with the editorial typography and palette. Camera, OCR and PDF generation are simulated.',
};

export default function PrototypePage(){
  return <PrototypeWorkspace/>;
}
