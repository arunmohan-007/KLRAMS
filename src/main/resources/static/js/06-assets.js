/* ============================================================
   KLRAMS viewer · 06-assets.js
   Structures & furniture layers (bridges, culverts, furniture) with map icons and popups.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
// ---- asset layers (bridges, culverts, furniture) ----
const ASSET_DATA={}; /* Build 163 — loaded geojson per asset type, for the filter summary card */
/* Shared opener for the tall .klpop cards (geotech assets, traffic). The CSS
   max-height cap alone is not enough: near the map edge maplibre can still
   anchor the card partly outside the viewport, leaving rows unreachable.
   After the popup renders, pan the map just enough that the whole card
   (plus tip) is inside the container. */
function klPopup(lngLat,html){
  const p=new maplibregl.Popup({maxWidth:'290px'}).setLngLat(lngLat).setHTML(html).addTo(map);
  setTimeout(function(){
    try{
      const el=p.getElement(); if(!el)return;
      const r=el.getBoundingClientRect(), m=map.getContainer().getBoundingClientRect();
      const pad=12; let dx=0,dy=0;
      if(r.top<m.top+pad)dy=r.top-(m.top+pad);
      else if(r.bottom>m.bottom-pad)dy=Math.min(r.bottom-(m.bottom-pad),r.top-(m.top+pad));
      if(r.left<m.left+pad)dx=r.left-(m.left+pad);
      else if(r.right>m.right-pad)dx=Math.min(r.right-(m.right-pad),r.left-(m.left+pad));
      if(dx||dy)map.panBy([Math.round(dx),Math.round(dy)],{duration:220});
    }catch(e){}
  },80);
  return p;
}
const ASSETS=[
  {type:'bridge',  layer:'as-bridge',  kind:'line',  color:'#8a5cb8', width:6, toggle:'showBridge',  label:'Bridge'},
  {type:'furniture_line', layer:'as-furnl', kind:'line', color:'#0fa3a3', width:4, toggle:'showFurnL', label:'Furniture (line)'},
  {type:'culvert', layer:'as-culvert', kind:'point', color:'#e07b2a', radius:6, toggle:'showCulvert', label:'Culvert'},
  {type:'furniture_point', layer:'as-furnp', kind:'point', color:'#3b6fa0', radius:5, toggle:'showFurnP', label:'Furniture (point)'},
  {type:'subgrade', layer:'as-soil', kind:'point', color:'#8a4d1f', radius:5, toggle:'showSoil', label:'Sub-Grade Soil'},
  {type:'bituminous_core', layer:'as-core', kind:'point', color:'#2b2b2b', radius:5, toggle:'showCore', label:'Bituminous Core'},
  {type:'pavement_crust', layer:'as-crust', kind:'point', color:'#b8860b', radius:5, toggle:'showCrust', label:'Pavement Crust'},
  {type:'fwd', layer:'as-fwd', kind:'point', color:'#7b1fa2', radius:5, toggle:'showFwd', label:'FWD'}
];
function fwdD0(p){if(!p)return null;for(const k in p){const kk=String(k).toLowerCase().replace(/[^a-z0-9]/g,'');if(kk==='d0'||kk==='do'){const v=p[k];if(v!=null&&v!=='')return v;}}return null;}
const FWD_D0_STOPS=[['#1a9850','< 100'],['#91cf60','100 – 200'],['#fee08b','200 – 350'],['#fdae61','350 – 500'],['#f46d43','500 – 700'],['#b2182b','> 700']];
function fwdScale(gj){let mx=0;((gj&&gj.features)||[]).forEach(f=>{const v=parseFloat(fwdD0(f.properties));if(!isNaN(v))mx=Math.max(mx,Math.abs(v));});return (mx>0&&mx<10)?1000:1;}
function fwdD0ColorExpr(){return ['case',['has','__d0'],['step',['to-number',['get','__d0']],'#1a9850',100,'#91cf60',200,'#fee08b',350,'#fdae61',500,'#f46d43',700,'#b2182b'],'#9aa0a6'];}
function renderFwdLegend(){const el=document.getElementById('fwdLegend');if(!el)return;el.innerHTML='<div class="fl-t">D0 deflection (microns)</div>'+FWD_D0_STOPS.map(x=>'<div class="fl-r"><span class="sw" style="background:'+x[0]+'"></span>'+x[1]+'</div>').join('');}
/* Build 164 — professional, unit-aware popups for the geotechnical datasets.
   Field keys below are the exact CSV column names; each row is [key, label, unit]. */
