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
function matchingFeatures(){const rows=activeRows();if(!DATA||!rows.length)return null;return DATA.features.filter(ft=>{const p=ft.properties;const t=rows.map(f=>{const raw=p[f.param];if(raw==null)return false;const v=+raw;switch(f.op){case'>':return v>+f.val;case'>=':return v>=+f.val;case'<':return v<+f.val;case'<=':return v<=+f.val;case'=':return v==+f.val;}});return mode==='all'?t.every(Boolean):t.some(Boolean);});}
function matchCount(){if(!DATA)return;const rows=activeRows();const fts=matchingFeatures();const n=fts?fts.length:DATA.features.length;document.getElementById('matchInfo').textContent=rows.length?(n+' of '+DATA.features.length+' segments match'):'';}
function applyFilter(){const ex=filterExpr();LANE_SLOTS.forEach(s=>{const id='seg-'+s.x;if(!map.getLayer(id))return;const base=condLaneFilter(s.x);map.setFilter(id,ex?['all',base,ex]:base);});matchCount();if(_condFitT)clearTimeout(_condFitT);const _fts=matchingFeatures();if(_fts&&_fts.length)_condFitT=setTimeout(()=>fitFeaturesBounds(_fts),550);}
cb.addEventListener('change',()=>{loadThreshDefaults();applyColors();syncCondMetricUI();});
['fair','poor'].forEach(id=>document.getElementById(id).addEventListener('input',()=>{applyColors();updateBandKey();}));
document.getElementById('showRoads').addEventListener('change',e=>{if(e.target.checked&&typeof ensureSegData==='function')ensureSegData();if(e.target.checked&&!map.getSource('roadnet')){loadRoads();return;}const _v=e.target.checked?'visible':'none';if(map.getLayer('roadnet'))map.setLayoutProperty('roadnet','visibility',_v);if(map.getLayer('roadnet-casing'))map.setLayoutProperty('roadnet-casing','visibility',_v);});
document.getElementById('showCond').addEventListener('change',e=>{if(e.target.checked&&!map.getSource('segs')){loadSegments();return;}CONDLAYERS.forEach(id=>{if(map.getLayer(id))map.setLayoutProperty(id,'visibility',e.target.checked?'visible':'none');});});
document.getElementById('showDist').addEventListener('change',e=>{const v=e.target.checked;if(v&&!map.getSource('district')){ensureBoundary('district').then(n=>{if(!n)alert('No district boundary imported yet — upload it in the Data console.');});return;}['district-fill','district-line','district-casing','district-label'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',v?'visible':'none');});});
document.getElementById('showCons').addEventListener('change',e=>{const v=e.target.checked;if(v&&!map.getSource('constituency')){ensureBoundary('constituency').then(n=>{if(!n)alert('No constituency boundary imported yet — upload it in the Data console.');});return;}['cons-fill','cons-line','cons-label'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',v?'visible':'none');});});

