/* Lite map viewer, no WebGL (map-lite.html).
   Lifted out of an inline <script> so the page carries no inline
   script, which is what lets script-src drop 'unsafe-inline'. */
/* Shared config (colours, PARAMS, LK, ROAD_FIELDS, dec) is loaded from
   /js/00-shared-config.js just above — the same file the WebGL viewer
   uses. This page used to keep its own copy, which drifted: patch work
   read % here and sqm there, and pothole and texture had lost their
   units. */
function esc(s){return String(s==null?'':s).replace(/[&<>"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c];});}

/* ---- map (Canvas renderer — no WebGL) ---- */
var map=L.map('map',{preferCanvas:true,zoomControl:true}).setView([8.52,76.95],9);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'© OpenStreetMap'}).addTo(map);

var roadLayer=L.layerGroup(),segLayer=L.layerGroup(),bridLayer=L.layerGroup(),culvLayer=L.layerGroup();
var SEG_GJ=null,ROAD_GJ=null,didFit=false;
var setStatus=function(m){document.getElementById('status').textContent=m;};

/* ---- condition colouring (mirrors colorExpr in 03-condition-style-filter.js) ---- */
function condBand(v,key){
  var p=PMAP[key];if(!p||v==null||isNaN(v))return null;
  if(v<p.fair)return GOOD;
  if(v<p.poor)return FAIR;
  return POOR;
}
function condColor(props,key){
  var raw=props?props[key]:null;
  if(raw==null||raw==='')return NONE;
  return condBand(+raw,key)||NONE;
}
/* ---- per-lane rendering (mirrors LANE_SLOTS/addCondLayers in 07-data-loaders.js) ----
   condition_segments collapses every lane surveyed at a chainage into ONE row
   (see SegmentService.java), keeping the per-lane readings in `lane_vals`
   (JSON: {xsp: {iri, crack, ...}, ...}). The full MapLibre viewer draws one
   offset line per lane (line-offset in pixels); Leaflet has no such paint
   property, so each lane's line is offset here by shifting its vertices a
   few metres perpendicular to the road at that point — same lane order/
   spacing as the full map (CC centre, CL1/CL2 left, CR1/CR2 right). */
var LANE_SLOTS=[{x:'CC',off:0},{x:'CL1',off:-1},{x:'CL2',off:-2},{x:'CR1',off:1},{x:'CR2',off:2}];
var LANE_OFFSET_M=4;
function segLaneVals(props){
  var lv=props?props.lane_vals:null;
  if(typeof lv==='string'){try{lv=JSON.parse(lv);}catch(e){lv=null;}}
  return (lv&&typeof lv==='object')?lv:null;
}
function offsetLatLngs(latlngs,meters){
  var n=latlngs.length,out=[];
  if(!meters||n<2){for(var j=0;j<n;j++)out.push([latlngs[j].lat,latlngs[j].lng]);return out;}
  var mPerDegLat=111320;
  for(var i=0;i<n;i++){
    var p0=latlngs[Math.max(0,i-1)],p1=latlngs[Math.min(n-1,i+1)];
    var lat=latlngs[i].lat,cosLat=Math.cos(lat*Math.PI/180)||1e-6;
    var dx=(p1.lng-p0.lng)*cosLat,dy=(p1.lat-p0.lat);
    var len=Math.sqrt(dx*dx+dy*dy)||1;
    var nx=-dy/len,ny=dx/len;
    var offDeg=meters/mPerDegLat;
    out.push([lat+ny*offDeg,latlngs[i].lng+(nx*offDeg)/cosLat]);
  }
  return out;
}
function bandLabel(props,key){
  var p=PMAP[key];var raw=props?props[key]:null;
  if(raw==null||raw===''||isNaN(+raw))return {t:'No data',c:NONE};
  var v=+raw;return v<p.fair?{t:'Good',c:GOOD}:(v<p.poor?{t:'Fair',c:FAIR}:{t:'Poor',c:POOR});
}

/* ---- popups ---- */
function roadPopup(props){
  var rows='';
  ROAD_FIELDS.forEach(function(f){
    var v=props[f[0]];if(v==null||String(v).trim()==='')return;
    if(f[2])v=dec(f[2],v);
    rows+='<tr><td class="k">'+esc(f[1])+'</td><td class="v">'+esc(v)+(f[3]||'')+'</td></tr>';
  });
  var title=props.Road_Name||props.road||'Road';
  return '<div class="lp"><h4>'+esc(title)+'</h4><table>'+rows+'</table></div>';
}
function segPopup(props){
  var key=document.getElementById('metric').value;
  var b=bandLabel(props,key);
  var rows='';
  PARAMS.forEach(function(p){
    var v=props[p.key];if(v==null||v==='')return;
    rows+='<tr><td class="k">'+esc(p.label)+'</td><td class="v">'+esc(v)+esc(p.unit||'')+'</td></tr>';
  });
  var road=props.road||props.Road_Name||'';
  var ch='';
  if(props.from_ch!=null&&props.to_ch!=null)ch=props.from_ch+' – '+props.to_ch+' m';
  else if(props.chainage!=null)ch=props.chainage+' m';
  var laneTag=props.__lane?(' · Lane '+esc(props.__lane)):'';
  return '<div class="lp"><h4>'+esc(road||'Condition segment')+laneTag+'</h4>'+
    (ch?'<div class="k" style="margin-bottom:6px">Chainage '+esc(ch)+'</div>':'')+
    '<div style="margin-bottom:8px"><span class="band" style="background:'+b.c+'">'+b.t+' · '+esc(PMAP[key].label)+'</span></div>'+
    '<table>'+rows+'</table></div>';
}
function assetPopup(props,title){
  var rows='';
  Object.keys(props||{}).forEach(function(k){
    if(/^(id|geom|the_geom|layer)$/i.test(k))return;
    var v=props[k];if(v==null||String(v).trim()==='')return;
    rows+='<tr><td class="k">'+esc(k)+'</td><td class="v">'+esc(v)+'</td></tr>';
  });
  return '<div class="lp"><h4>'+esc(title)+'</h4><table>'+rows+'</table></div>';
}

/* ---- fetch helper ---- */
function getJSON(url){
  return fetch(url,{credentials:'same-origin',headers:{'Accept':'application/json'}}).then(function(r){
    if(!r.ok)throw new Error(url+' → '+r.status);
    return r.json();
  });
}

/* ---- render layers ---- */
function renderRoads(){
  roadLayer.clearLayers();
  if(!ROAD_GJ)return;
  L.geoJSON(ROAD_GJ,{style:{color:NET,weight:2,opacity:.9},
    onEachFeature:function(f,l){l.bindPopup(function(){return roadPopup(f.properties||{});},{maxWidth:320});}
  }).addTo(roadLayer);
  document.getElementById('cRoads').textContent=((ROAD_GJ.features||[]).length).toLocaleString();
}
function renderSegs(){
  segLayer.clearLayers();
  if(!SEG_GJ)return;
  var key=document.getElementById('metric').value;
  (SEG_GJ.features||[]).forEach(function(f){
    var props=f.properties||{};
    var geom=f.geometry;if(!geom)return;
    var lineStrings=(geom.type==='MultiLineString')
      ?L.GeoJSON.coordsToLatLngs(geom.coordinates,1)
      :[L.GeoJSON.coordsToLatLngs(geom.coordinates,0)];
    var laneVals=segLaneVals(props);
    var lanes=(laneVals&&Object.keys(laneVals).length)?LANE_SLOTS.filter(function(s){return laneVals[s.x];}):null;
    lineStrings.forEach(function(latlngs){
      if(lanes){
        lanes.forEach(function(s){
          var lv=laneVals[s.x],v=lv[key];
          var color=(v==null||v==='')?NONE:(condBand(+v,key)||NONE);
          var coords=offsetLatLngs(latlngs,s.off*LANE_OFFSET_M);
          var laneProps=Object.assign({},props,lv,{__lane:s.x});
          L.polyline(coords,{color:color,weight:4,opacity:.95})
            .bindPopup(function(){return segPopup(laneProps);},{maxWidth:320})
            .addTo(segLayer);
        });
      }else{
        var color=condColor(props,key);
        var coords=offsetLatLngs(latlngs,0);
        L.polyline(coords,{color:color,weight:5,opacity:.95})
          .bindPopup(function(){return segPopup(props);},{maxWidth:320})
          .addTo(segLayer);
      }
    });
  });
  document.getElementById('cSegs').textContent=((SEG_GJ.features||[]).length).toLocaleString();
}
function renderAssets(gj,group,title,color,cntId){
  group.clearLayers();
  L.geoJSON(gj,{
    pointToLayer:function(f,latlng){return L.circleMarker(latlng,{radius:5,color:'#fff',weight:1,fillColor:color,fillOpacity:.95});},
    style:{color:color,weight:4,opacity:.9},
    onEachFeature:function(f,l){l.bindPopup(function(){return assetPopup(f.properties||{},title);},{maxWidth:320});}
  }).addTo(group);
  document.getElementById(cntId).textContent=((gj&&gj.features?gj.features.length:0)).toLocaleString();
}

/* ---- loaders ---- */
function loadRoads(){
  return getJSON('/api/roads/geojson').then(function(gj){
    ROAD_GJ=gj;renderRoads();
    if(!didFit){try{var b=L.geoJSON(gj).getBounds();if(b.isValid()){map.fitBounds(b,{padding:[20,20]});didFit=true;}}catch(e){}}
  }).catch(function(e){document.getElementById('cRoads').textContent='—';console.warn(e);});
}
function loadSegs(){
  return getJSON('/api/segments/geojson').then(function(gj){SEG_GJ=gj;renderSegs();
    /* segments drive PCI too — invalidate the cache and refresh any PCI layer already on */
    if(typeof pciComputed!=='undefined'){pciComputed=false;
      if(document.getElementById('tPciA').checked)renderPci('avg',pciAvgLayer,'cPciA');
      if(document.getElementById('tPciW').checked)renderPci('worst',pciWorstLayer,'cPciW');
    }
  })
    .catch(function(e){document.getElementById('cSegs').textContent='0';console.warn(e);});
}
var bridLoaded=false,culvLoaded=false;
function loadBridges(){if(bridLoaded)return Promise.resolve();bridLoaded=true;return getJSON('/api/assets/bridge/geojson').then(function(gj){renderAssets(gj,bridLayer,'Bridge',POOR,'cBrid');}).catch(function(){document.getElementById('cBrid').textContent='0';});}
function loadCulverts(){if(culvLoaded)return Promise.resolve();culvLoaded=true;return getJSON('/api/assets/culvert/geojson').then(function(gj){renderAssets(gj,culvLayer,'Culvert','#2f6fd6','cCulv');}).catch(function(){document.getElementById('cCulv').textContent='0';});}

/* ---- legend + metric ---- */
function buildMetric(){
  var sel=document.getElementById('metric');
  PARAMS.forEach(function(p){var o=document.createElement('option');o.value=p.key;o.textContent=p.label;sel.appendChild(o);});
  sel.value='iri';
  sel.addEventListener('change',function(){renderSegs();buildLegend();});
}
function buildLegend(){
  var p=PMAP[document.getElementById('metric').value];
  document.getElementById('legend').innerHTML=[
    ['Good ( < '+p.fair+' )',GOOD],['Fair ( '+p.fair+' – '+p.poor+' )',FAIR],['Poor ( ≥ '+p.poor+' )',POOR],['No data',NONE]
  ].map(function(r){return '<div class="li"><span class="swatch" style="background:'+r[1]+'"></span>'+esc(r[0])+'</div>';}).join('');
}

/* ---- layer toggles ---- */
function bindToggle(id,group,onFirst){
  var cb=document.getElementById(id);
  function apply(){if(cb.checked){if(onFirst)onFirst();map.addLayer(group);}else map.removeLayer(group);}
  cb.addEventListener('change',apply);
  return apply;
}
map.addLayer(roadLayer);map.addLayer(segLayer);
bindToggle('tRoads',roadLayer);
bindToggle('tSegs',segLayer);
bindToggle('tBrid',bridLayer,loadBridges);
bindToggle('tCulv',culvLayer,loadCulverts);

/* ---- extra asset layers (furniture, sub-grade, core, crust) ----
   Same generic /api/assets/{type}/geojson endpoint the full viewer uses; each
   loads once (guarded flag) and re-uses renderAssets() for point/line drawing. */
var EXTRA_ASSETS=[
  {type:'furniture_line',  id:'tFurnL', cnt:'cFurnL', color:'#0fa3a3', title:'Furniture (line)'},
  {type:'furniture_point', id:'tFurnP', cnt:'cFurnP', color:'#3b6fa0', title:'Furniture (point)'},
  {type:'subgrade',        id:'tSoil',  cnt:'cSoil',  color:'#8a4d1f', title:'Sub-Grade Soil'},
  {type:'bituminous_core', id:'tCore',  cnt:'cCore',  color:'#2b2b2b', title:'Bituminous Core'},
  {type:'pavement_crust',  id:'tCrust', cnt:'cCrust', color:'#b8860b', title:'Pavement Crust'}
];
EXTRA_ASSETS.forEach(function(a){
  a.group=L.layerGroup();
  var loaded=false;
  bindToggle(a.id,a.group,function(){
    if(loaded)return; loaded=true;
    getJSON('/api/assets/'+a.type+'/geojson')
      .then(function(gj){renderAssets(gj,a.group,a.title,a.color,a.cnt);})
      .catch(function(){document.getElementById(a.cnt).textContent='0';});
  });
});

/* ---- FWD (D0 deflection), coloured by D0 band (mirrors 06-assets.js) ----
   D0 is read exactly as uploaded — millimetres — with no scale detection. */
var FWD_STOPS=[[0.10,'#1a9850'],[0.20,'#91cf60'],[0.35,'#fee08b'],[0.50,'#fdae61'],[0.70,'#f46d43']];
var FWD_LEGEND=[['#1a9850','< 0.10'],['#91cf60','0.10 – 0.20'],['#fee08b','0.20 – 0.35'],['#fdae61','0.35 – 0.50'],['#f46d43','0.50 – 0.70'],['#b2182b','> 0.70']];
function fwdD0(p){if(!p)return null;for(var k in p){var kk=String(k).toLowerCase().replace(/[^a-z0-9]/g,'');if(kk==='d0'||kk==='do'){var v=p[k];if(v!=null&&v!=='')return v;}}return null;}
function fwdColor(p){var raw=fwdD0(p);if(raw==null||raw===''||isNaN(+raw))return NONE;var v=Math.abs(+raw);for(var i=0;i<FWD_STOPS.length;i++){if(v<FWD_STOPS[i][0])return FWD_STOPS[i][1];}return '#b2182b';}
var fwdLayer=L.layerGroup(),fwdLoaded=false;
bindToggle('tFwd',fwdLayer,function(){
  if(fwdLoaded)return; fwdLoaded=true;
  getJSON('/api/assets/fwd/geojson').then(function(gj){
    fwdLayer.clearLayers();
    L.geoJSON(gj,{
      pointToLayer:function(f,latlng){return L.circleMarker(latlng,{radius:5,color:'#fff',weight:1,fillColor:fwdColor(f.properties||{}),fillOpacity:.95});},
      onEachFeature:function(f,l){l.bindPopup(function(){return assetPopup(f.properties||{},'FWD (D0)');},{maxWidth:320});}
    }).addTo(fwdLayer);
    document.getElementById('cFwd').textContent=((gj&&gj.features?gj.features.length:0)).toLocaleString();
  }).catch(function(){document.getElementById('cFwd').textContent='0';});
});
document.getElementById('fwdLegend').innerHTML='<div class="hint" style="margin:2px 0 4px">D0 deflection (mm)</div>'+
  FWD_LEGEND.map(function(r){return '<div class="li"><span class="swatch" style="background:'+r[0]+'"></span>'+esc(r[1])+'</div>';}).join('');

/* ---- PCI engine (IRC:82-2023) — ported from js/14-pci-engine.js. Composite = area-
   weighted distress average across lanes; Worst-Lane = min of per-lane PCIs.
   Computed in the browser from the segments GeoJSON already loaded (SEG_GJ). */
var PCI_PARAMS=[{key:'crack',label:'Cracking'},{key:'ravelling',label:'Ravelling'},{key:'pothole',label:'Pothole'},{key:'patch_work',label:'Patch work'},{key:'rutting',label:'Rut depth'},{key:'iri',label:'IRI'}];
var PCI_W={crack:0.16,ravelling:0.12,pothole:0.08,patch_work:0.10,rutting:0.14,iri:0.40};
var PCI_BANDS=[{min:90,label:'Excellent',color:'#157f3c'},{min:80,label:'Good',color:'#7cb518'},{min:60,label:'Satisfactory',color:'#f2c200'},{min:40,label:'Fair',color:'#f08c00'},{min:20,label:'Poor',color:'#e8590c'},{min:0,label:'Fail',color:'#c92a2a'}];
function pciBand(v){for(var i=0;i<PCI_BANDS.length;i++){if(v>=PCI_BANDS[i].min)return PCI_BANDS[i];}return PCI_BANDS[PCI_BANDS.length-1];}
function indIndex(key,v){var t=PMAP[key];if(!t||v==null||isNaN(v))return null;v=Math.max(0,+v);var f=+t.fair,po=+t.poor;if(!(po>f&&f>0))return null;if(v<=f)return 100-(v/f)*20;if(v<=po)return 80-((v-f)/(po-f))*40;var cap=2*po;if(v<=cap)return 40-((v-po)/po)*40;return 0;}
var PCI_LANE_KEYS=['CC','CL1','CL2','CR1','CR2'];
function pciLaneDists(props){var out={};var lv=props.lane_vals;if(typeof lv==='string'){try{lv=JSON.parse(lv);}catch(e){lv=null;}}if(lv&&typeof lv==='object'&&Object.keys(lv).length){Object.keys(lv).forEach(function(L){var o=lv[L]||{},d={};PCI_PARAMS.forEach(function(pp){var v=o[pp.key];if(v!=null&&v!=='')d[pp.key]=+v;});out[L]=d;});return out;}PCI_LANE_KEYS.forEach(function(L){var any=false,d={};PCI_PARAMS.forEach(function(pp){var v=props[L+'_'+pp.key];if(v!=null&&v!==''){d[pp.key]=+v;any=true;}});if(any)out[L]=d;});return out;}
function pciFromDist(dist){var sw=0,acc=0;PCI_PARAMS.forEach(function(pp){var w=+PCI_W[pp.key]||0;if(w<=0)return;var raw=dist?dist[pp.key]:null;if(raw==null||raw==='')return;var I=indIndex(pp.key,+raw);if(I==null)return;acc+=w*I;sw+=w;});return sw>0?acc/sw:null;}
function pciAggDist(props,basis){var d={};PCI_PARAMS.forEach(function(pp){var k=pp.key;var v=(basis==='worst')?props[k]:((props['avg_'+k]!=null&&props['avg_'+k]!=='')?props['avg_'+k]:props[k]);if(v!=null&&v!=='')d[k]=+v;});return d;}
function pciLanePcis(props){var Ld=pciLaneDists(props),r=[];Object.keys(Ld).forEach(function(k){var v=pciFromDist(Ld[k]);if(v!=null)r.push({lane:k,pci:v});});return r;}
function segPCI(props,basis){var lanes=pciLanePcis(props);if(lanes.length){if(basis==='worst')return Math.min.apply(null,lanes.map(function(x){return x.pci;}));var Ld=pciLaneDists(props),keys=Object.keys(Ld),d={};PCI_PARAMS.forEach(function(pp){var s=0,n=0;keys.forEach(function(k){var v=Ld[k][pp.key];if(v!=null&&v!==''){s+=+v;n++;}});if(n)d[pp.key]=s/n;});var v=pciFromDist(d);return (v!=null)?v:(lanes.reduce(function(s,x){return s+x.pci;},0)/lanes.length);}return pciFromDist(pciAggDist(props,basis));}
var pciComputed=false;
function ensurePci(){
  if(pciComputed||!SEG_GJ)return;
  (SEG_GJ.features||[]).forEach(function(f){
    var va=segPCI(f.properties,'avg');  f.properties.pci_avg  =(va==null)?-1:Math.round(va*10)/10;
    var vw=segPCI(f.properties,'worst');f.properties.pci_worst=(vw==null)?-1:Math.round(vw*10)/10;
  });
  pciComputed=true;
}
function pciPopupLite(props,basis){
  var prop=(basis==='worst')?'pci_worst':'pci_avg';var v=+props[prop];var b=(v>=0)?pciBand(v):null;
  var av=+props.pci_avg,wv=+props.pci_worst,road=props.road||props.Road_Name||'';
  var head=b?('<div style="font-size:20px;font-weight:700;color:var(--ink)">'+v.toFixed(1)+' <span style="font-size:11px;color:var(--muted);font-weight:500">/100</span> <span class="band" style="background:'+b.color+'">'+esc(b.label)+'</span></div>'):'<div style="color:var(--muted)">No PCI at this segment</div>';
  return '<div class="lp"><h4>'+(basis==='worst'?'Worst-Lane PCI':'Composite PCI')+(road?(' · '+esc(road)):'')+'</h4>'+head+
    '<div class="hint" style="margin-top:6px">Composite <b>'+((av>=0)?av.toFixed(1):'–')+'</b> · Worst-Lane <b>'+((wv>=0)?wv.toFixed(1):'–')+'</b></div></div>';
}
var pciAvgLayer=L.layerGroup(),pciWorstLayer=L.layerGroup();
function renderPci(basis,group,cntId){
  group.clearLayers();
  if(!SEG_GJ){document.getElementById(cntId).textContent='—';return;}
  ensurePci();
  var prop=(basis==='worst')?'pci_worst':'pci_avg',n=0;
  L.geoJSON(SEG_GJ,{style:function(f){var v=+f.properties[prop];if(v>=0)n++;return {color:(v>=0)?pciBand(v).color:NONE,weight:5,opacity:.95};},
    onEachFeature:function(f,l){l.bindPopup(function(){return pciPopupLite(f.properties||{},basis);},{maxWidth:320});}
  }).addTo(group);
  document.getElementById(cntId).textContent=n.toLocaleString();
}
bindToggle('tPciA',pciAvgLayer,function(){renderPci('avg',pciAvgLayer,'cPciA');});
bindToggle('tPciW',pciWorstLayer,function(){renderPci('worst',pciWorstLayer,'cPciW');});
document.getElementById('pciLegend').innerHTML=PCI_BANDS.map(function(b){return '<div class="li"><span class="swatch" style="background:'+b.color+'"></span>'+esc(b.label)+' <span style="color:var(--muted);font-size:11px">'+b.min+'+</span></div>';}).join('')+'<div class="li"><span class="swatch" style="background:'+NONE+'"></span>No data</div>';

/* ---- road search (zoom to matching road) ---- */
document.getElementById('search').addEventListener('input',function(e){
  var q=e.target.value.trim().toLowerCase();var st=document.getElementById('searchStatus');
  if(!q||!ROAD_GJ){st.textContent='';return;}
  var hit=(ROAD_GJ.features||[]).filter(function(f){var p=f.properties||{};return String(p.Road_Name||'').toLowerCase().indexOf(q)>=0||String(p.Road_Num||'').toLowerCase().indexOf(q)>=0;});
  if(!hit.length){st.textContent='No match';return;}
  st.textContent=hit.length+' match'+(hit.length>1?'es':'');
  try{var b=L.geoJSON({type:'FeatureCollection',features:hit.slice(0,50)}).getBounds();if(b.isValid())map.fitBounds(b,{padding:[30,30]});}catch(_){}
});

/* ---- My location (live GPS dot) ----
   Same field tool as the full viewer's GeolocateControl, rebuilt on Leaflet's
   map.locate({watch:true}): a dot that keeps moving with the device while
   driving a road, so the condition data underneath can be checked on the
   ground. Panning the map by hand drops out of follow mode (the dot keeps
   updating); tapping the button again re-locks onto it.
   Needs a secure context — browsers refuse geolocation over plain http. */
var LL_COLOR='#00a3ff';
var llMarker=null,llHalo=null,llWatching=false,llFollow=false,llBtn=null,llFirstFix=false,llErrTimer=null;

function llChip(){return document.getElementById('llChip');}
function llShowChip(html){var c=llChip();if(!c)return;c.innerHTML=html;c.classList.add('on');}
function llHideChip(){var c=llChip();if(c)c.classList.remove('on');}

function llStart(){
  if(!navigator.geolocation){llFlash('This browser has no location support');return;}
  llWatching=true;llFollow=true;llFirstFix=true;
  if(llBtn){llBtn.classList.add('on');llBtn.classList.add('seek');}
  llShowChip('<span class="llacc">Locating…</span>');
  map.locate({watch:true,enableHighAccuracy:true,maximumAge:2000,timeout:10000});
}
function llStop(){
  llWatching=false;llFollow=false;
  map.stopLocate();
  if(llBtn){llBtn.classList.remove('on');llBtn.classList.remove('seek');}
  if(llMarker){map.removeLayer(llMarker);llMarker=null;}
  if(llHalo){map.removeLayer(llHalo);llHalo=null;}
  llHideChip();
}
function llFlash(msg){
  llShowChip('<span class="llacc">'+esc(msg)+'</span>');
  clearTimeout(llErrTimer);llErrTimer=setTimeout(llHideChip,6000);
}

map.on('locationfound',function(e){
  if(!llWatching)return;
  if(llBtn)llBtn.classList.remove('seek');
  clearTimeout(llErrTimer);
  var acc=isFinite(e.accuracy)?Math.round(e.accuracy):null;
  /* Accuracy halo first, so the dot draws on top of it. */
  if(!llHalo){llHalo=L.circle(e.latlng,{radius:acc||0,color:LL_COLOR,weight:1,opacity:.5,fillColor:LL_COLOR,fillOpacity:.12,interactive:false}).addTo(map);}
  else{llHalo.setLatLng(e.latlng);llHalo.setRadius(acc||0);}
  if(!llMarker){llMarker=L.circleMarker(e.latlng,{radius:7,color:'#fff',weight:3,fillColor:LL_COLOR,fillOpacity:1,interactive:false}).addTo(map);}
  else{llMarker.setLatLng(e.latlng);}
  if(llFollow){
    if(llFirstFix){map.setView(e.latlng,Math.max(map.getZoom(),16));llFirstFix=false;}
    else map.panTo(e.latlng,{animate:true,duration:.5});
  }
  /* speed is m/s and null when the device cannot derive it (stationary, or a
     desktop positioned off wifi rather than GPS). */
  var spd=(e.speed!=null&&isFinite(e.speed)&&e.speed>0.3)?(' · <b>'+(e.speed*3.6).toFixed(0)+'</b> km/h'):'';
  llShowChip('<span><b>'+e.latlng.lat.toFixed(5)+'</b>, <b>'+e.latlng.lng.toFixed(5)+'</b></span>'+
    '<span class="llacc">±'+(acc==null?'—':acc+' m')+'</span>'+spd);
});
map.on('locationerror',function(e){
  if(!llWatching)return;
  if(llBtn)llBtn.classList.remove('seek');
  /* code 1 = permission denied, 2 = position unavailable, 3 = timeout */
  var denied=(e&&e.code===1);
  /* Stop before flashing: llStop() clears the chip, so the message has to be
     written after it or it is wiped in the same tick. */
  if(denied)llStop();
  llFlash(denied?'Location blocked — allow location for this site in the browser'
                :'No location fix — move to open sky and try again');
});
/* Dragging the map means "let me look elsewhere": keep tracking, stop chasing. */
map.on('dragstart',function(){if(llWatching)llFollow=false;});

var LlControl=L.Control.extend({
  options:{position:'topleft'},
  onAdd:function(){
    var c=L.DomUtil.create('div','leaflet-bar leaflet-control ll-ctl');
    var a=L.DomUtil.create('a','',c);
    a.href='#';a.title='My location — live GPS dot that follows you';
    a.setAttribute('role','button');a.setAttribute('aria-label','My location');
    a.innerHTML='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">'+
      '<circle cx="12" cy="12" r="3.2"/><circle cx="12" cy="12" r="7.5" opacity=".55"/>'+
      '<path d="M12 1.6v3M12 19.4v3M1.6 12h3M19.4 12h3"/></svg>';
    llBtn=a;
    L.DomEvent.disableClickPropagation(c);
    L.DomEvent.on(a,'click',function(ev){
      L.DomEvent.preventDefault(ev);
      if(!llWatching)llStart();
      else if(!llFollow){llFollow=true;llFirstFix=true;}   /* re-lock onto the dot */
      else llStop();
    });
    return c;
  }
});
map.addControl(new LlControl());
(function(){var c=L.DomUtil.create('div','');c.id='llChip';map.getContainer().appendChild(c);})();

/* ---- go ---- */
buildMetric();buildLegend();
Promise.all([loadRoads(),loadSegs()]).then(function(){setStatus('Ready.');})
  .catch(function(){setStatus('Some data could not be loaded — check your connection / sign-in.');});
