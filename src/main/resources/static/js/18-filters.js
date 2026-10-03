/* ============================================================
   KLRAMS viewer · 18-filters.js
   New filters for the unified Filter folder — FWD (D0 mm),
   PCI (value range, both layers), Traffic (min ADT) — plus the
   layer-off lock handling shared by every filter section.
   (Network and Road-Condition filters keep their original logic
   in 03/05, and the Avg IRI 2 km filter lives with its layer in
   32-iri-2km.js; their controls are simply relocated into the folder.)
   ============================================================ */

/* ---------- FWD: filter by D0 deflection (mm) ---------- */
function applyFwdFilter(){
  if(!map.getLayer('as-fwd')) return;
  const mn=parseFloat(document.getElementById('fwdMin').value);
  const mx=parseFloat(document.getElementById('fwdMax').value);
  const conds=[];
  if(!isNaN(mn)) conds.push(['>=',['to-number',['get','__d0']],mn]);
  if(!isNaN(mx)) conds.push(['<=',['to-number',['get','__d0']],mx]);
  const lineFilter = conds.length ? ['all'].concat(conds) : null;
  ['as-fwd','as-fwd-icon'].forEach(id=>{ if(map.getLayer(id)) map.setFilter(id, lineFilter); });
  if(map.getLayer('as-fwd-pt'))
    map.setFilter('as-fwd-pt', ['all',['==',['geometry-type'],'Point']].concat(conds));
}
function clearFwdFilter(){
  document.getElementById('fwdMin').value='';
  document.getElementById('fwdMax').value='';
  ['as-fwd','as-fwd-icon'].forEach(id=>{ if(map.getLayer(id)) map.setFilter(id, null); });
  if(map.getLayer('as-fwd-pt')) map.setFilter('as-fwd-pt', ['==',['geometry-type'],'Point']);
}

/* ---------- PCI: filter both layers by value range ---------- */
function applyPciFilter(){
  const mn=parseFloat(document.getElementById('pciMin').value);
  const mx=parseFloat(document.getElementById('pciMax').value);
  /* Property name follows the render mode: the tile carries pci_def_*, the
     GeoJSON path stamps pci_*. Filtering on the wrong one silently matches
     nothing, which reads as "no segments in that PCI range" rather than as a
     bug. */
  [['pci-avg',pciProp('avg')],['pci-worst',pciProp('worst')]].forEach(([id,prop])=>{
    if(!map.getLayer(id)) return;
    const conds=['all',['!=',['get',prop],-1]];
    if(!isNaN(mn)) conds.push(['>=',['get',prop],mn]);
    if(!isNaN(mx)) conds.push(['<=',['get',prop],mx]);
    map.setFilter(id, conds);
  });
}
function clearPciFilter(){
  document.getElementById('pciMin').value='';
  document.getElementById('pciMax').value='';
  ['pci-avg','pci-worst'].forEach(id=>{ if(map.getLayer(id)) map.setFilter(id, null); });
}

/* ---------- Traffic: filter by minimum ADT ----------
   An ungrouped station is scored on its own ADT (survey total ÷ survey days).
   A station that belongs to a Calculation Rules traffic-station group is scored
   on the group's combined ADT — the same merge the popup and the dashboard use
   (totals add, days take the longer survey) — and every placed member of that
   group receives that one number, so the filter keeps or hides the whole group
   together. A member that was never placed still contributes its counts. */
