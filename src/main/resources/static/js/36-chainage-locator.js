/* ============================================================
   KLRAMS viewer · 36-chainage-locator.js
   Chainage Locator — turn a ROAD chainage into a point on the map.

   The distinction this whole tool exists for
   ------------------------------------------
   The chainage an engineer quotes ("2 600 on the Kollam–Punalur road") is
   measured along the WHOLE road. Geometry, though, is stored per SECTION, and
   each section carries only the slice of road chainage it covers, in
   Rd_Str_cha..Rd_End_cha. So 2 600 is NOT "2 600 m into a section" — it is:

     1. find the section whose road-chainage range contains 2 600
        (the one running 2 000 .. 3 000), then
     2. go 2 600 − 2 000 = 600 m into THAT section.

   Step 2 alone, applied to the first section, is the obvious wrong answer:
   it would clamp to the end of a 1 000 m section and be silently off by
   kilometres for every chainage past the first section.

   Both steps run server-side (/api/roads/chainage/locate) against the same
   linear reference that already places condition segments, FWD stretches and
   traffic stations, so the pin lands exactly where the condition segment for
   that chainage is drawn — not near it.

   Link roads and dual carriageways can give several sections the same road
   chainage. Every one of them is a real answer, so every one gets a pin.

   The road list honours the Road Network filter: when one is applied,
   window.NET_SCOPE holds the surviving section labels, and only road names
   with a section in that set can be picked — and only those sections are
   searched.
   ============================================================ */

let CHL_ROAD = null;          // {name, sections:[label], min, max} — the picked road
let CHL_MARKERS = [];         // maplibregl.Marker[], one per matching section
let CHL_LAST = null;          // last successful locate, for "Zoom to pin"
let _chlPinSeq = 0;           // unique <defs> ids — two pins on one map must not share a gradient id
let _chlScopeSig = null;      // NET_SCOPE signature the open list was built from