const ASSET_UNITS_SCHEMA={
  subgrade:{lane:'Xsp',groups:[
    {t:'Classification',rows:[['Soil Type','Soil type','']]},
    {t:'Atterberg limits',rows:[['LL','Liquid limit','%'],['PL','Plastic limit','%'],['PI','Plasticity index','%']]},
    {t:'Strength & compaction',rows:[['CBR','CBR (soaked)','%'],['MDD','Max dry density','g/cc'],['OMC','Optimum moisture','%'],['FDD','Field dry density','g/cc'],['FMC','Field moisture','%'],['Doc','Degree of compaction','%']]},
    {t:'Gradation',rows:[['Gravel Content','Gravel','%'],['Sand Content','Sand','%']]},
    {t:'IS sieve · % passing',grid:true,rows:[['Percentage_IS_Sieve_20mm','20 mm','%'],['Percentage_IS_Sieve_10mm','10 mm','%'],['Percentage_IS_Sieve_4.75mm','4.75 mm','%'],['Percentage_IS_Sieve_2.36mm','2.36 mm','%'],['Percentage_IS_Sieve_0.425mm','0.425 mm','%'],['Percentage_IS_Sieve_0.075mm','0.075 mm','%']]}
  ]},
  bituminous_core:{lane:'XSP',groups:[
    {t:'Core',rows:[['Core No','Core no.','']]},
    {t:'Layer thickness',rows:[['Observed Thickness of Wearing Course mm','Wearing course','mm'],['Observed Thickness of Binder Course mm','Binder course','mm'],['Total Observed bituminous layers thickness mm','Total bituminous','mm']]},
    {t:'Bulk density',rows:[['Bulk Density of Wearing Course gmcc','Wearing course','g/cc'],['Bulk Density of Binder Course gmcc','Binder course','g/cc']]}
  ]},
  pavement_crust:{lane:'XSP',groups:[
    {t:'Composition',rows:[['Surface Type','Surface',''],['Base Type','Base',''],['Sub Base Type','Sub-base',''],['Sub Grade Soil Type','Sub-grade soil','']]},
    {t:'Layer thickness',rows:[['Surface Thickness','Surface','mm'],['Base Thickness','Base','mm'],['Sub Base Thickness','Sub-base','mm']]},
    {t:'Sub-grade',rows:[['Sub Grade CBR','Sub-grade CBR','%']]}
  ]}
};
function _assetUVal(raw,unit){
  if(raw==null||String(raw).trim()==='')return null;
  const s=String(raw).trim();
  if(unit){const num=Number(s.replace(/,/g,''));if(!isNaN(num))return escH(s)+' <span class="kp-u">'+escH(unit)+'</span>';}
  return escH(s);
}
function _assetPrettyKey(k){return String(k).replace(/_/g,' ').replace(/\s+/g,' ').trim();}
/* derive a friendly label + unit from a raw column name (e.g. "...Thickness mm"
   → {label:"... Thickness", unit:"mm"}). Only pulls units that are explicitly
   present in the name, so we never invent an incorrect unit. */
