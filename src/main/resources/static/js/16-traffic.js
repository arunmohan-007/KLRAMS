/* ============================================================
   KLRAMS viewer · 16-traffic.js
   Traffic stations: load from the Data Console store, place by chainage, popup with ADT / PHT / direction.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
/* ===== Traffic stations (loaded from the Data Console via localStorage) ===== */
let TRAFFIC_STN={type:'FeatureCollection',features:[]};let TRAFFIC_COUNTS={};let TRAFFIC_LOADED=false;
const TRF_KEY='klrams_traffic_v1';
function trfGetStore(){try{return JSON.parse(localStorage.getItem(TRF_KEY))||{};}catch(e){return {};}}
function trfN(n){const x=Number(n);if(isNaN(x))return '–';try{return x.toLocaleString('en-IN');}catch(e){return String(x);}}
/* build 161 — stored counts can arrive malformed from older imports:
   a JSON string (double-encoded) or a bare number. Normalise to the
   {total,byDir,byClass,byHour,days,...} object the popup expects. */
function trfCountObj(c){
  if(c==null)return null;
  if(typeof c==='string'){try{c=JSON.parse(c);}catch(e){const n=+String(c).replace(/[, ]/g,'');return isNaN(n)?null:{total:n};}}
  if(typeof c==='number')return {total:c};
  if(typeof c==='object')return c;
  return null;
}
function setTrfStatus(m){const el=document.getElementById('trfStatus');if(el)el.innerHTML=m;}
function trfPad(n){return (n<10?'0':'')+n;}
function trfChainagePoint(section,ch){try{const rd=ROADS[section];if(!rd||!rd.geometry)return null;const len=parseFloat(rd.properties.len)||parseFloat(rd.properties.Measrd_Len)||0;if(!(len>0))return null;const line=lineOf(rd);const gl=turf.length(line,{units:'kilometers'});let f=ch/len;if(isNaN(f))return null;f=Math.max(0,Math.min(f,1));const pt=turf.along(line,f*gl,{units:'kilometers'});const c=pt&&pt.geometry&&pt.geometry.coordinates;if(!c||isNaN(c[0])||isNaN(c[1]))return null;return c;}catch(e){return null;}}
function trfEnsureRoads(cb){if(ROADS&&Object.keys(ROADS).length){cb();return;}fetch('/api/roads/geojson').then(r=>r.json()).then(gj=>{((gj&&gj.features)||[]).forEach(f=>{if(f&&f.properties)ROADS[f.properties.road]=f;});cb();}).catch(()=>cb());}
function trfEnsureLayer(){if(!map.getSource('trafficstn'))map.addSource('trafficstn',{type:'geojson',data:TRAFFIC_STN});else map.getSource('trafficstn').setData(TRAFFIC_STN);if(!map.getLayer('trafficstn-lyr')){map.addLayer({id:'trafficstn-lyr',type:'circle',source:'trafficstn',paint:{'circle-radius':['interpolate',['linear'],['zoom'],8,4,13,7,16,10],'circle-color':'#1565c0','circle-stroke-color':'#ffffff','circle-stroke-width':2,'circle-opacity':0.9}});map.on('click','trafficstn-lyr',e=>{if(e.features.length)trafficPopup(e.lngLat,e.features[0].properties);});map.on('mouseenter','trafficstn-lyr',()=>map.getCanvas().style.cursor='pointer');map.on('mouseleave','trafficstn-lyr',()=>map.getCanvas().style.cursor='');}const tg=document.getElementById('showTraffic');map.setLayoutProperty('trafficstn-lyr','visibility',(tg&&tg.checked)?'visible':'none');}
function trfFit(){const fs=TRAFFIC_STN.features;if(!fs.length)return;let a=180,b=90,c=-180,d=-90;fs.forEach(f=>{const x=f.geometry.coordinates[0],y=f.geometry.coordinates[1];a=Math.min(a,x);c=Math.max(c,x);b=Math.min(b,y);d=Math.max(d,y);});try{map.fitBounds([[a,b],[c,d]],{padding:60,maxZoom:13});}catch(e){}}
function trfFetchStore(done){fetch('/api/traffic/store').then(r=>r.ok?r.json():null).then(st=>{if(st&&((st.stations&&st.stations.length)||(st.counts&&Object.keys(st.counts).length)))return done(st);done(trfGetStore());}).catch(()=>done(trfGetStore()));}
function loadTraffic(cb){if(TRAFFIC_LOADED){if(cb)cb();return;}setTrfStatus('Loading traffic data\u2026');trfFetchStore(function(st){const recs=st.stations||[];
  /* build 161 \u2014 normalise every stored count entry (handles double-encoded strings
     and other legacy shapes) and count how many are actually usable. */
  TRAFFIC_COUNTS={};let _cOK=0,_cBad=0;
  const _raw=st.counts||{};
  Object.keys(_raw).forEach(function(k){const o=trfCountObj(_raw[k]);if(o&&o.total!=null){TRAFFIC_COUNTS[k]=o;_cOK++;}else if(o){TRAFFIC_COUNTS[k]=o;_cBad++;}else _cBad++;});
  window.__trfCounts={ok:_cOK,bad:_cBad};if(!recs.length){setTrfStatus('No traffic data — import it in the Data Console.');return;}setTrfStatus('Placing stations by chainage…');trfEnsureRoads(function(){let nCh=0,nLL=0;const feats=[];recs.forEach(rec=>{const ch=(rec.ch==null?NaN:+rec.ch),lat=(rec.lat==null?NaN:+rec.lat),lng=(rec.lng==null?NaN:+rec.lng);let coord=null,placed='';if(rec.section&&!isNaN(ch))coord=trfChainagePoint(rec.section,ch);if(coord){placed='chainage';nCh++;}else if(!isNaN(lat)&&!isNaN(lng)){coord=[lng,lat];placed='latlng';nLL++;}if(!coord)return;feats.push({type:'Feature',geometry:{type:'Point',coordinates:coord},properties:{name:rec.name,road:rec.road||'',section:rec.section||'',ch:(rec.ch==null?'':rec.ch),xsp:rec.xsp||'',lat:isNaN(lat)?null:lat,lng:isNaN(lng)?null:lng,placed:placed}});});TRAFFIC_STN={type:'FeatureCollection',features:feats};TRAFFIC_LOADED=true;window.__trfPlaced={ch:nCh,ll:nLL};trfEnsureLayer();trfFit();setTrfStatus(feats.length?('<b>'+feats.length+'</b> stations'+(nCh?(' · '+nCh+' on chainage'):'')+(_cOK?(' · counts for <b>'+_cOK+'</b>'):' · no counts')+(_cBad?(' · <b style="color:#e8590c">'+_cBad+' unreadable — re-import the counts CSV</b>'):'')+'.'):'No traffic data — import it in the Data Console.');if(typeof updateNetScopeCard==='function')updateNetScopeCard();if(cb)cb();});});}
