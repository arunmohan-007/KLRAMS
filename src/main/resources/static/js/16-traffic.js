/* ============================================================
   KLRAMS viewer · 16-traffic.js
   Traffic stations: load from the Data Console store, place by chainage, popup with ADT / PHT / direction.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
/* ===== Traffic stations (loaded from the Data Console via localStorage) ===== */
let TRAFFIC_STN={type:'FeatureCollection',features:[]};let TRAFFIC_COUNTS={};let TRAFFIC_LOADED=false;
let trfPopSeq=0; /* unique id per grouped-station popup, so its Combined/This-carriageway panes never collide with another popup's DOM ids */
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
function trfEnsureRoads(cb){ensureFullRoadsGeojson().then(cb,cb);}
/* Place every station on its road, and prefer the SERVER to do it.
   /api/traffic/stations/geojson runs the same chainage -> point linear reference
   trfChainagePoint() does, but in PostGIS (ST_LineInterpolatePoint over the standard
   reference length: Rd_End_cha - Rd_Str_cha, then Measrd_Len, then geodesic length —
   CLAUDE.md), so it returns 8 KB of finished points. Doing it in the browser instead
   costs the ENTIRE 4 MB road network first, purely to read geometry we then throw
   away: that one download is what undoes the road-network tile migration, and it is
   the reason this is worth changing for a dataset of only ~33 stations.

   The browser path is kept as a fallback, not deleted. trfFetchStore() can answer
   out of localStorage when the API has nothing, and those records may have no rows
   in traffic_stations at all — the server would place none of them. Anything that
   yields zero placed stations therefore falls back to placing them here, so an
   offline/legacy store keeps working exactly as before. */
function trfPlaceStations(recs,done){
  fetch('/api/traffic/stations/geojson',{credentials:'same-origin'})
    .then(r=>r.ok?r.json():null)
    .then(gj=>{
      const fs=(gj&&gj.features)||[];
      if(!fs.length)throw new Error('server placed none');
      fs.forEach(f=>{if(f.properties)f.properties.placed='chainage';});
      done(fs,Math.max(0,recs.length-fs.length));
    })
    .catch(()=>{
      trfEnsureRoads(function(){
        let nSkip=0;const feats=[];
        recs.forEach(rec=>{
          const ch=(rec.ch==null?NaN:+rec.ch),lat=(rec.lat==null?NaN:+rec.lat),lng=(rec.lng==null?NaN:+rec.lng);
          const coord=(rec.section&&!isNaN(ch))?trfChainagePoint(rec.section,ch):null;
          if(!coord){nSkip++;return;}
          feats.push({type:'Feature',geometry:{type:'Point',coordinates:coord},properties:{name:rec.name,road:rec.road||'',section:rec.section||'',ch:(rec.ch==null?'':rec.ch),xsp:rec.xsp||'',lat:isNaN(lat)?null:lat,lng:isNaN(lng)?null:lng,placed:'chainage'}});
        });
        done(feats,nSkip);
      });
    });
}
function trfEnsureLayer(){if(!map.getSource('trafficstn'))map.addSource('trafficstn',{type:'geojson',data:TRAFFIC_STN});else map.getSource('trafficstn').setData(TRAFFIC_STN);if(!map.getLayer('trafficstn-lyr')){map.addLayer({id:'trafficstn-lyr',type:'circle',source:'trafficstn',paint:{'circle-radius':['interpolate',['linear'],['zoom'],8,4,13,7,16,10],'circle-color':'#1565c0','circle-stroke-color':'#ffffff','circle-stroke-width':2,'circle-opacity':0.9}});map.on('click','trafficstn-lyr',e=>{if(e.features.length)trafficPopup(e.lngLat,e.features[0].properties);});map.on('mouseenter','trafficstn-lyr',()=>map.getCanvas().style.cursor='pointer');map.on('mouseleave','trafficstn-lyr',()=>map.getCanvas().style.cursor='');}const tg=document.getElementById('showTraffic');map.setLayoutProperty('trafficstn-lyr','visibility',(tg&&tg.checked)?'visible':'none');}
function trfFit(){const fs=TRAFFIC_STN.features;if(!fs.length)return;let a=180,b=90,c=-180,d=-90;fs.forEach(f=>{const x=f.geometry.coordinates[0],y=f.geometry.coordinates[1];a=Math.min(a,x);c=Math.max(c,x);b=Math.min(b,y);d=Math.max(d,y);});try{map.fitBounds([[a,b],[c,d]],{padding:60,maxZoom:13});}catch(e){}}
function trfFetchStore(done){fetch('/api/traffic/store').then(r=>r.ok?r.json():null).then(st=>{if(st&&((st.stations&&st.stations.length)||(st.counts&&Object.keys(st.counts).length)))return done(st);done(trfGetStore());}).catch(()=>done(trfGetStore()));}
function loadTraffic(cb){if(TRAFFIC_LOADED){if(cb)cb();return;}setTrfStatus('Loading traffic data…');trfFetchStore(function(st){const recs=st.stations||[];
  /* build 161 — normalise every stored count entry (handles double-encoded strings
     and other legacy shapes) and count how many are actually usable. */
  TRAFFIC_COUNTS={};let _cOK=0,_cBad=0;
  const _raw=st.counts||{};
  Object.keys(_raw).forEach(function(k){const o=trfCountObj(_raw[k]);if(o&&o.total!=null){TRAFFIC_COUNTS[k]=o;_cOK++;}else if(o){TRAFFIC_COUNTS[k]=o;_cBad++;}else _cBad++;});
  window.__trfCounts={ok:_cOK,bad:_cBad};if(!recs.length){setTrfStatus('No traffic data — import it in the Data Console.');return;}setTrfStatus('Placing stations by chainage…');trfPlaceStations(recs,function(feats,nSkip){TRAFFIC_STN={type:'FeatureCollection',features:feats};TRAFFIC_LOADED=true;window.__trfPlaced={ch:feats.length,skip:nSkip};trfEnsureLayer();trfFit();setTrfStatus(feats.length?('<b>'+feats.length+'</b> stations'+(_cOK?(' · counts for <b>'+_cOK+'</b>'):' · no counts')+(_cBad?(' · <b style="color:#e8590c">'+_cBad+' unreadable — re-import the counts CSV</b>'):'')+(nSkip?(' · <b style="color:#e8590c">'+nSkip+' not placed — section label not found on any road</b>'):'')+'.'):'No traffic data — import it in the Data Console.');if(typeof updateNetScopeCard==='function')updateNetScopeCard();if(cb)cb();});});}