function chlEl(id){ return document.getElementById(id); }
function chlEsc(s){ return String(s==null?'':s).replace(/[&<>"]/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function chlNum(v){
  if(v==null||v==='') return NaN;
  const n = parseFloat(String(v).replace(/,/g,'').trim());
  return isFinite(n) ? n : NaN;
}
/* null must render as an em dash, not "0 m": isFinite(null) is true in JS, so
   the null check has to come first — the server sends null min/max for a road
   with no chainage range at all. */
function chlFmtM(m){
  if(m == null || !isFinite(Number(m))) return '—';
  const r = Math.round(Number(m));
  try{ return r.toLocaleString('en-IN') + ' m'; }catch(e){ return r + ' m'; }
}
/* Survey notation: 2 600 m is written "2 + 600" (km + m) on a drawing. */
function chlFmtKmM(m){
  if(m == null || !isFinite(Number(m))) return '—';
  const v = Number(m), s = v < 0 ? '-' : '', a = Math.abs(v);
  return s + Math.floor(a/1000) + ' + ' + String(Math.round(a%1000)).padStart(3,'0');
}

/* ---------- reading the road list ---------- */

/* Property bags for every section. netMetaRows() (05-road-network.js) already
   answers this correctly in both tile mode (RoadsIndex) and GeoJSON mode
   (ROADS); fall back to the same two sources if it is not loaded. */
function chlSectionRows(){
  if(typeof netMetaRows === 'function') return netMetaRows();
  if(typeof RoadsIndex !== 'undefined' && RoadsIndex.all().length) return RoadsIndex.all();
  if(typeof ROADS !== 'undefined') return Object.keys(ROADS||{}).map(k=>{
    const f = ROADS[k], p = Object.assign({}, (f&&f.properties)||{});
    if(p.road==null) p.road = (p.Section_La!=null ? p.Section_La : k);
    return p;
  });
  return [];
}
function chlScope(){ return (typeof window!=='undefined' && window.NET_SCOPE) ? window.NET_SCOPE : null; }
function chlScopeSig(){ const s = chlScope(); return s ? ('n'+s.size) : ''; }

/* Distinct Road_Name, each with the sections that carry it and the road
   chainage those sections span. Scoped to NET_SCOPE when a Road Network
   filter is applied — a road whose every section was filtered off the map
   is not offered, and a partly filtered road offers only its surviving
   sections, so the locator can never pin a section that is not on screen. */
let _chlListCache = null, _chlListKey = null;
function chlRoadList(){
  const rows = chlSectionRows();
  /* Rebuilt only when the section set or the filter scope actually moves —
     otherwise every keystroke in the combobox would re-scan the whole
     network's property bags. */
  const key = rows.length + '|' + chlScopeSig();
  if(_chlListCache && _chlListKey === key) return _chlListCache;
  const scope = chlScope(), byName = new Map();
  rows.forEach(p => {
    if(!p) return;
    const sec = String(p.road!=null ? p.road : (p.Section_La!=null ? p.Section_La : ''));
    if(!sec) return;
    if(scope && !scope.has(sec)) return;
    const name = String(p.Road_Name==null ? '' : p.Road_Name).trim();
    if(!name) return;
    let e = byName.get(name);
    if(!e){ e = {name:name, sections:[], min:Infinity, max:-Infinity, cls:String(p.Road_Class==null?'':p.Road_Class).trim()}; byName.set(name, e); }
    e.sections.push(sec);
    const a = chlNum(p.Rd_Str_cha), b = chlNum(p.Rd_End_cha);
    if(isFinite(a) && isFinite(b) && a !== b){ e.min = Math.min(e.min,a,b); e.max = Math.max(e.max,a,b); }
  });
  const list = Array.from(byName.values());
  list.forEach(e => { if(!isFinite(e.min) || !isFinite(e.max)){ e.min = NaN; e.max = NaN; } });
  list.sort((x,y) => x.name.localeCompare(y.name));
  _chlListCache = list; _chlListKey = key;
  return list;
}

/* ---------- the road combobox ---------- */

function chlRenderRoads(q){
  const box = chlEl('chlRoadList'); if(!box) return;
  const needle = String(q==null?'':q).trim().toLowerCase();
  let list = chlRoadList();
  if(needle) list = list.filter(e => e.name.toLowerCase().indexOf(needle) >= 0);
  if(!list.length){
    box.innerHTML = '<div class="lnone">' + (chlSectionRows().length
      ? (chlScope() ? 'No road in the current Road Network filter matches.' : 'No road matches.')
      : 'Road list still loading…') + '</div>';
    box.classList.add('show');
    return;
  }
  const shown = list.slice(0, 60);
  box.innerHTML = shown.map((e,i) =>
      '<div class="lit" data-i="'+i+'">'
    +   '<span class="pin"><svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M4 18h16M7 18V9M12 18V5M17 18v-7"/></svg></span>'
    +   '<span style="min-width:0"><span class="nm">'+chlEsc(e.name)+'</span>'
    +     '<span class="meta">'+e.sections.length+' section'+(e.sections.length===1?'':'s')
    +       (isFinite(e.min) ? ' · CH '+chlFmtM(e.min)+' – '+chlFmtM(e.max) : ' · no chainage range')
    +     '</span></span>'
    +   (e.cls ? '<span class="tp">'+chlEsc(e.cls)+'</span>' : '')
    + '</div>').join('')
    + (list.length > shown.length ? '<div class="lnone">'+(list.length-shown.length)+' more — keep typing to narrow.</div>' : '');
  box.classList.add('show');
  box.querySelectorAll('.lit').forEach(el => {
    el.onclick = () => chlPickRoad(shown[+el.dataset.i]);
  });
}
function chlCloseRoads(){ const b = chlEl('chlRoadList'); if(b) b.classList.remove('show'); }

function chlPickRoad(e){
  CHL_ROAD = e;
  const inp = chlEl('chlRoad'); if(inp) inp.value = e.name;
  const c = chlEl('chlRoadClear'); if(c) c.classList.add('show');
  chlCloseRoads();
  chlRenderRange();
  const ch = chlEl('chlCh'); if(ch) ch.focus();
}
/* focus only when the user hit the × — chlOnNetScope() also clears the road,
   and stealing focus while someone is editing a filter would be hostile. */
function chlClearRoad(focus){
  CHL_ROAD = null;
  const inp = chlEl('chlRoad'); if(inp){ inp.value = ''; if(focus) inp.focus(); }
  const c = chlEl('chlRoadClear'); if(c) c.classList.remove('show');
  chlRenderRange();
  chlCloseRoads();
}
function chlRenderRange(){
  const el = chlEl('chlRange'); if(!el) return;
  if(!CHL_ROAD){ el.innerHTML = ''; return; }
  if(!isFinite(CHL_ROAD.min)){
    el.innerHTML = '<b>'+chlEsc(CHL_ROAD.name)+'</b> — none of its sections carry a road chainage range, so a chainage cannot be located on it.';
    return;
  }
  el.innerHTML = '<b>'+chlEsc(CHL_ROAD.name)+'</b> runs CH <b>'+chlFmtM(CHL_ROAD.min)+'</b> to <b>'+chlFmtM(CHL_ROAD.max)
               + '</b> across '+CHL_ROAD.sections.length+' section'+(CHL_ROAD.sections.length===1?'':'s')+'.';
}
function chlRenderScope(){
  const el = chlEl('chlScope'); if(!el) return;
  const s = chlScope();
  if(!s){ el.textContent = ''; return; }
  const n = chlRoadList().length;
  el.innerHTML = 'A <b>Road Network filter</b> is applied — only the '+n+' road'+(n===1?'':'s')+' it matches can be selected.';
}

/* ---------- chainage entry ---------- */

/* Accept what people actually type: 2600, 2,600, "2+600" (the km+m survey
   notation), "2.6 km". A bare number is metres — that is the unit every
   chainage column in this database is in. */
function chlParseCh(raw){
  /* Strip every space, NBSP and zero-width character first, so "2 + 600",
     "2,600" and a value pasted out of a spreadsheet all reduce to one form. */
  const s = String(raw==null?'':raw).replace(/[\s\u200b\ufeff,]+/g,'');
  if(!s) return NaN;
  let m = s.match(/^(\d+(?:\.\d+)?)\+(\d+(?:\.\d+)?)$/);   // 2+600  (km + m)
  if(m) return (+m[1])*1000 + (+m[2]);
  m = s.match(/^(-?\d+(?:\.\d+)?)km$/i);                  // 2.6 km
  if(m) return (+m[1])*1000;
  m = s.match(/^(-?\d+(?:\.\d+)?)m?$/i);                   // 2600 / 2600 m
  if(m) return +m[1];
  return NaN;
}

/* ---------- the pin ---------- */

function chlPinEl(chainage){
  const id = 'chlg' + (++_chlPinSeq);   // two pins on one map must not share a gradient id
  const el = document.createElement('div');
  el.className = 'chpin';
  el.innerHTML =
      '<div class="chpin-tag"><b>CH '+chlEsc(chlFmtKmM(chainage))+'</b><span>'+chlEsc(chlFmtM(chainage))+'</span></div>'
    + '<div class="chpin-body">'
    +   '<span class="chpin-pulse"></span>'
    +   '<svg class="chpin-ic" width="38" height="50" viewBox="0 0 40 52" xmlns="http://www.w3.org/2000/svg">'
    +     '<defs><linearGradient id="'+id+'" x1="0" y1="0" x2="0" y2="1">'
    +       '<stop offset="0" stop-color="#4b9bff"/><stop offset="1" stop-color="#0b4ea8"/></linearGradient></defs>'
    +     '<path d="M20 1.6c-9.1 0-16.4 7.2-16.4 16.1C3.6 30 20 50.4 20 50.4S36.4 30 36.4 17.7C36.4 8.8 29.1 1.6 20 1.6Z" '
    +           'fill="url(#'+id+')" stroke="#fff" stroke-width="2.4"/>'
    +     '<circle cx="20" cy="17.7" r="9.4" fill="#fff"/>'
    +     '<rect x="14.8" y="10.6" width="10.4" height="2.7" rx="1.35" fill="#0b4ea8"/>'
    +     '<path d="M14.8 14h10.4v4.6a5.2 5.2 0 0 1-5.2 5.2 5.2 5.2 0 0 1-5.2-5.2z" fill="#0b4ea8"/>'
    +     '<path d="M17 16.6h6M17 19.6h3.8" stroke="#fff" stroke-width="1.5" stroke-linecap="round"/>'
    +   '</svg>'
    + '</div>';
  return el;
}

function chlPopupHtml(chainage, m){
  return '<div class="chpop">'
    + '<div class="chpop-h">CH '+chlEsc(chlFmtKmM(chainage))+' <span>'+chlEsc(chlFmtM(chainage))+'</span></div>'
    + '<div class="chpop-r"><span>Road</span><b>'+chlEsc(CHL_ROAD ? CHL_ROAD.name : '')+'</b></div>'
    + '<div class="chpop-r"><span>Section</span><b>'+chlEsc(m.section)+'</b></div>'
    + '<div class="chpop-r"><span>Section covers</span><b>CH '+chlFmtM(m.road_start)+' – '+chlFmtM(m.road_end)+'</b></div>'
    + '<div class="chpop-r"><span>Into section</span><b>'+chlFmtM(m.offset)+'</b></div>'
    + '<div class="chpop-r"><span>Coordinates</span><b>'+(+m.lat).toFixed(6)+', '+(+m.lng).toFixed(6)+'</b></div>'
    + '</div>';
}

function chlClear(){
  CHL_MARKERS.forEach(mk => { try{ mk.remove(); }catch(e){} });
  CHL_MARKERS = [];
  CHL_LAST = null;
  const o = chlEl('chlOut'); if(o){ o.style.display='none'; o.innerHTML=''; }
}

function chlZoom(){
  if(!CHL_LAST || !CHL_LAST.matches.length) return;
  const ms = CHL_LAST.matches;
  if(ms.length === 1){
    const z = map.getZoom();
    map.flyTo({center:[+ms[0].lng, +ms[0].lat], zoom:(Number.isFinite(z)?Math.max(z,17):17), duration:700});
    return;
  }
  const b = new maplibregl.LngLatBounds();
  ms.forEach(m => b.extend([+m.lng, +m.lat]));
  map.fitBounds(b, {padding:120, maxZoom:17, duration:800});
}

/* ---------- locate ---------- */

function chlOut(html, cls){
  const o = chlEl('chlOut'); if(!o) return;
  o.className = 'mout' + (cls ? ' ' + cls : '');
  o.innerHTML = html;
  o.style.display = html ? 'block' : 'none';
}

function chlLocate(){
  if(!CHL_ROAD){ chlOut('<div class="mstat"><span>Pick a road first</span><b class="mbad">no road</b></div>'); return; }
  const raw = (chlEl('chlCh')||{}).value;
  const ch = chlParseCh(raw);
  if(!isFinite(ch)){
    chlOut('<div class="mstat"><span>Chainage</span><b class="mbad">not a number</b></div>'
         + '<div class="mtime">Type metres (<b>2600</b>), the km+m form (<b>2+600</b>) or <b>2.6 km</b>.</div>');
    return;
  }
  chlOut('<div class="mstat"><span>CH '+chlEsc(chlFmtKmM(ch))+'</span><b class="mwait">locating…</b></div>');

  /* Send the scoped section list so the server searches exactly the sections
     the user can currently see. Without a filter this is empty and the whole
     road is searched. */
  const scope = chlScope();
  const qs = ['name='+encodeURIComponent(CHL_ROAD.name), 'chainage='+encodeURIComponent(ch)];
  if(scope) CHL_ROAD.sections.forEach(s => qs.push('section='+encodeURIComponent(s)));

  fetch('/api/roads/chainage/locate?'+qs.join('&'), {credentials:'same-origin'})
    .then(r => { if(!r.ok) throw new Error('HTTP '+r.status); return r.json(); })
    .then(j => {
      if(!j || !j.ok){
        chlClear();
        const reason = j && j.reason;
        if(reason === 'no_chainage'){
          chlOut('<div class="mstat"><span>CH '+chlEsc(chlFmtKmM(ch))+'</span><b class="mbad">not locatable</b></div>'
               + '<div class="mtime">No section of <b>'+chlEsc(CHL_ROAD.name)+'</b> carries a road start/end chainage, so there is nothing to locate this against.</div>');
        } else {
          /* Inside the road's overall extent but matched by nothing means the
             chainage falls in a GAP between sections — a real and common shape
             in this network (MC Road jumps 37 170 → 99 780). Saying only "the
             road runs 0 to 106 200" would read as if the value should have
             worked, so name the gap. */
          const lo = j && j.min, hi = j && j.max;
          const inGap = lo != null && hi != null && ch > Number(lo) && ch < Number(hi);
          chlOut('<div class="mstat"><span>CH '+chlEsc(chlFmtKmM(ch))+'</span><b class="mbad">'+(inGap?'not covered':'off the road')+'</b></div>'
               + '<div class="mtime">No section of <b>'+chlEsc(CHL_ROAD.name)+'</b> covers this chainage'
               + (inGap ? ' — it falls in a gap between the road&rsquo;s sections. ' : '. ')
               + 'Mapped sections span <b>CH '+chlFmtM(lo)+'</b> to <b>'+chlFmtM(hi)+'</b>.</div>');
        }
        return;
      }
      chlClear();
      CHL_LAST = {chainage:ch, matches:j.matches||[]};
      chlPlace(ch, CHL_LAST.matches);
    })
    .catch(() => {
      chlClear();
      chlOut('<div class="mstat"><span>CH '+chlEsc(chlFmtKmM(ch))+'</span><b class="mbad">unavailable</b></div>'
           + '<div class="mtime">The locator could not reach the server. Check the connection and try again.</div>');
    });
}

function chlPlace(ch, matches){
  if(!matches.length){ chlOut('<div class="mstat"><span>CH '+chlEsc(chlFmtKmM(ch))+'</span><b class="mbad">no match</b></div>'); return; }
  matches.forEach(m => {
    const el = chlPinEl(ch);
    const mk = new maplibregl.Marker({element:el, anchor:'bottom'})
      .setLngLat([+m.lng, +m.lat])
      .setPopup(new maplibregl.Popup({offset:34, closeButton:false, maxWidth:'300px'}).setHTML(chlPopupHtml(ch, m)))
      .addTo(map);
    CHL_MARKERS.push(mk);
  });
  CHL_MARKERS[0].togglePopup();

  /* The readout spells out BOTH steps, because the section it resolved to is
     the answer the user actually needs to check the tool against. */
  let h = '<div class="mstat"><span>Road chainage</span><b>CH '+chlEsc(chlFmtKmM(ch))+'</b></div>';
  matches.forEach((m, i) => {
    h += '<div class="chres'+(i?' more':'')+'">'
       +   '<div class="chres-s">'+chlEsc(m.section)+'</div>'
       +   '<div class="chres-m">Section covers CH <b>'+chlFmtM(m.road_start)+'</b> – <b>'+chlFmtM(m.road_end)+'</b>'
       +     ' · located <b>'+chlFmtM(m.offset)+'</b> into it</div>'
       +   '<div class="chres-c">'+(+m.lat).toFixed(6)+', '+(+m.lng).toFixed(6)+'</div>'
       + '</div>';
  });
  if(matches.length > 1){
    h += '<div class="mtime">This chainage sits on <b>'+matches.length+' sections</b> — linked roads and dual carriageways share road chainage, so every one is pinned.</div>';
  }
  chlOut(h);
  chlZoom();
}

/* ---------- pane wiring ---------- */

function openChainagePane(){
  if(typeof openPane === 'function') openPane('chainage');
  chlRefresh();
}
/* Rebuild the list whenever the pane is opened: the Road Network filter may
   have changed since last time, and in tile mode RoadsIndex may only have
   finished loading after the first open. */
function chlRefresh(){
  const go = () => { _chlScopeSig = chlScopeSig(); chlRenderScope(); chlRenderRange(); };
  if(typeof RoadsIndex !== 'undefined') RoadsIndex.ensure().then(go, go); else go();
}

/* Called by applyNetFilter() (05-road-network.js) every time the Road Network
   filter changes. A road that has just been filtered off the map must stop
   being the selected road — otherwise Locate would pin a section the user can
   no longer see, which is exactly the confusion the scoping exists to avoid. */
function chlOnNetScope(){
  const sig = chlScopeSig();
  if(sig === _chlScopeSig) return;
  _chlScopeSig = sig;
  if(CHL_ROAD){
    const still = chlRoadList().find(e => e.name === CHL_ROAD.name);
    if(still) CHL_ROAD = still;                 // re-scope its section list
    else { chlClear(); chlClearRoad(false); }
  }
  chlRenderScope();
  chlRenderRange();
}

(function initChainageLocator(){
  const inp = document.getElementById('chlRoad');
  if(!inp) return;
  inp.addEventListener('input', () => { CHL_ROAD = null; chlRenderRange();
    const c = chlEl('chlRoadClear'); if(c) c.classList.toggle('show', !!inp.value);
    chlRenderRoads(inp.value); });
  inp.addEventListener('focus', () => chlRenderRoads(inp.value));
  inp.addEventListener('keydown', e => {
    if(e.key === 'Escape'){ chlCloseRoads(); return; }
    if(e.key === 'Enter'){
      /* Enter on a single remaining match picks it — the common case after
         typing three letters of a road name. */
      const box = chlEl('chlRoadList'), first = box && box.querySelector('.lit');
      if(first){ first.click(); e.preventDefault(); }
    }
  });
  document.addEventListener('click', e => {
    const wrap = document.getElementById('chlCombo');
    if(wrap && !wrap.contains(e.target)) chlCloseRoads();
  });
})();
