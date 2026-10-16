import * as THREE from 'three';
export type SceneEngine={render:(progress:number,pointer:[number,number])=>void;resize:()=>void;dispose:()=>void};
export async function createDocumentScene(host:HTMLDivElement,image:string,mode:'hero'|'process',signal:AbortSignal):Promise<SceneEngine|null>{
 const renderer=new THREE.WebGLRenderer({alpha:true,antialias:true,powerPreference:'low-power'});
 renderer.setPixelRatio(Math.min(devicePixelRatio,1.5));renderer.setClearColor(0x101110,0);renderer.outputColorSpace=THREE.SRGBColorSpace;host.append(renderer.domElement);
 let texture:THREE.Texture;
 try{texture=await new THREE.TextureLoader().loadAsync(image)}catch(error){renderer.dispose();renderer.domElement.remove();throw error}
 if(signal.aborted){texture.dispose();renderer.dispose();renderer.domElement.remove();return null}
 texture.colorSpace=THREE.SRGBColorSpace;
 const scene=new THREE.Scene(),camera=new THREE.PerspectiveCamera(34,1,.1,50);camera.position.z=8.3;
 const geometry=new THREE.PlaneGeometry(8.5,5.67,72,48);
 const material=new THREE.ShaderMaterial({transparent:true,uniforms:{uImage:{value:texture},uProgress:{value:0},uStory:{value:mode==='process'?1:0},uPointer:{value:new THREE.Vector2()}},vertexShader:`
 uniform float uProgress;uniform float uStory;uniform vec2 uPointer;varying vec2 vUv;
 void main(){vUv=uv;vec3 p=position;float row=floor(uv.y*16.);float split=(1.-smoothstep(.05,.65,uProgress))*uStory;
 p.x+=sin(row*2.4)*split*.12;p.z+=cos(row*1.7)*split*.16;
 p.z+=sin(uv.x*3.14159)*.1*(1.-uProgress);p.x+=uPointer.x*.07;p.y+=uPointer.y*.035;
 gl_Position=projectionMatrix*modelViewMatrix*vec4(p,1.);}`,
 fragmentShader:`uniform sampler2D uImage;uniform float uProgress;uniform float uStory;varying vec2 vUv;
 void main(){vec4 tex=texture2D(uImage,vUv);vec3 c=tex.rgb;
 float edge=smoothstep(0.,.05,vUv.x)*smoothstep(0.,.05,1.-vUv.x)*smoothstep(0.,.05,vUv.y)*smoothstep(0.,.05,1.-vUv.y);
 float alpha=smoothstep(.015,.08,max(c.r,max(c.g,c.b)))*edge;gl_FragColor=vec4(c,alpha);
 #include <tonemapping_fragment>
 #include <colorspace_fragment>
 }`});
 const mesh=new THREE.Mesh(geometry,material);scene.add(mesh);let disposed=false;
 function resize(){const rect=host.getBoundingClientRect();if(rect.width&&rect.height){renderer.setSize(rect.width,rect.height,false);camera.aspect=rect.width/rect.height;camera.updateProjectionMatrix()}}
 function render(progress:number,pointer:[number,number]){if(disposed)return;material.uniforms.uProgress.value=progress;material.uniforms.uPointer.value.set(pointer[0],pointer[1]);mesh.position.set(mode==='hero'?(innerWidth<760?.4:1.55):.55,-.05,0);mesh.rotation.y=mode==='hero'?-.045:0;renderer.render(scene,camera)}
 resize();return{resize,render,dispose(){if(disposed)return;disposed=true;texture.dispose();geometry.dispose();material.dispose();renderer.dispose();renderer.domElement.remove()}};
}
