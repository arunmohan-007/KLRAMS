/* ============================================================
   KLRAMS viewer · 22-road-merged.js
   "Full Road Network (by Road Name)" — a second road-network
   layer. It is IMPORTED from a local GeoJSON or zipped shapefile,
   and (since build 118) PERSISTED to the database so it survives
   page refresh and server restart.

   On import: browser parses the file -> POST to
   /api/full-network/upload -> upserted into full_road_network
   (same road number/name = update, new = insert).
   On startup: GET /api/full-network/count for the row count only;
   geometry is served as MVT when TILES_ON (default), or as
   /api/full-network/geojson when ?tiles=0.

   It represents the same network merged per road name (full-road
   length etc.) and carries no Section Label. Only ONE road network
   is shown at a time, so this layer is mutually exclusive with the
   section-based "Road network" layer.
   ============================================================ */

let ROADS2_GJ=null, r2SelId=null, r2BlinkTimer=null, _r2HandlersWired=false;
const ROAD2_TILE_LAYER='fullroads';
function r2TilesOn(){return typeof TILES_ON!=='undefined'&&TILES_ON;}

/* build 120 — is "Video on click" currently on? When it is, the Full Road
   Network must stay completely passive so the survey-road video pick wins. */
function vmOn(){return !!(((document.getElementById('videoMode')||{}).checked)||((document.getElementById('videoMode2')||{}).checked));}

/* ---- field helpers ---- */
function r2RoadName(p){
  p=p||{};
  const direct=['Road_Name','ROAD_NAME','RoadName','road_name','Name','NAME','name'];
  for(const k of direct){if(p[k]!=null&&p[k]!=='')return String(p[k]);}
  for(const k in p){if(/name/i.test(k)&&k!=='props_json'&&p[k]!=null&&p[k]!=='')return String(p[k]);}
  return '';
}
function r2Field(p,keys){for(const k of keys){if(p&&p[k]!=null&&p[k]!=='')return String(p[k]);}return '';}
function r2Length(f){
  const p=f.properties||{};
  const keys=['len','Road_Leng','ROAD_LENG','Length_m','Length','length','LENGTH','Shape_Leng','SHAPE_Leng','Measrd_Len'];
  for(const k of keys){const v=p[k];if(v!=null&&v!==''&&!isNaN(Number(String(v).replace(/,/g,''))))return Math.round(Number(String(v).replace(/,/g,'')));}
  /* Tile-clipped geometries must not be measured — len comes from the MVT. */
  if(r2TilesOn()&&map.getSource('roadnet2')&&map.getSource('roadnet2').type==='vector')return null;
  try{ if(f.geometry&&typeof turf!=='undefined')return Math.round(turf.length(f,{units:'kilometers'})*1000); }catch(e){}
  return null;
}
/* Merge shapefile attrs shipped as props_json (tile mode) into flat properties
   the popup already knows how to read — same idea as assetProps(). */
function r2Props(raw){
  const p={};
  if(!raw)return p;
  for(const k in raw){if(k!=='props_json')p[k]=raw[k];}
  if(raw.props_json){try{Object.assign(p,JSON.parse(raw.props_json));}catch(e){}}
  if(p.road_name!=null&&p.Road_Name==null)p.Road_Name=p.road_name;
  if(p.road_num!=null&&p.Road_Num==null)p.Road_Num=p.road_num;
  return p;
}

/* ---- layer ---- */
function r2TeardownLayer(){
  r2StopBlink();
  ['roadnet2-sel','roadnet2','roadnet2-casing'].forEach(function(id){try{if(map.getLayer(id))map.removeLayer(id);}catch(e){}});
  try{if(map.getSource('roadnet2'))map.removeSource('roadnet2');}catch(e){}
}
/* Handlers are registered once against the layer ids. After an upload we tear
   the source down and recreate it with the same ids; MapLibre keeps the
   listeners, so rebinding would double-fire the popup. */
function r2WireHandlers(){
  if(_r2HandlersWired||typeof map==='undefined')return;
  _r2HandlersWired=true;
  map.on('click','roadnet2',e=>{
    if(vmOn())return;
    if(!e.features||!e.features.length)return;
    const f=e.features[0];
    const feat=r2TilesOn()
      ?{type:'Feature',properties:r2Props(f.properties),geometry:f.geometry,id:f.id}
      :f;
    roads2PopupShow(feat);r2Select(f.id);
  });
  map.on('mouseenter','roadnet2',()=>map.getCanvas().style.cursor='pointer');
  map.on('mouseleave','roadnet2',()=>map.getCanvas().style.cursor='');
}