function trafficCountFor(name){
  const raw=(typeof TRAFFIC_COUNTS!=='undefined')?TRAFFIC_COUNTS[name]:null;
  return (typeof trfCountObj==='function')?trfCountObj(raw):raw;
}
function trafficAdtOf(c){
  if(!c || c.total==null) return -1;
  return Math.round((+c.total||0)/Math.max(1, +c.days||1));
}
function trafficComputeAdt(){
  if(typeof TRAFFIC_STN==='undefined' || !TRAFFIC_STN.features) return;
  const byName=new Map();
  TRAFFIC_STN.features.forEach(f=>{
    const p=f.properties||(f.properties={});
    const name=String(p.name==null?'':p.name).trim();
    p.__adt=trafficAdtOf(trafficCountFor(name));
    if(!name) return;
    const list=byName.get(name);
    if(list) list.push(f); else byName.set(name,[f]);
  });
  const groups=(typeof CalcRules!=='undefined')?CalcRules.stationGroups:null;
  if(groups && typeof CalcRules.stationGroupMembers==='function' && typeof trfMergeCounts==='function'){
    const done=new Set();
    Object.keys(groups).forEach(function(name){
      if(done.has(name)) return;
      const members=CalcRules.stationGroupMembers(name);
      if(!members || members.length<2) return;
      members.forEach(m=>done.add(m));
      const merged=trfMergeCounts(members.map(trafficCountFor));
      const adt=merged ? Math.round((merged.total||0)/Math.max(1, merged.days||1)) : -1;
      members.forEach(m=>{(byName.get(m)||[]).forEach(f=>{ f.properties.__adt=adt; });});
    });
  }
  if(map.getSource('trafficstn')) map.getSource('trafficstn').setData(TRAFFIC_STN);
}
function applyTrafficFilter(){
  if(!map.getLayer('trafficstn-lyr')) return;
  const run=function(){
    trafficComputeAdt();
    const mn=parseFloat(document.getElementById('trfMin').value);
    if(isNaN(mn)) map.setFilter('trafficstn-lyr', null);
    else map.setFilter('trafficstn-lyr', ['all',['!=',['get','__adt'],-1],['>=',['get','__adt'],mn]]);
  };
  /* Groups arrive a moment after the page. Filtering before they land would
     score each carriageway on its own ADT and split a dual station. */
  if(typeof CalcRules!=='undefined' && CalcRules.ready && !CalcRules.stationGroups) CalcRules.ready().then(run, run);
  else run();
}
function clearTrafficFilter(){
  document.getElementById('trfMin').value='';
  if(map.getLayer('trafficstn-lyr')) map.setFilter('trafficstn-lyr', null);
}

/* ---------- Bridge / Culvert: attribute filters ----------
   Unlike FWD/PCI, these asset types have no fixed schema — road_assets.attrs is a free-form
   jsonb bag of whatever the CSV upload's headers were (AssetTileService), so the map layer
   (tile OR geojson) has no single property name to filter on directly. Instead: pull the
   whole-network attrs already downloaded for analysis (ASSET_DATA, via loadAssetData), match
   client-side using the same fuzzy column matching pickProp()/ckey() use elsewhere, then filter
   the map layer by the ids of the matches. Every asset feature carries its road_assets.id as
   either `asset_id` (tile mode) or `__id` (geojson mode; AssetController.geojson()) — coalesce
   picks whichever the current render mode used.
   Sub-Grade Soil and Bituminous Core used to have their own narrow version of this (a type
   dropdown, one or two numeric ranges) — replaced by the generic per-attribute, all-operator
   section builder in 37-layer-filters.js (assetTarget()), which reaches every CSV column
   instead of the two or three this file used to hard-code. */
function assetByType(t){ return ASSETS.find(a=>a.type===t); }
const BRIDGE_TYPE_KEYS=['bridgetype','structuretype'];
const CULVERT_TYPE_KEYS=['culverttype','type'];