/* Build 165 — sum counts across a traffic-station group (the two carriageways
   of one physical dual-road station, TVM_STN_021A / …B): total and by-hour
   add, days takes the longer survey, class/direction totals add, and the
   date range widens to cover both. Peak hour is then recomputed from the
   merged by-hour profile, exactly as TrafficDashboardController does in Java
   for the dashboard figures — so a popup's "combined" numbers reconcile with
   the dashboard's for the same group. Accepts already-normalised count
   objects (trfCountObj output); a name lookup is the caller's job so this
   works equally against TRAFFIC_COUNTS (popup) and a report's own counts map. */
function trfMergeCounts(countList){
  const objs=(countList||[]).filter(Boolean);
  if(!objs.length)return null;
  let total=0,days=0;const byHour=new Array(24).fill(0);const byClass={};const byDir={};
  let dateMin=null,dateMax=null;
  objs.forEach(c=>{
    total+=(+c.total||0);
    days=Math.max(days,+c.days||1);
    (c.byHour||[]).forEach((v,i)=>{if(i<24)byHour[i]+=(+v||0);});
    Object.keys(c.byClass||{}).forEach(k=>{byClass[k]=(byClass[k]||0)+(+c.byClass[k]||0);});
    Object.keys(c.byDir||{}).forEach(k=>{byDir[k]=(byDir[k]||0)+(+c.byDir[k]||0);});
    if(c.dateMin&&(!dateMin||trfDateBefore(c.dateMin,dateMin)))dateMin=c.dateMin;
    if(c.dateMax&&(!dateMax||trfDateBefore(dateMax,c.dateMax)))dateMax=c.dateMax;
  });
  const d=Math.max(1,days);
  let ph=0,phh=-1;for(let i=0;i<24;i++){const a=byHour[i]/d;if(a>ph){ph=a;phh=i;}}
  const peakBoth=phh>=0?{v:Math.round(ph),t:trfPad(phh)+':00–'+trfPad((phh+1)%24)+':00'}:null;
  return {total:total,days:d,byHour:byHour,byClass:byClass,byDir:byDir,dateMin:dateMin,dateMax:dateMax,peak:{both:peakBoth}};
}
/* Is display date a earlier than b? Mirrors TrafficDashboardController.before()/
   dayKey() in Java — dd-mmm-yyyy for new imports, dd/mm/yyyy for counts stored
   before that standard; unparseable values sort last. */
function trfDateBefore(a,b){
  const MON=['jan','feb','mar','apr','may','jun','jul','aug','sep','oct','nov','dec'];
  function key(s){
    if(!s)return Infinity;
    const v=String(s).trim();
    let m=/^(\d{1,2})-([a-z]{3})-(\d{4})$/i.exec(v);
    if(m){const mo=MON.indexOf(m[2].toLowerCase());if(mo>=0)return (+m[3])*10000+(mo+1)*100+(+m[1]);}
    m=/^(\d{1,2})[\/\-.](\d{1,2})[\/\-.](\d{4})$/.exec(v);
    if(m)return (+m[3])*10000+(+m[2])*100+(+m[1]);
    return Infinity;
  }
  return key(a)<key(b);
}

