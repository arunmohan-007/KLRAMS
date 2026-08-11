/* ============================================================
   KLRAMS viewer · 04-geo-helpers-boundaries.js
   Shared geometry/name helpers and the administrative boundary layers (district, constituency).
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
function lineOf(feature){const g=feature.geometry;let c=g.type==='MultiLineString'?g.coordinates.flat():g.coordinates;return turf.lineString(c);}
/* Classic SH brown / MDR blue — same look as the original DEFAULT legend. */
const CLS_BY_MODE={
  light:{NH:'#c0392b',SH:'#8a4d1f',MDR:'#1868c4',ODR:'#7d8ea3'},
  dark:{NH:'#ff6b6b',SH:'#d4a574',MDR:'#74c0fc',ODR:'#adb5bd'}
};
let NET_BASE_MODE='light';
let CLS=CLS_BY_MODE.light;
function roadClassKey(){return ['upcase',['trim',['to-string',['coalesce',['get','Road_Class'],'']]]];}
function netColor(){
  const c=['match',roadClassKey()];
  Object.entries(CLS).forEach(([k,v])=>c.push(k,v));
  c.push(CLS.ODR||'#7d8ea3');
  return c;
}
function netCasingColor(){return '#ffffff';}
/* Flat-ish base so every section stays visible; class scale adds hierarchy. */
function pavementWidthExpr(){return ['match',['to-string',['get','Pavement_W']],'1',6,'2',7,'3',8,'4',9,'5',10,8];}
function classWidthScale(){return ['match',roadClassKey(),'NH',1.45,'SH',1.35,'MDR',1.15,'ODR',0.85,1.1];}
function classOpacityScale(){return ['match',roadClassKey(),'NH',1,'SH',1,'MDR',1,'ODR',0.95,1];}
/* Bold statewide strokes so SH brown / MDR blue read clearly at ~100 km zoom
   without a dark blotch (hit stays under paint; halo is white only). */
const NET_WIDTH_STOPS=[[6,0.42],[8,0.7],[10,1.05],[12,1.55],[14,2.8],[16,4.2],[18,6.5]];
const CASING_RATIO=1.2;
function netWidthExpr(){
  const pw=pavementWidthExpr(),cs=classWidthScale();
  const e=['interpolate',['exponential',1.3],['zoom']];
  NET_WIDTH_STOPS.forEach(([z,m])=>{e.push(z,['*',['*',pw,cs],m]);});
  return e;
}
function netWidth(){return netWidthExpr();}
function netCasingWidth(){
  const pw=pavementWidthExpr(),cs=classWidthScale();
  const e=['interpolate',['exponential',1.3],['zoom']];
  NET_WIDTH_STOPS.forEach(([z,m])=>{e.push(z,['*',['*',pw,cs],+(m*CASING_RATIO).toFixed(4)]);});
  return e;
}
function netHitWidth(){return ['interpolate',['linear'],['zoom'],6,0.5,8,1.2,10,3,12,8,14,14,16,20];}
function netSortKey(){return ['match',roadClassKey(),'NH',4,'SH',3,'MDR',2,'ODR',1,0];}
function _scaleOp(expr,userOp){const u=(userOp==null||userOp>=0.999)?null:+userOp;return u==null?expr:['*',expr,Math.max(0.05,Math.min(1,u))];}
/* Only Carto Light is a clean, muted canvas the class colour can stand on by
   itself. Every other basemap (OSM Streets, Satellite, Topo, Night) bakes in
   its own colours/imagery right where our line sits, so a white halo is what
   keeps the class colour reading as OURS instead of blending into the
   basemap underneath it. */
