/* ============================================================
   KLRAMS viewer · 08-condition-popup-nsv.js
   Road / condition click popup and the NSV video marker placement helpers.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
function rating(p,v){const t=PMAP[p];if(v>=t.poor)return POOR;if(v>=t.fair)return FAIR;return GOOD;}
function condAt(roadId,ch){const arr=segsByRoad[roadId]||[];for(const f of arr){const p=f.properties;if(ch>=p.from_ch&&ch<=p.to_ch)return p;}return null;}

function fmt(v,unit){const n=+v;return (isNaN(n)?v:(Number.isInteger(n)?n:n.toFixed(2)))+(unit||'');}
/* build 75 — fetch condition segments for popups without drawing the layer,
   so the Road Network popup can show chainage averages even when the
   "Road Condition Data" layer toggle is off. No-op once data is in memory. */
function ensureSegData(){
  if(DATA&&DATA.features)return Promise.resolve(DATA);
  return fetch('/api/segments/geojson').then(r=>r.json()).then(gj=>{
    if(!gj||!gj.features)return null;
    DATA=gj;segsByRoad={};
    gj.features.forEach(f=>{const r=f.properties.road;(segsByRoad[r]=segsByRoad[r]||[]).push(f);let lv=f.properties.lane_vals;if(typeof lv==='string'){try{lv=JSON.parse(lv);}catch(e){lv=null;}}if(lv&&typeof lv==='object'){Object.keys(lv).forEach(xsp=>{f.properties['L_'+xsp]=1;const o=lv[xsp]||{};Object.keys(o).forEach(k=>{if(o[k]!=null)f.properties[xsp+'_'+k]=o[k];});});}});
    return DATA;
  }).catch(()=>null);
}
/* Lookup decode (codes -> finalised words, from Lookup_sheet_Master_R2). */
var DECODE={
 'Road Class':{SH:'State Highway',MDR:'Major District Road',ODR:'Other District Road',NH:'National Highway'},
 'Road Type':{FLR:'Four Lane Road',TLR:'Two Lane Road',SLR:'Single Lane Road',ILR:'Intermediate Road',WTL:'Wide Two Lane Road'},
 'Pavement Width':{'1':'\u2265 3.75m & < 5.5m','2':'> 5.5m & < 7m','3':'\u2265 7m & < 10.5m','4':'\u2265 10.5m & \u2264 12.5m','5':'> 12.5 m'},
 'Shoulder Width':{'1':'No shoulder','2':'< 1m','3':'\u2265 1m & \u2264 2m','4':'> 2m'},
 'Construction Type':{FLX:'Flexible',RGD:'Rigid',CMP:'Composite',WBM:'WBM',GRV:'Gravel',ERT:'Earthen',PVB:'Paver Block'},
 'Surface Type':{BT:'Bituminous',CC:'Cement Concrete',CN:'Cement Concrete',PVB:'Paver Block',WBM:'WBM',GRV:'Gravel',ERT:'Earthen'},
 'Owner':{KMRL:'Kochi Metro Rail Limited',KRFB:'Kerala Road Fund Board','KRFB-PMU':'Kerala Road Fund Board - PMU',KSTP:'Kerala State Transport Project',RICK:'Road Infrastructure Company Kerala Limited','PWD Maintenance':'PWD Maintenance','PWD Section':'PWD Section','PWD':'PWD'},
 'Environment':{URB:'Urban',SUB:'Semi-Urban',RUR:'Rural',FOR:'Forest Area',COA:'Coastal Area',HIL:'Hilly Area',IND:'Industrial Area',RES:'Residential Area',AGR:'Agricultural/Plantation Area'},
 'Terrain':{FLT:'Flat',RLL:'Rolling',HIL:'Hilly/Steep'}
};
function decodeVal(param,v){
  if(v==null||v==='')return '';
  var t=DECODE[param];var s=String(v).trim();if(!t)return s;
  if(t[s]!=null)return t[s];
  var up=s.toUpperCase();for(var k in t){if(k.toUpperCase()===up)return t[k];}
  for(var k2 in t){if(String(t[k2]).toUpperCase()===up)return t[k2];} /* already a finalised word */
  return s;
}
/* PCI from road/segment data only (no invented numbers). Returns number or null. */
function pciOf(props,c){
  var keys=['pci','composite_pci','comp_pci','PCI','avg_pci','road_pci'];
  for(var i=0;i<keys.length;i++){if(props&&props[keys[i]]!=null&&props[keys[i]]!=='')return +props[keys[i]];}
  if(c){for(var j=0;j<keys.length;j++){if(c[keys[j]]!=null&&c[keys[j]]!=='')return +c[keys[j]];}}
  return null;
}
function pciBand(p){if(p>=80)return{l:'Good',c:'#34d399'};if(p>=60)return{l:'Fair',c:'#f2c200'};return{l:'Poor',c:'#e24b4a'};}

