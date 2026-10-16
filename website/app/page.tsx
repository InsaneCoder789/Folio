import {Header,Footer} from '@/components/navigation';
import {Hero} from '@/components/hero';
import {Journey} from '@/components/journey';
import {RecognitionSpread,CardsSpread,ExportSpread,LibrarySpread} from '@/components/feature-spreads';
import {SupportingFeatures,Research} from '@/components/supporting';
export default function Home(){return <><Header/><main id="main"><Hero/><Journey/><RecognitionSpread/><CardsSpread/><ExportSpread/><LibrarySpread/><SupportingFeatures/><Research/></main><Footer/></>}
