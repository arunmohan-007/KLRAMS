/* Public portal (welcome.html). Lifted out of an inline <script> so the page
   carries no inline script, which is what lets script-src drop 'unsafe-inline'.
   Interactive controls dispatch through data-act (see js/00-actions.js). */
document.getElementById('yr').textContent = new Date().getFullYear();

  // scroll reveal
  var io = new IntersectionObserver(function(es){ es.forEach(function(e){ if(e.isIntersecting){ e.target.classList.add('in'); io.unobserve(e.target);} }); }, {threshold:.12});
  document.querySelectorAll('.reveal').forEach(function(el){ io.observe(el); });

  // FAQ (your real content)
  var FAQ = [
    {q:"What is KLRAMS?",a:"KLRAMS (Kerala Road Asset Management System) is a GIS-based platform of the Public Works Department, Government of Kerala, for surveying, assessing and managing the condition of the State's road network. It is operated by the RMMS Cell at the Kerala Highway Research Institute (KHRI)."},
    {q:"What does the system manage?",a:"Road inventory and classification, pavement condition (PCI), traffic surveys, Falling Weight Deflectometer (FWD) geotechnical data, and a repository of Government Orders — all on an interactive map."},
    {q:"What is the Pavement Condition Index (PCI)?",a:"PCI is a measure of pavement condition computed as per IRC:82-2023, used to prioritise maintenance across the network."},
    {q:"What is FWD?",a:"The Falling Weight Deflectometer measures pavement deflection (D0); these readings indicate the structural condition of the road."},
    {q:"Where can I find Government Orders?",a:"Open the GOs section on this portal to browse and search Government Orders and circulars by folder and name."}
  ];
  var faqBox = document.getElementById('faq');
  function esc(s){return String(s).replace(/[&<>]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;'}[c];});}
  FAQ.forEach(function(f){
    var d=document.createElement('details');
    d.innerHTML='<summary>'+esc(f.q)+'</summary><div class="fa">'+esc(f.a)+'</div>';
    faqBox.appendChild(d);
  });

  // ---- Government Orders (public GET) ----
  var GO_ALL = [];
  function renderGO(list){
    var box=document.getElementById('goList'), empty=document.getElementById('goEmpty');
    box.innerHTML='';
    if(!list.length){ empty.style.display='block'; empty.textContent='No government orders found.'; return; }
    empty.style.display='none';
    list.slice(0,12).forEach(function(f){
      var name=f.name||f.title||f.folder||'Government Order';
      var count=(f.count!=null?f.count:(f.docs!=null?f.docs:''));
      var a=document.createElement('a'); a.className='go'; a.href='#orders';
      a.innerHTML='<span class="gic"><svg viewBox="0 0 24 24" fill="none" stroke="#9ee23a" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"><path d="M5 3h10l4 4v14H5z"/><path d="M15 3v4h4"/></svg></span>'+
        '<span><div class="nm">'+esc(name)+'</div>'+(count!==''?'<div class="ct">'+esc(count)+' document(s)</div>':'')+'</span>';
      box.appendChild(a);
    });
  }
  fetch('/api/go/folders').then(function(r){return r.ok?r.json():[];}).then(function(list){
    GO_ALL = Array.isArray(list)?list:[]; renderGO(GO_ALL);
  }).catch(function(){ document.getElementById('goEmpty').style.display='block'; document.getElementById('goEmpty').textContent='Government Orders are unavailable right now.'; });
  document.getElementById('goq').addEventListener('input', function(e){
    var q=e.target.value.toLowerCase().trim();
    renderGO(!q?GO_ALL:GO_ALL.filter(function(f){return String(f.name||f.title||f.folder||'').toLowerCase().indexOf(q)>=0;}));
  });

  // ---- About / Contact (public GET) ----
  fetch('/api/site/content').then(function(r){return r.ok?r.json():null;}).then(function(c){
    if(!c) return;
    if(c.about)   document.getElementById('aboutText').textContent   = c.about;
    if(c.contact) document.getElementById('contactText').textContent = c.contact;
  }).catch(function(){});

  // ---- Survey Activities gallery + lightbox ----
  var SHOTS = [
    {src:'/img/survey/nsv.jpg',             tag:'NSV',       cap:'Network Survey Vehicle'},
    {src:'/img/survey/skid-resistance.jpg', tag:'Skid',      cap:'Skid Resistance Testing (BM-III)'},
    {src:'/img/survey/fwd.jpg',             tag:'FWD',       cap:'Falling Weight Deflectometer'},
    {src:'/img/survey/bituminous-core.jpg', tag:'Core',      cap:'Bituminous Core Extraction'},
    {src:'/img/survey/subgrade.jpg',        tag:'Sub-grade', cap:'Sub-Grade Soil Sampling'}
  ];
  var gWrap = document.getElementById('gallery');
  SHOTS.forEach(function(s, i){
    var d = document.createElement('div'); d.className = 'shot'; d.setAttribute('data-i', i);
    d.innerHTML = '<span class="tag">'+s.tag+'</span><img loading="lazy" src="'+s.src+'" alt="'+esc(s.cap)+'"><div class="cap">'+esc(s.cap)+'</div>';
    d.addEventListener('click', function(){ openLb(i); });
    gWrap.appendChild(d);
  });
  var lb=document.getElementById('lb'), lbImg=document.getElementById('lbImg'), lbCap=document.getElementById('lbCap'), lbI=0;
  function openLb(i){ lbI=(i+SHOTS.length)%SHOTS.length; lbImg.src=SHOTS[lbI].src; lbCap.textContent=SHOTS[lbI].cap; lb.classList.add('open'); }
  function closeLb(){ lb.classList.remove('open'); }
  document.getElementById('lbX').addEventListener('click', closeLb);
  document.getElementById('lbPrev').addEventListener('click', function(){ openLb(lbI-1); });
  document.getElementById('lbNext').addEventListener('click', function(){ openLb(lbI+1); });
  lb.addEventListener('click', function(e){ if(e.target===lb) closeLb(); });
  document.addEventListener('keydown', function(e){ if(!lb.classList.contains('open'))return; if(e.key==='Escape')closeLb(); if(e.key==='ArrowLeft')openLb(lbI-1); if(e.key==='ArrowRight')openLb(lbI+1); });

  // 3D hero (two-phase cinematic intro)
  if (window.NSVHero) NSVHero.init('scene', { mode:'hero', intro:true });

/* Was onclick="if(window.NSVHero)NSVHero.replay()" on the Replay button. */
function nsvReplay(){ if (window.NSVHero) NSVHero.replay(); }
