/* Road survey video player (video.html).
   Lifted out of an inline <script> so the page carries no inline
   script, which is what lets script-src drop 'unsafe-inline'. */
const map=new maplibregl.Map({
  container:'map',
  style:{version:8,sources:{osm:{type:'raster',tiles:['https://a.tile.openstreetmap.org/{z}/{x}/{y}.png','https://b.tile.openstreetmap.org/{z}/{x}/{y}.png','https://c.tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenStreetMap'}},layers:[{id:'osm',type:'raster',source:'osm'}]},
  center:[76.95,8.52], zoom:9
});
map.addControl(new maplibregl.NavigationControl(),'top-right');
const video=document.getElementById('video');
const chOverlay=document.getElementById('chOverlay');
let dir='fwd', cur=null, marker=null, carIcon=null, carLabel=null, seeking=false, lastChainage=0;
let CATALOG={};
function setDir(d){ dir=d; document.getElementById('fwd').classList.toggle('on',d==='fwd'); document.getElementById('rev').classList.toggle('on',d==='rev'); }
function addRoads(gj){
  if(map.getSource('roads')) return;
  map.addSource('roads',{type:'geojson',data:gj});
  map.addLayer({id:'roads',type:'line',source:'roads',paint:{'line-color':'#3b6fa0','line-width':['interpolate',['linear'],['zoom'],9,2,16,6]}});
  map.addLayer({id:'roads-hi',type:'line',source:'roads',paint:{'line-color':'#1d9e75','line-width':['interpolate',['linear'],['zoom'],9,4,16,9]},filter:['==',['get','road'],'__none__']});
  const b=new maplibregl.LngLatBounds();
  gj.features.forEach(f=>{const g=f.geometry;if(!g)return;const w=a=>{if(typeof a[0]==='number')b.extend(a);else a.forEach(w);};if(g.coordinates)w(g.coordinates);});
  if(!b.isEmpty()) map.fitBounds(b,{padding:40});
  map.on('click','roads',onRoadClick);
  map.on('mouseenter','roads',()=>map.getCanvas().style.cursor='pointer');
  map.on('mouseleave','roads',()=>map.getCanvas().style.cursor='');
}
Promise.all([
  fetch('/api/roads/geojson').then(r=>r.json()),
  fetch('/api/video/catalog').then(r=>r.json()).catch(()=>[]),
  new Promise(res=>{ if(map.loaded()) res(); else map.on('load',res); })
]).then(([gj, cat])=>{
  (cat||[]).forEach(e=>{ if(e.road) CATALOG[e.road]={file:e.file, direction:e.direction}; });
  if(!gj || !gj.features || !gj.features.length){ document.getElementById('roadId').textContent='No road geometry returned by the server.'; return; }
  addRoads(gj);
}).catch(e=>{ document.getElementById('roadId').textContent='Could not load roads: '+e.message; });
function lineOf(feature){ const g=feature.geometry; let coords=g.type==='MultiLineString'?g.coordinates.flat():g.coordinates; return turf.lineString(coords); }
function buildCarMarker(){
  const el=document.createElement('div'); el.className='carmark'; el.style.pointerEvents='none';
  el.innerHTML='<div class="chlabel" id="carLabel">0 m</div>'+
    '<div class="caricon" id="caricon"><svg width="26" height="40" viewBox="0 0 26 40" xmlns="http://www.w3.org/2000/svg">'+
    '<rect x="4" y="3" width="18" height="34" rx="6" fill="#0f2545" stroke="#ffffff" stroke-width="2"/>'+
    '<rect x="7" y="7" width="12" height="7" rx="2" fill="#9ec5ff"/>'+
    '<rect x="7" y="25" width="12" height="6" rx="2" fill="#9ec5ff"/>'+
    '<circle cx="4" cy="12" r="2.4" fill="#1c1c1c"/><circle cx="22" cy="12" r="2.4" fill="#1c1c1c"/>'+
    '<circle cx="4" cy="29" r="2.4" fill="#1c1c1c"/><circle cx="22" cy="29" r="2.4" fill="#1c1c1c"/>'+
    '</svg></div>';
  marker=new maplibregl.Marker({element:el, anchor:'center'});
  carIcon=el.querySelector('#caricon'); carLabel=el.querySelector('#carLabel');
}
function onRoadClick(e){
  const f=e.features[0]; const road=f.properties.road, name=f.properties.name||road;
  const len=parseFloat(f.properties.len)||0; const line=lineOf(f);
  const geoLenKm=turf.length(line,{units:'kilometers'});
  const snap=turf.nearestPointOnLine(line,[e.lngLat.lng,e.lngLat.lat],{units:'kilometers'});
  const frac=geoLenKm>0?snap.properties.location/geoLenKm:0; const chainage=frac*len; lastChainage=chainage;
  if(!cur || cur.road!==road){
    cur={road,name,len,line,geoLenKm};
    document.getElementById('roadName').textContent=name;
    document.getElementById('roadId').textContent=road;
    document.getElementById('lenVal').textContent=Math.round(len)+' m';
    document.getElementById('vfile').disabled=false;
    map.setFilter('roads-hi',['==',['get','road'],road]);
    const entry=CATALOG[road]; const srcChip=document.getElementById('srcChip');
    if(entry && entry.file){
      const src=/^https?:\/\//i.test(entry.file)?entry.file:('/videos/'+encodeURIComponent(entry.file));
      setDir(entry.direction==='reverse'?'rev':'fwd');
      video.src=src; video.load();
      document.getElementById('srcName').textContent=entry.file; srcChip.style.display='';
      document.getElementById('hint').textContent='Catalogue video loaded ('+(entry.direction||'front')+'). Press play; the car drives the road.';
    } else {
      video.removeAttribute('src'); video.load(); srcChip.style.display='none';
      document.getElementById('hint').textContent='No catalogue video for this road — load a local file to test.';
    }
    chOverlay.style.display='';
  }
  updateChainage(frac); placeMarker(frac); seekToChainage(chainage);
}
function updateChainage(frac){
  if(!cur) return;
  const ch=Math.round(frac*cur.len);
  document.getElementById('chVal').textContent=ch+' m';
  if(carLabel) carLabel.textContent=ch+' m';
  chOverlay.textContent='CH '+ch+' m';
}
function placeMarker(frac){
  if(!cur) return;
  if(!marker) buildCarMarker();
  const f=Math.max(0,Math.min(frac,1)); const d=f*cur.geoLenKm;
  const pt=turf.along(cur.line,d,{units:'kilometers'});
  marker.setLngLat(pt.geometry.coordinates).addTo(map);
  const eps=Math.max(cur.geoLenKm*0.003,0.003);
  const a=turf.along(cur.line,Math.max(0,d-eps),{units:'kilometers'});
  const b=turf.along(cur.line,Math.min(cur.geoLenKm,d+eps),{units:'kilometers'});
  let brg=turf.bearing(a,b); if(dir==='rev') brg+=180;
  if(carIcon) carIcon.style.transform='rotate('+brg+'deg)';
}
function seekToChainage(chainage){
  if(!cur || !video.duration || isNaN(video.duration)) return;
  const fch=cur.len>0?chainage/cur.len:0; const tFrac=dir==='fwd'?fch:(1-fch);
  seeking=true; video.currentTime=Math.max(0,Math.min(tFrac,1))*video.duration; setTimeout(()=>seeking=false,50);
}
video.addEventListener('loadedmetadata',()=>seekToChainage(lastChainage));
video.addEventListener('timeupdate',()=>{
  if(!cur || !video.duration || seeking) return;
  const tFrac=video.currentTime/video.duration; const fch=dir==='fwd'?tFrac:(1-tFrac);
  updateChainage(fch); placeMarker(fch);
});
document.getElementById('vfile').addEventListener('change',ev=>{
  const file=ev.target.files[0]; if(!file) return;
  video.src=URL.createObjectURL(file); video.load();
  document.getElementById('srcChip').style.display=''; chOverlay.style.display='';
  document.getElementById('srcName').textContent=file.name+' (local)';
  document.getElementById('hint').textContent='Local video loaded. Press play; the car drives the road.';
});