let NET_HALO=true;
function netCasingOpacity(userOp){
  if(!NET_HALO)return 0;
  /* Soft white edge only — enough to lift colour off OSM, not a dark fill. */
  return _scaleOp(['interpolate',['linear'],['zoom'],6,0.35,8,0.5,10,0.7,12,0.9],userOp);
}
function netFillOpacity(userOp){return _scaleOp(classOpacityScale(),userOp);}
function _netUserOpacityFromUi(){
  try{
    const sw=document.getElementById('showRoads');
    const lx=sw&&sw.parentNode&&sw.parentNode.querySelector('.lx.on .lx-op');
    if(lx)return Math.max(0.15,Math.min(1,+lx.value/100));
  }catch(e){}
  return 1;
}
function applyNetUserOpacity(userOp){
  if(userOp==null)userOp=_netUserOpacityFromUi();
  if(map.getLayer('roadnet'))map.setPaintProperty('roadnet','line-opacity',netFillOpacity(userOp));
  if(map.getLayer('roadnet-casing'))map.setPaintProperty('roadnet-casing','line-opacity',netCasingOpacity(userOp));
}
function applyNetBasemapStyle(baseName){
  const n=baseName||((document.getElementById('basemap')||{}).value)||'osm';
  NET_BASE_MODE=(n==='dark'||n==='sat')?'dark':'light';
  NET_HALO=(n!=='light');
  CLS=CLS_BY_MODE[NET_BASE_MODE];
  if(!map.getLayer||!map.getLayer('roadnet'))return;
  try{
    const sel=document.getElementById('netColorBy');
    const byClass=!sel||sel.value==='__class__';
    if(byClass)map.setPaintProperty('roadnet','line-color',netColor());
    map.setPaintProperty('roadnet','line-width',netWidth());
    if(map.getLayer('roadnet')){
      try{map.setLayoutProperty('roadnet','line-sort-key',netSortKey());}catch(e){}
    }
    if(map.getLayer('roadnet-casing')){
      map.setPaintProperty('roadnet-casing','line-color',netCasingColor());
      map.setPaintProperty('roadnet-casing','line-width',netCasingWidth());
    }
    applyNetUserOpacity();
    if(byClass&&typeof renderNetLegend==='function')renderNetLegend(null);
  }catch(e){}
}
const NAME_KEYS=['NAME','Name','name','DISTRICT','District','district','AC_NAME','LAC_NAME','CONSTITUEN','Constituency','LABEL'];
function featName(p){for(const k of NAME_KEYS)if(p&&p[k]!=null&&p[k]!=='')return String(p[k]);return '';}
function nameExpr(){const e=['coalesce'];NAME_KEYS.forEach(k=>e.push(['get',k]));e.push('');return e;}
function boundaryPopup(lngLat,p,title,accent){
  let rows='';Object.keys(p).forEach(k=>{const v=p[k];if(v==null||v==='')return;rows+=`<tr><td class="k">${k}</td><td class="v" style="font-size:11px">${v}</td></tr>`;});
  new maplibregl.Popup({maxWidth:'300px'}).setLngLat(lngLat).setHTML(`<div class="pop"><div class="sec" style="background:${accent}">${title}${featName(p)?(' · '+featName(p)):''}</div><table>${rows}</table></div>`).addTo(map);
}
function clickIsOnFeatures(point,layers){
  const present=layers.filter(l=>map.getLayer(l));
  if(!present.length)return false;
  return map.queryRenderedFeatures(point,{layers:present}).length>0;
}
const BSPEC={
 district:{src:'district',fills:'district-fill',layers:['district-fill','district-casing','district-line','district-label'],toggle:'showDist',title:'District boundary',accent:'#0e2038'},
 constituency:{src:'constituency',fills:'cons-fill',layers:['cons-fill','cons-line','cons-label'],toggle:'showCons',title:'Constituency',accent:'#0d7a51'}};
function addBoundary(type,data){
  const s=BSPEC[type]; if(map.getSource(s.src)){map.getSource(s.src).setData(data);return;}
  const before=map.getLayer('roadnet')?'roadnet':undefined;
  map.addSource(s.src,{type:'geojson',data});
  if(type==='district'){
    map.addLayer({id:'district-fill',type:'fill',source:s.src,paint:{'fill-color':'#0e2038','fill-opacity':0.03}},before);
    map.addLayer({id:'district-casing',type:'line',source:s.src,paint:{'line-color':'#ffffff','line-width':4,'line-opacity':0.7}},before);
    map.addLayer({id:'district-line',type:'line',source:s.src,paint:{'line-color':'#0e2038','line-width':2.4,'line-dasharray':[4,2],'line-opacity':0.85}},before);
    try{map.addLayer({id:'district-label',type:'symbol',source:s.src,layout:{'text-field':nameExpr(),'text-size':13,'text-letter-spacing':0.08,'text-transform':'uppercase'},paint:{'text-color':'#0e2038','text-halo-color':'#ffffff','text-halo-width':1.8,'text-opacity':0.9}},before);}catch(e){}
    map.on('click','district-fill',e=>{
      if(clickIsOnFeatures(e.point,['roadnet','roadnet2','seg-CC','seg-CL1','seg-CL2','seg-CR1','seg-CR2','as-bridge','as-furnl','as-culvert','as-furnp','as-soil','as-core','as-crust','as-fwd','cons-fill']))return;
      if(e.features.length)boundaryPopup(e.lngLat,e.features[0].properties,'District boundary','#0e2038');
    });
  }else{
    map.addLayer({id:'cons-fill',type:'fill',source:s.src,paint:{'fill-color':'#16a06b','fill-opacity':0.05}},before);
    map.addLayer({id:'cons-line',type:'line',source:s.src,paint:{'line-color':'#0d7a51','line-width':1.6,'line-dasharray':[1,2,4,2],'line-opacity':0.85}},before);
    try{map.addLayer({id:'cons-label',type:'symbol',source:s.src,layout:{'text-field':nameExpr(),'text-size':11.5,'text-letter-spacing':0.04},paint:{'text-color':'#0d7a51','text-halo-color':'#ffffff','text-halo-width':1.6,'text-opacity':0.9}},before);}catch(e){}
    map.on('click','cons-fill',e=>{
      if(clickIsOnFeatures(e.point,['roadnet','roadnet2','seg-CC','seg-CL1','seg-CL2','seg-CR1','seg-CR2','as-bridge','as-furnl','as-culvert','as-furnp','as-soil','as-core','as-crust','as-fwd']))return;
      if(e.features.length)boundaryPopup(e.lngLat,e.features[0].properties,'Constituency','#0d7a51');
    });
  }
  const tg=document.getElementById(s.toggle);
  const vis=(tg&&tg.checked)?'visible':'none';
  s.layers.forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',vis);});
}
function ensureBoundary(type){
  return fetch('/api/boundary/'+type).then(r=>r.json()).then(d=>{
    if(d&&d.features&&d.features.length)addBoundary(type,d);
    return d&&d.features?d.features.length:0;
  }).catch(()=>0);
}
function loadBoundaries(){return Promise.all([ensureBoundary('district'),ensureBoundary('constituency')]);}

