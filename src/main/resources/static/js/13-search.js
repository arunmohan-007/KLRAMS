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
    box.innerHTML=list.map((p,i)=>{const num=p.Road_Num?(' · No. '+escH(String(p.Road_Num))):'';return `<div class="it" data-i="${i}"><div class="nm">${escH(String(p.name||p.road||''))}</div><div class="id">${escH(String(p.road||''))}${num}</div></div>`;}).join('');
    box.classList.add('show');
    box.querySelectorAll('.it').forEach(el=>el.onclick=()=>choose(items[+el.dataset.i]));
  }
  /* The typeahead list is metadata only (RoadsIndex, no geometry) -- cheap
     enough to search across the whole network on every keystroke. The chosen
     result is the one place that needs coordinates, so it hydrates just that
     one road (RoadsIndex.hydrateFeature) before fitting bounds / picking. */
  function choose(p){
    if(!p)return;
    box.classList.remove('show');inp.value=p.name||p.road;
    RoadsIndex.hydrateFeature(p.road).then(f=>{
      if(!f)return;
      const line=lineOf(f);const b=new maplibregl.LngLatBounds();line.geometry.coordinates.forEach(c=>b.extend(c));
      if(!b.isEmpty())map.fitBounds(b,{padding:90,maxZoom:15,duration:700});
      const s=line.geometry.coordinates[0];
      onPick(p.road,{lng:s[0],lat:s[1]});
    });
  }
  inp.addEventListener('input',()=>{
    const q=inp.value.trim().toLowerCase();
    if(!q){box.classList.remove('show');return;}
    const doFilter=()=>{
      const m=RoadsIndex.all().filter(p=>String(p.name||'').toLowerCase().includes(q)||String(p.road||'').toLowerCase().includes(q)||String(p.Road_Num||'').toLowerCase().includes(q)).slice(0,10);
      render(m);
    };
    /* build 120 — if the road index hasn't loaded yet, fetch it on demand
       instead of asking the user to turn on the layer first. Search now works
       straight away and is independent of any layer toggle. */
    if(!RoadsIndex.all().length){
      box.innerHTML='<div class="none">Loading roads…</div>';box.classList.add('show');
      RoadsIndex.ensure().then(()=>{
        if(inp.value.trim().toLowerCase()!==q)return;            // user kept typing
        if(!RoadsIndex.all().length){box.innerHTML='<div class="none">No road data available.</div>';return;}
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
/* Up to two pins stay on the map so a place search and a lat/long (or a
   second place) can be dropped one after the other without wiping the first. */
const LOC_PIN_MAX=2;
const LOC_PIN_COLORS=[
  {fill:'#15976a',stroke:'#0d7a51'},
  {fill:'#2f6fed',stroke:'#1a4fbf'}
];
let locMarkers=[];
function clearLocation(){
  locMarkers.forEach(m=>{try{m.remove();}catch(e){}});
  locMarkers=[];
  const c=document.getElementById('locClear');if(c)c.classList.remove('show');
}
function locPinSvg(color){
  return '<svg width="26" height="34" viewBox="0 0 26 34" xmlns="http://www.w3.org/2000/svg"><path d="M13 1C6.4 1 1 6.3 1 12.8 1 21.5 13 33 13 33s12-11.5 12-20.2C25 6.3 19.6 1 13 1Z" fill="'+color.fill+'" stroke="'+color.stroke+'" stroke-width="1.5"/><circle cx="13" cy="12.6" r="4.4" fill="#fff"/></svg>';
}
function locFitAll(){
  if(!locMarkers.length)return;
  if(locMarkers.length===1){
    const ll=locMarkers[0].getLngLat();
    const z=map.getZoom();
    map.flyTo({center:[ll.lng,ll.lat],zoom:(Number.isFinite(z)?Math.max(z,16):16),duration:700});
    return;
  }
  const b=new maplibregl.LngLatBounds();
  locMarkers.forEach(m=>b.extend(m.getLngLat()));
  if(b.isEmpty())return;
  map.fitBounds(b,{padding:80,maxZoom:16,duration:800});
}
function placeLocation(lon,lat,label){
  /* Cap at LOC_PIN_MAX: a third search drops the oldest pin so the newest
     two always stay — place then coordinate, or two places, either order. */
  if(locMarkers.length>=LOC_PIN_MAX){
    try{locMarkers.shift().remove();}catch(e){}
  }
  const color=LOC_PIN_COLORS[locMarkers.length%LOC_PIN_COLORS.length];
  const el=document.createElement('div');el.className='locpin';
  el.innerHTML=locPinSvg(color);
  const safe=String(label).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
  const marker=new maplibregl.Marker({element:el,anchor:'bottom'}).setLngLat([lon,lat])
    .setPopup(new maplibregl.Popup({offset:28,closeButton:false}).setHTML('<div style="font:600 12.5px Inter,system-ui,sans-serif;color:#1f2a3d;max-width:220px;line-height:1.35">'+safe+'</div>'))
    .addTo(map);
  marker.togglePopup();
  locMarkers.push(marker);
  const c=document.getElementById('locClear');if(c)c.classList.add('show');
  locFitAll();
}
function parseLatLng(q){
  /* Tolerate copy-paste quirks: NBSP, thin space, Arabic comma, "lat, lon" labels. */
  let s=String(q==null?'':q).trim()
    .replace(/[\u00a0\u2000-\u200b\u202f\u205f]/g,' ')
    .replace(/[;；،]/g,',')
    .replace(/^\s*(?:lat(?:itude)?|lon(?:gitude)?|lng)\s*[:=]?\s*/i,'')
    .replace(/\s+/g,' ');
  const m=s.match(/^([+-]?\d{1,3}(?:\.\d+)?)\s*[, ]\s*([+-]?\d{1,3}(?:\.\d+)?)\s*$/);
  if(!m)return null;
  const a=parseFloat(m[1]),b=parseFloat(m[2]);
  if(!isFinite(a)||!isFinite(b))return null;
  let lat,lng;
  /* Kerala lon is ~74–78 (all ≤90), so "|x|>90 ⇒ longitude" cannot tell
     76.9, 8.5 apart from 8.5, 76.9. Prefer a Kerala-shaped pair in either
     order; otherwise fall back to the hint's lat, lon convention. */
  const kLat=v=>v>=7&&v<=14,kLng=v=>v>=74&&v<=79;
  if(kLat(a)&&kLng(b)){lat=a;lng=b;}
  else if(kLng(a)&&kLat(b)){lng=a;lat=b;}
  else if(Math.abs(a)>90&&Math.abs(b)<=90){lng=a;lat=b;}
  else if(Math.abs(b)>90&&Math.abs(a)<=90){lat=a;lng=b;}
  else if(Math.abs(a)<=90&&Math.abs(b)<=180){lat=a;lng=b;}
  else if(Math.abs(b)<=90&&Math.abs(a)<=180){lat=b;lng=a;}
  else return null;
  if(Math.abs(lat)>90||Math.abs(lng)>180)return null;
  return {lat:lat,lng:lng};
}
function setupLocationSearch(){
  const inp=document.getElementById('locInput'),box=document.getElementById('locResults'),clr=document.getElementById('locClear');
  if(!inp)return;
  let items=[],active=-1,t=null,seq=0;
  /* /api/geocode hands back Nominatim's single display_name string —
     "Kazhakkoottam, Thiruvananthapuram, Kerala, 695582, India" — so the head of
     it is the place and the tail is the context. The postcode and "India" are
     dropped: neither distinguishes one Kerala result from another. */
  function split(full){
    const parts=String(full||'').split(',').map(s=>s.trim()).filter(Boolean);
    const name=parts.shift()||'Unnamed place';
    const meta=parts.filter(s=>!/^\d{4,6}$/.test(s)&&s.toLowerCase()!=='india').slice(0,3).join(', ');
    return {name:name,meta:meta};
  }
  function fromProxy(r){
    const s=split(r&&r.name);
    return {name:s.name,meta:s.meta,kind:String((r&&r.kind)||'').replace(/_/g,' '),
            lng:+(r&&r.lng),lat:+(r&&r.lat),
            bbox:(r&&Array.isArray(r.bbox)&&r.bbox.length===4)?r.bbox:null};
  }
  const pinSvg='<span class="pin"><svg width="13" height="16" viewBox="0 0 13 16" fill="none" stroke="currentColor" stroke-width="1.5"><path d="M6.5 1C3.6 1 1 3.4 1 6.4 1 10.5 6.5 15 6.5 15S12 10.5 12 6.4C12 3.4 9.4 1 6.5 1Z"/><circle cx="6.5" cy="6.2" r="1.7" fill="currentColor" stroke="none"/></svg></span>';
  function gotoCoord(ll){
    box.classList.remove('show');inp.value=ll.lat.toFixed(6)+', '+ll.lng.toFixed(6);clr.classList.add('show');
    placeLocation(ll.lng,ll.lat,'Lat '+ll.lat.toFixed(6)+', Lng '+ll.lng.toFixed(6));
  }
  function renderCoord(ll){
    /* Bump seq so any in-flight geocoder response from a partial typed number
       (e.g. "8.5241" before the comma) cannot overwrite this coordinate row. */
    seq++;
    items=[{__coord:ll}];active=0;
    const cross='<span class="pin"><svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="7"/><path d="M12 1v4M12 19v4M1 12h4M19 12h4"/></svg></span>';
    box.innerHTML='<div class="lit active" data-i="0">'+cross+'<div><div class="nm">Go to '+ll.lat.toFixed(6)+', '+ll.lng.toFixed(6)+'</div><div class="meta">Latitude, longitude</div></div><span class="tp">coordinate</span></div>';
    box.classList.add('show');
    box.querySelectorAll('.lit').forEach(el=>el.onclick=()=>choose(items[+el.dataset.i]));
  }
  function render(list){
    items=list;active=-1;
    if(!list.length){box.innerHTML='<div class="lnone">No matching place.</div>';box.classList.add('show');return;}
    box.innerHTML=list.map((f,i)=>
      `<div class="lit" data-i="${i}">${pinSvg}<div><div class="nm">${escH(f.name)}</div>${f.meta?`<div class="meta">${escH(f.meta)}</div>`:''}</div>${f.kind?`<span class="tp">${escH(f.kind)}</span>`:''}</div>`
    ).join('');
    box.classList.add('show');
    box.querySelectorAll('.lit').forEach(el=>el.onclick=()=>choose(items[+el.dataset.i]));
  }
  function choose(f){
    if(f&&f.__coord){gotoCoord(f.__coord);return;}
    if(!f||!isFinite(f.lng)||!isFinite(f.lat))return;
    box.classList.remove('show');inp.value=f.name;
    /* Single-result extent zoom only when this is the first pin — a second
       place would otherwise override the two-pin fitBounds that placeLocation
       is about to run. The proxy hands bbox on as [west, south, east, north]. */
    if(!locMarkers.length&&f.bbox){
      const b=f.bbox;map.fitBounds([[b[0],b[1]],[b[2],b[3]]],{padding:80,maxZoom:16,duration:800});
    }
    placeLocation(f.lng,f.lat,f.meta?f.name+' \u2014 '+f.meta:f.name);
  }
  /* Goes through the server rather than calling a geocoder from the browser:
     it works on the PWD networks that reach KLRAMS but not arbitrary
     third-party hosts, it keeps staff IPs and typed queries off a third party,
     and one identified caller can honour the provider's rate limit where thirty
     browsers cannot. Same reasoning, and the same app.geocode.* configuration,
     documented on GeocodeController. */
  function run(q){
    const my=++seq;box.innerHTML='<div class="lloading">Searching\u2026</div>';box.classList.add('show');
    fetch('/api/geocode?q='+encodeURIComponent(q))
      .then(r=>r.json())
      .then(d=>{if(my!==seq)return;render((Array.isArray(d)?d:[]).map(fromProxy));})
      .catch(()=>{if(my!==seq)return;box.innerHTML='<div class="lnone">Location search unavailable. Check your internet connection and try again.</div>';box.classList.add('show');});
  }
  inp.addEventListener('input',()=>{
    const q=inp.value.trim();clr.classList.toggle('show',!!inp.value||locMarkers.length>0);
    if(t)clearTimeout(t);
    const __ll=parseLatLng(q);if(__ll){renderCoord(__ll);return;}
    if(q.length<3){box.classList.remove('show');return;}
    t=setTimeout(()=>run(q),350);
  });
  inp.addEventListener('keydown',e=>{
    if(e.key==='Enter'){
      /* Prefer a live parse of the input so Enter still jumps when a stale
         Photon reply wiped the coordinate row, or the dropdown was dismissed. */
      const ll=parseLatLng(inp.value);
      if(ll){e.preventDefault();choose({__coord:ll});return;}
      const els=box.querySelectorAll('.lit');if(!els.length)return;
      e.preventDefault();choose(items[active<0?0:active]);return;
    }
    const els=box.querySelectorAll('.lit');if(!els.length)return;
    if(e.key==='ArrowDown'){active=Math.min(active+1,els.length-1);e.preventDefault();}
    else if(e.key==='ArrowUp'){active=Math.max(active-1,0);e.preventDefault();}
    else return;
    els.forEach((el,i)=>el.classList.toggle('active',i===active));
  });
  clr.addEventListener('click',()=>{inp.value='';box.classList.remove('show');clearLocation();inp.focus();});
  document.addEventListener('click',e=>{const w=document.querySelector('.locsearch');if(w&&!w.contains(e.target))box.classList.remove('show');});
}