function trafficPopupHTML(props){const name=props.name||'';const c=trfCountObj(TRAFFIC_COUNTS[name]);let h='<div class="pop"><div class="sec">Traffic station \u00b7 '+escH(name)+'</div><div style="font-size:14px;font-weight:700;color:#0e2038">'+escH(props.road||'\u2014')+'</div><div style="font-size:11px;color:#64718a;margin:2px 0 2px">'+escH(props.section||'')+((props.ch!==''&&props.ch!=null)?(' \u00b7 Ch '+escH(props.ch)+' m'):'')+(props.xsp?(' \u00b7 '+escH(props.xsp)):'')+'</div>';if(props.lat!=null&&props.lng!=null&&!isNaN(props.lat)&&!isNaN(props.lng))h+='<div style="font-size:11px;color:#64718a;margin-bottom:8px">Lat '+(+props.lat).toFixed(6)+', Lng '+(+props.lng).toFixed(6)+'</div>';if(c){const days=c.days||1;let ph=0,phh=-1;const bh=c.byHour||[];for(let i=0;i<24;i++){const a=(+bh[i]||0)/days;if(a>ph){ph=a;phh=i;}}const pht=Math.round(ph);const tim=(phh>=0)?(trfPad(phh)+':00\u2013'+trfPad((phh+1)%24)+':00'):'\u2013';const range=c.dateMin?(c.dateMin+((c.dateMax&&c.dateMax!==c.dateMin)?(' \u2013 '+c.dateMax):'')):'';h+='<table><tr><td class="k">Survey period</td><td class="v">'+days+' day'+(days>1?'s':'')+'</td></tr>';if(range)h+='<tr><td class="k">Dates</td><td class="v" style="font-weight:500;font-size:11px">'+escH(range)+'</td></tr>';h+='<tr><td class="k">'+days+'-day traffic</td><td class="v">'+trfN(c.total)+'</td></tr><tr><td class="k">Avg Daily Traffic (ADT)</td><td class="v">'+trfN(Math.round((c.total||0)/days))+'</td></tr>';
  /* build 162 — peak hour = max volume in any continuous 60-min window recorded
     during the survey, shown per direction and combined. Older imports without
     c.peak fall back to the previous clock-hour estimate. */
  const pkRow=(lbl,p)=>'<tr><td class="k">'+lbl+'</td><td class="v">'+trfN(p.v)+'<div style="font-weight:500;font-size:10px;color:#94a3b5">'+escH(p.t)+(p.d&&p.d!=='-'?(' · '+escH(p.d)):'')+'</div></td></tr>';
  if(c.peak&&c.peak.both){
    h+=pkRow('Peak hour (both directions)',c.peak.both);
    Object.keys(c.peak.dir||{}).sort().forEach(dn=>{h+=pkRow('Peak hour · '+escH(dn),c.peak.dir[dn]);});
  }else{
    h+='<tr><td class="k">Peak Hour Traffic (PHT)</td><td class="v">'+trfN(pht)+'</td></tr><tr><td class="k">Peak hour</td><td class="v">'+tim+'</td></tr>';
  }
  h+='</table>';h+='<div style="font-size:10px;text-transform:uppercase;letter-spacing:.5px;color:#94a3b5;margin:8px 0 3px">Direction-wise traffic</div><table>';Object.keys(c.byDir||{}).sort().forEach(dir=>{h+='<tr><td class="k">'+escH(dir)+'</td><td class="v">'+trfN(c.byDir[dir])+' <span style="color:#94a3b5;font-weight:500">('+trfN(Math.round((c.byDir[dir]||0)/days))+'/day)</span></td></tr>';});h+='</table>';const cls=Object.keys(c.byClass||{}).map(k=>[k,c.byClass[k]]).sort((a,b)=>b[1]-a[1]).slice(0,5);if(cls.length){h+='<div style="font-size:10px;text-transform:uppercase;letter-spacing:.5px;color:#94a3b5;margin:8px 0 3px">Top vehicle classes</div><table>';cls.forEach(e=>{h+='<tr><td class="k">'+escH(e[0])+'</td><td class="v">'+trfN(e[1])+'</td></tr>';});h+='</table>';}}else{h+='<div style="font-size:11px;color:#94a3b5">No count data for this station \u2014 import the count CSV in the Data Console.</div>';}return h+'</div>';}
function trafficPopup(lngLat,props){new maplibregl.Popup({maxWidth:'300px'}).setLngLat(lngLat).setHTML(trafficPopupHTML(props)).addTo(map);}
(function initTraffic(){const tg=document.getElementById('showTraffic');if(!tg)return;function showLayer(){if(map.getLayer('trafficstn-lyr'))map.setLayoutProperty('trafficstn-lyr','visibility','visible');}tg.addEventListener('change',function(e){if(e.target.checked){TRAFFIC_LOADED=false;loadTraffic(showLayer);}else if(map.getLayer('trafficstn-lyr'))map.setLayoutProperty('trafficstn-lyr','visibility','none');});})();