function klTab(btn,id){
  var card=btn.closest('.klcard');if(!card)return;
  card.querySelectorAll('.kc-tab').forEach(function(t){t.classList.remove('on');});btn.classList.add('on');
  card.querySelectorAll('.kc-pane').forEach(function(p){p.classList.toggle('on',p.id===id);});
  var sc=card.querySelector('.kc-panes');if(sc)sc.scrollTop=0;
}
function buildPopup(props,roadId,ch,lane){
  var esc=function(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');};
  var loc=function(keys){return (typeof nsvLoc==='function')?nsvLoc(props,keys):(function(){for(var i=0;i<keys.length;i++){var k=keys[i];if(props&&props[k]!=null&&props[k]!=='')return String(props[k]);}return '';})();};
  var name=props.name||props.Road_Name||loc(['Road_Name'])||roadId||'';
  var clsRaw=loc(['Road_Class','ROAD_CLASS','RoadClass','Class']);
  var cls=clsRaw?clsRaw.toString().toUpperCase():'';
  var clsKey=(cls.indexOf('MDR')>=0?'mdr':cls.indexOf('ODR')>=0?'odr':cls.indexOf('NH')>=0?'nh':'sh');
  var rnum=loc(['Road_Num','Road_No','RoadNumber','road_num','ROAD_NUM']);
  if(!rnum && roadId){var mm=String(roadId).match(/\/(SH|MDR|ODR|NH)\/0*(\d+)/i);if(mm)rnum=mm[2];}
  var district=loc(['District','district','Dist','DISTRICT','Distrct']);

  var c=condAt(roadId,ch);
  var fromCh=(c&&c.from_ch!=null)?+c.from_ch:null, toCh=(c&&c.to_ch!=null)?+c.to_ch:null;
  var segKm=(fromCh!=null&&toCh!=null)?((toCh-fromCh)/1000):null;
  var sLoc=loc(['Rd_Str_Loc','Start_Loc','Start_Location','Strt_Loc','start_location']);
  var eLoc=loc(['Rd_End_Loc','End_Loc','End_Location','end_location']);
  var lenKm=(function(){var m=loc(['Measrd_Len','Measured_Len','measrd_len']);if(m){var n=+String(m).replace(/[^0-9.]/g,'');if(!isNaN(n))return n/1000;}var l=parseFloat(props.len);if(!isNaN(l))return l/1000;return null;})();
  var carriage=loc(['Single_Du','Carriageway','carriageway']);
  var paveW=decodeVal('Pavement Width',loc(['Pavement_W','Pavement_Width','pavement_w']));
  var rtype=decodeVal('Road Type',loc(['Road_Type','RoadType','road_type']));
  var rid=String(roadId).replace(/\\/g,'\\\\').replace(/'/g,"\\'");

  var H='<div class="klcard">';
  /* header */
  H+='<div class="kc-head"><div class="kc-back" onclick="hideInspector()">&#8249;&nbsp; All roads</div>'+
     '<div class="kc-name">'+esc(name)+'</div><div class="kc-meta">'+
     '<span class="kc-chip '+clsKey+'">'+esc(cls||'SH')+(rnum?(' '+esc(rnum)):'')+'</span>'+
     '<span class="kc-sec">'+esc(roadId||props.road||'')+'</span>'+
     (district?'<span class="kc-pin">&#9678; '+esc(district)+'</span>':'')+'</div></div>';

  /* tab bar */
  H+='<div class="kc-tabs">'+
     '<button class="kc-tab" onclick="klTab(this,\'tab-prof\')">Profile</button>'+
     '<button class="kc-tab" onclick="klTab(this,\'tab-loc\')">Chainage</button>'+
     '<button class="kc-tab on" onclick="klTab(this,\'tab-cond\')">Condition</button>'+
     '<button class="kc-tab" onclick="klTab(this,\'tab-fwd\')">FWD</button>'+
     '</div><div class="kc-panes">';

  /* ---- Profile pane (road attributes, decoded) ---- */
  var attrs=[
    ['Road class', decodeVal('Road Class',clsRaw)],
    ['Road type', rtype],
    ['Construction', decodeVal('Construction Type',loc(['Cons_Type','Construction_Type','cons_type']))],
    ['Surface', decodeVal('Surface Type',loc(['Surface_Ty','Surface_Type','surface_type','Surface']))],
    ['Owner', decodeVal('Owner',loc(['Current_Ow','Owner','Current_Owner','owner']))],
    ['PWD section office', loc(['PWD_Sec','PWD_Section','pwd_sec'])],
    ['Road number', rnum],
    ['Carriageway', carriage],
    ['Pavement width', paveW],
    ['Shoulder width', decodeVal('Shoulder Width',loc(['Shoulder_W','Shoulder_Width','shoulder_w']))],
    ['Environment', decodeVal('Environment',loc(['Environmen','Environment','environment']))],
    ['Terrain', decodeVal('Terrain',loc(['Terrain','terrain']))]
  ].filter(function(a){return a[1]!=null&&a[1]!=='';});
  H+='<div class="kc-pane" id="tab-prof"><div class="kc-invgrid">';
  attrs.forEach(function(a){H+='<div class="kc-inv"><div class="ik">'+esc(a[0])+'</div><div class="iv">'+esc(a[1])+'</div></div>';});
  if(!attrs.length)H+='<div class="kp-none">No attributes recorded.</div>';
  H+='</div></div>';

  /* ---- Chainage & Location pane ---- */
  H+='<div class="kc-pane" id="tab-loc">';
  var num=function(v){if(v==null||v==='')return null;var n=+String(v).replace(/[^0-9.\-]/g,'');return isNaN(n)?null:n;};
  var rStrN=num(loc(['Rd_Str_cha','rd_str_cha','RD_STR_CHA']));
  var rEndN=num(loc(['Rd_End_cha','Rd_End_Cha','rd_end_cha','RD_END_CHA']));
  var ccS=(rStrN!=null)?rStrN:fromCh, ccE=(rEndN!=null)?rEndN:toCh;
  var ccKm=(ccS!=null&&ccE!=null)?(Math.abs(ccE-ccS)/1000):segKm;
  if(ccS!=null&&ccE!=null){
    H+='<div class="kc-condhead">Continuous chainage</div>';
    H+='<div class="kc-chrow"><span class="kc-ch">'+Math.round(ccS).toLocaleString()+' m</span><span class="kc-arr">&rarr;</span><span class="kc-ch">'+Math.round(ccE).toLocaleString()+' m</span>'+
       (ccKm!=null?'<span class="kc-km">'+ccKm.toFixed(2)+' km</span>':'')+'</div>';
  }
  if(sLoc||eLoc){
    H+='<div class="kc-locrow"><span class="kc-dot s"></span>'+esc(sLoc||'\u2013')+'<span class="kc-arr2">&rarr;</span><span class="kc-dot e"></span>'+esc(eLoc||'\u2013')+'</div>';
  }
  var locRows=[
    ['Section', roadId||props.road||''],
    ['Measured length', lenKm!=null?lenKm.toFixed(2)+' km':'']
  ].filter(function(a){return a[1]!=null&&a[1]!=='';});
  if(locRows.length){H+='<div class="kc-kvs" style="margin-top:11px">';locRows.forEach(function(a){H+='<div class="kc-kv"><span class="k">'+esc(a[0])+'</span><span class="v">'+esc(a[1])+'</span></div>';});H+='</div>';}
  H+='</div>';

  /* ---- Condition Data pane (matrix + PCI at chainage) ---- */
  H+='<div class="kc-pane on" id="tab-cond">';
  H+='<div class="kc-condhead">At '+Math.round(ch).toLocaleString()+' m'+(c&&c.xsp_list?' &middot; '+esc(c.xsp_list):'')+'</div>';
  if(c){
    var lv=null;try{lv=(typeof c.lane_vals==='string')?JSON.parse(c.lane_vals):c.lane_vals;}catch(e){}
    var lanes=lv?Object.keys(lv).sort():[];
    var metrics=[['iri','IRI','m/km'],['crack','Crack','%'],['rutting','Rutting','mm'],['ravelling','Ravel','%'],['texture','Texture',''],['patch_work','Patch','%']]
      .filter(function(m){return c['avg_'+m[0]]!=null||c[m[0]]!=null;});
    var cellCol=function(mk,x){return (x!=null&&typeof PMAP!=='undefined'&&PMAP[mk])?rating(mk,x):'';};
    H+='<table class="kc-ctbl"><tr><th class="mh">Metric</th>';
    if(lanes.length)lanes.forEach(function(L){H+='<th>'+esc(L)+'</th>';});
    H+='<th class="avg">Avg</th></tr>';
    metrics.forEach(function(m){
      var mk=m[0];H+='<tr><td class="mk">'+m[1]+(m[2]?'<span class="u"> '+m[2]+'</span>':'')+'</td>';
      if(lanes.length)lanes.forEach(function(L){var x=(lv[L]&&lv[L][mk]!=null)?+lv[L][mk]:null;var col=cellCol(mk,x);H+='<td'+(col?(' style="background:'+col+'2e"'):'')+'>'+(x!=null?(+x).toFixed(2):'\u2013')+'</td>';});
      var av=(c['avg_'+mk]!=null)?+c['avg_'+mk]:(c[mk]!=null?+c[mk]:null);var acol=cellCol(mk,av);
      H+='<td class="avgc"'+(acol?(' style="background:'+acol+'2e"'):'')+'>'+(av!=null?av.toFixed(2):'\u2013')+'</td></tr>';
    });
    H+='</table>';
    /* PCI at this chainage — persistent via global store (build 143) */
    var _pr=(window.KL&&KL.pci)?KL.pci(roadId,ch):{comp:pciOf(props,c),worst:(function(){var ks=['worst_pci','worst_lane_pci','pci_worst'];for(var i=0;i<ks.length;i++){if(c&&c[ks[i]]!=null)return +c[ks[i]];if(props[ks[i]]!=null)return +props[ks[i]];}return null;})()};
    var pciC=_pr.comp, pciW=_pr.worst;
    H+='<div class="kc-pcirow">';
    if(pciC!=null){var b=pciBand(pciC);H+='<div class="kc-pci"><span class="pl">Composite PCI</span><span class="pv" style="color:'+b.c+'">'+Math.round(pciC)+'</span></div>';}
    if(pciW!=null){var b2=pciBand(pciW);H+='<div class="kc-pci"><span class="pl">Worst-lane PCI</span><span class="pv" style="color:'+b2.c+'">'+Math.round(pciW)+'</span></div>';}
    if(pciC==null&&pciW==null)H+='<div class="kc-pend" style="margin:0">PCI not linked at this chainage</div>';
    H+='</div>';
  }else{H+='<div class="kp-none">No survey done at this chainage.</div>';}
  H+='</div>';

  /* ---- FWD pane (dedicated FWD module, by chainage range, in microns) ---- */
  H+='<div class="kc-pane" id="tab-fwd">';
  var kAt=(window.KL&&KL.atExact)?KL.atExact(roadId,ch):{};
  if(c){for(var _k in c){if(kAt[_k]==null||kAt[_k]==='')kAt[_k]=c[_k];}}
  var pick=function(o,keys){for(var i=0;i<keys.length;i++){if(o[keys[i]]!=null&&o[keys[i]]!=='')return o[keys[i]];}return null;};
  var _fr=(window.FWD&&FWD.at)?FWD.at(roadId,ch):null;
  var fwd=_fr?_fr.d0:null;
  var dnums=(_fr&&_fr.ds&&_fr.ds.length)?_fr.ds.filter(function(d){return d[0]!==0;}).map(function(d){return ['D'+d[0],d[1]];}):[];
  var traf=pick(kAt,['traffic_loc','traffic_location','aadt_loc','traffic_pt','aadt']);
  var soil=pick(kAt,['soil_subgrade','subgrade','cbr','soil_pt']);
  var core=pick(kAt,['bit_core','bituminous_core','core_thk','bt_thk','core_pt']);
  var hasPts=(fwd!=null||dnums.length||traf!=null||soil!=null||core!=null);
  if(hasPts){
    var rng=(_fr&&!isNaN(_fr.from)&&!isNaN(_fr.to))?(Math.round(_fr.from).toLocaleString()+' \u2013 '+Math.round(_fr.to).toLocaleString()+' m'):(Math.round(ch).toLocaleString()+' m');
    H+='<div class="kc-condhead">Deflection (microns) &middot; '+rng+'</div><div class="kc-kvs">';
    if(fwd!=null)H+='<div class="kc-kv"><span class="k">Central deflection (D0)</span><span class="v">'+fwd+' &micro;m</span></div>';
    dnums.forEach(function(d){H+='<div class="kc-kv"><span class="k">Deflection '+d[0]+'</span><span class="v">'+d[1]+' &micro;m</span></div>';});
    if(traf!=null)H+='<div class="kc-kv"><span class="k">Traffic location</span><span class="v">'+esc(String(traf))+'</span></div>';
    if(soil!=null)H+='<div class="kc-kv"><span class="k">Soil sub-grade</span><span class="v">'+esc(String(soil))+'</span></div>';
    if(core!=null)H+='<div class="kc-kv"><span class="k">Bituminous core</span><span class="v">'+esc(String(core))+'</span></div>';
    H+='</div>';
  } else {
    H+='<div class="kp-none">No FWD survey at this chainage.<br><span style="color:#7fa8cf">FWD loads automatically; if empty, no deflection range covers this point.</span></div>';
  }
  H+='</div>';

  H+='</div>'; /* /kc-panes */

  /* ---- footer actions (always visible) ---- */
  var hasVid=(typeof CATALOG!=='undefined'&&CATALOG[roadId]&&CATALOG[roadId].file);
  H+='<div class="kc-foot">';
  if(hasVid)H+='<button class="kc-playbtn" onclick="playSurveyFromPopup(\''+rid+'\','+(+ch||0)+')">&#9658;&nbsp; Play footage</button>';
  H+='<button class="kc-exportbtn" onclick="exportRoadCSV(\''+rid+'\')">&#8681;&nbsp; Export CSV</button>';
  H+='</div></div>';
  return H;
}

/* Export the decoded attribute set for a road as a CSV download. */
function exportRoadCSV(roadId){
  var f=(typeof ROADS!=='undefined')?ROADS[roadId]:null;if(!f)return;var p=f.properties||{};
  var loc=function(keys){for(var i=0;i<keys.length;i++){if(p[keys[i]]!=null&&p[keys[i]]!=='')return String(p[keys[i]]);}return '';};
  var rows=[
    ['Section label', roadId||p.road||''],
    ['Road name', p.name||p.Road_Name||''],
    ['Road number', loc(['Road_Num','Road_No'])],
    ['Road class', decodeVal('Road Class',loc(['Road_Class']))],
    ['Road type', decodeVal('Road Type',loc(['Road_Type']))],
    ['Construction type', decodeVal('Construction Type',loc(['Cons_Type']))],
    ['Surface type', decodeVal('Surface Type',loc(['Surface_Ty','Surface']))],
    ['Owner', decodeVal('Owner',loc(['Current_Ow','Owner']))],
    ['PWD section office', loc(['PWD_Sec'])],
    ['Carriageway', loc(['Single_Du'])],
    ['Pavement width', decodeVal('Pavement Width',loc(['Pavement_W']))],
    ['Shoulder width', decodeVal('Shoulder Width',loc(['Shoulder_W']))],
    ['Environment', decodeVal('Environment',loc(['Environmen']))],
    ['Start location', loc(['Rd_Str_Loc'])],
    ['End location', loc(['Rd_End_Loc'])],
    ['Start chainage (m)', loc(['Rd_Str_cha','Start_Chai'])],
    ['End chainage (m)', loc(['Rd_End_cha','End_Chaina'])],
    ['Measured length (m)', loc(['Measrd_Len'])]
  ];
  var q=function(s){s=String(s==null?'':s);return /[",\n]/.test(s)?('"'+s.replace(/"/g,'""')+'"'):s;};
  var csv='Attribute,Value\r\n'+rows.map(function(r){return q(r[0])+','+q(r[1]);}).join('\r\n');
  var blob=new Blob([csv],{type:'text/csv;charset=utf-8;'});var url=URL.createObjectURL(blob);
  var a=document.createElement('a');a.href=url;a.download=(String(roadId).replace(/[^\w.-]+/g,'_')||'road')+'_attributes.csv';
  document.body.appendChild(a);a.click();document.body.removeChild(a);setTimeout(function(){URL.revokeObjectURL(url);},1500);
}

/* Right-docked inspector panel (replaces the floating click popup) + zoom/highlight. */
var _inspCtx=null;
/* Open the inspector for a clicked road. Loads condition/PCI/FWD segment data
   on the spot (via ensureSegData) so the card is populated even when the
   Road Condition Data / FWD layer toggles are OFF, then refreshes in place. */
function openInspector(props,roadId,ch,lane){
  _inspCtx={props:props,roadId:roadId,ch:ch,lane:lane};
  showInspector(buildPopup(props,roadId,ch,lane),roadId);
  if(typeof ensureSegData==='function'){
    try{ensureSegData().then(function(){if(_inspCtx&&_inspCtx.roadId===roadId)refreshInspectorData();});}catch(e){}
  }
}
function refreshInspectorData(){
  if(!_inspCtx)return;var body=document.getElementById('riBody'),panel=document.getElementById('roadInspector');
  if(!body||!panel||!panel.classList.contains('open'))return;
  var act=document.querySelector('#roadInspector .kc-pane.on');var curId=act?act.id:'tab-cond';
  body.innerHTML=buildPopup(_inspCtx.props,_inspCtx.roadId,_inspCtx.ch,_inspCtx.lane);
  var b=document.querySelector('#roadInspector .kc-tab[onclick*="'+curId+'"]');if(b)klTab(b,curId);
}
function showInspector(html,roadId){
  var panel=document.getElementById('roadInspector'),body=document.getElementById('riBody');
  if(!panel||!body){return;}
  body.innerHTML=html;body.scrollTop=0;panel.classList.add('open');
  try{if(roadId&&typeof ROADS!=='undefined'&&ROADS[roadId])highlightRoad(ROADS[roadId]);}catch(e){}
}
function hideInspector(){var p=document.getElementById('roadInspector');if(p)p.classList.remove('open');_inspCtx=null;if(typeof clearHighlight==='function')clearHighlight();}

/* Bright highlight of the selected road + zoom to it (right padding clears the panel). */
function highlightRoad(feature){
  if(typeof map==='undefined'||!feature||!feature.geometry)return;
  var data={type:'FeatureCollection',features:[feature]};
  if(map.getSource('sel-road')){map.getSource('sel-road').setData(data);}
  else{
    map.addSource('sel-road',{type:'geojson',data:data});
    map.addLayer({id:'sel-road-glow',type:'line',source:'sel-road',layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':'#34d399','line-width':11,'line-opacity':0.35,'line-blur':3}});
    map.addLayer({id:'sel-road-line',type:'line',source:'sel-road',layout:{'line-cap':'round','line-join':'round'},paint:{'line-color':'#9af0c9','line-width':3.4}});
  }
  ['sel-road-glow','sel-road-line'].forEach(function(id){try{map.moveLayer(id);}catch(e){}});
  try{var b=new maplibregl.LngLatBounds();var w=function(a){if(typeof a[0]==='number')b.extend(a);else a.forEach(w);};w(feature.geometry.coordinates);
    if(!b.isEmpty())map.fitBounds(b,{padding:{top:70,left:70,right:350,bottom:70},maxZoom:15,duration:700});}catch(e){}
}
function clearHighlight(){if(typeof map==='undefined')return;['sel-road-line','sel-road-glow'].forEach(function(id){try{if(map.getLayer(id))map.removeLayer(id);}catch(e){}});try{if(map.getSource('sel-road'))map.removeSource('sel-road');}catch(e){}}

/* Open the survey dock for a road straight from the inspector, at the clicked
   chainage — synthesises the map point from the road geometry and reuses onPick,
   so the existing video pipeline runs unchanged. */
function playSurveyFromPopup(roadId,ch){
  try{var el=document.getElementById('videoMode');if(el){el.checked=true;if(typeof syncVClick==='function')syncVClick();}}catch(e){}
  if(typeof hideInspector==='function')hideInspector();
  if(typeof ROADS==='undefined'||!ROADS[roadId]||typeof onPick!=='function')return;
  var f=ROADS[roadId];var line=(typeof lineOf==='function')?lineOf(f):null;if(!line||typeof turf==='undefined')return;
  var len=parseFloat(f.properties.len)||0;var km=turf.length(line,{units:'kilometers'});
  var d=(len>0&&km>0)?(Math.max(0,Math.min(+ch||0,len))/len)*km:0;
  var pt=turf.along(line,d,{units:'kilometers'});
  onPick(roadId,{lng:pt.geometry.coordinates[0],lat:pt.geometry.coordinates[1]},null);
}

function buildCar(){const el=document.createElement('div');el.className='nsvmark';el.style.pointerEvents='none';el.innerHTML='<div class="nsvtag">RMMS Cell</div><div class="nsviri" id="carIri">IRI –</div><div class="nsvicon" id="caricon"><svg width="34" height="50" viewBox="0 0 34 50" xmlns="http://www.w3.org/2000/svg"><rect x="1" y="22" width="6" height="3" rx="1" fill="#0e2038"/><rect x="27" y="22" width="6" height="3" rx="1" fill="#0e2038"/><rect x="7" y="2" width="20" height="46" rx="6" fill="#eef2f7" stroke="#0e2038" stroke-width="2"/><rect x="10" y="5" width="14" height="6" rx="2" fill="#9ec5ff"/><rect x="9" y="14" width="3" height="18" rx="1.5" fill="#bcd4ee"/><rect x="22" y="14" width="3" height="18" rx="1.5" fill="#bcd4ee"/><rect x="13" y="16" width="8" height="14" rx="2" fill="#0e2038"/><circle cx="17" cy="23" r="2.6" fill="#16a06b"/><circle cx="17" cy="9" r="1.6" fill="#0e2038"/><rect x="10" y="44.5" width="4" height="2" rx="1" fill="#da4b43"/><rect x="20" y="44.5" width="4" height="2" rx="1" fill="#da4b43"/></svg></div><div class="nsvdata" id="carLabel">CH 0 m</div>';marker=new maplibregl.Marker({element:el,anchor:'center'});carIcon=el.querySelector('#caricon');carLabel=el.querySelector('#carLabel');carIri=el.querySelector('#carIri');}
function placeCar(frac){if(!cur)return;if(!marker)buildCar();const f=Math.max(0,Math.min(frac,1)),d=f*cur.geoLenKm;const pt=turf.along(cur.line,d,{units:'kilometers'});curCarLL=pt.geometry.coordinates;marker.setLngLat(curCarLL).addTo(map);const eps=Math.max(cur.geoLenKm*0.003,0.003);const a=turf.along(cur.line,Math.max(0,d-eps),{units:'kilometers'});const b=turf.along(cur.line,Math.min(cur.geoLenKm,d+eps),{units:'kilometers'});let brg=turf.bearing(a,b);if(dir==='rev')brg+=180;window.curCarBrg=brg;if(carIcon)carIcon.style.transform='rotate('+brg+'deg)';}
function laneIriAt(roadId,ch){
  const c=condAt(roadId,ch);
  if(!c)return null;
  let lv=null;try{lv=typeof c.lane_vals==='string'?JSON.parse(c.lane_vals):c.lane_vals;}catch(e){}
  if(lv){
    if(lv.CC&&lv.CC.iri!=null)return {iri:+lv.CC.iri,lane:'Road'};
    const pref=dir==='fwd'?['CL1','CL2']:['CR1','CR2'];
    for(const L of pref)if(lv[L]&&lv[L].iri!=null)return {iri:+lv[L].iri,lane:L};
    for(const L of Object.keys(lv))if(lv[L].iri!=null)return {iri:+lv[L].iri,lane:L};
  }
  if(c.avg_iri!=null)return {iri:+c.avg_iri,lane:'Avg'};
  if(c.iri!=null)return {iri:+c.iri,lane:''};
  return {iri:null,lane:''};
}
function setChainage(frac){
  if(!cur)return;
  const ch=Math.round(frac*cur.len);
  document.getElementById('dCh').textContent=ch.toLocaleString();
  const di=document.getElementById('dIri');
  /* build 122 — the vehicle (and dock) now report the AVERAGE IRI of the
     carriageway at this chainage, matching the lane-wise + average HUD. */
  const c=(typeof condAt==='function')?condAt(cur.road,ch):null;
  let avg=null;
  if(c){
    if(c.avg_iri!=null&&c.avg_iri!=='')avg=+c.avg_iri;
    else if(c.iri!=null&&c.iri!=='')avg=+c.iri;
  }
  if(avg==null){
    if(carIri){carIri.textContent='No survey';carIri.style.background='#64718a';}
    if(carLabel)carLabel.textContent='CH '+ch+' m';
    if(di)di.textContent='\u2013';
    return;
  }
  const t=avg.toFixed(2);
  if(carIri){carIri.textContent='Avg IRI '+t;carIri.style.background=(typeof rating==='function')?rating('iri',avg):'#64718a';}
  if(carLabel)carLabel.textContent='CH '+ch+' m';
  if(di)di.textContent=t;
}
function setDir(d){dir=d;document.getElementById('fwd').classList.toggle('on',d==='fwd');document.getElementById('rev').classList.toggle('on',d==='rev');if(typeof updateRouteLabel==='function')updateRouteLabel();}
let playSpeed=1;
