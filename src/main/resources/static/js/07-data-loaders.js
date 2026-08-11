/* ============================================================
   KLRAMS viewer · 07-data-loaders.js
   Road & condition-segment loaders and the parallel-lane condition layers.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
/* Build: the roads/segments GeoJSON responses are several MB and this host is
   slow enough that the connection sometimes gets reset mid-transfer
   (net::ERR_CONNECTION_RESET) before the body finishes — a proxy/timeout
   issue upstream of this app, not a bad request. Retry a couple of times
   with backoff instead of leaving the map silently empty after one blip. */
function fetchJsonRetry(url,tries){
  function attempt(n){
    return fetch(url).then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();}).catch(function(err){
      if(n>1)return new Promise(function(res){setTimeout(res,900);}).then(function(){return attempt(n-1);});
      throw err;
    });
  }
  return attempt(tries||3);
}
/* Build 172 — road-network load recovery.
   The old guard treated map.getSource('roadnet') as "the network is loaded", but
   a successful load adds a source AND three layers. Anything that threw between
   the two (style not finished loading, a paint expression the validator rejects,
   a connection reset after the source was created) left the source present and
   the layers missing — and because every later loadRoads() short-circuited on
   that source, the road network could never reappear for the rest of the
   session. That is the "sometimes doesn't load, and then won't reload" bug.
   Three changes: the ready-check tests the LAYER, a failed attempt tears down
   whatever half-built state it left so the next one starts clean, and the
   failure message carries a real Retry button instead of telling the user to
   toggle the layer off and on. */
let _roadsInflight=null,_roadsWired=false;
function roadsReady(){return !!(map.getSource('roadnet')&&map.getLayer('roadnet'));}
/* Drop a half-built network so a retry rebuilds from scratch. Layers first —
   MapLibre refuses to remove a source that still has layers on it. */
function teardownRoads(){
  ['roadnet-hit','roadnet','roadnet-casing'].forEach(function(id){try{if(map.getLayer(id))map.removeLayer(id);}catch(e){}});
  try{if(map.getSource('roadnet'))map.removeSource('roadnet');}catch(e){}
}
function roadsStatus(msg,retry){
  const st=document.getElementById('status');if(!st)return;
  st.textContent=msg;
  if(!retry)return;
  const b=document.createElement('button');
  b.type='button';b.textContent='Retry';
  b.style.cssText='margin-left:8px;padding:1px 9px;font:inherit;font-size:11px;cursor:pointer;border:1px solid currentColor;border-radius:10px;background:transparent;color:inherit';
  b.onclick=function(){roadsStatus('Loading road network…',false);loadRoads(true);};
  st.appendChild(b);
}
/* Registered once and kept across teardown/rebuild: MapLibre keys delegated
   listeners by layer id, so re-adding 'roadnet-hit' reuses them. Re-registering
   on every rebuild would fire onPick twice per click. */
