/* ============================================================
   KLRAMS viewer · 42-select-by-road.js
   Select by Road — pick road segments straight off the map (click to
   add/remove, no need to hit the line pixel-perfect), then Apply to
   turn the pick list into a Road Network filter. Applying does not
   invent a new scoping mechanism: it sets window.SELECT_ROAD_SCOPE and
   re-runs applyNetFilter() (05-road-network.js), which now intersects
   that with whatever the Road Network attribute filter already has —
   so every road-linked layer (condition, PCI, FWD, traffic, bridges,
   culverts, soil, core…) gets scoped exactly the way the existing
   attribute filter scopes them. Picking itself never touches NET_SCOPE
   — only "Apply Filter" does.
   ============================================================ */
window.SELECT_ROAD_SCOPE = null;

let srPicking = false;
let srApplied = false;
const srPicks = new Map();   // road label -> {road, name, len}

/* Measured length off whichever alias the section carries, same rule
   the scope card uses (05-road-network.js, renderNetScopeCard). */
function srRoadLen(p){
  const mk = Object.keys(p).find(k => /meas/i.test(k) && /len/i.test(k));
  const raw = mk != null ? p[mk] : p.len;
  const n = parseFloat(String(raw == null ? '' : raw).replace(/,/g, ''));
  return isNaN(n) ? 0 : n;
}
function srEsc(s){
  return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
}
/* Extend a plain [minX,minY,maxX,maxY] box with a feature's own geometry —
   walks LineString/MultiLineString coordinate nesting either depth. Only
   used to fly the map to whatever got picked; the picked road's own tile
   geometry is close enough for that; it does not need ROADS[road] to be
   hydrated with the full un-clipped line the way a popup click does. */
function srExtendBBox(box, geom){
  if(!geom) return box;
  const walk = a => { if(typeof a[0] === 'number'){ if(!box) box=[a[0],a[1],a[0],a[1]]; else { box[0]=Math.min(box[0],a[0]); box[1]=Math.min(box[1],a[1]); box[2]=Math.max(box[2],a[0]); box[3]=Math.max(box[3],a[1]); } } else a.forEach(walk); };
  if(geom.coordinates) walk(geom.coordinates);
  return box;
}

/* The highlight layer lives on the SAME 'roadnet' source the network
   itself uses (vector tile or GeoJSON, whichever is live), so it needs
   no data of its own and tracks a re-import automatically. It is never
   scope-limited by NET_SCOPE — it must keep showing every pick even
   while a different (or the same) filter is narrowing everything else. */
function srEnsureHighlightLayer(){
  if(!map.getSource('roadnet')) return false;
  if(map.getLayer('selroad-pick')) return true;
  const src = map.getSource('roadnet');
  const def = {
    id:'selroad-pick', type:'line', source:'roadnet',
    layout:{'line-cap':'round','line-join':'round'},
    paint:{
      'line-color':'#ffb100',
      'line-width':['interpolate',['linear'],['zoom'],6,2.5,12,6,16,11],
      'line-opacity':0.9
    },
    filter:['in',['to-string',['coalesce',['get','road'],'']],['literal',[]]]
  };
  if(src && src.type === 'vector') def['source-layer'] = (typeof ROAD_TILE_LAYER !== 'undefined') ? ROAD_TILE_LAYER : 'roads';
  try{ map.addLayer(def); return true; }catch(e){ return false; }
}
/* The amber pick overlay is feedback for the act of picking, not part of
   the filtered result — once Apply Filter has run, the picked roads must
   render exactly like a road matched by any other filter (plain colour-by
   styling, scoped only via setFilter on 'roadnet'), or they visibly stick
   out in orange forever. So the overlay stays visible while actively
   picking (or before the first Apply, as a preview), and disappears the
   moment picks are applied and picking stops. */
function srHighlightVisibility(){
  return (srPicking || !srApplied) ? 'visible' : 'none';
}
function srUpdateHighlight(){
  if(!srEnsureHighlightLayer()) return;
  const list = Array.from(srPicks.keys());
  try{ map.setFilter('selroad-pick', ['in',['to-string',['coalesce',['get','road'],'']],['literal',list]]); }catch(e){}
  try{ map.setLayoutProperty('selroad-pick', 'visibility', srHighlightVisibility()); }catch(e){}
}

