/* Survey Archive viewer (survey-archive.html).
   Lifted out of an inline <script> so the page carries no inline
   script, which is what lets script-src drop 'unsafe-inline'. */
var map=L.map('map',{preferCanvas:true}).setView([10.2,76.4],8);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png',{maxZoom:19,attribution:'&copy; OpenStreetMap'}).addTo(map);

function escH(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
function popupHtml(title,p){
  var rows=Object.keys(p||{}).map(function(k){return '<tr><td>'+escH(k)+'</td><td><b>'+escH(p[k])+'</b></td></tr>';}).join('');
  return '<div class="lp"><h4>'+escH(title)+'</h4><table>'+rows+'</table></div>';
}
function iriColor(v){v=+v;if(isNaN(v))return '#b9c2cc';if(v<2.55)return '#2ba66a';if(v<3.30)return '#FFC400';return '#da4b43';}
function d0Color(v){v=+v;if(isNaN(v))return '#9aa0a6';if(v<100)return '#1a9850';if(v<200)return '#91cf60';if(v<350)return '#fee08b';if(v<500)return '#fdae61';if(v<700)return '#f46d43';return '#b2182b';}
/* D0 lives in the kept CSV attrs under a slightly different header per file */
function findD0(p){for(var k in p){var kk=String(k).toLowerCase().replace(/[^a-z0-9]/g,'');if(kk==='d0'||kk==='do'){return p[k];}}return null;}

/* ---- layer registry: id, label, availability-count key, fetch url, renderer ---- */
var LAYERS=[
  {id:'cond',   label:'Condition segments', dot:'#2ba66a', key:'condition_segments',
   url:function(pid){return '/api/segments/geojson?period_id='+pid;},
   make:function(gj){return L.geoJSON(gj,{style:function(f){return {color:iriColor(f.properties&&f.properties.iri),weight:3.5,opacity:.95};},
     onEachFeature:function(f,l){l.bindPopup(popupHtml('Condition · '+(f.properties.road||''),f.properties));}});}},
  {id:'fwdseg', label:'FWD segments',       dot:'#fdae61', key:'fwd_segments',
   url:function(pid){return '/api/fwd-segments/geojson?period_id='+pid;},
   make:function(gj){return L.geoJSON(gj,{style:function(f){return {color:d0Color(f.properties&&f.properties.d0),weight:4,opacity:.95};},
     onEachFeature:function(f,l){l.bindPopup(popupHtml('FWD · '+(f.properties.road||''),f.properties));}});}},
  {id:'fwd',    label:'FWD points',         dot:'#2a5d9c', key:'fwd',
   url:function(pid){return '/api/assets/fwd/geojson?period_id='+pid;},
   make:function(gj){return L.geoJSON(gj,{pointToLayer:function(f,ll){return L.circleMarker(ll,{radius:4,color:'#123c6b',weight:1,fillColor:d0Color(findD0(f.properties)),fillOpacity:.9});},
     onEachFeature:function(f,l){l.bindPopup(popupHtml('FWD point · '+(f.properties.road||''),f.properties));}});}},
  {id:'traffic',label:'Traffic stations',   dot:'#d4a02e', key:'traffic_stations',
   url:function(pid){return '/api/traffic/stations/geojson?period_id='+pid;},
   make:function(gj){return L.geoJSON(gj,{pointToLayer:function(f,ll){return L.circleMarker(ll,{radius:6,color:'#8a6a12',weight:1.5,fillColor:'#d4a02e',fillOpacity:.95});},
     onEachFeature:function(f,l){l.bindPopup(popupHtml('Traffic station · '+(f.properties.name||''),f.properties));}});}},
  {id:'soil',   label:'Sub-grade soil',     dot:'#6b4e9e', key:'subgrade',
   url:function(pid){return '/api/assets/subgrade/geojson?period_id='+pid;},
   make:function(gj){return L.geoJSON(gj,{pointToLayer:function(f,ll){return L.circleMarker(ll,{radius:5,color:'#4a3570',weight:1,fillColor:'#6b4e9e',fillOpacity:.9});},
     onEachFeature:function(f,l){l.bindPopup(popupHtml('Soil test · '+(f.properties.road||''),f.properties));}});}},
  {id:'core',   label:'Bituminous core',    dot:'#c2603f', key:'bituminous_core',
   url:function(pid){return '/api/assets/bituminous_core/geojson?period_id='+pid;},
   make:function(gj){return L.geoJSON(gj,{pointToLayer:function(f,ll){return L.circleMarker(ll,{radius:5,color:'#8a4029',weight:1,fillColor:'#c2603f',fillOpacity:.9});},
     onEachFeature:function(f,l){l.bindPopup(popupHtml('Bituminous core · '+(f.properties.road||''),f.properties));}});}},
  {id:'crust',  label:'Pavement crust',     dot:'#2a5d9c', key:'pavement_crust',
   url:function(pid){return '/api/assets/pavement_crust/geojson?period_id='+pid;},
   make:function(gj){return L.geoJSON(gj,{pointToLayer:function(f,ll){return L.circleMarker(ll,{radius:5,color:'#1b3f6e',weight:1,fillColor:'#2a5d9c',fillOpacity:.9});},
     onEachFeature:function(f,l){l.bindPopup(popupHtml('Pavement crust · '+(f.properties.road||''),f.properties));}});}}
];

var periods=[];        // availability payload rows
var curPid=null;
var live={};           // layer id -> Leaflet layer currently on the map
var cache={};          // 'id|pid' -> Leaflet layer (built once per period)
var wanted={};         // layer id -> user wants it on (persists across period switches)
LAYERS.forEach(function(l){wanted[l.id]=(l.id==='cond');});   // condition on by default

function setStatus(t){document.getElementById('status').textContent=t||'';}
function curPeriod(){for(var i=0;i<periods.length;i++)if(+periods[i].id===+curPid)return periods[i];return null;}

/* roads base layer, once */
fetch('/api/roads/geojson').then(function(r){return r.json();}).then(function(gj){
  var lyr=L.geoJSON(gj,{style:{color:'#8a99ad',weight:1.4,opacity:.7}}).addTo(map);
  try{map.fitBounds(lyr.getBounds(),{padding:[16,16]});}catch(e){}
  lyr.bringToBack();
}).catch(function(){});

function loadPeriods(){
  fetch('/api/survey-periods/availability',{cache:'no-store'}).then(function(r){
    if(!r.ok)throw new Error('HTTP '+r.status);
    return r.json();
  }).then(function(d){
    periods=(d&&d.periods)||[];
    var sel=document.getElementById('perSel');
    sel.innerHTML=periods.map(function(p){
      return '<option value="'+(+p.id)+'"'+(p.is_active?' selected':'')+'>'+escH(p.name)+(p.is_active?' · current':'')
        +((p.start_date||p.end_date)?(' ('+escH(p.start_date||'…')+' – '+escH(p.end_date||'…')+')'):'')+'</option>';
    }).join('')||'<option value="">No survey periods yet</option>';
    var act=periods.filter(function(p){return p.is_active;})[0];
    curPid=act?act.id:(periods[0]&&periods[0].id);
    if(curPid!=null)sel.value=String(curPid);
    renderLayerList();
    refreshLayers();
  }).catch(function(e){
    setStatus('Could not load periods ('+e.message+') — sign in first?');
  });
}
document.getElementById('perSel').addEventListener('change',function(){
  curPid=+this.value||null;
  renderLayerList();
  refreshLayers();
});

function renderLayerList(){
  var p=curPeriod();
  var c=(p&&p.counts)||{};
  document.getElementById('perPill').textContent=p?p.name:'—';
  document.getElementById('layerList').innerHTML=LAYERS.map(function(l){
    var n=+c[l.key]||0;
    var off=n===0;
    return '<div class="sw'+(off?' off':'')+'" title="'+(off?'No data in this period':'')+'">'
      +'<span class="dot" style="background:'+l.dot+'"></span>'+l.label
      +'<span class="cnt">'+(off?'no data':n.toLocaleString())+'</span>'
      +'<input type="checkbox" id="tg_'+l.id+'"'+(off?' disabled':(wanted[l.id]?' checked':''))
      +' data-change="toggleLayerEl" data-args="'+l.id+'"></div>';
  }).join('');
}

function toggleLayer(id,on){wanted[id]=on;refreshLayers();}
/* The checkbox used to pass this.checked inline; data-args carries only the id,
   so the checked state is read back off the element that fired. */
function toggleLayerEl(id){toggleLayer(id,KLAct.el().checked);}

function refreshLayers(){
  if(curPid==null)return;
  var p=curPeriod(),c=(p&&p.counts)||{};
  LAYERS.forEach(function(l){
    var key=l.id+'|'+curPid;
    var shouldShow=wanted[l.id]&&(+c[l.key]||0)>0;
    if(live[l.id]){map.removeLayer(live[l.id]);delete live[l.id];}
    if(!shouldShow)return;
    if(cache[key]){live[l.id]=cache[key].addTo(map);return;}
    setStatus('Loading '+l.label+'…');
    fetch(l.url(curPid)).then(function(r){return r.json();}).then(function(gj){
      var lyr=l.make(gj);
      cache[key]=lyr;
      /* only add if the user still wants it and the period hasn't changed */
      if(wanted[l.id]&&+curPid===+key.split('|')[1]){live[l.id]=lyr.addTo(map);}
      setStatus('');
    }).catch(function(e){setStatus('Failed to load '+l.label+' ('+e.message+')');});
  });
}

loadPeriods();
