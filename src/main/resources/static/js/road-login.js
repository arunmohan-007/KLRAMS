/* ============================================================
   KLRAMS · road-login.js
   A motorway at dusk: lane markings stream toward the camera,
   an overhead SIGN GANTRY straddles the road (the login mounts
   onto it in the DOM), and roadside signs flick past.
   Pure Three.js r128. Self-contained. CSS fallback on failure.
   ============================================================ */
(function (global) {
  'use strict';
  const AMBER = 0xffa630;   // signal amber
  const SKY_T = 0x14233b;   // dusk sky top
  const SKY_B = 0x2a3b56;   // horizon glow
  const ASPHALT = 0x10151f;

  function cssFallback(){
    if(document.getElementById('rl-fallback'))return;
    document.body.classList.add('no-webgl');
    const st=document.createElement('style');
    st.textContent="#rl-fallback{position:fixed;inset:0;z-index:0;background:linear-gradient(180deg,#14233b 0%,#2a3b56 42%,#10151f 60%);overflow:hidden}#rl-fallback .rd{position:absolute;left:50%;bottom:-8%;width:64%;height:120%;transform:translateX(-50%) perspective(440px) rotateX(60deg);transform-origin:bottom;background:#0e131c}#rl-fallback .dl{position:absolute;left:50%;top:0;width:2%;height:100%;transform:translateX(-50%);background:repeating-linear-gradient(180deg,#f2f5fa 0 6%,transparent 6% 13%);animation:rls .6s linear infinite}@keyframes rls{to{background-position:0 24px}}";
    document.head.appendChild(st);
    const d=document.createElement('div');d.id='rl-fallback';d.innerHTML='<div class="rd"><div class="dl"></div></div>';
    document.body.insertBefore(d,document.body.firstChild);
  }

  function init(canvasId){
    const canvas=document.getElementById(canvasId); if(!canvas)return;
    if(!global.THREE){console.warn('[RL] no three');cssFallback();return;}
    let renderer;
    try{renderer=new THREE.WebGLRenderer({canvas,antialias:true,alpha:false,powerPreference:'high-performance'});}
    catch(e){cssFallback();return;}
    try{build(renderer,canvas);console.info('[RL] road scene running');}
    catch(e){console.error('[RL] err',e);cssFallback();}
  }

  function build(renderer,canvas){
    const reduced=global.matchMedia&&matchMedia('(prefers-reduced-motion: reduce)').matches;
    renderer.setPixelRatio(Math.min(global.devicePixelRatio||1,1.7));

    const scene=new THREE.Scene();
    scene.fog=new THREE.Fog(0x1b2740,30,120);

    // gradient sky (big plane far back)
    const skyCanvas=document.createElement('canvas');skyCanvas.width=4;skyCanvas.height=256;
    const sctx=skyCanvas.getContext('2d');const grad=sctx.createLinearGradient(0,0,0,256);
    grad.addColorStop(0,'#14233b');grad.addColorStop(.55,'#2a3b56');grad.addColorStop(.78,'#5a4a52');grad.addColorStop(1,'#3a3340');
    sctx.fillStyle=grad;sctx.fillRect(0,0,4,256);
    const skyTex=new THREE.CanvasTexture(skyCanvas);
    const sky=new THREE.Mesh(new THREE.PlaneGeometry(600,300),new THREE.MeshBasicMaterial({map:skyTex,depthWrite:false,fog:false}));
    sky.position.set(0,40,-160);scene.add(sky);
    // sun/horizon glow
    const glow=new THREE.Mesh(new THREE.CircleGeometry(26,40),new THREE.MeshBasicMaterial({color:0xffb04a,transparent:true,opacity:.5,blending:THREE.AdditiveBlending,depthWrite:false,fog:false}));
    glow.position.set(0,9,-150);scene.add(glow);

    const camera=new THREE.PerspectiveCamera(52,2,0.1,400);
    camera.position.set(0,3.4,12);camera.lookAt(0,3.0,-40);

    scene.add(new THREE.HemisphereLight(0x4a5e80,0x0a0e16,0.9));
    const key=new THREE.DirectionalLight(0xffd9a8,0.9);key.position.set(-8,16,-6);scene.add(key);
    const amberL=new THREE.PointLight(AMBER,1.4,60);amberL.position.set(0,8,-10);scene.add(amberL);

    // ---- road ----
    const road=new THREE.Mesh(new THREE.PlaneGeometry(20,400),new THREE.MeshStandardMaterial({color:ASPHALT,roughness:.95,metalness:.05}));
    road.rotation.x=-Math.PI/2;road.position.z=-150;scene.add(road);
    // shoulders
    [-11,11].forEach(x=>{const s=new THREE.Mesh(new THREE.PlaneGeometry(4,400),new THREE.MeshStandardMaterial({color:0x0a0e15,roughness:1}));s.rotation.x=-Math.PI/2;s.position.set(x,-.01,-150);scene.add(s);});
    // solid edge lines
    [-4.6,4.6].forEach(x=>{const e=new THREE.Mesh(new THREE.PlaneGeometry(0.2,400),new THREE.MeshBasicMaterial({color:0xeef3fb}));e.rotation.x=-Math.PI/2;e.position.set(x,.02,-150);scene.add(e);});

    // dashed lane lines (two interior lanes)
    const RECYCLE=140,GAP=6,N=Math.ceil(RECYCLE/GAP)+4;
    const dashMat=new THREE.MeshBasicMaterial({color:0xf2f6fc});
    const dashes=[];
    [0].forEach(x=>{for(let i=0;i<N;i++){const d=new THREE.Mesh(new THREE.PlaneGeometry(0.22,2.8),dashMat);d.rotation.x=-Math.PI/2;d.position.set(x,.02,12-i*GAP);scene.add(d);dashes.push(d);}});
    const laneMat=new THREE.MeshBasicMaterial({color:AMBER,transparent:true,opacity:.4});
    [-2.3,2.3].forEach(x=>{for(let i=0;i<N;i++){const d=new THREE.Mesh(new THREE.PlaneGeometry(0.14,2.0),laneMat);d.rotation.x=-Math.PI/2;d.position.set(x,.02,12-i*GAP);scene.add(d);dashes.push(d);}});

    // ---- overhead SIGN GANTRY (frames the login in DOM; here we build the steel) ----
    const gantry=new THREE.Group();gantry.position.set(0,0,-16);scene.add(gantry);
    const steel=new THREE.MeshStandardMaterial({color:0x39414d,roughness:.6,metalness:.5});
    // two posts
    [-6.2,6.2].forEach(x=>{const post=new THREE.Mesh(new THREE.CylinderGeometry(0.22,0.26,11,12),steel);post.position.set(x,5.5,0);gantry.add(post);
      const base=new THREE.Mesh(new THREE.BoxGeometry(0.8,0.4,0.8),steel);base.position.set(x,0.2,0);gantry.add(base);});
    // top truss beam
    const beam=new THREE.Mesh(new THREE.BoxGeometry(13.2,0.5,0.6),steel);beam.position.set(0,10.6,0);gantry.add(beam);
    const beam2=new THREE.Mesh(new THREE.BoxGeometry(13.2,0.3,0.5),steel);beam2.position.set(0,9.9,0);gantry.add(beam2);
    // amber marker lights along the beam
    for(let i=-6;i<=6;i+=2){const m=new THREE.Mesh(new THREE.SphereGeometry(0.12,8,8),new THREE.MeshBasicMaterial({color:AMBER}));m.position.set(i,10.25,0.32);gantry.add(m);}

    // ---- roadside signs flicking past ----
    const signMat=new THREE.MeshStandardMaterial({color:0x1d6b3a,roughness:.5,emissive:0x0a3018,emissiveIntensity:.3});
    const signPost=new THREE.MeshStandardMaterial({color:0x222932,roughness:.7,metalness:.4});
    const signs=[];
    const SIGN_RECYCLE=160;
    for(let i=0;i<6;i++){
      const grp=new THREE.Group();
      const side=i%2?1:-1;
      const post=new THREE.Mesh(new THREE.CylinderGeometry(0.1,0.1,5,8),signPost);post.position.y=2.5;grp.add(post);
      const board=new THREE.Mesh(new THREE.BoxGeometry(2.6,1.6,0.1),signMat);board.position.y=4.6;grp.add(board);
      const brd2=new THREE.Mesh(new THREE.PlaneGeometry(2.3,1.3),new THREE.MeshBasicMaterial({color:0x3fe07a,transparent:true,opacity:.16}));brd2.position.set(0,4.6,0.06);grp.add(brd2);
      grp.position.set(side*7.4,0,-(i*SIGN_RECYCLE/6)-10);
      grp.rotation.y=side>0?-0.25:0.25;
      scene.add(grp);signs.push(grp);
    }

    // distant ambient lights (oncoming traffic glow)
    const blobs=[];
    for(let i=0;i<8;i++){const b=new THREE.Mesh(new THREE.SphereGeometry(0.18,8,8),new THREE.MeshBasicMaterial({color:0xfff0d0,transparent:true,opacity:.8}));b.position.set((Math.random()*2-1)*3-3,1.0,-(Math.random()*120+30));scene.add(b);blobs.push(b);}

    function resize(){const w=canvas.clientWidth,h=canvas.clientHeight;if(!w||!h)return;renderer.setSize(w,h,false);camera.aspect=w/h;camera.updateProjectionMatrix();}
    global.addEventListener('resize',resize);resize();

    let tx=0,px=0;
    if(!reduced)global.addEventListener('pointermove',e=>{tx=e.clientX/global.innerWidth-0.5;},{passive:true});

    let t0=performance.now(),running=true,raf;
    const SPEED=46;
    function frame(now){
      if(!running)return;const dt=Math.min((now-t0)/1000,0.05);t0=now;
      dashes.forEach(d=>{d.position.z+=SPEED*dt;if(d.position.z>13)d.position.z-=RECYCLE;});
      signs.forEach(g=>{g.position.z+=SPEED*dt;if(g.position.z>14)g.position.z-=SIGN_RECYCLE;});
      blobs.forEach(b=>{b.position.z+=SPEED*1.3*dt;if(b.position.z>14){b.position.z-=150;b.position.x=(Math.random()*2-1)*3-3;}});
      px+=(tx-px)*0.04;
      camera.position.x=px*2.2;
      camera.position.y=3.4+Math.sin(now/2400)*0.06;
      camera.lookAt(px*1.2,3.0,-40);
      renderer.render(scene,camera);
      raf=requestAnimationFrame(frame);
    }
    if(reduced){renderer.render(scene,camera);return;}
    raf=requestAnimationFrame(frame);
    document.addEventListener('visibilitychange',()=>{if(document.hidden){running=false;if(raf)cancelAnimationFrame(raf);}else if(!running){running=true;t0=performance.now();raf=requestAnimationFrame(frame);}});
  }

  global.RoadLogin={init};
})(window);