function srStatus(msg, bad){
  const el = document.getElementById('srStatus'); if(!el) return;
  el.textContent = msg || '';
  /* klrams-dark.css sets .statusline{color:...!important}, so only an
     inline !important wins over it — same rule saveNetFilter's status uses. */
  if(bad) el.style.setProperty('color', '#e07b7b', 'important');
  else el.style.removeProperty('color');
}
function srRenderChips(){
  const box = document.getElementById('srChips'); if(!box) return;
  if(!srPicks.size){ box.innerHTML = '<div class="sr-empty">No road segments picked yet — click roads on the map to add them here.</div>'; return; }
  let html = '';
  srPicks.forEach((v, road) => {
    html += '<span class="sr-chip" title="' + srEsc(v.name || road) + '"><span>' + srEsc(v.name || road) + '</span>'
      + '<button type="button" data-road="' + srEsc(road) + '" title="Remove from selection" aria-label="Remove">&times;</button></span>';
  });
  box.innerHTML = html;
  box.querySelectorAll('button[data-road]').forEach(b => { b.onclick = () => srRemove(b.getAttribute('data-road')); });
}
function srUpdateSummary(){
  const cEl = document.getElementById('srCount'), lEl = document.getElementById('srLen'), sEl = document.getElementById('srSummary');
  if(!cEl) return;
  let totalLen = 0; srPicks.forEach(v => { totalLen += v.len || 0; });
  cEl.textContent = srPicks.size;
  if(lEl) lEl.textContent = (totalLen / 1000).toFixed(1) + ' km';
  if(sEl) sEl.style.display = srPicks.size ? 'flex' : 'none';
}
function srUpdateHintBar(){
  const bar = document.getElementById('selRoadHint'); if(!bar) return;
  bar.classList.toggle('show', srPicking);
  const n = document.getElementById('srhCount'); if(n) n.textContent = srPicks.size;
}
function srRefresh(){
  srRenderChips();
  srUpdateSummary();
  srUpdateHighlight();
  srUpdateHintBar();
}

/* Toggling picking on/off never discards what is already picked — only
   the explicit Clear button does that — so switching to check something
   else on the map and coming back keeps the work in progress. */
function srSetPicking(on){
  srPicking = !!on;
  const btn = document.getElementById('srToggleBtn');
  if(btn){ btn.textContent = srPicking ? 'Stop picking' : 'Start picking on map'; btn.classList.toggle('on', srPicking); }
  const c = (map && map.getContainer) ? map.getContainer() : document.getElementById('map');
  if(c) c.classList.toggle('mselroad', srPicking);
  /* Only one click-hijacking map tool at a time: starting a pick session
     while the Measure tool is active turns Measure off the same way
     clicking its own active button would (setMeasureMode toggles off
     when called with the mode already on). */
  if(srPicking && typeof measureMode !== 'undefined' && measureMode && typeof setMeasureMode === 'function') setMeasureMode(measureMode);
  if(srPicking) srEnsureHighlightLayer();
  if(map.getLayer('selroad-pick')){ try{ map.setLayoutProperty('selroad-pick', 'visibility', srHighlightVisibility()); }catch(e){} }
  srUpdateHintBar();
}
function srTogglePick(){ srSetPicking(!srPicking); }

function srOnMapClick(e){
  if(!srPicking) return;
  if(!map.getLayer('roadnet-hit')){ srStatus('Road network is still loading — try again in a moment.', true); return; }
  /* A small box around the click, not a single pixel — "select randomly
     from screen" means the click does not have to land on the line
     itself, only reasonably near it. */
  const pad = 5;
  const feats = map.queryRenderedFeatures(
    [[e.point.x - pad, e.point.y - pad], [e.point.x + pad, e.point.y + pad]],
    {layers:['roadnet-hit']}
  );
  if(!feats.length){ srStatus('No road segment there — click a little closer to a road line.', true); return; }
  const p = feats[0].properties || {};
  const road = p.road != null ? String(p.road) : null;
  if(road == null){ srStatus('That segment has no section label, so it cannot be selected.', true); return; }
  if(srPicks.has(road)){
    srPicks.delete(road);
    srStatus('Removed ' + (p.name || road) + ' from the selection.');
  }else{
    const bbox = srExtendBBox(null, feats[0].geometry);
    srPicks.set(road, {road, name: p.name || road, len: srRoadLen(p), bbox});
    srStatus('Added ' + (p.name || road) + ' to the selection.');
  }
  srRefresh();
  /* Once a filter is already applied, keep it live as picks change —
     matches every other filter section, where editing a field re-applies
     immediately rather than waiting for another click on Apply. */
  if(srApplied) srReapply();
}
function srRemove(road){
  srPicks.delete(road);
  srRefresh();
  if(srApplied) srReapply();
}
function srReapply(){
  window.SELECT_ROAD_SCOPE = srPicks.size ? new Set(srPicks.keys()) : null;
  if(!srPicks.size) srApplied = false;
  if(typeof applyNetFilter === 'function') applyNetFilter();
}
function applySelectRoadFilter(){
  if(!srPicks.size){ srStatus('Pick at least one road segment on the map first.', true); return; }
  srApplied = true;
  window.SELECT_ROAD_SCOPE = new Set(srPicks.keys());
  if(typeof applyNetFilter === 'function') applyNetFilter();
  srSetPicking(false);
  const n = srPicks.size;
  srStatus('Filter applied — ' + n + ' road segment' + (n === 1 ? '' : 's') + ' in scope. Every road-linked layer is now limited to ' + (n === 1 ? 'it' : 'them') + '.');
  srFitToPicks();
}
/* Fly to whatever was picked — the attribute filter's own fit-bounds only
   fires off its own `list`, which stays empty when nothing but a selection
   is driving the scope (see the attrScope/selScope split in
   05-road-network.js applyNetFilter). Built from each pick's own bbox
   (captured at click time from the queried feature's geometry) rather than
   ROADS[road], which in tile mode is only hydrated for roads a popup has
   actually opened. */
