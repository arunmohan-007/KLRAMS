/* ============================================================
   KLRAMS viewer · 17-measure.js
   Measurement & routing tools: length ruler, polygon area, and
   shortest-path routing along OSM roads (OSRM). Length & area use
   Turf.js; routing calls an OSRM service (endpoint configurable).
   ============================================================ */
let measureMode = null;          // null | 'length' | 'area' | 'route'
let measurePts = [];             // array of [lng,lat]
/* Public OSRM demo server — fine for the pilot. For production, host your own
   OSRM (or use a keyed routing API) and change this URL. */
const OSRM_URL = 'https://router.project-osrm.org/route/v1/driving/';

function mEl(id){ return document.getElementById(id); }
function setMHint(t){ const e=mEl('mHint'); if(e) e.textContent=t; }
function setMOut(h){ const e=mEl('mOut'); if(e){ e.innerHTML=h; e.style.display=h?'block':'none'; } }
function mKillPopups(){ document.querySelectorAll('.maplibregl-popup').forEach(el=>el.remove()); }
function mCursorClass(on){ const c=(map&&map.getContainer)?map.getContainer():document.getElementById('map'); if(c) c.classList.toggle('mmeasure', !!on); }

function fmtLen(km){
  if(!isFinite(km)) return '—';
  return km < 1 ? (km*1000).toFixed(0)+' m' : km.toFixed(2)+' km';
}
function fmtArea(m2){
  if(!isFinite(m2)) return '—';
  if(m2 < 10000) return m2.toFixed(0)+' m²';
  if(m2 < 1000000) return (m2/10000).toFixed(2)+' ha';
  return (m2/1000000).toFixed(2)+' km²';
}
function fmtDur(s){
  s = Math.round(s);
  if(s < 60) return s+' sec';
  const m = Math.round(s/60);
  if(m < 60) return m+' min';
  const h = Math.floor(m/60), mm = m%60;
  return h+' h'+(mm?(' '+mm+' min'):'');
}

function measureEnsureLayers(){
  if(map.getSource('measure-src')) return;
  map.addSource('measure-src', {type:'geojson', data:{type:'FeatureCollection', features:[]}});
  map.addSource('measure-route-src', {type:'geojson', data:{type:'FeatureCollection', features:[]}});
  map.addLayer({id:'measure-fill', type:'fill', source:'measure-src',
    filter:['==',['geometry-type'],'Polygon'],
    paint:{'fill-color':'#1d6fe0','fill-opacity':0.12}});
  map.addLayer({id:'measure-route', type:'line', source:'measure-route-src',
    layout:{'line-cap':'round','line-join':'round'},
    paint:{'line-color':'#0b8a3b','line-width':5,'line-opacity':0.85}});
  map.addLayer({id:'measure-line', type:'line', source:'measure-src',
    filter:['==',['geometry-type'],'LineString'],
    layout:{'line-cap':'round','line-join':'round'},
    paint:{'line-color':'#1d6fe0','line-width':3,'line-dasharray':[2,1.3]}});
  map.addLayer({id:'measure-pts', type:'circle', source:'measure-src',
    filter:['==',['geometry-type'],'Point'],
    paint:{'circle-radius':5,'circle-color':'#ffffff','circle-stroke-color':'#1d6fe0','circle-stroke-width':2.5}});
}

function clearRouteLine(){
  if(map.getSource('measure-route-src'))
    map.getSource('measure-route-src').setData({type:'FeatureCollection', features:[]});
}
function clearDrawn(){
  if(map.getSource('measure-src'))
    map.getSource('measure-src').setData({type:'FeatureCollection', features:[]});
  clearRouteLine();
}

function updateMBtns(){
  [['length','mLen'],['area','mArea'],['route','mRoute']].forEach(([m,id])=>{
    const b=mEl(id); if(b) b.classList.toggle('on', measureMode===m);
  });
}

function setMeasureMode(mode){
  measureEnsureLayers();
  if(measureMode === mode){            // tapping the active tool turns it off
    measureMode = null; measurePts = []; clearDrawn(); updateMBtns();
    map.getCanvas().style.cursor=''; mCursorClass(false); if(map.doubleClickZoom) map.doubleClickZoom.enable();
    setMHint('Pick a tool, then click on the map.'); setMOut('');
    return;
  }
  measureMode = mode; measurePts = []; clearDrawn(); updateMBtns(); mKillPopups();
  map.getCanvas().style.cursor='crosshair'; mCursorClass(true);
  if(map.doubleClickZoom) map.doubleClickZoom.disable();
  setMHint(mode==='length' ? 'Click points along the road — distance adds up. Tap Length again to stop.'
        : mode==='area'   ? 'Click at least 3 points to enclose an area.'
        :                   'Click the origin, then the destination. Add more clicks for stops.');
  setMOut('');
}