function ensureRoads2Layer(){
  if(typeof map==='undefined')return;
  if(r2TilesOn()){
    if(map.getSource('roadnet2')){
      /* Already a vector source — nothing to push. After an upload we tear down
         and recreate so MapLibre refetches tiles against the new rows. */
      if(map.getSource('roadnet2').type==='vector')return;
      r2TeardownLayer();
    }
    map.addSource('roadnet2',{type:'vector',promoteId:'id',
      tiles:[location.origin+'/api/full-network/tiles/{z}/{x}/{y}.mvt'],
      minzoom:0,maxzoom:16});
    const mk=function(id,extra){return Object.assign({id:id,type:'line',source:'roadnet2','source-layer':ROAD2_TILE_LAYER},extra);};
    map.addLayer(mk('roadnet2-casing',{layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#0b1322','line-width':['interpolate',['linear'],['zoom'],8,3.6,16,9.5]}}));
    map.addLayer(mk('roadnet2',{layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#3a4ad6','line-width':['interpolate',['linear'],['zoom'],8,2,16,6]}}));
    map.addLayer(mk('roadnet2-sel',{filter:['==',['id'],-1],layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#ff5d2e','line-width':['interpolate',['linear'],['zoom'],8,5,16,12],'line-opacity':1}}));
    r2WireHandlers();
    return;
  }
  if(!ROADS2_GJ)return;
  if(map.getSource('roadnet2')){
    if(map.getSource('roadnet2').type==='geojson'){map.getSource('roadnet2').setData(ROADS2_GJ);return;}
    r2TeardownLayer();
  }
  map.addSource('roadnet2',{type:'geojson',data:ROADS2_GJ,generateId:true});
  map.addLayer({id:'roadnet2-casing',type:'line',source:'roadnet2',layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#0b1322','line-width':['interpolate',['linear'],['zoom'],8,3.6,16,9.5]}});
  map.addLayer({id:'roadnet2',type:'line',source:'roadnet2',layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#3a4ad6','line-width':['interpolate',['linear'],['zoom'],8,2,16,6]}});
  map.addLayer({id:'roadnet2-sel',type:'line',source:'roadnet2',filter:['==',['id'],-1],layout:{'line-cap':'round','line-join':'round','visibility':'none'},paint:{'line-color':'#ff5d2e','line-width':['interpolate',['linear'],['zoom'],8,5,16,12],'line-opacity':1}});
  r2WireHandlers();
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
  const p=r2Props(f.properties||{});
  const v=x=>(x==null||x==='')?'\u2014':escH(x);
  const name=r2RoadName(p);
  const rno=r2Field(p,['Road_Num','Road_No','ROAD_NO','RoadNo','road_no','Road_Number','road_num']);
  const clsRaw=r2Field(p,['Road_Class','ROAD_CLASS','RoadClass','Class','road_class']);
  const cls=clsRaw?((typeof dec==='function'&&dec('Road_Class',clsRaw))||clsRaw):'';
  const sCh=r2Field(p,['Rd_Str_cha','Rd_Str_Cha','Road_Start','Start_Chainage','Start_Ch','RdStrCha','start_chainage']);
  const eCh=r2Field(p,['Rd_End_cha','Rd_End_Cha','Road_End','End_Chainage','End_Ch','RdEndCha','end_chainage']);
  const sLoc=r2Field(p,['Rd_Str_Loc','Start_Loc','Start_Location','Strt_Loc','start_location']);
  const eLoc=r2Field(p,['Rd_End_Loc','End_Loc','End_Location','end_location']);
  const len=r2Length({properties:p,geometry:f.geometry});
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
function r2FitGj(gj){
  try{const b=new maplibregl.LngLatBounds();gj.features.forEach(f=>{const g=f.geometry;if(!g)return;const w=a=>{if(typeof a[0]==='number')b.extend(a);else a.forEach(w);};if(g.coordinates)w(g.coordinates);});if(!b.isEmpty())map.fitBounds(b,{padding:50});}catch(e){}
}
function r2Apply(gj){
  if(!gj||!gj.features||!gj.features.length){r2SetStatus('No features found in file.');return;}
  ROADS2_COUNT=gj.features.length;
  const t2=document.getElementById('showRoads2'); if(t2)t2.checked=true;
  r2CloseAllPopups(); hideSectionRoadNet();
  r2FitGj(gj);
  r2SetStatus(gj.features.length+' roads imported \u00b7 saving\u2026');
  r2Persist(gj).then(function(ok){
    if(r2TilesOn()){
      /* Drop any local GeoJSON paint and recreate from tiles so MapLibre
         fetches against the just-upserted rows. */
      ROADS2_GJ=null;
      r2TeardownLayer();
      ensureRoads2Layer();
      if(t2&&t2.checked)setRoads2Visible(true);
    }else{
      ROADS2_GJ=gj;
      ensureRoads2Layer();
      if(t2&&t2.checked)setRoads2Visible(true);
    }
    if(!ok){/* status already set by r2Persist */}
  });
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
        ROADS2_COUNT=(j.total!=null?j.total:gj.features.length);
        r2SetStatus('\u2713 Saved '+ROADS2_COUNT+' roads (added '+(j.inserted||0)+', updated '+(j.updated||0)+')');
        return true;
      }
      r2SetStatus('Shown, but server save failed: '+((j&&j.message)||'unknown'));
      return false;
    }).catch(e=>{ r2SetStatus('Shown, but server save failed: '+((e&&e.message)||e)); return false; });
}

/* ---- load persisted network from DB, ON DEMAND ---- */
function r2WhenMapReady(cb){
  if(typeof map!=='undefined'&&map&&map.isStyleLoaded&&map.isStyleLoaded()){cb();return;}
  if(typeof map!=='undefined'&&map&&map.on){map.on('load',cb);return;}
  setTimeout(()=>r2WhenMapReady(cb),300);
}
/* build 118 — this download used to run unconditionally at DOMContentLoaded, but
   ensureRoads2Layer() creates the layer HIDDEN and only the "Full Road Network"
   toggle ever reveals it. On the live network that is several MB of geometry
   fetched on every map open for a layer most sessions never switch on — it was
   one of the largest single payloads on the page.

   Startup now asks /api/full-network/count for the row count (a few bytes), which
   is all the UI actually needs up front: whether a saved network exists, and how
   many roads to name in the status line. The FeatureCollection is fetched the
   first time the toggle is turned on (GeoJSON mode only); in tile mode the
   vector source template is enough and MapLibre fetches the viewport. */
let ROADS2_COUNT=null,_r2LoadP=null;
function r2LoadCount(){
  return fetch('/api/full-network/count',{credentials:'same-origin'})
    .then(r=>r.ok?r.json():null).then(j=>{
      ROADS2_COUNT=(j&&j.count)||0;
      if(ROADS2_COUNT)r2SetStatus(ROADS2_COUNT+' roads (saved)');
    }).catch(()=>{ROADS2_COUNT=null;});   /* null = unknown; the toggle still tries */
}
function loadRoads2FromServer(){
  if(r2TilesOn()){
    return new Promise(function(res){
      r2WhenMapReady(function(){
        ensureRoads2Layer();
        if(ROADS2_COUNT)r2SetStatus(ROADS2_COUNT+' roads (saved)');
        res(true);
      });
    });
  }
  if(ROADS2_GJ)return Promise.resolve(ROADS2_GJ);
  if(_r2LoadP)return _r2LoadP;
  r2SetStatus('Loading full road network…');
  var _p=fetch('/api/full-network/geojson',{credentials:'same-origin'}).then(r=>r.json()).then(gj=>{
    if(!gj||!gj.features||!gj.features.length){r2SetStatus('Import a layer first.');return null;}
    ROADS2_GJ=gj; ROADS2_COUNT=gj.features.length;
    r2WhenMapReady(()=>ensureRoads2Layer());   // layer created hidden; toggle shows it
    r2SetStatus(gj.features.length+' roads (saved)');
    return gj;
  }).catch(e=>{r2SetStatus('Could not load saved network: '+((e&&e.message)||e));return null;});
  _r2LoadP=_p;
  /* Clear on settle so a failed load can be retried; a SUCCESSFUL one is held by
     ROADS2_GJ above, which is what short-circuits the repeat call. */
  _p.then(function(){_r2LoadP=null;},function(){_r2LoadP=null;});
  return _p;
}

/* ---- wiring ---- */
(function(){
  function wire(){
    const t2=document.getElementById('showRoads2');
    if(t2)t2.addEventListener('change',e=>{
      if(e.target.checked){
        /* Only refuse outright when we KNOW the table is empty. ROADS2_COUNT is
           null when the count call failed, in which case fall through and let the
           real fetch decide. */
        if(!r2TilesOn()&&!ROADS2_GJ&&ROADS2_COUNT===0){r2SetStatus('Import a layer first.');e.target.checked=false;return;}
        if(r2TilesOn()&&ROADS2_COUNT===0){r2SetStatus('Import a layer first.');e.target.checked=false;return;}
        r2CloseAllPopups(); hideSectionRoadNet();
        Promise.resolve(loadRoads2FromServer()).then(function(ok){
          if(r2TilesOn()){
            if(!ok&&ROADS2_COUNT===0){e.target.checked=false;setSectionHit(true);return;}
            if(t2.checked)setRoads2Visible(true);
            return;
          }
          if(!ROADS2_GJ){e.target.checked=false;setSectionHit(true);return;}
          if(t2.checked)setRoads2Visible(true);   /* they may have toggled off mid-download */
        });
      }
      else { setRoads2Visible(false); setSectionHit(true); }
    });
    const t1=document.getElementById('showRoads');
    if(t1)t1.addEventListener('change',e=>{ if(e.target.checked){ r2CloseAllPopups(); hideFullRoadNet(); setSectionHit(true); } });
    const fi=document.getElementById('roads2File');
    if(fi)fi.addEventListener('change',function(){r2OnFile(this);});
    r2LoadCount();   // just how many roads are saved; geometry waits for the toggle
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',wire);else wire();
})();