function _assetKeyMeta(k){
  let key=String(k).replace(/_/g,' ').replace(/\s+/g,' ').trim();let unit='';
  if(/\bgm\s?cc\b/i.test(key)){unit='g/cc';key=key.replace(/\bgm\s?cc\b/i,'');}
  else if(/\bmm\b/i.test(key)){unit='mm';key=key.replace(/\bmm\b/i,'');}
  else if(/\bcm\b/i.test(key)){unit='cm';key=key.replace(/\bcm\b/i,'');}
  else if(/\b(sq\s?m|m2)\b/i.test(key)){unit='m²';key=key.replace(/\b(sq\s?m|m2)\b/i,'');}
  else if(/\bkg\b/i.test(key)){unit='kg';key=key.replace(/\bkg\b/i,'');}
  return {label:key.replace(/\s+/g,' ').trim(),unit:unit};
}
function assetProPopup(lngLat,p,type,label){
  const sch=ASSET_UNITS_SCHEMA[type];
  const rdv=pickProp(p,ROAD_KEYS),road=(rdv!=null?rdv:p.road)||'';
  const lane=p[sch.lane]||'';
  const handled={};
  Object.keys(p).forEach(k=>{const c=ckey(k);if(k.charAt(0)==='_'||ROAD_KEYS.indexOf(c)>=0||FROM_KEYS.indexOf(c)>=0||TO_KEYS.indexOf(c)>=0)handled[k]=1;});
  ['from_ch','to_ch','Chainage','Date',sch.lane,'Section Label','Section Label Code','Section Start Date'].forEach(k=>{handled[k]=1;});
  const f=(p.from_ch!=null&&p.from_ch!=='')?p.from_ch:null,t2=(p.to_ch!=null&&p.to_ch!=='')?p.to_ch:null;
  let chTxt='';
  if(f!=null&&t2!=null)chTxt=escH(f)+' – '+escH(t2)+' m';
  else if(f!=null)chTxt=escH(f)+' m';
  else if(p.Chainage!=null&&p.Chainage!=='')chTxt=escH(p.Chainage)+' m';
  let h='<div class="klpop asset-klpop">';
  h+='<div class="kp-head"><div class="kp-name">'+escH(label)+'</div><div class="kp-meta">'+(lane?'<span class="kp-chip">'+escH(lane)+'</span>':'')+(road?'<span class="kp-sec">'+escH(road)+'</span>':'')+'</div></div>';
  let loc='';
  if(chTxt)loc+='<div class="kp-attr"><span class="kp-k">Chainage</span><span class="kp-v">'+chTxt+'</span></div>';
  if(p.Date)loc+='<div class="kp-attr"><span class="kp-k">Test date</span><span class="kp-v">'+escH(p.Date)+'</span></div>';
  if(loc)h+='<div class="kp-block"><div class="kp-eyebrow">Location</div><div class="kp-attrs">'+loc+'</div></div>';
  sch.groups.forEach(g=>{
    g.rows.forEach(r=>{handled[r[0]]=1;});
    if(g.grid){
      let cells='';g.rows.forEach(r=>{const v=_assetUVal(p[r[0]],r[2]);if(v==null)return;cells+='<div class="kp-gcell"><span class="kp-gk">'+escH(r[1])+'</span><span class="kp-gv">'+v+'</span></div>';});
      if(cells)h+='<div class="kp-block"><div class="kp-eyebrow">'+escH(g.t)+'</div><div class="kp-grid">'+cells+'</div></div>';
    }else{
      let rows='';g.rows.forEach(r=>{const v=_assetUVal(p[r[0]],r[2]);if(v==null)return;rows+='<div class="kp-attr"><span class="kp-k">'+escH(r[1])+'</span><span class="kp-v">'+v+'</span></div>';});
      if(rows)h+='<div class="kp-block"><div class="kp-eyebrow">'+escH(g.t)+'</div><div class="kp-attrs">'+rows+'</div></div>';
    }
  });
  let extra='';Object.keys(p).forEach(k=>{if(handled[k])return;const v=p[k];if(v==null||v==='')return;extra+='<div class="kp-attr"><span class="kp-k">'+escH(_assetPrettyKey(k))+'</span><span class="kp-v">'+escH(v)+'</span></div>';});
  if(extra)h+='<div class="kp-block"><div class="kp-eyebrow">Additional</div><div class="kp-attrs">'+extra+'</div></div>';
  h+='</div>';
  klPopup(lngLat,h);
}
function assetPopup(lngLat,p,label,type){
  p=p||{};
  if(type&&ASSET_UNITS_SCHEMA[type])return assetProPopup(lngLat,p,type,label);
  const rdv=pickProp(p,ROAD_KEYS),road=(rdv!=null?rdv:p.road)||'';
  const fr=pickProp(p,FROM_KEYS),to=pickProp(p,TO_KEYS);
  const sc=+p.__dscale||1;
  const isFwd=String(label).toUpperCase()==='FWD';
  const lane=p.XSP||p.Xsp||p.xsp||'';
  const defl=[];
  Object.keys(p).forEach(k=>{if(k.charAt(0)==='_')return;const m=ckey(k).match(/^d(\d+)$/);if(m){const v=p[k];if(v!=null&&v!=='')defl.push([parseInt(m[1],10),v]);}});
  defl.sort((x,y)=>x[0]-y[0]);
  const skip={};Object.keys(p).forEach(k=>{const c=ckey(k);if(k==='__d0'||k==='__dscale'||/^d\d+$/.test(c)||ROAD_KEYS.indexOf(c)>=0||FROM_KEYS.indexOf(c)>=0||TO_KEYS.indexOf(c)>=0||c==='xsp'||c==='date')skip[k]=1;});
  /* FWD popup: hide survey-admin rows (survey type/version/dates, company). */
  if(isFwd){const _fwdHide={surveytype:1,surveyversion:1,surveyenddate:1,surveystartdate:1,sectionstartdate:1,surveyingcompanyname:1,surveycompanyname:1};Object.keys(p).forEach(k=>{if(_fwdHide[ckey(k)])skip[k]=1;});}
  let chTxt='';
  if(fr!=null&&fr!==''&&to!=null&&to!=='')chTxt=escH(fr)+' – '+escH(to)+' m';
  else if(fr!=null&&fr!=='')chTxt=escH(fr)+' m';
  let h='<div class="klpop asset-klpop">';
  h+='<div class="kp-head"><div class="kp-name">'+escH(label)+'</div><div class="kp-meta">'+(lane?'<span class="kp-chip">'+escH(lane)+'</span>':'')+(road?'<span class="kp-sec">'+escH(road)+'</span>':'')+'</div></div>';
  let loc='';
  if(chTxt)loc+='<div class="kp-attr"><span class="kp-k">Chainage</span><span class="kp-v">'+chTxt+'</span></div>';
  if(p.Date)loc+='<div class="kp-attr"><span class="kp-k">Date</span><span class="kp-v">'+escH(p.Date)+'</span></div>';
  if(loc)h+='<div class="kp-block"><div class="kp-eyebrow">Location</div><div class="kp-attrs">'+loc+'</div></div>';
  let rows='';
  Object.keys(p).forEach(k=>{if(skip[k])return;const v=p[k];if(v==null||v==='')return;const meta=_assetKeyMeta(k);rows+='<div class="kp-attr"><span class="kp-k">'+escH(meta.label||k)+'</span><span class="kp-v">'+(_assetUVal(v,meta.unit)||escH(v))+'</span></div>';});
  if(rows)h+='<div class="kp-block"><div class="kp-eyebrow">'+(isFwd?'Details':'Attributes')+'</div><div class="kp-attrs">'+rows+'</div></div>';
  if(defl.length)h+='<div class="kp-block"><div class="kp-eyebrow">Deflections'+(sc===1000?' · microns':'')+'</div><div class="kp-grid">'+defl.map(d=>'<div class="kp-gcell"><span class="kp-gk">D'+d[0]+'</span><span class="kp-gv">'+escH(sc===1000?Math.round(+d[1]*sc):d[1])+'</span></div>').join('')+'</div></div>';
  h+='</div>';
  klPopup(lngLat,h);
}
const ICON_SVGS={
 'ic-soil':'<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30"><circle cx="15" cy="15" r="13" fill="#8a4d1f" stroke="#fff" stroke-width="2.4"/><path d="M8 18c2-2 4 2 7 0s4 2 7 0" fill="none" stroke="#fff" stroke-width="2"/><circle cx="11" cy="12" r="1.5" fill="#fff"/><circle cx="17" cy="11" r="1.2" fill="#fff"/><circle cx="20" cy="14" r="1.4" fill="#fff"/></svg>',
 'ic-core':'<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30"><circle cx="15" cy="15" r="13" fill="#2b2b2b" stroke="#fff" stroke-width="2.4"/><rect x="11" y="8" width="8" height="14" rx="2" fill="none" stroke="#fff" stroke-width="2"/><path d="M11 13h8M11 17h8" stroke="#fff" stroke-width="1.8"/></svg>',
 'ic-crust':'<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30"><circle cx="15" cy="15" r="13" fill="#b8860b" stroke="#fff" stroke-width="2.4"/><path d="M8 11h14M8 15h14M8 19h14" stroke="#fff" stroke-width="2.2"/></svg>',
 'ic-fwd':'<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30"><circle cx="15" cy="15" r="13" fill="#7b1fa2" stroke="#fff" stroke-width="2.4"/><path d="M15 8v8" stroke="#fff" stroke-width="2.4"/><path d="M11 13l4 4 4-4" fill="none" stroke="#fff" stroke-width="2.2"/><path d="M9 21c2 1.5 10 1.5 12 0" fill="none" stroke="#fff" stroke-width="2"/></svg>',
 'ic-bridge':'<svg xmlns="http://www.w3.org/2000/svg" width="34" height="34" viewBox="0 0 34 34"><rect x="1" y="1" width="32" height="32" rx="7" fill="#8a5cb8" stroke="#fff" stroke-width="2"/><path d="M6 23v-5c0-6 22-6 22 0v5" fill="none" stroke="#fff" stroke-width="2.4"/><path d="M6 23h22M10 18.2V23M17 16.5V23M24 18.2V23" stroke="#fff" stroke-width="2"/></svg>',
 'ic-culvert':'<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30"><circle cx="15" cy="15" r="13" fill="#e07b2a" stroke="#fff" stroke-width="2.4"/><circle cx="15" cy="15" r="6.5" fill="none" stroke="#fff" stroke-width="2.4"/><path d="M11 15h8" stroke="#fff" stroke-width="2"/></svg>',
 'ic-furnp':'<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30"><circle cx="15" cy="15" r="13" fill="#3b6fa0" stroke="#fff" stroke-width="2.4"/><path d="M15 7l7 11H8z" fill="#fff"/><rect x="14" y="18" width="2" height="6" fill="#fff"/></svg>',
 'ic-furnl':'<svg xmlns="http://www.w3.org/2000/svg" width="32" height="32" viewBox="0 0 32 32"><rect x="1" y="1" width="30" height="30" rx="7" fill="#0fa3a3" stroke="#fff" stroke-width="2"/><path d="M6 13h20M6 19h20" stroke="#fff" stroke-width="2.6"/><path d="M9 13v6M16 13v6M23 13v6" stroke="#fff" stroke-width="2"/></svg>'};
