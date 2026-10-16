import {state} from './state.js';

export function applyAppearance(){
  const appearance=state.settings.appearance==='Light'?'Light':'Dark';
  document.documentElement.dataset.appearance=appearance;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content',appearance==='Light'?'#f4f5ee':'#101110');
  window.parent.postMessage({type:'appearance',value:appearance},location.origin);
}