function srFitToPicks(){
  if(!window.maplibregl || typeof map === 'undefined') return;
  let box = null;
  srPicks.forEach(v => { if(v.bbox) box = box ? [Math.min(box[0],v.bbox[0]),Math.min(box[1],v.bbox[1]),Math.max(box[2],v.bbox[2]),Math.max(box[3],v.bbox[3])] : v.bbox.slice(); });
  if(!box) return;
  try{ map.fitBounds([[box[0],box[1]],[box[2],box[3]]], {padding:60, maxZoom:16}); }catch(e){}
}
function clearSelectRoadFilter(){
  const hadPicks = srPicks.size, wasApplied = srApplied;
  srPicks.clear();
  srApplied = false;
  window.SELECT_ROAD_SCOPE = null;
  srRefresh();
  if(wasApplied && typeof applyNetFilter === 'function') applyNetFilter();
  srStatus(hadPicks ? 'Selection cleared.' : '');
}

/* ============================================================
   Saved Select-by-Road picks.
   Same table and endpoint as the Road Network attribute filter
   (/api/saved-filters), a different kind — kind is opaque server-side
   (SavedFilterController stores whatever string is posted), so this
   needed no backend change. Payload is {roads:[{road,name,len},...]},
   which is exactly enough to rebuild srPicks and its chips on load; the
   filter itself is still applied through the normal Apply Filter path,
   never restored pre-applied, so loading a saved pick list behaves
   exactly like picking those same roads by hand. */