function loadIcon(name){return new Promise(res=>{if(map.hasImage(name))return res();const img=new Image(40,40);img.onload=()=>{if(!map.hasImage(name))map.addImage(name,img,{pixelRatio:2});res();};img.onerror=()=>res();img.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(ICON_SVGS[name]);});}
function addAssetLayer(a,gj,asLine){
  if(map.getSource(a.layer)){map.getSource(a.layer).setData(gj);return;}
  map.addSource(a.layer,{type:'geojson',data:gj});
  const icon=a.type==='bridge'?'ic-bridge':a.type==='culvert'?'ic-culvert':a.type==='furniture_line'?'ic-furnl':a.type==='subgrade'?'ic-soil':a.type==='bituminous_core'?'ic-core':a.type==='pavement_crust'?'ic-crust':a.type==='fwd'?'ic-fwd':'ic-furnp';
  const t=document.getElementById(a.toggle);
  if(a.type==='fwd'){
    const col=fwdD0ColorExpr();
    map.addLayer({id:a.layer,type:'line',source:a.layer,layout:{'line-cap':'round'},paint:{'line-color':col,'line-width':['interpolate',['linear'],['zoom'],10,3.6,16,7.5]}});
    map.addLayer({id:a.layer+'-pt',type:'circle',source:a.layer,filter:['==',['geometry-type'],'Point'],paint:{'circle-color':col,'circle-radius':['interpolate',['linear'],['zoom'],10,3.2,16,6],'circle-stroke-color':'#ffffff','circle-stroke-width':1.2}});
    map.addLayer({id:a.layer+'-icon',type:'symbol',source:a.layer,layout:{'symbol-placement':'line-center','text-field':['case',['has','__d0'],['concat','(',['to-string',['get','__d0']],')'],''],'text-size':['interpolate',['linear'],['zoom'],10,9.5,16,12.5],'text-allow-overlap':true},paint:{'text-color':'#2b1840','text-halo-color':'#ffffff','text-halo-width':1.7}});
    [a.layer,a.layer+'-pt',a.layer+'-icon'].forEach(id=>{map.on('click',id,e=>{if(e.features.length)assetPopup(e.lngLat,e.features[0].properties,a.label,a.type);});map.on('mouseenter',id,()=>map.getCanvas().style.cursor='pointer');map.on('mouseleave',id,()=>map.getCanvas().style.cursor='');if(t)map.setLayoutProperty(id,'visibility',t.checked?'visible':'none');});
    renderFwdLegend();const lg=document.getElementById('fwdLegend');if(lg&&t)lg.style.display=t.checked?'block':'none';
    return;
  }
  if(a.kind==='line'||asLine){
    const lw=a.width||5;
    map.addLayer({id:a.layer,type:'line',source:a.layer,layout:{'line-cap':'round'},paint:{'line-color':a.color,'line-width':['interpolate',['linear'],['zoom'],10,lw*0.6,16,lw*1.6]}});
    map.on('click',a.layer,e=>{if(e.features.length)assetPopup(e.lngLat,e.features[0].properties,a.label,a.type);});
    map.on('mouseenter',a.layer,()=>map.getCanvas().style.cursor='pointer');
    map.on('mouseleave',a.layer,()=>map.getCanvas().style.cursor='');
    if(t)map.setLayoutProperty(a.layer,'visibility',t.checked?'visible':'none');
    loadIcon(icon).then(()=>{if(map.getLayer(a.layer+'-icon'))return;const lo={'symbol-placement':'line-center','icon-image':icon,'icon-size':['interpolate',['linear'],['zoom'],10,0.55,16,1.0],'icon-allow-overlap':true},pt={};if(a.type==='fwd'){lo['text-field']=['case',['has','__d0'],['concat','(',['to-string',['get','__d0']],')'],''];lo['text-size']=['interpolate',['linear'],['zoom'],10,9.5,16,12.5];lo['text-offset']=[0,1.2];lo['text-anchor']='top';lo['text-allow-overlap']=true;pt['text-color']='#7b1fa2';pt['text-halo-color']='#ffffff';pt['text-halo-width']=1.5;}map.addLayer({id:a.layer+'-icon',type:'symbol',source:a.layer,layout:lo,paint:pt});const t2=document.getElementById(a.toggle);if(t2&&!t2.checked)map.setLayoutProperty(a.layer+'-icon','visibility','none');});
  }else{
    loadIcon(icon).then(()=>{map.addLayer({id:a.layer,type:'symbol',source:a.layer,layout:{'icon-image':icon,'icon-size':['interpolate',['linear'],['zoom'],10,0.5,16,1.0],'icon-allow-overlap':true}});
      map.on('click',a.layer,e=>{if(e.features.length)assetPopup(e.lngLat,e.features[0].properties,a.label,a.type);});
      map.on('mouseenter',a.layer,()=>map.getCanvas().style.cursor='pointer');
      map.on('mouseleave',a.layer,()=>map.getCanvas().style.cursor='');
      if(t)map.setLayoutProperty(a.layer,'visibility',t.checked?'visible':'none');});
  }
}
/* Build 167 — single shared FWD download: both the as-fwd map layer (loadAsset
   below) and the chainage-lookup module (24-fwd.js) need /api/assets/fwd/geojson.
   Each used to fetch it independently, so every login downloaded the FWD survey
   twice. Share one promise; cleared on failure so a later toggle can retry. */