function wireRoadHandlers(){
  if(_roadsWired)return;_roadsWired=true;
  map.on('click','roadnet-hit',e=>{const _t=map.queryRenderedFeatures(e.point)[0];if(_t&&/^(as-|pci|trafficstn)/.test(_t.layer.id))return;if(e.features.length){let _lane=null;try{const _cl=CONDLAYERS.filter(id=>map.getLayer(id));if(_cl.length){const _lf=map.queryRenderedFeatures(e.point,{layers:_cl});if(_lf&&_lf.length){const _m=/^seg-(.+)$/.exec(_lf[0].layer.id);if(_m)_lane=_m[1];}}}catch(_e){}onPick(e.features[0].properties.road,e.lngLat,_lane);}});
  map.on('mouseenter','roadnet-hit',()=>map.getCanvas().style.cursor='pointer');
  map.on('mouseleave','roadnet-hit',()=>map.getCanvas().style.cursor='');
}
function renderRoads(gj,noFit){
  gj.features.forEach(f=>{ROADS[f.properties.road]=f;});
  buildAttrMeta(gj);
  renderNetLegend(null);
  if(map.getSource('roadnet')&&map.getLayer('roadnet')){
    map.getSource('roadnet').setData(gj);
  }else{
    /* A source left over from a half-built attempt would make addSource throw
       ("There is already a source with this ID"), so clear before rebuilding. */
    teardownRoads();
    map.addSource('roadnet',{type:'geojson',data:gj});
    map.addLayer({id:'roadnet-casing',type:'line',source:'roadnet',layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':'#0b1322','line-width':netCasingWidth(),'line-opacity':netCasingOpacity()}});
    map.addLayer({id:'roadnet',type:'line',source:'roadnet',layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':netColor(),'line-width':netWidth(),'line-opacity':netFillOpacity()}});
    map.addLayer({id:'roadnet-hit',type:'line',source:'roadnet',layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':'#000000','line-opacity':0.01,'line-width':['interpolate',['linear'],['zoom'],8,12,12,16,16,24]}});
    wireRoadHandlers();
  }
  /* build 120 — match the styled survey lines to the Road-network toggle (so
     preloading roads on startup does NOT force them visible), but keep the
     invisible hit layer ALWAYS clickable for Video-on-click. */
  {const _sr=document.getElementById('showRoads');const _vv=(_sr&&_sr.checked)?'visible':'none';if(map.getLayer('roadnet'))map.setLayoutProperty('roadnet','visibility',_vv);if(map.getLayer('roadnet-casing'))map.setLayoutProperty('roadnet-casing','visibility',_vv);if(map.getLayer('roadnet-hit'))map.setLayoutProperty('roadnet-hit','visibility','visible');}
  const b=new maplibregl.LngLatBounds();
  gj.features.forEach(f=>{const g=f.geometry;if(!g)return;const w=a=>{if(typeof a[0]==='number')b.extend(a);else a.forEach(w);};if(g.coordinates)w(g.coordinates);});
  if(!noFit&&!b.isEmpty())map.fitBounds(b,{padding:50});
}
/* The road network's ROAD_TILE_LAYER inside the MVT, as RoadTileService names it.
   Every layer bound to the vector 'roadnet' source must declare it, or MapLibre
   silently renders nothing at all -- no error, no warning, just a blank layer. */
const ROAD_TILE_LAYER='roads';
/* Build the vector-tile roadnet source + its three layers, with none of the
   ~4 MB GeoJSON fetch loadRoads() otherwise needs: the tile source only ever
   asks for the current viewport, and the whole-network questions (search,
   the attribute filter, the asset register) are answered by RoadsIndex
   instead, which is 43x lighter because it carries no geometry at all. */
function ensureRoadSource(){
  if(roadsReady())return Promise.resolve();
  teardownRoads();
  map.addSource('roadnet',{type:'vector',
    tiles:[location.origin+'/api/roads/tiles/{z}/{x}/{y}.mvt'],
    minzoom:0,maxzoom:16});
  const mk=(id,extra)=>Object.assign({id:id,type:'line',source:'roadnet','source-layer':ROAD_TILE_LAYER},extra);
  map.addLayer(mk('roadnet-casing',{layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':'#0b1322','line-width':netCasingWidth(),'line-opacity':netCasingOpacity()}}));
  map.addLayer(mk('roadnet',{layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':netColor(),'line-width':netWidth(),'line-opacity':netFillOpacity()}}));
  map.addLayer(mk('roadnet-hit',{layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':'#000000','line-opacity':0.01,'line-width':['interpolate',['linear'],['zoom'],8,12,12,16,16,24]}}));
  wireRoadHandlers();
  /* Same rule as the GeoJSON path: preloading roads must not force them
     visible, but the invisible hit layer stays clickable always. */
  {const _sr=document.getElementById('showRoads');const _vv=(_sr&&_sr.checked)?'visible':'none';
   map.setLayoutProperty('roadnet','visibility',_vv);
   map.setLayoutProperty('roadnet-casing','visibility',_vv);
   map.setLayoutProperty('roadnet-hit','visibility','visible');}
  return Promise.resolve();
}
/* Line-referenced assets (bridges, line furniture) and chainage-placed
   traffic stations both need REAL geometry for whichever roads they
   reference -- not one road (NSV's need), not metadata (search/filter/
   register's need). That requirement is untouched by the tile flip and
   costs exactly what it always has; this exists only so ensureRoads()
   (04-geo-helpers-boundaries.js) and trfEnsureRoads() (16-traffic.js)
   share ONE fetch instead of each bypassing loadRoads()'s TILES_ON gate
   and re-requesting the full network directly. In GeoJSON mode, if the
   normal preload already populated ROADS, this is a no-op -- identical to
   today. In tile mode it still has to fetch the full network once, the
   same cost this already carried before any of this migration existed. */
let _fullRoadsPromise=null;
function ensureFullRoadsGeojson(){
  if(!TILES_ON&&Object.keys(ROADS).length)return Promise.resolve();
  if(_fullRoadsPromise)return _fullRoadsPromise;
  _fullRoadsPromise=fetchJsonRetry('/api/roads/geojson',3).then(gj=>{
    ((gj&&gj.features)||[]).forEach(f=>{if(f&&f.properties&&f.properties.road!=null)ROADS[f.properties.road]=f;});
    return gj;
  }).catch(()=>null);
  return _fullRoadsPromise;
}
function loadRoads(noFit){
  /* Build 121 — in-flight de-dupe: on slow networks the /api/roads/geojson fetch
     stays open for many seconds and the ready-check below only closes AFTER it
     resolves, so concurrent callers each started their own ~6MB download.
     Share the running promise instead. */
  if(_roadsInflight)return _roadsInflight;
  /* Build 122 — post-resolution guard: the in-flight de-dupe only covers
     CONCURRENT callers. Once the first fetch RESOLVES, _roadsInflight resets to
     null, so a later unconditional caller (13-search.js loadRoads(true), the
     asset modules) re-downloaded the full roads GeoJSON even though the map
     already held it. Build 172 checks the layer rather than just the source, so
     a half-built network is rebuilt instead of being mistaken for a good one. */
  if(roadsReady())return Promise.resolve();
  if(TILES_ON){
    /* Every caller of loadRoads() -- search, the asset modules, 15-main.js's
       preload -- gets tile mode for free from here; none of them need to know
       which mode is active. */
    var _tp=Promise.all([
      ensureRoadSource(),
      RoadsIndex.ensure(),
      (typeof buildAttrMetaFromServer==='function')?buildAttrMetaFromServer():Promise.resolve()
    ]).then(()=>{if(typeof renderNetLegend==='function')renderNetLegend(null);}).catch(err=>{
      teardownRoads();
      roadsStatus('Road network failed to load ('+err.message+').',true);
    });
    _roadsInflight=_tp;
    _tp.then(function(){_roadsInflight=null;},function(){_roadsInflight=null;});
    return _tp;
  }
  var _p=fetchJsonRetry('/api/roads/geojson',3).then(gj=>{
    if(!gj||!gj.features)throw new Error('malformed response');
    if(!gj.features.length){roadsStatus('No road network uploaded yet.',false);return;}
    renderRoads(gj,noFit);
    /* Keep RoadsIndex warm in GeoJSON mode too — the network filter, search
       and asset register prefer it, and without this they saw an empty index
       (0 matches / dead roadnet-hit scope) whenever ?tiles=1 was off. */
    if(typeof RoadsIndex!=='undefined')RoadsIndex.ensure();
  }).catch(err=>{
    /* Leave nothing half-built behind, or the retry would short-circuit. */
    teardownRoads();
    roadsStatus('Road network failed to load ('+err.message+').',true);
  });
  _roadsInflight=_p;
  _p.then(function(){_roadsInflight=null;},function(){_roadsInflight=null;});
  return _p;
}
const LANE_SLOTS=[{x:'CC',off:0},{x:'CL1',off:-1},{x:'CL2',off:-2},{x:'CR1',off:1},{x:'CR2',off:2}];
const CONDLAYERS=LANE_SLOTS.map(s=>'seg-'+s.x);
/* The layer name inside the MVT, as SegmentTileService names it. Every layer
   bound to a vector source must declare it or MapLibre silently renders
   nothing — no error, no warning, just a blank map. */
const SEG_TILE_LAYER='segments';
const CONDWIDTH=['interpolate',['linear'],['zoom'],10,2.6,16,6.5];
function condLaneFilter(x){return ['==',['coalesce',['get','L_'+x],0],1];}
function laneOffset(off){return ['interpolate',['linear'],['zoom'],10,off*2.4,16,off*6];}
function laneColorExpr(xsp){const p=cb.value,fair=+document.getElementById('fair').value,poor=+document.getElementById('poor').value,k=xsp+'_'+p;return ['case',['!',['has',k]],NONE,['step',['to-number',['get',k]],GOOD,fair,FAIR,poor,POOR]];}
function addCondLayers(){if(map.getLayer('seg-CC'))return;/* create the lane layers already matching the showCond toggle, so a background
   preload (toggle off) never flashes the condition colours on before
   KLLayers.syncLazyLayers() runs. */
const _cv=((document.getElementById('showCond')||{}).checked)?'visible':'none';LANE_SLOTS.forEach(s=>{const id='seg-'+s.x;const _l={id:id,type:'line',source:'segs',filter:condLaneFilter(s.x),layout:{'line-cap':'round','visibility':_cv},paint:{'line-color':laneColorExpr(s.x),'line-width':CONDWIDTH,'line-offset':laneOffset(s.off)}};if(TILES_ON)_l['source-layer']=SEG_TILE_LAYER;map.addLayer(_l);map.on('mouseenter',id,()=>map.getCanvas().style.cursor='pointer');map.on('mouseleave',id,()=>map.getCanvas().style.cursor='');});}
/* Create the `segs` source and its lane layers.

   In tile mode this runs WITHOUT any download: the source is a tile template
   and MapLibre fetches only what the viewport needs, so the map is usable
   before a single segment row has been transferred. That is the whole point of
   the exercise — the GeoJSON path had to have the entire network in hand first.

   promoteId lifts seg_id to the feature id so a future per-segment
   setFeatureState (custom PCI weights) has something stable to key on; without
   it MapLibre assigns its own ids per tile and they do not survive a re-fetch. */
function ensureSegSource(){
  if(map.getSource('segs'))return true;
  if(!TILES_ON)return false;                      /* GeoJSON mode builds it in loadSegments */
  map.addSource('segs',{type:'vector',promoteId:'seg_id',
    tiles:[location.origin+'/api/segments/tiles/{z}/{x}/{y}.mvt'],
    minzoom:0,maxzoom:16});
  addCondLayers();
  return true;
}
let _segsInflight=null;
function loadSegments(){if(_segsInflight)return _segsInflight;const _needRoads=!roadsReady();var _sp=Promise.resolve(_needRoads?loadRoads(true):null).then(()=>{document.getElementById('status').textContent='Loading segments…';return fetchJsonRetry('/api/segments/geojson',3).then(gj=>{if(!gj||!gj.features||!gj.features.length){document.getElementById('status').textContent='No condition segments yet. Build them in the data console.';return;}DATA=gj;segsByRoad={};gj.features.forEach(f=>{const r=f.properties.road;(segsByRoad[r]=segsByRoad[r]||[]).push(f);let lv=f.properties.lane_vals;if(typeof lv==='string'){try{lv=JSON.parse(lv);}catch(e){lv=null;}}if(lv&&typeof lv==='object'){Object.keys(lv).forEach(xsp=>{f.properties['L_'+xsp]=1;const o=lv[xsp]||{};Object.keys(o).forEach(k=>{if(o[k]!=null)f.properties[xsp+'_'+k]=o[k];});});}});if(TILES_ON){ensureSegSource();}else if(map.getSource('segs'))map.getSource('segs').setData(gj);else{/* tolerance:0 disables GeoJSON simplification — the default (0.375) collapses short condition segments to zero length at low zoom, so they only appeared once you zoomed in to ~10km. Keep every segment rendered at all zooms. */map.addSource('segs',{type:'geojson',data:gj,tolerance:0});addCondLayers();}applyColors();applyFilter();if(typeof updateNetScopeCard==='function')updateNetScopeCard();document.getElementById('status').textContent='✓ '+gj.features.length+' condition segments loaded.';});}).then(()=>{if(typeof KLLayers!=='undefined')KLLayers.syncLazyLayers();}).catch(e=>{document.getElementById('status').textContent='Error: '+e.message;});_segsInflight=_sp;_sp.then(function(){_segsInflight=null;},function(){_segsInflight=null;});return _sp;}
/* The "Play footage" button only renders when CATALOG[roadId] exists, so this
   fetch has to be reliable:
   - cache:'no-store' — some browsers heuristically cached this GET, so after a
     catalog upload they served a stale (or empty) catalog and the button went
     missing until a hard refresh.
   - retry up to 2x with backoff — a single transient failure used to be swallowed
     silently, leaving CATALOG empty for the whole session (no button on ANY road). */
function loadCatalog(_try){
  return fetch('/api/video/catalog',{cache:'no-store'})
    .then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return r.json();})
    .then(function(c){(c||[]).forEach(function(e){if(e.road)CATALOG[e.road]={file:e.file,direction:e.direction};});})
    .catch(function(err){
      if((_try||0)<2)return new Promise(function(res){setTimeout(res,800);}).then(function(){return loadCatalog((_try||0)+1);});
      console.warn('Video catalog failed to load — "Play footage" buttons will be hidden this session:',err);
    });
}

