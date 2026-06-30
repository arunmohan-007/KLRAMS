/* ============================================================
   KLRAMS viewer · 22-road-merged.js
   "Full Road Network (by Road Name)" — a second road-network
   layer. It is IMPORTED from a local GeoJSON or zipped shapefile,
   and (since build 118) PERSISTED to the database so it survives
   page refresh and server restart.

   On import: browser parses the file -> POST to
   /api/full-network/upload -> upserted into full_road_network
   (same road number/name = update, new = insert).
   On startup: GET /api/full-network/geojson loads the saved data.

   It represents the same network merged per road name (full-road
   length etc.) and carries no Section Label. Only ONE road network
   is shown at a time, so this layer is mutually exclusive with the
   section-based "Road network" layer.
   ============================================================ */

let ROADS2_GJ=null, r2SelId=null, r2BlinkTimer=null;

/* build 120 — is "Video on click" currently on? When it is, the Full Road
   Network must stay completely passive so the survey-road video pick wins. */
function vmOn(){return !!(((document.getElementById('videoMode')||{}).checked)||((document.getElementById('videoMode2')||{}).checked));}

/* ---- field helpers ---- */
function r2RoadName(p){
  p=p||{};
  const direct=['Road_Name','ROAD_NAME','RoadName','road_name','Name','NAME','name'];
  for(const k of direct){if(p[k]!=null&&p[k]!=='')return String(p[k]);}
  for(const k in p){if(/name/i.test(k)&&p[k]!=null&&p[k]!=='')return String(p[k]);}
  return '';
}
function r2Field(p,keys){for(const k of keys){if(p&&p[k]!=null&&p[k]!=='')return String(p[k]);}return '';}
function r2Length(f){
  const p=f.properties||{};
  const keys=['Road_Leng','ROAD_LENG','Length_m','Length','length','LENGTH','Shape_Leng','SHAPE_Leng','Measrd_Len','len'];
  for(const k of keys){const v=p[k];if(v!=null&&v!==''&&!isNaN(Number(String(v).replace(/,/g,''))))return Math.round(Number(String(v).replace(/,/g,'')));}
  try{ if(f.geometry&&typeof turf!=='undefined')return Math.round(turf.length(f,{units:'kilometers'})*1000); }catch(e){}
  return null;
}