let SR_SAVED = [];
function _srInfo(msg, bad){
  const el = document.getElementById('srSavedInfo'); if(!el) return;
  el.textContent = msg || '';
  if(bad) el.style.setProperty('color', '#e07b7b', 'important');
  else el.style.removeProperty('color');
}
function srFilterState(){
  return {roads: Array.from(srPicks.values()).map(v => ({road: v.road, name: v.name, len: v.len}))};
}
function renderSrSavedList(){
  const sel = document.getElementById('srSavedSel'); if(!sel) return;
  const keep = sel.value;
  sel.innerHTML = '';
  const ph = document.createElement('option');
  ph.value = ''; ph.textContent = SR_SAVED.length ? '— Select a saved selection —' : '— No saved selections —';
  sel.appendChild(ph);
  SR_SAVED.forEach(s => {
    const o = document.createElement('option');
    o.value = String(s.id);
    o.textContent = s.name + (s.mine ? (s.shared ? ' (shared with all)' : '') : ' (shared by ' + s.owner + ')');
    sel.appendChild(o);
  });
  if(keep && SR_SAVED.some(s => String(s.id) === keep)) sel.value = keep;
  onSrSavedPick();
}
function onSrSavedPick(){
  const sel = document.getElementById('srSavedSel'), del = document.getElementById('srSavedDel');
  if(!sel || !del) return;
  const s = SR_SAVED.find(x => String(x.id) === sel.value);
  del.disabled = !(s && s.mine);
  del.title = s ? (s.mine ? 'Delete "' + s.name + '"' : 'Only ' + s.owner + ' can delete this shared selection') : 'Delete the selected saved selection';
  const nameEl = document.getElementById('srSaveName'), shareEl = document.getElementById('srSaveShared');
  if(s && s.mine){
    if(nameEl) nameEl.value = s.name;
    if(shareEl) shareEl.checked = !!s.shared;
  }else if(!s){
    if(shareEl) shareEl.checked = false;
  }
}
function refreshSrSavedList(){
  return fetch('/api/saved-filters?kind=selectroad', {credentials:'same-origin', headers:{'Accept':'application/json'}})
    .then(r => r.ok ? r.json() : [])
    .then(list => { SR_SAVED = Array.isArray(list) ? list : []; renderSrSavedList(); })
    .catch(() => { SR_SAVED = []; renderSrSavedList(); });
}
function saveSelectRoadFilter(){
  const nameEl = document.getElementById('srSaveName'); if(!nameEl) return;
  const name = nameEl.value.trim();
  if(!name){ _srInfo('Give the selection a name first.', true); nameEl.focus(); return; }
  const state = srFilterState();
  if(!state.roads.length){ _srInfo('Pick at least one road segment before saving.', true); return; }
  const shareEl = document.getElementById('srSaveShared');
  const existing = SR_SAVED.find(s => s.mine && s.name.toLowerCase() === name.toLowerCase());
  const wantShared = !!(shareEl && shareEl.checked);
  if(existing && !confirm('You already have a selection named "' + existing.name + '". Overwrite it?')) return;
  if(existing && existing.shared && !wantShared &&
     !confirm('"' + existing.name + '" is currently shared with all users.\n\nSaving with "Share with all users" unticked will remove it from everyone else. Continue?')) return;
  _srInfo('Saving…');
  fetch('/api/saved-filters', {
    method:'POST', credentials:'same-origin',
    headers:{'Content-Type':'application/json'},
    body: JSON.stringify({kind:'selectroad', name:name, shared: wantShared, payload: state})
  }).then(r => r.json().then(j => ({ok:r.ok, j:j})))
    .then(res => {
      if(!res.ok || !res.j.ok){ _srInfo(res.j.error || 'Could not save the selection.', true); return; }
      nameEl.value = '';
      return refreshSrSavedList().then(() => {
        const saved = SR_SAVED.find(s => s.mine && s.name === res.j.name);
        if(saved) document.getElementById('srSavedSel').value = String(saved.id);
        onSrSavedPick();
        _srInfo('Saved "' + res.j.name + '"' + (res.j.shared ? ' and shared with all users.' : '.'));
      });
    })
    .catch(() => _srInfo('Could not reach the server.', true));
}
function loadSrSaved(){
  const sel = document.getElementById('srSavedSel'); if(!sel) return;
  const s = SR_SAVED.find(x => String(x.id) === sel.value);
  if(!s){ _srInfo('Pick a saved selection to load.', true); return; }
  const p = s.payload || {};
  const roads = Array.isArray(p.roads) ? p.roads : [];
  if(!roads.length){ _srInfo('"' + s.name + '" has no road segments saved.', true); return; }
  srPicks.clear();
  roads.forEach(r => { if(r && r.road != null) srPicks.set(String(r.road), {road:String(r.road), name:r.name || String(r.road), len:r.len || 0}); });
  srRefresh();
  if(srApplied) srReapply();
  _srInfo('Loaded "' + s.name + '" — ' + srPicks.size + ' road segment' + (srPicks.size === 1 ? '' : 's') + '. Click Apply Filter to use it.');
}
function deleteSrSaved(){
  const sel = document.getElementById('srSavedSel'); if(!sel) return;
  const s = SR_SAVED.find(x => String(x.id) === sel.value);
  if(!s || !s.mine) return;
  if(!confirm('Delete the saved selection "' + s.name + '"?' + (s.shared ? '\n\nIt is shared, so it will disappear for all users.' : ''))) return;
  fetch('/api/saved-filters/' + encodeURIComponent(s.id), {method:'DELETE', credentials:'same-origin'})
    .then(r => r.json().then(j => ({ok:r.ok, j:j})))
    .then(res => {
      if(!res.ok || !res.j.ok){ _srInfo(res.j.error || 'Could not delete the selection.', true); return; }
      sel.value = '';
      return refreshSrSavedList().then(() => _srInfo('Deleted "' + s.name + '".'));
    })
    .catch(() => _srInfo('Could not reach the server.', true));
}
/* "Share with all users" is an ADMIN/SUPER_ADMIN control, same rule and
   same event as the Road Network filter's share row (05-road-network.js). */
function _srApplyRole(role){
  const row = document.getElementById('srShareRow'); if(!row) return;
  row.style.display = (role === 'ADMIN' || role === 'SUPER_ADMIN') ? '' : 'none';
}
document.addEventListener('kl-role-ready', e => _srApplyRole(e.detail));
if(typeof window.__klRole !== 'undefined') _srApplyRole(window.__klRole);

(function(){
  if(typeof map === 'undefined') return;
  map.on('click', srOnMapClick);
  /* The lock overlay already hides this section's controls when the Road
     network layer is off (18-filters.js refreshFilterLocks), but the map
     click listener above has no idea the section is hidden — stop an
     active pick session explicitly so clicks on a hidden network are not
     silently swallowed. */
  const showRoadsEl = document.getElementById('showRoads');
  if(showRoadsEl) showRoadsEl.addEventListener('change', () => { if(!showRoadsEl.checked && srPicking) srSetPicking(false); });
  srRefresh();
  if(document.readyState === 'loading') document.addEventListener('DOMContentLoaded', refreshSrSavedList);
  else refreshSrSavedList();
})();