/* Switches a grouped traffic popup between its "Combined" and "This
   carriageway" panes — the two panes are built once (trafficPopupHTML) and
   just have their display toggled, so switching needs no data refetch. */
function trfSwitchBlk(uid,which,btn){
  const c=document.getElementById(uid+'-c'),s=document.getElementById(uid+'-s');
  if(c)c.style.display=(which==='c')?'':'none';
  if(s)s.style.display=(which==='s')?'':'none';
  if(btn&&btn.parentElement)Array.prototype.forEach.call(btn.parentElement.children,el=>el.classList.toggle('on',el===btn));
}
/* Build 164 — professional station popup built on the shared .klpop card:
   a title + station meta, a 3-up stat row (ADT / peak / survey days), then
   survey summary, direction split and vehicle-class breakdown — every total
   annotated with the survey period and a per-day figure, plus a full
   class list on demand. Build 165 — a grouped station (dual-road A/B pair)
   gets a Combined / This-carriageway tab switch above these blocks, so the
   popup matches the dashboard/report total while still showing what this
   specific carriageway carries, without doubling the card's length. */
function trfCountBlockHTML(c){
  const days=c.days||1;
  let ph=0,phh=-1;const bh=c.byHour||[];for(let i=0;i<24;i++){const a=(+bh[i]||0)/days;if(a>ph){ph=a;phh=i;}}
  const pht=Math.round(ph);const tim=(phh>=0)?(trfPad(phh)+':00–'+trfPad((phh+1)%24)+':00'):'–';
  const peakBoth=(c.peak&&c.peak.both)?c.peak.both:{v:pht,t:tim,d:'-'};
  const adt=Math.round((c.total||0)/days);
  const range=c.dateMin?(c.dateMin+((c.dateMax&&c.dateMax!==c.dateMin)?(' – '+c.dateMax):'')):'';
  const dayNote='over '+days+' day'+(days>1?'s':'');
  /* headline stat cards */
  let h='<div class="kp-block"><div class="kp-stats">'
    +'<div class="kp-stat"><div class="kp-sv">'+trfN(adt)+'</div><div class="kp-sl">Avg daily · ADT</div></div>'
    +'<div class="kp-stat"><div class="kp-sv">'+trfN(peakBoth.v)+'</div><div class="kp-sl">Peak / hour</div></div>'
    +'<div class="kp-stat"><div class="kp-sv">'+days+'</div><div class="kp-sl">Survey days</div></div>'
    +'</div></div>';
  /* survey summary */
  h+='<div class="kp-block"><div class="kp-eyebrow">Survey summary</div><div class="kp-attrs">';
  if(range)h+='<div class="kp-attr"><span class="kp-k">Dates</span><span class="kp-v">'+escH(range)+'</span></div>';
  h+='<div class="kp-attr"><span class="kp-k">'+days+'-day total</span><span class="kp-v">'+trfN(c.total)+' <span class="kp-u">veh</span></span></div>';
  h+='<div class="kp-attr"><span class="kp-k">Peak hour (both)</span><span class="kp-v">'+trfN(peakBoth.v)+' <span class="kp-u">'+escH(peakBoth.t)+(peakBoth.d&&peakBoth.d!=='-'?(' · '+escH(peakBoth.d)):'')+'</span></span></div>';
  if(c.peak&&c.peak.dir)Object.keys(c.peak.dir).sort().forEach(dn=>{const pp=c.peak.dir[dn];h+='<div class="kp-attr"><span class="kp-k">Peak · '+escH(dn)+'</span><span class="kp-v">'+trfN(pp.v)+' <span class="kp-u">'+escH(pp.t)+'</span></span></div>';});
  h+='</div></div>';
  /* direction split */
  const dirs=Object.keys(c.byDir||{});
  if(dirs.length){
    h+='<div class="kp-block"><div class="kp-eyebrow">Direction-wise · '+dayNote+'</div><div class="kp-attrs">';
    dirs.sort().forEach(dir=>{h+='<div class="kp-attr"><span class="kp-k">'+escH(dir)+'</span><span class="kp-v">'+trfN(c.byDir[dir])+' <span class="kp-sub">'+trfN(Math.round((c.byDir[dir]||0)/days))+'/day</span></span></div>';});
    h+='</div></div>';
  }
  /* vehicle classes — top 5, with the full list on demand (avg count per day, not the raw survey-period total) */
  const allCls=Object.keys(c.byClass||{}).map(k=>[k,c.byClass[k]]).sort((a,b)=>b[1]-a[1]);
  if(allCls.length){
    const clsRow=(nm,val)=>'<div class="kp-attr"><span class="kp-k">'+escH(nm)+'</span><span class="kp-v">'+trfN(Math.round((val||0)/days))+'</span></div>';
    h+='<div class="kp-block"><div class="kp-eyebrow">Top vehicle classes · avg count per day</div><div class="kp-attrs">';
    allCls.slice(0,5).forEach(e=>{h+=clsRow(e[0],e[1]);});
    h+='</div>';
    if(allCls.length>5){h+='<details class="trf-allcls"><summary>View all '+allCls.length+' vehicle classes</summary><div class="kp-attrs" style="margin-top:7px">';allCls.forEach(e=>{h+=clsRow(e[0],e[1]);});h+='</div></details>';}
    h+='</div>';
  }
  return h;
}
function trafficPopupHTML(props){
  const name=props.name||'';
  const c=trfCountObj(TRAFFIC_COUNTS[name]);
  const road=props.road||name||'—';
  const secBits=[];
  if(props.section)secBits.push(escH(props.section));
  if(props.ch!==''&&props.ch!=null)secBits.push('Ch '+escH(props.ch)+' m');
  if(props.xsp)secBits.push(escH(props.xsp));
  const members=(typeof CalcRules!=='undefined'&&CalcRules.stationGroupMembers)?CalcRules.stationGroupMembers(name):[name];
  const grouped=members.length>1;
  const combined=grouped?trfMergeCounts(members.map(m=>trfCountObj(TRAFFIC_COUNTS[m]))):null;
  let h='<div class="klpop trf-klpop">';
  h+='<div class="kp-head"><div class="kp-name">'+escH(road)+'</div><div class="kp-meta"><span class="kp-chip">Traffic</span><span class="kp-sec">'+escH(name)+(secBits.length?(' · '+secBits.join(' · ')):'')+'</span></div>';
  if(props.lat!=null&&props.lng!=null&&!isNaN(props.lat)&&!isNaN(props.lng))h+='<div class="kp-locrow" style="margin-top:8px"><span class="kp-sec">Lat '+(+props.lat).toFixed(6)+', Lng '+(+props.lng).toFixed(6)+'</span></div>';
  h+='</div>';
  if(!c&&!combined){h+='<div class="kp-block"><div class="kp-none">No count data for this station — import the count CSV in the Data Console.</div></div></div>';return h;}
  if(grouped){
    const uid='trfp'+(++trfPopSeq);
    h+='<div class="kp-seg">'
      +'<button type="button" class="kp-segbtn on" onclick="trfSwitchBlk(\''+uid+'\',\'c\',this)">Combined</button>'
      +'<button type="button" class="kp-segbtn" onclick="trfSwitchBlk(\''+uid+'\',\'s\',this)">This carriageway</button>'
      +'</div>';
    h+='<div id="'+uid+'-c">';
    h+='<div class="kp-block" style="padding-bottom:0;padding-top:10px"><div class="kp-eyebrow">Combined · both carriageways ('+members.map(escH).join(' + ')+')</div></div>';
    h+=combined?trfCountBlockHTML(combined):'<div class="kp-block"><div class="kp-none">No count data for either carriageway.</div></div>';
    h+='</div>';
    h+='<div id="'+uid+'-s" style="display:none">';
    h+='<div class="kp-block" style="padding-bottom:0;padding-top:10px"><div class="kp-eyebrow">This carriageway · '+escH(name)+'</div></div>';
    h+=c?trfCountBlockHTML(c):'<div class="kp-block"><div class="kp-none">No count data for this carriageway.</div></div>';
    h+='</div>';
  } else {
    h+=trfCountBlockHTML(c);
  }
  h+='</div>';
  return h;
}
/* Fall back to a plain popup if klPopup (06-assets.js) is missing — protects
   against a stale-cache mix where this file updated but 06 did not. */
function trafficPopup(lngLat,props){
  const h=trafficPopupHTML(props);
  if(typeof klPopup==='function')klPopup(lngLat,h);
  else new maplibregl.Popup({maxWidth:'290px'}).setLngLat(lngLat).setHTML(h).addTo(map);
}
(function initTraffic(){const tg=document.getElementById('showTraffic');if(!tg)return;function showLayer(){if(map.getLayer('trafficstn-lyr'))map.setLayoutProperty('trafficstn-lyr','visibility','visible');}tg.addEventListener('change',function(e){if(e.target.checked){TRAFFIC_LOADED=false;loadTraffic(showLayer);}else if(map.getLayer('trafficstn-lyr'))map.setLayoutProperty('trafficstn-lyr','visibility','none');});})();