/* ---- layer ---- */
function ensureRoads2Layer(){
  if(!ROADS2_GJ||typeof map==='undefined')return;
  if(map.getSource('roadnet2')){map.getSource('roadnet2').setData(ROADS2_GJ);return;}
  map.addSource('roadnet2',{type:'geojson',data:ROADS2_GJ,generateId:true});
  map.addLayer({id:'roadnet2-casing',type:'line',source:'roadnet2',layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#0b1322','line-width':['interpolate',['linear'],['zoom'],8,3.6,16,9.5]}});
  map.addLayer({id:'roadnet2',type:'line',source:'roadnet2',layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#3a4ad6','line-width':['interpolate',['linear'],['zoom'],8,2,16,6]}});
  map.addLayer({id:'roadnet2-sel',type:'line',source:'roadnet2',filter:['==',['id'],-1],layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#ff5d2e','line-width':['interpolate',['linear'],['zoom'],8,5,16,12],'line-opacity':1}});
  map.on('click','roadnet2',e=>{ if(vmOn())return; /* build 120 — never steal a video-mode click */ if(e.features&&e.features.length){const f=e.features[0];roads2PopupShow(f);r2Select(f.id);} });
  map.on('mouseenter','roadnet2',()=>map.getCanvas().style.cursor='pointer');
  map.on('mouseleave','roadnet2',()=>map.getCanvas().style.cursor='');
}
function r2StartBlink(){ r2StopBlink(); let on=true; r2BlinkTimer=setInterval(()=>{on=!on;if(map.getLayer('roadnet2-sel'))map.setPaintProperty('roadnet2-sel','line-opacity',on?1:0.12);},360); }
function r2StopBlink(){ if(r2BlinkTimer){clearInterval(r2BlinkTimer);r2BlinkTimer=null;} if(map.getLayer('roadnet2-sel'))map.setPaintProperty('roadnet2-sel','line-opacity',1); }
function r2Select(id){ r2SelId=id; if(map.getLayer('roadnet2-sel')){map.setFilter('roadnet2-sel',['==',['id'],id]);map.setLayoutProperty('roadnet2-sel','visibility','visible');} r2StartBlink(); }
function r2ClearSel(){ r2StopBlink(); r2SelId=null; if(map.getLayer('roadnet2-sel')){map.setFilter('roadnet2-sel',['==',['id'],-1]);map.setLayoutProperty('roadnet2-sel','visibility','none');} }
function r2EnsureCard(){
  let c=document.getElementById('roadInfoCard');
  if(!c){c=document.createElement('div');c.id='roadInfoCard';c.className='ri-card';c.style.display='none';const mp=document.getElementById('map');(mp||document.body).appendChild(c);}
  return c;
}
function r2CloseCard(){ const c=document.getElementById('roadInfoCard'); if(c)c.style.display='none'; r2ClearSel(); }
function roads2PopupShow(f){
  const p=f.properties||{};
  const v=x=>(x==null||x==='')?'\u2014':escH(x);
  const name=r2RoadName(p);
  const rno=r2Field(p,['Road_Num','Road_No','ROAD_NO','RoadNo','road_no','Road_Number']);
  const clsRaw=r2Field(p,['Road_Class','ROAD_CLASS','RoadClass','Class','road_class']);
  const cls=clsRaw?((typeof dec==='function'&&dec('Road_Class',clsRaw))||clsRaw):'';
  const sCh=r2Field(p,['Rd_Str_cha','Rd_Str_Cha','Road_Start','Start_Chainage','Start_Ch','RdStrCha','start_chainage']);
  const eCh=r2Field(p,['Rd_End_cha','Rd_End_Cha','Road_End','End_Chainage','End_Ch','RdEndCha','end_chainage']);
  const sLoc=r2Field(p,['Rd_Str_Loc','Start_Loc','Start_Location','Strt_Loc','start_location']);
  const eLoc=r2Field(p,['Rd_End_Loc','End_Loc','End_Location','end_location']);
  const len=r2Length(f);
  const lenBig=(len!=null)?(len>=1000?(len/1000).toFixed(2):String(len)):'\u2014';
  const lenU=(len!=null)?(len>=1000?'km':'m'):'';
  const rows=[['Road Number',rno],['Road Class',cls],['Road Start Chainage',sCh],['Road End Chainage',eCh],['Start Location',sLoc],['End Location',eLoc]];
  const rowsHtml=rows.map(r=>'<div class="ri-row"><span class="ri-k">'+r[0]+'</span><span class="ri-v">'+v(r[1])+'</span></div>').join('');
  const html='<div class="ri-head"><div class="ri-ic"><svg viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"#fff\" stroke-width=\"1.9\" stroke-linecap=\"round\" stroke-linejoin=\"round\"><path d=\"M5 20 9 4M19 20 15 4M12 7v2M12 12v2M12 17v1\"/></svg></div><div class="ri-title"><div class="ri-eyebrow">FULL ROAD</div><div class="ri-name">'+v(name)+'</div></div><button class="ri-x" onclick="r2CloseCard()" aria-label="Close">&times;</button></div>'
    +'<div class="ri-body"><div class="ri-len"><span class="b">'+lenBig+'</span><span class="u">'+lenU+'</span><span class="cap">Total length</span></div>'+rowsHtml+'</div>';
  const c=r2EnsureCard(); c.innerHTML=html; c.style.display='block';
}
function setRoads2Visible(on){
  ensureRoads2Layer();
  const v=on?'visible':'none';
  ['roadnet2','roadnet2-casing'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',v);});
  if(!on)r2CloseCard();
}

/* ---- mutual exclusivity (only one road network shown) ---- */
function setSectionHit(on){const v=on?'visible':'none';if(map.getLayer('roadnet-hit'))map.setLayoutProperty('roadnet-hit','visibility',v);}
function r2CloseAllPopups(){ try{document.querySelectorAll('.maplibregl-popup').forEach(el=>el.remove());}catch(e){} r2CloseCard(); }
function hideSectionRoadNet(){
  const t=document.getElementById('showRoads'); if(t)t.checked=false;
  /* build 120 — hide only the STYLED section lines; keep the invisible
     'roadnet-hit' layer clickable so Video-on-click still picks survey roads
     (and plays video) even while the Full Road Network layer is shown. */
  ['roadnet','roadnet-casing'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility','none');});
  if(map.getLayer('roadnet-hit'))map.setLayoutProperty('roadnet-hit','visibility','visible');
}
function hideFullRoadNet(){
  const t=document.getElementById('showRoads2'); if(t)t.checked=false;
  setRoads2Visible(false);
}

/* ---- import (local GeoJSON or zipped shapefile) -> render + PERSIST ---- */
function importRoadLayer(){const fi=document.getElementById('roads2File');if(fi)fi.click();}
function r2SetStatus(txt){const s=document.getElementById('roads2Status');if(s)s.textContent=txt;}
function r2Apply(gj){
  if(!gj||!gj.features||!gj.features.length){r2SetStatus('No features found in file.');return;}
  ROADS2_GJ=gj; ensureRoads2Layer();
  const t2=document.getElementById('showRoads2'); if(t2)t2.checked=true;
  r2CloseAllPopups(); hideSectionRoadNet(); setRoads2Visible(true);
  try{const b=new maplibregl.LngLatBounds();gj.features.forEach(f=>{const g=f.geometry;if(!g)return;const w=a=>{if(typeof a[0]==='number')b.extend(a);else a.forEach(w);};if(g.coordinates)w(g.coordinates);});if(!b.isEmpty())map.fitBounds(b,{padding:50});}catch(e){}
  r2SetStatus(gj.features.length+' roads imported \u00b7 saving\u2026');
  r2Persist(gj);
}
function r2OnFile(input){
  const file=input.files&&input.files[0]; if(!file)return;
  const nm=file.name.toLowerCase();
  r2SetStatus('Reading '+file.name+'\u2026');
  if(nm.endsWith('.zip')){
    if(typeof shp==='undefined'){r2SetStatus('Shapefile support unavailable \u2014 please import a GeoJSON file.');input.value='';return;}
    file.arrayBuffer().then(buf=>shp(buf)).then(gj=>{
      if(Array.isArray(gj)){const feats=[];gj.forEach(g=>{((g&&g.features)||[]).forEach(ft=>feats.push(ft));});gj={type:'FeatureCollection',features:feats};}
      r2Apply(gj);input.value='';
    }).catch(e=>{r2SetStatus('Could not read shapefile: '+((e&&e.message)||e));input.value='';});
  } else {
    file.text().then(t=>{let gj;try{gj=JSON.parse(t);}catch(e){r2SetStatus('Not a valid GeoJSON file.');input.value='';return;}r2Apply(gj);input.value='';})
      .catch(()=>{r2SetStatus('Could not read file.');input.value='';});
  }
}

/* ---- persist to DB (survives refresh + restart) ---- */
function r2Persist(gj){
  return fetch('/api/full-network/upload?mode=merge',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(gj)})
    .then(r=>r.json()).then(j=>{
      if(j&&j.status==='ok'){
        r2SetStatus('\u2713 Saved '+(j.total!=null?j.total:gj.features.length)+' roads (added '+(j.inserted||0)+', updated '+(j.updated||0)+')');
      } else {
        r2SetStatus('Shown, but server save failed: '+((j&&j.message)||'unknown'));
      }
    }).catch(e=>{ r2SetStatus('Shown, but server save failed: '+((e&&e.message)||e)); });
}

/* ---- load persisted network from DB on startup ---- */
function r2WhenMapReady(cb){
  if(typeof map!=='undefined'&&map&&map.isStyleLoaded&&map.isStyleLoaded()){cb();return;}
  if(typeof map!=='undefined'&&map&&map.on){map.on('load',cb);return;}
  setTimeout(()=>r2WhenMapReady(cb),300);
}
function loadRoads2FromServer(){
  return fetch('/api/full-network/geojson').then(r=>r.json()).then(gj=>{
    if(!gj||!gj.features||!gj.features.length)return;
    ROADS2_GJ=gj;
    r2WhenMapReady(()=>ensureRoads2Layer());   // layer created hidden; toggle shows it
    r2SetStatus(gj.features.length+' roads (saved)');
  }).catch(()=>{});
}

/* ---- wiring ---- */
(function(){
  function wire(){
    const t2=document.getElementById('showRoads2');
    if(t2)t2.addEventListener('change',e=>{
      if(e.target.checked){ if(!ROADS2_GJ){r2SetStatus('Import a layer first.');e.target.checked=false;return;} r2CloseAllPopups(); hideSectionRoadNet(); setRoads2Visible(true); }
      else { setRoads2Visible(false); setSectionHit(true); }
    });
    const t1=document.getElementById('showRoads');
    if(t1)t1.addEventListener('change',e=>{ if(e.target.checked){ r2CloseAllPopups(); hideFullRoadNet(); setSectionHit(true); } });
    const fi=document.getElementById('roads2File');
    if(fi)fi.addEventListener('change',function(){r2OnFile(this);});
    loadRoads2FromServer();   // restore any previously-saved full network
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',wire);else wire();
})();
