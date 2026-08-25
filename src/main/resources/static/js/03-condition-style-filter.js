/* ============================================================
   KLRAMS viewer · 03-condition-style-filter.js
   Condition layer: colour-by parameter, Good/Fair/Poor thresholds, attribute filters and display mode.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
const cb=document.getElementById('colorBy');
PARAMS.forEach(p=>{const o=document.createElement('option');o.value=p.key;o.textContent=p.label;cb.appendChild(o);});
/* build 75 — mirror of the colour-by selector placed on the Layers panel,
   under the "Road Condition Data" toggle. Both controls stay in sync and
   drive the same single-metric colouring of the condition layer. */
const cbHome=document.getElementById('condMetricHome');
if(cbHome){PARAMS.forEach(p=>{const o=document.createElement('option');o.value=p.key;o.textContent=p.label;cbHome.appendChild(o);});}
function syncCondMetricUI(){if(cbHome&&cbHome.value!==cb.value)cbHome.value=cb.value;}
function setCondMetric(key){if(!key||key===cb.value){syncCondMetricUI();return;}cb.value=key;loadThreshDefaults();applyColors();syncCondMetricUI();}
if(cbHome)cbHome.addEventListener('change',e=>setCondMetric(e.target.value));
function loadThreshDefaults(){let p=PMAP[cb.value];if(!p){p=PARAMS[0];cb.value=p.key;}document.getElementById('cbLabel').textContent=p.label;document.getElementById('fair').value=p.fair;document.getElementById('poor').value=p.poor;updateBandKey();}
function resetThresholds(){loadThreshDefaults();applyColors();}
function updateBandKey(){const f=document.getElementById('fair').value,po=document.getElementById('poor').value;const set=(id,v)=>{const el=document.getElementById(id);if(el)el.textContent=v;};set('kGood',f);set('kFairLo',f);set('kFairHi',po);set('kPoor',po);}
loadThreshDefaults();syncCondMetricUI();
/* The two selectors above are filled from PARAMS at load, but the labels in
   PARAMS only become the RMMS cell's own once the attribute catalogue arrives
   (01-config.js). This re-labels what is already on screen when it does.

   Options are RELABELLED, not rebuilt: rebuilding would drop the current
   selection and, on the Layers-panel mirror, the change listener wired above.
   Chaining onto the same promise 01-config.js used guarantees this runs after
   its overlay, because handlers registered later on a promise run later. */
if(window.AttrCatalog)AttrCatalog.ready().then(function(){
  [cb,cbHome].forEach(function(sel){
    if(!sel)return;
    Array.prototype.forEach.call(sel.options,function(o){
      const p=PMAP[o.value];if(p)o.textContent=p.label;
    });
  });
  const lbl=document.getElementById('cbLabel'),p=PMAP[cb.value];
  if(lbl&&p)lbl.textContent=p.label;
  // Filter rows carry their own copy of the parameter list.
  if(filters.length)renderFilters();
});
function colorExpr(){const p=cb.value,fair=+document.getElementById('fair').value,poor=+document.getElementById('poor').value;return ['case',['==',['coalesce',['get',p],-1],-1],NONE,['step',['get',p],GOOD,fair,FAIR,poor,POOR]];}
function applyColors(){LANE_SLOTS.forEach(s=>{const id='seg-'+s.x;if(map.getLayer(id))map.setPaintProperty(id,'line-color',laneColorExpr(s.x));});}
const OPS={'>':'>','>=':'>=','=':'==','<=':'<=','<':'<'};
function addFilter(p){filters.push({param:p||'iri',op:'>',val:''});renderFilters();applyFilter();}
function clearFilters(){filters=[];renderFilters();applyFilter();}
function setMode(m){mode=m;document.getElementById('mAll').classList.toggle('on',m==='all');document.getElementById('mAny').classList.toggle('on',m==='any');applyFilter();}
function renderFilters(){const box=document.getElementById('filters');box.innerHTML='';filters.forEach((f,i)=>{const row=document.createElement('div');row.className='frow';const ps=PARAMS.map(p=>`<option value="${p.key}" ${p.key===f.param?'selected':''}>${p.label}</option>`).join('');const os=Object.keys(OPS).map(o=>`<option ${o===f.op?'selected':''}>${o}</option>`).join('');row.innerHTML=`<select>${ps}</select><select>${os}</select><input type="number" step="0.1" value="${f.val}"><span class="x">&times;</span>`;const[sp,so,iv]=row.querySelectorAll('select,input');sp.onchange=e=>{f.param=e.target.value;applyFilter();};so.onchange=e=>{f.op=e.target.value;applyFilter();};iv.oninput=e=>{f.val=e.target.value;applyFilter();};row.querySelector('.x').onclick=()=>{filters.splice(i,1);renderFilters();applyFilter();};box.appendChild(row);});}
function activeRows(){return filters.filter(f=>f.val!==''&&!isNaN(+f.val));}
function filterExpr(){const r=activeRows();if(!r.length)return null;return [mode==='all'?'all':'any',...r.map(f=>['all',['!=',['coalesce',['get',f.param],-1],-1],[OPS[f.op],['get',f.param],+f.val]])];}
let _condFitT=null;
function matchingFeatures(){return Segs.matching(activeRows(),mode);}
function matchCount(){if(!Segs.collection())return;const rows=activeRows();const fts=matchingFeatures();const total=Segs.count();const n=fts?fts.length:total;document.getElementById('matchInfo').textContent=rows.length?(n+' of '+total+' segments match'):'';}
/* Tile mode has no segments array to count or to fit bounds around, so both
   come from /api/segments/match instead — the same two numbers, derived by SQL
   over the whole network rather than by scanning a download.

   The sequence guard matters more here than it looks: typing in a threshold box
   fires on every keystroke, so several requests are in flight at once and they
   can finish out of order. Without it the label settles on whichever response
   happens to land last, which is not necessarily the newest filter. */
