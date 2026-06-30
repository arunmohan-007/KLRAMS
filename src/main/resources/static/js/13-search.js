/* ============================================================
   KLRAMS viewer · 13-search.js
   Road search and base-map location search (place names + lat/long coordinates).
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
function setupSearch(){
  const inp=document.getElementById('searchInput'),box=document.getElementById('searchResults');
  let items=[],active=-1;
  function render(list){
    items=list;active=-1;
    if(!list.length){box.innerHTML='<div class="none">No matching road</div>';box.classList.add('show');return;}
    box.innerHTML=list.map((f,i)=>{const p=f.properties;const num=p.Road_Num?(' · No. '+p.Road_Num):'';return `<div class="it" data-i="${i}"><div class="nm">${p.name||p.road}</div><div class="id">${p.road}${num}</div></div>`;}).join('');
    box.classList.add('show');
    box.querySelectorAll('.it').forEach(el=>el.onclick=()=>choose(items[+el.dataset.i]));
  }
  function choose(f){
    if(!f)return;
    box.classList.remove('show');inp.value=f.properties.name||f.properties.road;
    const line=lineOf(f);const b=new maplibregl.LngLatBounds();line.geometry.coordinates.forEach(c=>b.extend(c));
    if(!b.isEmpty())map.fitBounds(b,{padding:90,maxZoom:15,duration:700});
    const s=line.geometry.coordinates[0];
    onPick(f.properties.road,{lng:s[0],lat:s[1]});
  }
  inp.addEventListener('input',()=>{
    const q=inp.value.trim().toLowerCase();
    if(!q){box.classList.remove('show');return;}
    const doFilter=()=>{
      const m=Object.values(ROADS).filter(f=>{const p=f.properties;return String(p.name||'').toLowerCase().includes(q)||String(p.road||'').toLowerCase().includes(q)||String(p.Road_Num||'').toLowerCase().includes(q);}).slice(0,10);
      render(m);
    };
    /* build 120 — if the road network hasn't loaded yet, fetch it on demand
       instead of asking the user to turn on the layer first. Search now works
       straight away and is independent of any layer toggle. */
    if(!Object.keys(ROADS).length){
      box.innerHTML='<div class="none">Loading roads…</div>';box.classList.add('show');
      Promise.resolve(typeof loadRoads==='function'?loadRoads(true):null).then(()=>{
        if(inp.value.trim().toLowerCase()!==q)return;            // user kept typing
        if(!Object.keys(ROADS).length){box.innerHTML='<div class="none">No road data available.</div>';return;}
        doFilter();
      });
      return;
    }
    doFilter();
  });
  inp.addEventListener('keydown',e=>{
    const els=box.querySelectorAll('.it');if(!els.length)return;
    if(e.key==='ArrowDown'){active=Math.min(active+1,els.length-1);e.preventDefault();}
    else if(e.key==='ArrowUp'){active=Math.max(active-1,0);e.preventDefault();}
    else if(e.key==='Enter'){choose(items[active<0?0:active]);return;}
    else return;
    els.forEach((el,i)=>el.classList.toggle('active',i===active));
  });
  document.addEventListener('click',e=>{if(!document.getElementById('search').contains(e.target))box.classList.remove('show');});
}
/* ===== location (base map) geocoding search ===== */
let locMarker=null;
function clearLocation(){if(locMarker){locMarker.remove();locMarker=null;}const c=document.getElementById('locClear');if(c)c.classList.remove('show');}
function placeLocation(lon,lat,label){
  if(locMarker)locMarker.remove();
  const el=document.createElement('div');el.className='locpin';
  el.innerHTML='<svg width="26" height="34" viewBox="0 0 26 34" xmlns="http://www.w3.org/2000/svg"><path d="M13 1C6.4 1 1 6.3 1 12.8 1 21.5 13 33 13 33s12-11.5 12-20.2C25 6.3 19.6 1 13 1Z" fill="#15976a" stroke="#0d7a51" stroke-width="1.5"/><circle cx="13" cy="12.6" r="4.4" fill="#fff"/></svg>';
  const safe=String(label).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  locMarker=new maplibregl.Marker({element:el,anchor:'bottom'}).setLngLat([lon,lat])
    .setPopup(new maplibregl.Popup({offset:28,closeButton:false}).setHTML('<div style="font:600 12.5px Inter,system-ui,sans-serif;color:#1f2a3d;max-width:220px;line-height:1.35">'+safe+'</div>'))
    .addTo(map);
  locMarker.togglePopup();
  const c=document.getElementById('locClear');if(c)c.classList.add('show');
}
function parseLatLng(q){const m=String(q).trim().match(/^([+-]?\d{1,3}(?:\.\d+)?)\s*[,\s]\s*([+-]?\d{1,3}(?:\.\d+)?)$/);if(!m)return null;let a=parseFloat(m[1]),b=parseFloat(m[2]),lat,lng;if(Math.abs(a)<=90&&Math.abs(b)<=180){lat=a;lng=b;}else if(Math.abs(b)<=90&&Math.abs(a)<=180){lat=b;lng=a;}else return null;if(Math.abs(lat)>90||Math.abs(lng)>180)return null;return {lat:lat,lng:lng};}
function setupLocationSearch(){
  const inp=document.getElementById('locInput'),box=document.getElementById('locResults'),clr=document.getElementById('locClear');
  if(!inp)return;
  let items=[],active=-1,t=null,seq=0;
  function meta(p){
    const parts=[p.city,p.county,p.district,p.state,p.country].filter(Boolean);
    const seen=new Set([String(p.name||'').toLowerCase()]);const out=[];
    for(const x of parts){const k=String(x).toLowerCase();if(!seen.has(k)){seen.add(k);out.push(x);}}
    return out.slice(0,3).join(', ');
  }
  const pinSvg='<span class="pin"><svg width="13" height="16" viewBox="0 0 13 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M6.5 1C3.6 1 1 3.4 1 6.4 1 10.5 6.5 15 6.5 15S12 10.5 12 6.4C12 3.4 9.4 1 6.5 1Z"/><circle cx="6.5" cy="6.2" r="1.7" fill="currentColor" stroke="none"/></svg></span>';
  function gotoCoord(ll){box.classList.remove('show');inp.value=ll.lat.toFixed(6)+', '+ll.lng.toFixed(6);clr.classList.add('show');map.flyTo({center:[ll.lng,ll.lat],zoom:16,duration:900});placeLocation(ll.lng,ll.lat,'Lat '+ll.lat.toFixed(6)+', Lng '+ll.lng.toFixed(6));}
  function renderCoord(ll){items=[{__coord:ll}];active=0;const cross='<span class="pin"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="7"/><path d="M12 1v4M12 19v4M1 12h4M19 12h4"/></svg></span>';box.innerHTML='<div class="lit active" data-i="0">'+cross+'<div><div class="nm">Go to '+ll.lat.toFixed(6)+', '+ll.lng.toFixed(6)+'</div><div class="meta">Latitude, longitude</div></div><span class="tp">coordinate</span></div>';box.classList.add('show');box.querySelectorAll('.lit').forEach(el=>el.onclick=()=>choose(items[+el.dataset.i]));}
  function render(list){
    items=list;active=-1;
    if(!list.length){box.innerHTML='<div class="lnone">No matching place.</div>';box.classList.add('show');return;}
    box.innerHTML=list.map((f,i)=>{
      const p=f.properties||{};const nm=p.name||p.street||p.city||'Unnamed place';
      const m=meta(p);const tp=String(p.osm_value||p.osm_key||'').replace(/_/g,' ');
      return `<div class="lit" data-i="${i}">${pinSvg}<div><div class="nm">${escH(nm)}</div>${m?`<div class="meta">${escH(m)}</div>`:''}</div>${tp?`<span class="tp">${escH(tp)}</span>`:''}</div>`;
    }).join('');
    box.classList.add('show');
    box.querySelectorAll('.lit').forEach(el=>el.onclick=()=>choose(items[+el.dataset.i]));
  }
  function choose(f){
    if(f&&f.__coord){gotoCoord(f.__coord);return;}
    if(!f||!f.geometry)return;const p=f.properties||{};const c=f.geometry.coordinates;if(!c)return;
    const lon=+c[0],lat=+c[1];const nm=p.name||p.city||'Location';const m=meta(p);
    box.classList.remove('show');inp.value=nm;
    if(Array.isArray(p.extent)&&p.extent.length===4){const e=p.extent;map.fitBounds([[e[0],e[3]],[e[2],e[1]]],{padding:80,maxZoom:16,duration:800});}
    else map.flyTo({center:[lon,lat],zoom:15,duration:900});
    placeLocation(lon,lat,m?nm+' \u2014 '+m:nm);
  }
  function run(q){
    const my=++seq;box.innerHTML='<div class="lloading">Searching\u2026</div>';box.classList.add('show');
    const url='https://photon.komoot.io/api/?limit=6&lang=en&lat=8.52&lon=76.95&q='+encodeURIComponent(q);
    fetch(url).then(r=>r.json()).then(d=>{if(my!==seq)return;render((d&&d.features)||[]);})
      .catch(()=>{if(my!==seq)return;box.innerHTML='<div class="lnone">Location search unavailable. Check your internet connection and try again.</div>';box.classList.add('show');});
  }
  inp.addEventListener('input',()=>{
    const q=inp.value.trim();clr.classList.toggle('show',!!inp.value||!!locMarker);
    if(t)clearTimeout(t);
    const __ll=parseLatLng(q);if(__ll){renderCoord(__ll);return;}
    if(q.length<3){box.classList.remove('show');return;}
    t=setTimeout(()=>run(q),350);
  });
  inp.addEventListener('keydown',e=>{
    const els=box.querySelectorAll('.lit');if(!els.length)return;
    if(e.key==='ArrowDown'){active=Math.min(active+1,els.length-1);e.preventDefault();}
    else if(e.key==='ArrowUp'){active=Math.max(active-1,0);e.preventDefault();}
    else if(e.key==='Enter'){choose(items[active<0?0:active]);return;}
    else return;
    els.forEach((el,i)=>el.classList.toggle('active',i===active));
  });
  clr.addEventListener('click',()=>{inp.value='';box.classList.remove('show');clearLocation();inp.focus();});
  document.addEventListener('click',e=>{const w=document.querySelector('.locsearch');if(w&&!w.contains(e.target))box.classList.remove('show');});
}

