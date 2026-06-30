/* ============================================================
   KLRAMS · nsv-hero.js  (v3 — two-phase cinematic)
   Phase 1: a survey network draws itself across a dark grid
            (glowing nodes + scanning lines — "the network mapped").
   Transition: the camera dives toward the surface.
   Phase 2: an NSV survey vehicle drives the road with a LiDAR sweep.

   init(canvasId, { mode:'hero'|'login', intro:true|false })
   - mode 'hero'  -> full two-phase intro then loop
   - mode 'login' -> skips straight to the driving loop (calmer bg)
   - No WebGL/Three.js -> animated CSS road injected (always motion).
   - prefers-reduced-motion -> single static frame.
   - Pauses while tab hidden.  window.NSVHero.replay() restarts intro.
   ============================================================ */
(function (global) {
  'use strict';

  const LANE   = 0x9ee23a;
  const SCAN   = 0x2bd478;
  const VOID    = 0x06120d;
  const ASPHALT = 0x122017;

  let _replay = null;

  function cssFallback() {
    if (document.getElementById('nsv-fallback')) return;
    document.body.classList.add('no-webgl');
    const style = document.createElement('style');
    style.textContent = [
      '#nsv-fallback{position:fixed;inset:0;z-index:0;overflow:hidden;background:',
      'radial-gradient(1200px 600px at 50% 120%,#13202b 0%,transparent 60%),',
      'linear-gradient(180deg,#070a0f 0%,#0a1018 55%,#0d1a18 100%)}',
      '#nsv-fallback .road{position:absolute;left:50%;bottom:-10%;width:60%;height:130%;',
      'transform:translateX(-50%) perspective(420px) rotateX(58deg);transform-origin:bottom center;',
      'background:linear-gradient(90deg,transparent 0,#10161e 8%,#10161e 92%,transparent 100%)}',
      '#nsv-fallback .dash{position:absolute;left:50%;top:0;width:2.2%;height:100%;transform:translateX(-50%);',
      'background:repeating-linear-gradient(180deg,#f4f7fb 0 5%,transparent 5% 11%);',
      'animation:nsvscroll .6s linear infinite;opacity:.9}',
      '#nsv-fallback .edge{position:absolute;top:0;height:100%;width:1.2%;background:rgba(158,226,58,.6);box-shadow:0 0 14px rgba(158,226,58,.5)}',
      '#nsv-fallback .edge.l{left:7%}#nsv-fallback .edge.r{right:7%}',
      '@keyframes nsvscroll{to{background-position:0 22px}}'
    ].join('');
    document.head.appendChild(style);
    const box = document.createElement('div'); box.id = 'nsv-fallback';
    box.innerHTML = '<div class="road"><div class="edge l"></div><div class="dash"></div><div class="edge r"></div></div>';
    document.body.insertBefore(box, document.body.firstChild);
  }

  function init(canvasId, opts) {
    opts = opts || {};
    const mode = opts.mode || 'login';
    const wantIntro = opts.intro !== false && mode === 'hero';
    const canvas = document.getElementById(canvasId);
    if (!canvas) return;
    if (!global.THREE) { console.warn('[NSV] Three.js not loaded — CSS road.'); cssFallback(); return; }
    let renderer;
    try { renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, powerPreference: 'high-performance' }); }
    catch (e) { console.warn('[NSV] WebGL unavailable — CSS road.', e); cssFallback(); return; }
    try { buildScene(renderer, canvas, mode, wantIntro); console.info('[NSV] scene running.'); }
    catch (e) { console.error('[NSV] scene error — CSS road.', e); cssFallback(); }
  }

  function buildScene(renderer, canvas, mode, wantIntro) {
    const reduced = global.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches;
    renderer.setPixelRatio(Math.min(global.devicePixelRatio || 1, 1.7));

    const scene = new THREE.Scene();
    scene.fog = new THREE.Fog(VOID, 22, 70);

    const camera = new THREE.PerspectiveCamera(mode === 'hero' ? 50 : 44, 2, 0.1, 400);

    // camera key positions
    const camMap   = new THREE.Vector3(0, 46, 30);    // high look-down over the network
    const camDrive = new THREE.Vector3(2.4, 2.6, 9.6);// behind the vehicle
    const lookMap   = new THREE.Vector3(0, 0, -8);
    const lookDrive = new THREE.Vector3(0, 1.05, -6);

    // ---- lights ----
    scene.add(new THREE.HemisphereLight(0x3a4763, 0x05070a, 0.85));
    const key = new THREE.DirectionalLight(0xffffff, 1.0); key.position.set(5, 14, 7); scene.add(key);
    const amberL = new THREE.PointLight(LANE, 1.5, 30); amberL.position.set(0, 3, 2); scene.add(amberL);
    const cyanL  = new THREE.PointLight(SCAN, 1.7, 26); cyanL.position.set(0, 2, -3); scene.add(cyanL);

    // =========================================================
    // PHASE 1 — survey network that draws itself
    // =========================================================
    const network = new THREE.Group(); scene.add(network);

    // faint grid
    const grid = new THREE.GridHelper(140, 40, 0x152330, 0x0e1722);
    grid.material.opacity = 0.25; grid.material.transparent = true;
    grid.position.y = 0.01; network.add(grid);

    // build a branching road graph on the ground plane
    const rng = mulberry(20260626);
    const nodes = [];
    for (let i = 0; i < 26; i++) nodes.push(new THREE.Vector3((rng()*2-1)*55, 0.05, (rng()*2-1)*55));
    // connect each node to a couple of nearest others -> network edges
    const edges = [];
    nodes.forEach((a, i) => {
      const near = nodes.map((b, j) => ({ j, d: a.distanceTo(b) })).filter(o => o.j !== i).sort((p, q) => p.d - q.d).slice(0, 2);
      near.forEach(o => { if (o.j > i) edges.push([i, o.j]); });
    });

    // edge line material (amber), revealed progressively
    const edgeMeshes = [];
    edges.forEach(([i, j]) => {
      const a = nodes[i], b = nodes[j];
      const geo = new THREE.BufferGeometry().setFromPoints([a, b]);
      const m = new THREE.LineBasicMaterial({ color: LANE, transparent: true, opacity: 0 });
      const line = new THREE.Line(geo, m);
      line.userData.len = a.distanceTo(b);
      network.add(line); edgeMeshes.push(line);
    });

    // node markers (cyan, pulse in)
    const nodeMeshes = [];
    nodes.forEach(p => {
      const m = new THREE.Mesh(new THREE.SphereGeometry(0.7, 12, 10),
        new THREE.MeshBasicMaterial({ color: SCAN, transparent: true, opacity: 0 }));
      m.position.copy(p); m.position.y = 0.4; network.add(m); nodeMeshes.push(m);
    });

    // radar sweep ring on the plane
    const ring = new THREE.Mesh(new THREE.RingGeometry(0.1, 2.2, 48),
      new THREE.MeshBasicMaterial({ color: SCAN, transparent: true, opacity: 0.0, side: THREE.DoubleSide, blending: THREE.AdditiveBlending, depthWrite: false }));
    ring.rotation.x = -Math.PI / 2; ring.position.y = 0.06; network.add(ring);

    // =========================================================
    // PHASE 2 — road + vehicle (built, hidden until transition)
    // =========================================================
    const drive = new THREE.Group(); drive.visible = false; scene.add(drive);

    const surface = new THREE.Mesh(new THREE.PlaneGeometry(12, 240),
      new THREE.MeshStandardMaterial({ color: ASPHALT, roughness: 0.92, metalness: 0.05 }));
    surface.rotation.x = -Math.PI / 2; surface.position.z = -90; drive.add(surface);
    [-5.4, 5.4].forEach(x => {
      const e = new THREE.Mesh(new THREE.PlaneGeometry(0.18, 240), new THREE.MeshBasicMaterial({ color: LANE, transparent: true, opacity: 0.9 }));
      e.rotation.x = -Math.PI / 2; e.position.set(x, 0.03, -90); drive.add(e);
    });
    const DASH_GAP = 5, DASH_N = 40, RECYCLE = DASH_GAP * DASH_N;
    const dashMat = new THREE.MeshBasicMaterial({ color: 0xf6f9fd });
    const dashes = [];
    for (let i = 0; i < DASH_N; i++) { const d = new THREE.Mesh(new THREE.PlaneGeometry(0.22, 2.4), dashMat); d.rotation.x = -Math.PI / 2; d.position.set(0, 0.04, 9 - i * DASH_GAP); drive.add(d); dashes.push(d); }
    const laneMat = new THREE.MeshBasicMaterial({ color: LANE, transparent: true, opacity: 0.5 });
    const laneDashes = [];
    [-2.7, 2.7].forEach(x => { for (let i = 0; i < DASH_N; i++) { const d = new THREE.Mesh(new THREE.PlaneGeometry(0.12, 1.6), laneMat); d.rotation.x = -Math.PI / 2; d.position.set(x, 0.04, 9 - i * DASH_GAP); drive.add(d); laneDashes.push(d); } });

    const scanMat = new THREE.MeshBasicMaterial({ color: SCAN, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false });
    const scan = new THREE.Mesh(new THREE.PlaneGeometry(11.2, 2.2), scanMat); scan.rotation.x = -Math.PI / 2; scan.position.set(0, 0.05, -6); drive.add(scan);

    const points = [];
    for (let i = 0; i < 70; i++) {
      const p = new THREE.Mesh(new THREE.CircleGeometry(0.09, 8), new THREE.MeshBasicMaterial({ color: SCAN, transparent: true, opacity: 0, blending: THREE.AdditiveBlending, depthWrite: false }));
      p.rotation.x = -Math.PI / 2; p.position.set(rng()*10 - 5, 0.045, -(rng()*70 + 4)); p.userData.phase = rng()*Math.PI*2;
      drive.add(p); points.push(p);
    }
    const van = buildVan(); van.position.set(0, 0, -3.0); drive.add(van);

    const dN = 280, dPos = new Float32Array(dN * 3);
    for (let i = 0; i < dN; i++) { dPos[i*3]=rng()*44-22; dPos[i*3+1]=rng()*16; dPos[i*3+2]=-(rng()*84); }
    const dustGeo = new THREE.BufferGeometry(); dustGeo.setAttribute('position', new THREE.BufferAttribute(dPos, 3));
    const dust = new THREE.Points(dustGeo, new THREE.PointsMaterial({ color: 0xaecbe0, size: 0.06, transparent: true, opacity: 0.55 }));
    drive.add(dust);

    // ---- resize ----
    function resize() { const w = canvas.clientWidth, h = canvas.clientHeight; if (!w || !h) return; renderer.setSize(w, h, false); camera.aspect = w / h; camera.updateProjectionMatrix(); }
    global.addEventListener('resize', resize); resize();

    let tx = 0, ty = 0, px = 0, py = 0;
    if (!reduced) global.addEventListener('pointermove', e => { tx = e.clientX/global.innerWidth - 0.5; ty = e.clientY/global.innerHeight - 0.5; }, { passive: true });

    // ---- timeline ----
    const SPEED = 42;
    const T_MAP = 3.4, T_DIVE = 2.2;       // seconds
    let phase, tStart, running = true, raf, bp = 0;

    function start(intro) {
      phase = intro ? 'map' : 'drive';
      tStart = performance.now();
      network.visible = intro;
      drive.visible = !intro;
      if (!intro) { camera.position.copy(camDrive); }
    }
    _replay = () => start(true);

    function lerpV(a, b, t) { return a.clone().lerp(b, t); }
    function easeInOut(t) { return t < .5 ? 2*t*t : 1 - Math.pow(-2*t+2, 2)/2; }

    function frame(now) {
      if (!running) return;
      const dt = Math.min((now - tStart)/1000, 1e9);
      const stepDt = 1/60;

      if (phase === 'map') {
        const t = dt / T_MAP;
        // reveal edges progressively
        edgeMeshes.forEach((ln, i) => { const at = (i / edgeMeshes.length) * 0.7; ln.material.opacity = clamp((t - at) * 4, 0, 0.9); });
        nodeMeshes.forEach((nm, i) => { const at = (i / nodeMeshes.length) * 0.8; const o = clamp((t - at) * 5, 0, 1); nm.material.opacity = o; nm.scale.setScalar(0.4 + o); });
        // radar ring expands repeatedly
        const rt = (dt % 1.6) / 1.6;
        ring.scale.setScalar(1 + rt * 18); ring.material.opacity = (1 - rt) * 0.5;
        // gentle camera orbit while mapping
        camera.position.set(Math.sin(now/3000)*10, camMap.y, camMap.z + Math.cos(now/3000)*6);
        camera.lookAt(lookMap);
        if (dt >= T_MAP) { phase = 'dive'; tStart = now; }
      }
      else if (phase === 'dive') {
        const t = easeInOut(clamp(dt / T_DIVE, 0, 1));
        // crossfade network out, drive in midway
        network.visible = t < 0.85;
        drive.visible = t > 0.35;
        const netFade = clamp(1 - (t - 0.4) / 0.45, 0, 1);
        edgeMeshes.forEach(ln => ln.material.opacity *= 0.92);
        if (network.visible) network.scale.setScalar(1);
        // sweep camera from map down to behind the van
        camera.position.copy(lerpV(camMap, camDrive, t));
        const look = lerpV(lookMap, lookDrive, t); camera.lookAt(look);
        if (dt >= T_DIVE) { phase = 'drive'; tStart = now; network.visible = false; }
      }
      else { // drive loop
        [dashes, laneDashes].forEach(arr => arr.forEach(d => { d.position.z += SPEED*stepDt; if (d.position.z > 11) d.position.z -= RECYCLE; }));
        scan.position.z += SPEED*0.6*stepDt; const life = (scan.position.z + 34)/42; if (scan.position.z > 8) scan.position.z = -34;
        scanMat.opacity = Math.max(0, Math.sin(Math.min(life,1)*Math.PI))*0.55;
        points.forEach(p => { p.position.z += SPEED*0.55*stepDt; if (p.position.z > 9) { p.position.z -= 74; p.position.x = rng()*10-5; } p.userData.phase += stepDt*3.4; p.material.opacity = Math.max(0, Math.sin(p.userData.phase))*0.9; });
        const a = dust.geometry.attributes.position.array; for (let i=0;i<dN;i++){ a[i*3+2]+=SPEED*0.3*stepDt; if(a[i*3+2]>8)a[i*3+2]-=84; } dust.geometry.attributes.position.needsUpdate = true;
        if (van.userData.wheels) van.userData.wheels.forEach(w => w.rotation.x -= SPEED*stepDt*0.5);
        van.position.y = Math.sin(now/200)*0.03; van.position.x = Math.sin(now/1600)*0.5;
        van.rotation.y = Math.sin(now/1600 + Math.PI/2)*0.04; van.rotation.z = Math.sin(now/760)*0.01;
        bp += stepDt; const blink = (Math.sin(bp*7) > 0) ? 1 : 0.12;
        if (van.userData.beacon) van.userData.beacon.material.opacity = blink;
        if (van.userData.beaconLight) van.userData.beaconLight.intensity = blink*1.6;
        if (van.userData.sensors) { const s = 0.55 + Math.abs(Math.sin(now/260))*0.6; van.userData.sensors.forEach(m => m.material.opacity = s); }
        px += (tx-px)*0.04; py += (ty-py)*0.04;
        camera.position.x = camDrive.x + px*1.8 + Math.sin(now/3000)*0.3;
        camera.position.y = camDrive.y - py*1.0 + Math.sin(now/2200)*0.1;
        camera.position.z = camDrive.z;
        camera.lookAt(lookDrive);
      }

      renderer.render(scene, camera);
      raf = requestAnimationFrame(frame);
    }

    start(wantIntro && !reduced);
    if (reduced) { drive.visible = true; network.visible = false; camera.position.copy(camDrive); camera.lookAt(lookDrive); renderer.render(scene, camera); return; }
    raf = requestAnimationFrame(frame);
    document.addEventListener('visibilitychange', () => {
      if (document.hidden) { running = false; if (raf) cancelAnimationFrame(raf); }
      else if (!running) { running = true; tStart = performance.now(); raf = requestAnimationFrame(frame); }
    });
  }

  function buildVan() {
    const g = new THREE.Group();
    const white  = new THREE.MeshStandardMaterial({ color: 0xf2f5f8, roughness: 0.45, metalness: 0.15 });
    const dark   = new THREE.MeshStandardMaterial({ color: 0x12171f, roughness: 0.6, metalness: 0.35 });
    const glass  = new THREE.MeshStandardMaterial({ color: 0x0e1a26, roughness: 0.12, metalness: 0.7, emissive: 0x06303a, emissiveIntensity: 0.4 });
    const amber  = new THREE.MeshStandardMaterial({ color: LANE, roughness: 0.4, metalness: 0.2, emissive: LANE, emissiveIntensity: 0.5 });

    const lower = new THREE.Mesh(new THREE.BoxGeometry(2.3, 1.0, 4.8), white); lower.position.y = 0.98; g.add(lower);
    const upper = new THREE.Mesh(new THREE.BoxGeometry(2.12, 0.98, 3.1), white); upper.position.set(0, 1.78, 0.45); g.add(upper);
    [-1.17, 1.17].forEach(x => { const s = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.24, 4.6), amber); s.position.set(x, 1.06, 0); g.add(s); });
    const rearWin = new THREE.Mesh(new THREE.BoxGeometry(1.74, 0.72, 0.08), glass); rearWin.position.set(0, 1.9, 2.02); g.add(rearWin);
    [-1.08, 1.08].forEach(x => { const w = new THREE.Mesh(new THREE.BoxGeometry(0.06, 0.56, 2.5), glass); w.position.set(x, 1.95, 0.45); g.add(w); });
    const bumper = new THREE.Mesh(new THREE.BoxGeometry(2.34, 0.36, 0.22), dark); bumper.position.set(0, 0.52, 2.5); g.add(bumper);
    const red = new THREE.MeshStandardMaterial({ color: 0xff453a, emissive: 0xff2a20, emissiveIntensity: 1.8 });
    [-0.86, 0.86].forEach(x => { const tl = new THREE.Mesh(new THREE.BoxGeometry(0.38, 0.28, 0.1), red); tl.position.set(x, 1.08, 2.46); g.add(tl); });
    const tlGlow = new THREE.PointLight(0xff453a, 0.8, 6); tlGlow.position.set(0, 1.1, 3.2); g.add(tlGlow);

    const wheelGeo = new THREE.CylinderGeometry(0.48, 0.48, 0.36, 22); wheelGeo.rotateZ(Math.PI/2);
    const tyre = new THREE.MeshStandardMaterial({ color: 0x05070a, roughness: 0.85 });
    const hub  = new THREE.MeshStandardMaterial({ color: 0x9aa6b4, roughness: 0.5, metalness: 0.6 });
    const wheels = [];
    [[-1.13,1.7],[1.13,1.7],[-1.13,-1.7],[1.13,-1.7]].forEach(p => { const w = new THREE.Mesh(wheelGeo, tyre); w.position.set(p[0],0.48,p[1]); const cap = new THREE.Mesh(new THREE.BoxGeometry(0.06,0.5,0.12), hub); cap.position.set(p[0]>0?0.19:-0.19,0,0); w.add(cap); g.add(w); wheels.push(w); });

    const rig = new THREE.Group(); rig.position.set(0, 2.28, 0.4); g.add(rig);
    const bar = new THREE.Mesh(new THREE.BoxGeometry(2.0, 0.1, 0.14), dark); bar.position.set(0, 0.12, 1.05); rig.add(bar);
    const sensors = [];
    [-0.7, 0, 0.7].forEach(x => {
      const s = new THREE.Mesh(new THREE.BoxGeometry(0.12,0.12,0.12), new THREE.MeshBasicMaterial({ color: SCAN, transparent: true, opacity: 0.8 })); s.position.set(x,0.05,1.05); rig.add(s); sensors.push(s);
      const beam = new THREE.Mesh(new THREE.PlaneGeometry(0.04,2.2), new THREE.MeshBasicMaterial({ color: SCAN, transparent: true, opacity: 0.22, blending: THREE.AdditiveBlending, depthWrite: false })); beam.position.set(x,-1.0,1.05); rig.add(beam);
    });
    const mast = new THREE.Mesh(new THREE.CylinderGeometry(0.06,0.06,0.7,12), dark); mast.position.set(0,0.45,0); rig.add(mast);
    const dome = new THREE.Mesh(new THREE.SphereGeometry(0.21,18,14), dark); dome.position.set(0,0.86,0); rig.add(dome);
    const ringM = new THREE.Mesh(new THREE.TorusGeometry(0.21,0.035,10,26), new THREE.MeshBasicMaterial({ color: SCAN })); ringM.position.set(0,0.86,0); ringM.rotation.x = Math.PI/2; rig.add(ringM);
    const beacon = new THREE.Mesh(new THREE.BoxGeometry(0.54,0.14,0.2), new THREE.MeshBasicMaterial({ color: LANE, transparent: true, opacity: 1 })); beacon.position.set(0,0.17,-0.72); rig.add(beacon);
    const beaconLight = new THREE.PointLight(LANE, 1.4, 7); beaconLight.position.set(0,0.42,-0.72); rig.add(beaconLight);

    g.userData = { wheels, sensors, beacon, beaconLight };
    return g;
  }

  function clamp(v, a, b) { return Math.max(a, Math.min(b, v)); }
  function mulberry(a) { return function () { a |= 0; a = a + 0x6D2B79F5 | 0; let t = Math.imul(a ^ a >>> 15, 1 | a); t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t; return ((t ^ t >>> 14) >>> 0) / 4294967296; }; }

  global.NSVHero = { init, replay: function () { if (_replay) _replay(); } };
})(window);