function fwdGeojsonFetch(force){
  if(force)window._fwdGeoP=null;
  if(!window._fwdGeoP){
    window._fwdGeoP=fetch('/api/assets/fwd/geojson',{credentials:'same-origin'}).then(r=>r.ok?r.json():null);
    window._fwdGeoP.catch(()=>{window._fwdGeoP=null;});
  }
  return window._fwdGeoP;
}
function loadAsset(a){
    return (a.type==='fwd'?fwdGeojsonFetch():fetch('/api/assets/'+a.type+'/geojson').then(r=>r.json())).then(gj=>{
      if(!gj||!gj.features||!gj.features.length)return;
      const asLine=(a.type==='fwd')||((a.kind==='point')&&isStretchData(gj));
      const go=()=>{if(asLine)linRefFeatures(gj);if(a.type==='fwd'){const sc=fwdScale(gj);gj.features.forEach(f=>{const v=fwdD0(f.properties);if(v!=null&&v!=='')f.properties.__d0=Math.round(+v*sc);f.properties.__dscale=sc;});}
        /* Build 163 — resolve each feature's section label into __sec so the
           network-scope filter can match assets regardless of CSV column names */
        gj.features.forEach(f=>{const v=pickProp(f.properties,ROAD_KEYS);if(v!=null&&v!=='')f.properties.__sec=String(v);});
        ASSET_DATA[a.type]=gj;
        addAssetLayer(a,gj,asLine);
        if(typeof updateNetScopeCard==='function')updateNetScopeCard();};
      if(asLine)return ensureRoads().then(go);
      go();
    }).catch(()=>{});
}
function loadAssets(){return Promise.all(ASSETS.map(loadAsset));}
ASSETS.forEach(a=>{
  const t=document.getElementById(a.toggle);
  if(t)t.addEventListener('change',e=>{
    if(a.type==='fwd'){const lg=document.getElementById('fwdLegend');if(lg)lg.style.display=e.target.checked?'block':'none';}
    if(e.target.checked&&!map.getLayer(a.layer)){loadAsset(a);return;}
    [a.layer,a.layer+'-pt',a.layer+'-icon'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',e.target.checked?'visible':'none');});
  });
});
renderFwdLegend();
function applyNetColor(){
  const a=document.getElementById('netColorBy').value;
  if(map.getLayer('roadnet'))map.setPaintProperty('roadnet','line-color',a==='__class__'?netColor():netColorByExpr(a));
  renderNetLegend(a==='__class__'?null:a);
}
document.getElementById('netColorBy').addEventListener('change',applyNetColor);
