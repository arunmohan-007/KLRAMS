/* ============================================================
   KLRAMS viewer · 02-map-core.js
   MapLibre map creation, navigation/scale controls, and the base-map switcher.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
const map=new maplibregl.Map({container:'map',style:{version:8,glyphs:'https://demotiles.maplibre.org/font/{fontstack}/{range}.pbf',sources:{
  osm:{type:'raster',tiles:['https://a.tile.openstreetmap.org/{z}/{x}/{y}.png','https://b.tile.openstreetmap.org/{z}/{x}/{y}.png','https://c.tile.openstreetmap.org/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenStreetMap'},
  sat:{type:'raster',tiles:['https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}'],tileSize:256,attribution:'© Esri'},
  topo:{type:'raster',tiles:['https://a.tile.opentopomap.org/{z}/{x}/{y}.png','https://b.tile.opentopomap.org/{z}/{x}/{y}.png'],tileSize:256,attribution:'© OpenTopoMap'},
  light:{type:'raster',tiles:['https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png','https://b.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png'],tileSize:256,attribution:'© CARTO'},
  dark:{type:'raster',tiles:['https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png','https://b.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png'],tileSize:256,attribution:'© CARTO'}},
 layers:[
  {id:'osm',type:'raster',source:'osm'},
  {id:'sat',type:'raster',source:'sat',layout:{visibility:'none'}},
  {id:'topo',type:'raster',source:'topo',layout:{visibility:'none'}},
  {id:'light',type:'raster',source:'light',layout:{visibility:'none'}},
  {id:'dark',type:'raster',source:'dark',layout:{visibility:'none'}}]},center:[76.95,8.52],zoom:9});
map.addControl(new maplibregl.NavigationControl({showCompass:false}),'top-right');
const BASEMAPS=['osm','sat','topo','light','dark'];
window.setBaseLayer=function(name){
  BASEMAPS.forEach(b=>{if(map.getLayer(b))map.setLayoutProperty(b,'visibility',b===name?'visible':'none');});
  const sel=document.getElementById('basemap');if(sel&&sel.value!==name)sel.value=name;
};
document.getElementById('basemap').addEventListener('change',e=>window.setBaseLayer(e.target.value));
// Apply Night mode (dark basemap) on load if the user enabled it from the launcher.
map.on('load',()=>{try{if(localStorage.getItem('klNight')==='1')window.setBaseLayer('dark');}catch(e){}});
map.addControl(new maplibregl.ScaleControl({maxWidth:120,unit:'metric'}));

function fitFeaturesBounds(feats,opts){if(!feats||!feats.length||typeof turf==='undefined')return;try{const bb=turf.bbox({type:'FeatureCollection',features:feats});if(![bb[0],bb[1],bb[2],bb[3]].every(isFinite))return;map.fitBounds([[bb[0],bb[1]],[bb[2],bb[3]]],Object.assign({padding:70,maxZoom:16,duration:700},opts||{}));}catch(e){}}