/* ---- linear referencing: from/to chainage -> stretch along road centreline ---- */
function ensureRoads(){return ensureFullRoadsGeojson().then(()=>{});}
const ROAD_KEYS=['road','label','sectionla','section','sectionlabel','roadid','roadno','roadnumber','roadname','secid'];
const FROM_KEYS=['fromch','fromchainage','startch','startchainage','chainagefrom','chfrom','frch','fromm','startm','from','start'];
const TO_KEYS=['toch','tochainage','endch','endchainage','chainageto','chto','tch','tom','endm','to','end'];
function ckey(k){return String(k).toLowerCase().replace(/[^a-z0-9]/g,'');}
function pickProp(p,cands){if(!p)return null;for(const k in p){if(cands.indexOf(ckey(k))>=0){const v=p[k];if(v!=null&&v!=='')return v;}}return null;}
function resolveRoad(p){let v=pickProp(p,ROAD_KEYS);if(v==null&&p&&p.road!=null)v=p.road;if(v!=null){if(ROADS[v])return v;const sv=String(v).trim();if(ROADS[sv])return sv;}for(const k in p){const val=p[k];if(val==null)continue;const sv=String(val).trim();if(sv&&ROADS[sv])return sv;}if(v!=null){const sv=String(v).trim();for(const k in ROADS){const pr=(ROADS[k]&&ROADS[k].properties)||{};if(String(pr.road)===sv||String(pr.name)===sv)return k;}}return null;}
function chainageStretch(p){const roadKey=resolveRoad(p);if(roadKey==null)return null;const rd=ROADS[roadKey];if(!rd||!rd.geometry)return null;const len=parseFloat(rd.properties&&rd.properties.len)||0;if(!(len>0))return null;let a=parseFloat(pickProp(p,FROM_KEYS)),b=parseFloat(pickProp(p,TO_KEYS));if(isNaN(a)&&isNaN(b))return null;if(isNaN(a))a=b;if(isNaN(b))b=a;a=Math.max(0,Math.min(a,len));b=Math.max(0,Math.min(b,len));if(b<a){const t=a;a=b;b=t;}let line;try{line=lineOf(rd);}catch(e){return null;}const geoLenKm=turf.length(line,{units:'kilometers'});if(!(geoLenKm>0))return null;const sKm=(a/len)*geoLenKm,eKm=(b/len)*geoLenKm;if(eKm-sKm<1e-6){const pt=turf.along(line,sKm,{units:'kilometers'});return pt&&pt.geometry?pt.geometry:null;}try{const sl=turf.lineSliceAlong(line,sKm,eKm,{units:'kilometers'});return sl&&sl.geometry?sl.geometry:null;}catch(err){return null;}}
function linRefFeatures(gj){let ok=0;((gj&&gj.features)||[]).forEach(f=>{const g=chainageStretch(f.properties||{});if(g){f.geometry=g;ok++;}});return ok;}
function isStretchData(gj){const fs=(gj&&gj.features)||[];if(!fs.length)return false;let n=0,sp=0;for(const f of fs){const p=f.properties||{};const fr=pickProp(p,FROM_KEYS),to=pickProp(p,TO_KEYS);if(fr!=null&&to!=null){n++;if(+to!==+fr)sp++;}}return n>0&&sp>=Math.max(1,Math.floor(n*0.5));}
