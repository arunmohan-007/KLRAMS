/* ============================================================
   KLRAMS module launcher — shared across console, viewer,
   GO Portal and Site Control. Injects a full-screen overlay
   with a searchable grid of module tiles.

   Build 84:
   - vertical colour-accented tiles (not iRoads-style rows)
   - gradient header band with brand + tagline + Night-mode switch
   - Night mode flips the map to a dark basemap (map.html) and
     remembers the choice in localStorage ('klNight'), restoring
     the previous basemap when switched off.
   - refined enterprise styling: tighter tiles, softer accents,
     inset icon rings, subtle card/panel depth.

   Usage: include <script src="/js/launcher.js"></script> and a
   button: <button class="kl-open" onclick="openLauncher()">…</button>
   ============================================================ */
(function(){
  const CSS = `
  .kl-open{display:inline-flex;align-items:center;gap:7px;background:rgba(255,255,255,.10);border:1px solid rgba(255,255,255,.20);color:#dbe6f4;font-size:13px;font-weight:600;padding:7px 13px;border-radius:9px;cursor:pointer;font-family:inherit;line-height:1}
  .kl-open:hover{background:rgba(255,255,255,.20);color:#fff}
  .kl-big{padding:0;width:42px;height:42px;min-width:42px;justify-content:center;background:linear-gradient(135deg,#19b277,#0d7a51);border:0;border-radius:11px;color:#fff;box-shadow:0 2px 9px rgba(13,122,81,.45);margin-right:6px}
  .kl-big:hover{filter:brightness(1.08);background:linear-gradient(135deg,#19b277,#0d7a51)}
  .kl-big svg{width:21px;height:21px}

  .kl-ov{position:fixed;inset:0;z-index:99999;background:rgba(6,11,21,.66);backdrop-filter:blur(5px);display:none;animation:klf .16s ease}
  .kl-ov.show{display:flex}
  @keyframes klf{from{opacity:0}to{opacity:1}}
  @keyframes klrise{from{opacity:0;transform:translateY(14px)}to{opacity:1;transform:none}}

  .kl-panel{position:relative;margin:auto;width:min(1040px,94vw);height:min(660px,92vh);background:#0e1726;border:1px solid #22304a;border-radius:20px;display:flex;flex-direction:column;overflow:hidden;box-shadow:0 34px 100px rgba(0,0,0,.62);animation:klrise .2s ease}
  .kl-panel:before{content:'';position:absolute;top:0;left:0;right:0;height:3px;background:linear-gradient(90deg,#19b277,#3b86e6,#8a68e6,#d3aa3e);z-index:2}

  .kl-topbar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 22px;background:linear-gradient(120deg,#0c1322 0%,#12243f 55%,#163a5f 100%);border-bottom:1px solid #1d2944}
  .kl-brandwrap{display:flex;align-items:center;gap:13px;min-width:0}
  .kl-logo{width:42px;height:42px;flex-shrink:0;border-radius:13px;background:linear-gradient(135deg,#19b277,#0d7a51);display:flex;align-items:center;justify-content:center;color:#fff;box-shadow:0 4px 14px rgba(13,122,81,.5)}
  .kl-logo svg{width:23px;height:23px}
  .kl-brandtxt{display:flex;flex-direction:column;line-height:1.15;min-width:0}
  .kl-brandtxt b{font-size:22px;font-weight:800;color:#fff;letter-spacing:1px}
  .kl-brandtxt b span{color:#e6c878}
  .kl-brandtxt i{font-style:normal;font-size:11.5px;color:#90a6c6;margin-top:3px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}

  .kl-actions{display:flex;align-items:center;gap:11px;flex-shrink:0}
  .kl-night{display:inline-flex;align-items:center;gap:9px;background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.14);color:#cdd9ea;font-family:inherit;font-size:12.5px;font-weight:600;padding:8px 12px;border-radius:11px;cursor:pointer;line-height:1}
  .kl-night:hover{background:rgba(255,255,255,.12);color:#fff}
  .kl-night svg{width:15px;height:15px;color:#e6c878}
  .kl-sw{width:34px;height:18px;border-radius:10px;background:#2a3a57;position:relative;transition:background .2s;flex-shrink:0}
  .kl-sw i{position:absolute;top:2px;left:2px;width:14px;height:14px;border-radius:50%;background:#cbd6e6;transition:left .2s,background .2s}
  .kl-night.on{color:#fff;border-color:#3b86e6}
  .kl-night.on .kl-sw{background:linear-gradient(90deg,#3b86e6,#8a68e6)}
  .kl-night.on .kl-sw i{left:18px;background:#fff}
  .kl-close{background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.12);color:#9fb2cd;font-size:22px;width:36px;height:36px;border-radius:10px;cursor:pointer;line-height:1;display:flex;align-items:center;justify-content:center}
  .kl-close:hover{color:#fff;background:rgba(255,255,255,.14)}

  .kl-body{flex:1;overflow:auto;padding:24px 26px 28px;background:radial-gradient(125% 78% at 50% -8%,#13203a 0%,#0e1726 58%)}
  .kl-search{position:relative;max-width:560px;margin:0 auto 26px}
  .kl-search input{width:100%;background:#0a1322;border:1px solid #243352;border-radius:12px;color:#e6edf6;font-size:14.5px;padding:12px 14px 12px 44px;font-family:inherit;box-shadow:inset 0 1px 2px rgba(0,0,0,.35)}
  .kl-search input:focus{outline:none;border-color:#2f7fe0;box-shadow:inset 0 1px 2px rgba(0,0,0,.35),0 0 0 3px rgba(47,127,224,.16)}
  .kl-search svg{position:absolute;left:15px;top:50%;transform:translateY(-50%);color:#6b7d99}

  .kl-sec{font-size:10.5px;font-weight:700;letter-spacing:.9px;text-transform:uppercase;color:#73869f;margin:6px 2px 14px;display:flex;align-items:center;gap:12px}
  .kl-sec:after{content:'';flex:1;height:1px;background:linear-gradient(90deg,#22304a,transparent)}
  .kl-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(190px,1fr));gap:13px;margin-bottom:26px}

  .kl-card{position:relative;display:flex;flex-direction:column;align-items:flex-start;gap:10px;background:linear-gradient(180deg,#15233f,#111c33);border:1px solid #243352;border-radius:14px;padding:16px 15px 15px;cursor:pointer;text-decoration:none;color:#fff;overflow:hidden;font-family:inherit;text-align:left;transition:transform .15s,border-color .15s,box-shadow .15s,background .15s}
  .kl-card:hover{transform:translateY(-2px);border-color:var(--ac);box-shadow:0 12px 26px -14px var(--ac),0 0 0 1px var(--ac) inset}
  .kl-strip{position:absolute;top:0;left:0;right:0;height:3px;background:var(--ac);opacity:.78}
  .kl-card.cur{border-color:var(--ac)}
  .kl-card.cur:after{content:'CURRENT';position:absolute;top:11px;right:11px;font-size:8px;font-weight:800;letter-spacing:.5px;color:var(--ac);background:rgba(255,255,255,.06);padding:3px 6px;border-radius:6px}
  .kl-ic{width:44px;height:44px;border-radius:12px;display:flex;align-items:center;justify-content:center;flex-shrink:0;color:#fff;box-shadow:0 4px 12px -4px var(--ac),inset 0 0 0 1px rgba(255,255,255,.13)}
  .kl-ic svg{width:22px;height:22px}
  .kl-lbl{font-size:14px;font-weight:700;line-height:1.25;letter-spacing:.2px}
  .kl-dsc{font-size:11.5px;color:#90a3bf;line-height:1.45}

  .ic-green{background:linear-gradient(135deg,#19b277,#0d7a51)}
  .ic-blue{background:linear-gradient(135deg,#3b86e6,#1d5bb0)}
  .ic-gold{background:linear-gradient(135deg,#d3aa3e,#9a7416)}
  .ic-violet{background:linear-gradient(135deg,#8a68e6,#5a3fb0)}
  .ic-teal{background:linear-gradient(135deg,#17b3b3,#0c7f7f)}

  @media(max-width:600px){.kl-brandtxt i{display:none}.kl-night span{display:none}.kl-topbar{padding:14px 16px}.kl-body{padding:18px 16px 22px}}
  `;
  const AC = {'ic-green':'#19b277','ic-blue':'#3b86e6','ic-gold':'#d3aa3e','ic-violet':'#8a68e6','ic-teal':'#17b3b3'};
  const ICON = {
    grid:'<svg viewBox="0 0 24 24" fill="currentColor"><rect x="3" y="3" width="7" height="7" rx="1.6"/><rect x="14" y="3" width="7" height="7" rx="1.6"/><rect x="3" y="14" width="7" height="7" rx="1.6"/><rect x="14" y="14" width="7" height="7" rx="1.6"/></svg>',
    moon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>',
    map:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><circle cx="12" cy="10" r="3"/><path d="M12 21s-7-6.2-7-11a7 7 0 0 1 14 0c0 4.8-7 11-7 11z"/></svg>',
    db:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/></svg>',
    go:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M7 3h7l4 4v14H7z"/><path d="M14 3v4h4M10 13h5M10 17h5"/></svg>',
    gear:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><circle cx="12" cy="12" r="3"/><path d="M19.4 13a1 1 0 0 0 .2 1.1l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1 1 0 0 0-1.7.7V18a2 2 0 1 1-4 0v-.1a1 1 0 0 0-1.7-.7l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1 1 0 0 0-.7-1.7H6a2 2 0 1 1 0-4h.1a1 1 0 0 0 .7-1.7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1 1 0 0 0 1.7-.7V6a2 2 0 1 1 4 0v.1a1 1 0 0 0 1.7.7l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1 1 0 0 0 .2 1.1z"/></svg>'
  };
  const CARDS = [
    {href:'/home.html', label:'Home',         desc:'KLRAMS staff home screen',     cls:'ic-teal',   ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M3 11l9-8 9 8"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-6h4v6"/></svg>'},
    {href:'/map.html',  label:'GIS Viewer',   desc:'Interactive road network map', cls:'ic-green',  ic:ICON.map},
    {href:'/',          label:'Data Console', desc:'GIS & survey data imports',    cls:'ic-blue',   ic:ICON.db},
    {href:'/go.html',   label:'GO Portal',    desc:'Government Orders',            cls:'ic-gold',   ic:ICON.go},
    {href:'/admin.html',label:'Site Control', desc:'Website content',              cls:'ic-violet', ic:ICON.gear}
  ];
  const VIEWER = [
    {label:'Dashboard', desc:'Network analytics', cls:'ic-blue',   ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="3" y="3" width="8" height="8" rx="1"/><rect x="13" y="3" width="8" height="5" rx="1"/><rect x="13" y="10" width="8" height="11" rx="1"/><rect x="3" y="13" width="8" height="8" rx="1"/></svg>', act:"openPane('dashboard');loadDashboard();closeLauncher()"},
    {label:'Asset Register', desc:'Roads, culverts, bridges', cls:'ic-teal', ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="3" y="4" width="18" height="16" rx="1.5"/><path d="M3 9h18M3 14h18M9 4v16M15 4v16"/></svg>', act:"openRegScreen();closeLauncher()"},
    {label:'PCI', desc:'Pavement Condition Index', cls:'ic-green', ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M4 15a8 8 0 0 1 16 0"/><path d="M12 15l4.5-3.5"/><circle cx="12" cy="15" r="1.3"/></svg>', act:"openPciScreen('weights');closeLauncher()"},
    {label:'Road Condition Data', desc:'Colour & thresholds', cls:'ic-gold', ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M4 18l5-6 4 3 6-8"/><path d="M4 21h16"/></svg>', act:"openCondScreen();closeLauncher()"},
    {label:'Report Hub', desc:'FWD, condition, soil, core, crust', cls:'ic-violet', ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h6"/></svg>', act:"openReportHub();closeLauncher()"},
    {label:'NSV Videos', desc:'Survey footage catalogue & player', cls:'ic-blue', ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><rect x="3" y="6" width="13" height="12" rx="2"/><path d="M16 10l5-3v10l-5-3z"/></svg>', act:"openNsvScreen();closeLauncher()"},
    {label:'Climate', desc:'Flood susceptibility analytics', cls:'ic-teal', ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3s5.5 6 5.5 10a5.5 5.5 0 0 1-11 0C6.5 9 12 3 12 3z"/><path d="M9.5 14a2.6 2.6 0 0 0 2.5 2"/></svg>', act:"openClimate();closeLauncher()"},
    {label:'Filters', desc:'Filter data layers', cls:'ic-violet', ic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9"><path d="M4 5h16l-6 7v5l-4 2v-7z"/></svg>', act:"openPane('filter');refreshFilterLocks();closeLauncher()"}
  ];

  function tileInner(c){
    return '<span class="kl-strip"></span>'
      +'<span class="kl-ic '+c.cls+'">'+c.ic+'</span>'
      +'<span class="kl-lbl">'+c.label+'</span>'
      +'<span class="kl-dsc">'+c.desc+'</span>';
  }
  function here(href){
    const p = location.pathname;
    if(href==='/') return p==='/' || p==='/index.html';
    return p===href;
  }
  function card(c){
    return '<a class="kl-card'+(here(c.href)?' cur':'')+'" style="--ac:'+(AC[c.cls]||'#3b86e6')+'" href="'+c.href+'" data-l="'+(c.label+' '+c.desc).toLowerCase()+'">'+tileInner(c)+'</a>';
  }
  function vcard(c){
    return '<button class="kl-card" style="--ac:'+(AC[c.cls]||'#3b86e6')+'" data-l="'+(c.label+' '+c.desc).toLowerCase()+'" onclick="'+c.act+'">'+tileInner(c)+'</button>';
  }

  /* ---- Night mode ------------------------------------------------------ */
  function nightOn(){ try{ return localStorage.getItem('klNight')==='1'; }catch(e){ return false; } }
  function syncNightBtn(){ var b=document.getElementById('klNightBtn'); if(b){ b.classList.toggle('on', nightOn()); b.setAttribute('aria-checked', nightOn()?'true':'false'); } }
  window.applyKlNight = function(on){
    // Only the viewer (map.html) has a basemap to darken.
    if(window.map && map.getLayer && map.getLayer('dark') && typeof window.setBaseLayer==='function'){
      var sel=document.getElementById('basemap');
      if(on){
        var prev = sel ? sel.value : 'osm';
        if(prev!=='dark'){ try{ localStorage.setItem('klBasePrev', prev); }catch(e){} }
        window.setBaseLayer('dark');
      } else {
        var back='osm'; try{ back=localStorage.getItem('klBasePrev')||'osm'; }catch(e){}
        if(back==='dark') back='osm';
        window.setBaseLayer(back);
      }
    }
    document.documentElement.classList.toggle('kl-nightmode', !!on);
  };
  window.toggleKlNight = function(){
    var on = !nightOn();
    try{ localStorage.setItem('klNight', on?'1':'0'); }catch(e){}
    syncNightBtn();
    window.applyKlNight(on);
  };

  function inject(){
    const st=document.createElement('style');st.textContent=CSS;document.head.appendChild(st);
    const ov=document.createElement('div');ov.className='kl-ov';ov.id='klOv';
    ov.innerHTML='<div class="kl-panel">'
      +'<div class="kl-topbar">'
        +'<div class="kl-brandwrap">'
          +'<span class="kl-logo">'+ICON.grid+'</span>'
          +'<span class="kl-brandtxt"><b>KL<span>RAMS</span></b><i>Kerala Road Asset Management System · PWD</i></span>'
        +'</div>'
        +'<div class="kl-actions">'
          +'<button class="kl-night" id="klNightBtn" onclick="toggleKlNight()" role="switch" aria-checked="false">'+ICON.moon+'<span>Night mode</span><span class="kl-sw"><i></i></span></button>'
          +'<button class="kl-close" onclick="closeLauncher()" aria-label="Close">&times;</button>'
        +'</div>'
      +'</div>'
      +'<div class="kl-body">'
        +'<div class="kl-search"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>'
        +'<input id="klSearch" placeholder="Search modules…" autocomplete="off" oninput="klFilter()"></div>'
        +'<div class="kl-sec">Platform modules</div>'
        +'<div class="kl-grid" id="klGrid">'+CARDS.map(card).join('')+'</div>'
        +(typeof window.openPane==='function'?('<div class="kl-sec">Viewer tools</div><div class="kl-grid">'+VIEWER.map(vcard).join('')+'</div>'):'')
      +'</div></div>';
    ov.addEventListener('click',function(e){if(e.target===ov)closeLauncher();});
    document.body.appendChild(ov);
    document.addEventListener('keydown',function(e){if(e.key==='Escape')closeLauncher();});
    syncNightBtn();
  }
  window.openLauncher=function(){var o=document.getElementById('klOv');if(o){o.classList.add('show');syncNightBtn();setTimeout(function(){var s=document.getElementById('klSearch');if(s)s.focus();},30);}};
  window.closeLauncher=function(){var o=document.getElementById('klOv');if(o)o.classList.remove('show');};
  window.klFilter=function(){var q=(document.getElementById('klSearch').value||'').toLowerCase();document.querySelectorAll('#klOv .kl-card').forEach(function(c){c.style.display=c.dataset.l.indexOf(q)>=0?'':'none';});};
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',inject);else inject();
})();