function assetFilterOptions(type,keys){
  const gj=ASSET_DATA[type]; const seen=new Set();
  ((gj&&gj.features)||[]).forEach(f=>{const v=pickProp(f.properties,keys);if(v!=null&&String(v).trim()!=='')seen.add(String(v).trim());});
  return Array.from(seen).sort((a,b)=>a.localeCompare(b));
}
function fillAssetTypeSelect(selId,type,keys){
  const sel=document.getElementById(selId); if(!sel) return;
  const cur=sel.value;
  const opts=assetFilterOptions(type,keys);
  sel.innerHTML='<option value="">Any type</option>'+opts.map(o=>{const e=String(o).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/"/g,'&quot;');return '<option value="'+e+'">'+e+'</option>';}).join('');
  if(opts.indexOf(cur)>=0)sel.value=cur;
}
function ensureAssetFilterUI(type,selId,keys){
  const a=assetByType(type); if(!a) return Promise.resolve();
  return loadAssetData(a).then(()=>{ fillAssetTypeSelect(selId,type,keys); });
}
function assetMatchIds(type,test){
  const gj=ASSET_DATA[type]; const ids=[];
  ((gj&&gj.features)||[]).forEach(f=>{const p=f.properties||{}; const id=p.__id!=null?p.__id:p.asset_id; if(id==null)return; if(test(p))ids.push(id);});
  return ids;
}
function applyAssetIdFilter(a,ids){
  const expr = ids ? ['in',['coalesce',['get','asset_id'],['get','__id']],['literal',ids]] : null;
  [a.layer,a.layer+'-icon',a.layer+'-pt'].forEach(id=>{ if(map.getLayer(id)) map.setFilter(id, expr); });
}
function assetNum(raw){ if(raw==null||raw==='')return NaN; return parseFloat(String(raw).replace(/,/g,'')); }

function applyBridgeFilter(){
  const a=assetByType('bridge'); if(!a||!map.getLayer(a.layer))return;
  loadAssetData(a).then(()=>{
    const val=document.getElementById('brgType').value;
    if(!val){ applyAssetIdFilter(a,null); return; }
    applyAssetIdFilter(a,assetMatchIds('bridge',p=>String(pickProp(p,BRIDGE_TYPE_KEYS)||'').trim()===val));
  });
}
function clearBridgeFilter(){
  const sel=document.getElementById('brgType'); if(sel)sel.value='';
  const a=assetByType('bridge'); if(a)applyAssetIdFilter(a,null);
}

function applyCulvertFilter(){
  const a=assetByType('culvert'); if(!a||!map.getLayer(a.layer))return;
  loadAssetData(a).then(()=>{
    const val=document.getElementById('culvType').value;
    if(!val){ applyAssetIdFilter(a,null); return; }
    applyAssetIdFilter(a,assetMatchIds('culvert',p=>String(pickProp(p,CULVERT_TYPE_KEYS)||'').trim()===val));
  });
}
function clearCulvertFilter(){
  const sel=document.getElementById('culvType'); if(sel)sel.value='';
  const a=assetByType('culvert'); if(a)applyAssetIdFilter(a,null);
}

/* ---------- layer-off locks ---------- */
function fLayerOn(id){ const e=document.getElementById(id); return e?e.checked:false; }
function enableLayer(id){
  const e=document.getElementById(id);
  if(e && !e.checked){ e.checked=true; e.dispatchEvent(new Event('change')); }
  setTimeout(refreshFilterLocks,80);
}
function refreshFilterLocks(){
  [['fsecSelRoad','showRoads'],['fsecNet','showRoads'],['fsecCond','showCond'],['fsecTrf','showTraffic'],
   ['fsecFwd','showFwd'],['fsecIri','showIri2km'],['fsecPci',null],
   ['fsecBridge','showBridge'],['fsecCulv','showCulvert']].forEach(([sec,layer])=>{
    const s=document.getElementById(sec); if(!s) return;
    const on = (sec==='fsecPci') ? (fLayerOn('showPciAvg')||fLayerOn('showPciWorst')) : fLayerOn(layer);
    s.classList.toggle('locked', !on);
  });
}
(function(){
  if(typeof map==='undefined') return;
  ['showRoads','showCond','showTraffic','showFwd','showIri2km','showPciAvg','showPciWorst',
   'showBridge','showCulvert','showSoil','showCore'].forEach(id=>{
    const e=document.getElementById(id);
    if(e) e.addEventListener('change',()=>setTimeout(refreshFilterLocks,40));
  });
  /* Populate the type dropdowns as soon as there is data to populate them from —
     either the layer is switched on, or its Filter-folder section is opened
     while the layer is already on (loadAssetData() is cached, so this costs
     nothing on the second call either way). */
  const brgOn=document.getElementById('showBridge'), culvOn=document.getElementById('showCulvert');
  if(brgOn) brgOn.addEventListener('change',()=>{ if(brgOn.checked) ensureAssetFilterUI('bridge','brgType',BRIDGE_TYPE_KEYS); });
  if(culvOn) culvOn.addEventListener('change',()=>{ if(culvOn.checked) ensureAssetFilterUI('culvert','culvType',CULVERT_TYPE_KEYS); });
  /* Same idea for the Road Network section, whose column list can be missing
     for its own reasons (see ensureNetAttrs in 05-road-network.js): opening the
     section is the moment it is needed, so that is when it is fetched. */
  const secNet=document.getElementById('fsecNet');
  if(secNet) secNet.addEventListener('toggle',()=>{
    if(secNet.open&&typeof ensureNetAttrs==='function'&&!netAttrsLoaded())
      ensureNetAttrs().then(()=>{if(typeof renderNetFilters==='function')renderNetFilters();});
  });
  const secBrg=document.getElementById('fsecBridge'), secCulv=document.getElementById('fsecCulv');
  if(secBrg) secBrg.addEventListener('toggle',()=>{ if(secBrg.open&&fLayerOn('showBridge')) ensureAssetFilterUI('bridge','brgType',BRIDGE_TYPE_KEYS); });
  if(secCulv) secCulv.addEventListener('toggle',()=>{ if(secCulv.open&&fLayerOn('showCulvert')) ensureAssetFilterUI('culvert','culvType',CULVERT_TYPE_KEYS); });
})();