function measureUndo(){
  if(!measureMode || !measurePts.length) return;
  measurePts.pop(); measureUpdate();
}
function measureClear(){
  measurePts = []; clearDrawn(); setMOut('');
}

function measureUpdate(){
  if(!map.getSource('measure-src')) return;
  const feats = measurePts.map(c => ({type:'Feature', geometry:{type:'Point', coordinates:c}, properties:{}}));
  let out = '';
  if(measureMode === 'length'){
    if(measurePts.length >= 2){
      const line = {type:'Feature', geometry:{type:'LineString', coordinates:measurePts}, properties:{}};
      feats.push(line);
      out = '<b>'+fmtLen(turf.length(line,{units:'kilometers'}))+'</b><span class="ms">total length · '+measurePts.length+' points</span>';
    } else out = '';
    clearRouteLine();
  } else if(measureMode === 'area'){
    if(measurePts.length >= 3){
      const ring = measurePts.concat([measurePts[0]]);
      const poly = {type:'Feature', geometry:{type:'Polygon', coordinates:[ring]}, properties:{}};
      feats.push(poly);
      const per = turf.length({type:'Feature', geometry:{type:'LineString', coordinates:ring}}, {units:'kilometers'});
      out = '<b>'+fmtArea(turf.area(poly))+'</b><span class="ms">area · perimeter '+fmtLen(per)+'</span>';
    } else {
      if(measurePts.length === 2) feats.push({type:'Feature', geometry:{type:'LineString', coordinates:measurePts}, properties:{}});
      out = '';
    }
    clearRouteLine();
  } else if(measureMode === 'route'){
    if(measurePts.length >= 2){
      feats.push({type:'Feature', geometry:{type:'LineString', coordinates:measurePts}, properties:{}}); // straight connector while routing
      map.getSource('measure-src').setData({type:'FeatureCollection', features:feats});
      measureFetchRoute();   // owns the readout (shows straight line + by-road)
      return;
    } else { clearRouteLine(); out=''; }
  }
  map.getSource('measure-src').setData({type:'FeatureCollection', features:feats});
  setMOut(out);
}

function straightLineKm(){
  if(measurePts.length<2) return 0;
  return turf.length({type:'Feature', geometry:{type:'LineString', coordinates:measurePts}}, {units:'kilometers'});
}
function routeStatHtml(roadKm, straightKm, durSec){
  let road;
  if(typeof roadKm==='number') road='<b>'+fmtLen(roadKm)+'</b>';
  else if(roadKm==='none') road='<b class="mbad">no road route</b>';
  else if(roadKm==='err')  road='<b class="mbad">unavailable</b>';
  else road='<b class="mwait">finding\u2026</b>';
  let h='<div class="mstat"><span>By road</span>'+road+'</div>'
       +'<div class="mstat"><span>Straight line</span><b>'+fmtLen(straightKm)+'</b></div>';
  if(typeof roadKm==='number' && durSec!=null){
    const kmh = durSec>0 ? Math.round(roadKm/(durSec/3600)) : null;
    h+='<div class="mtime">Driving '+fmtLen(roadKm)+' by road takes about '+fmtDur(durSec)+' by car'
       +(kmh?(', at an average speed of '+kmh+' km/h'):'')+'.</div>';
  }
  return h;
}
function measureFetchRoute(){
  const straightKm = straightLineKm();
  setMOut(routeStatHtml('wait', straightKm, null));   // show straight line immediately
  const coords = measurePts.map(c => c[0]+','+c[1]).join(';');
  fetch(OSRM_URL + coords + '?overview=full&geometries=geojson')
    .then(r => r.json())
    .then(j => {
      if(!j || j.code !== 'Ok' || !j.routes || !j.routes.length){
        setMOut(routeStatHtml('none', straightKm, null)); clearRouteLine(); return;
      }
      const rt = j.routes[0];
      map.getSource('measure-route-src').setData({type:'Feature', geometry:rt.geometry, properties:{}});
      setMOut(routeStatHtml(rt.distance/1000, straightKm, rt.duration));
    })
    .catch(() => { setMOut(routeStatHtml('err', straightKm, null)); clearRouteLine(); });
}

(function initMeasure(){
  if(typeof map === 'undefined') return;
  map.on('click', function(e){
    if(!measureMode) return;
    measurePts.push([e.lngLat.lng, e.lngLat.lat]);
    measureUpdate();
    mKillPopups();
  });
  // While a measure tool is active, suppress all feature popups so clicks only drop points.
  if(window.maplibregl && maplibregl.Popup){
    const _add = maplibregl.Popup.prototype.addTo;
    maplibregl.Popup.prototype.addTo = function(m){ return measureMode ? this : _add.call(this, m); };
  }
})();