let _matchSeq=0;
function refreshMatchStatsRemote(){
  const info=document.getElementById('matchInfo');
  const rows=activeRows();
  if(_condFitT)clearTimeout(_condFitT);
  if(!rows.length){if(info)info.textContent='';return;}
  const seq=++_matchSeq;
  Segs.matchStats(rows,mode).then(function(s){
    if(seq!==_matchSeq)return;                 /* a newer filter superseded this */
    if(info)info.textContent=s.count+' of '+s.total+' segments match';
    if(s.bbox&&s.count)_condFitT=setTimeout(function(){
      try{map.fitBounds([[s.bbox[0],s.bbox[1]],[s.bbox[2],s.bbox[3]]],{padding:60});}catch(e){}
    },550);
  }).catch(function(){if(seq===_matchSeq&&info)info.textContent='';});
}
function applyFilter(){const ex=filterExpr();LANE_SLOTS.forEach(s=>{const id='seg-'+s.x;if(!map.getLayer(id))return;const base=condLaneFilter(s.x);map.setFilter(id,ex?['all',base,ex]:base);});if(TILES_ON){refreshMatchStatsRemote();return;}matchCount();if(_condFitT)clearTimeout(_condFitT);const _fts=matchingFeatures();if(_fts&&_fts.length)_condFitT=setTimeout(()=>fitFeaturesBounds(_fts),550);}
cb.addEventListener('change',()=>{loadThreshDefaults();applyColors();syncCondMetricUI();});
['fair','poor'].forEach(id=>document.getElementById(id).addEventListener('input',()=>{applyColors();updateBandKey();}));
document.getElementById('showRoads').addEventListener('change',e=>{/* Pre-warming the whole condition network here only makes sense in GeoJSON mode,
   where that download happens anyway. In tile mode it is the ONE thing turning
   this toggle on was supposed to stop costing, and the popup no longer needs it:
   it calls ensureSegDataForRoad() on click (08-condition-popup-nsv.js), which
   fetches just the clicked road. */if(e.target.checked&&!TILES_ON&&typeof ensureSegData==='function')ensureSegData();/* Build 172 — roadsReady() (not just the source) so toggling the layer can
   rebuild a half-built network instead of flipping visibility on nothing. */
if(e.target.checked&&!roadsReady()){loadRoads();return;}const _v=e.target.checked?'visible':'none';if(map.getLayer('roadnet'))map.setLayoutProperty('roadnet','visibility',_v);if(map.getLayer('roadnet-casing'))map.setLayoutProperty('roadnet-casing','visibility',_v);});
document.getElementById('showCond').addEventListener('change',e=>{if(e.target.checked&&!map.getSource('segs')){/* Tiles need only a source + layers, not the whole-network GeoJSON this
   toggle used to pull down just to draw a viewport. loadSegments() stays the
   GeoJSON-mode path; ensureSegSource() is the same call the boot preload
   already makes in tile mode. */if(TILES_ON){ensureSegSource();}else{loadSegments();return;}}CONDLAYERS.forEach(id=>{if(map.getLayer(id))map.setLayoutProperty(id,'visibility',e.target.checked?'visible':'none');});});
document.getElementById('showDist').addEventListener('change',e=>{const v=e.target.checked;if(v&&!map.getSource('district')){ensureBoundary('district').then(n=>{if(!n)alert('No district boundary imported yet — upload it in the Data console.');});return;}['district-fill','district-line','district-casing','district-label'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',v?'visible':'none');});});
document.getElementById('showCons').addEventListener('change',e=>{const v=e.target.checked;if(v&&!map.getSource('constituency')){ensureBoundary('constituency').then(n=>{if(!n)alert('No constituency boundary imported yet — upload it in the Data console.');});return;}['cons-fill','cons-line','cons-label'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',v?'visible':'none');});});

