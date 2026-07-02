/* ============================================================
   KLRAMS viewer · 12-nsv-video.js
   NSV video player: pick a road, sync playback to chainage, and follow mode.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
// keep the two "Video on click" checkboxes in sync
(function(){
  const a=document.getElementById('videoMode'), b=document.getElementById('videoMode2');
  if(a&&b){a.addEventListener('change',()=>{b.checked=a.checked;});b.addEventListener('change',()=>{a.checked=b.checked;a.dispatchEvent(new Event('change'));});}
})();
function setSpeed(r,btn){playSpeed=r;const v=document.getElementById('video');if(v)v.playbackRate=r;document.querySelectorAll('#speedSeg button').forEach(b=>b.classList.toggle('on',b===btn));}
const video=document.getElementById('video'),vidempty=document.getElementById('vidempty');
function dirFromCatalog(d){const t=String(d==null?'':d).toLowerCase().trim();if(!t)return null;if(t.indexOf('rev')>=0||t.indexOf('back')>=0||t.indexOf('rear')>=0||t==='b'||t==='bwd')return 'rev';if(t.indexOf('front')>=0||t.indexOf('forward')>=0||t.indexOf('fwd')>=0||t==='f'||t==='fw')return 'fwd';return null;}
function applyDirAvailability(d){const fb=document.getElementById('fwd'),rb=document.getElementById('rev');const a=dirFromCatalog(d);if(!fb||!rb){setDir(a||'fwd');return;}[fb,rb].forEach(b=>{b.disabled=false;b.classList.remove('disabled');b.removeAttribute('title');});if(a==='fwd'){rb.disabled=true;rb.classList.add('disabled');rb.title='This survey was recorded in the Front direction only';setDir('fwd');}else if(a==='rev'){fb.disabled=true;fb.classList.add('disabled');fb.title='This survey was recorded in the Back direction only';setDir('rev');}else{setDir('fwd');}}
function nsvLoc(props,keys){for(var i=0;i<keys.length;i++){var k=keys[i];if(props&&props[k]!=null&&props[k]!=='')return String(props[k]);}return '';}
function updateRouteLabel(){
  var has=!(typeof cur==='undefined'||!cur||(!cur.startLoc&&!cur.endLoc));
  var html='\u2014',ttl='';
  if(has){var s=cur.startLoc||'\u2014',e=cur.endLoc||'\u2014';var A=(dir==='rev')?e:s,B=(dir==='rev')?s:e;html='<span class="rt-a">'+escH(A)+'</span><span class="rt-arrow">\u2192</span><span class="rt-b">'+escH(B)+'</span>';ttl=((dir==='rev')?'Back':'Front')+' direction: '+A+' \u2192 '+B;}
  ['dRoute','hudRoute'].forEach(function(id){var el=document.getElementById(id);if(!el)return;if(html==='\u2014'){el.textContent='\u2014';el.removeAttribute('title');}else{el.innerHTML=html;el.title=ttl;}});
}
function onPick(roadId,lngLat,lane){
  const feature=ROADS[roadId];if(!feature)return;
  const name=feature.properties.name||roadId,len=parseFloat(feature.properties.len)||0;
  const line=lineOf(feature),geoLenKm=turf.length(line,{units:'kilometers'});
  const snap=turf.nearestPointOnLine(line,[lngLat.lng,lngLat.lat],{units:'kilometers'});
  const frac=geoLenKm>0?snap.properties.location/geoLenKm:0,chainage=frac*len;lastChainage=chainage;
  const videoMode=document.getElementById('videoMode').checked;
  if(!videoMode){
    if(typeof openInspector==='function'){openInspector(feature.properties,roadId,chainage,lane);}
    else if(typeof showInspector==='function'){showInspector(buildPopup(feature.properties,roadId,chainage,lane),roadId);}
    else{new maplibregl.Popup({maxWidth:'300px'}).setLngLat(lngLat).setHTML(buildPopup(feature.properties,roadId,chainage,lane)).addTo(map);}
    return;
  }
  const entry=CATALOG[roadId];
  if(!(entry&&entry.file)){
    // No survey video for this road — leave the dock hidden, just show a brief notice.
    new maplibregl.Popup({maxWidth:'260px'}).setLngLat(lngLat).setHTML('<div class="pop"><div class="sec">'+escH(name)+'</div><div style="font-size:12px;color:#64718a;padding:3px 0 1px">No survey video for this road yet.</div></div>').addTo(map);
    return;
  }
  if(!cur||cur.road!==roadId){
    cur={road:roadId,name,len,line,geoLenKm,cls:nsvLoc(feature.properties,['Road_Class','ROAD_CLASS','RoadClass','Class']),rnum:nsvLoc(feature.properties,['Road_Num','Road_No','RoadNumber','road_num']),rtype:nsvLoc(feature.properties,['Road_Type','RoadType','road_type']),carriage:nsvLoc(feature.properties,['Single_Du','Carriageway','carriageway']),cons:nsvLoc(feature.properties,['Cons_Type','Construction_Type','cons_type']),startCh:nsvLoc(feature.properties,['Rd_Str_cha','Start_Chai','start_chainage']),endCh:nsvLoc(feature.properties,['Rd_End_cha','End_Chaina','end_chainage']),startLoc:nsvLoc(feature.properties,['Rd_Str_Loc','Start_Loc','Start_Location','Strt_Loc','start_location']),endLoc:nsvLoc(feature.properties,['Rd_End_Loc','End_Loc','End_Location','end_location'])};
    document.getElementById('dRoadName').textContent=name;
    document.getElementById('dRoadId').textContent=roadId;
    document.getElementById('dLen').textContent=Math.round(len).toLocaleString()+' m';
    updateRouteLabel();syncHudInfo();
    const src=/^https?:\/\//i.test(entry.file)?entry.file:('/videos/'+encodeURIComponent(entry.file));
    applyDirAvailability(entry.direction);video.style.display='';vidempty.style.display='none';video.src=src;video.load();
    document.getElementById('dock').classList.add('open','loaded');
    renderCoverageSummary();
    /* condition segments may still be loading — refresh coverage once they arrive */
    if(typeof ensureSegData==='function'){const _r=roadId;ensureSegData().then(function(){if(cur&&cur.road===_r){renderCoverageSummary();buildTravelPlan();if(video.duration&&!isNaN(video.duration))seek(lastChainage);}});}
  }
  setChainage(frac);placeCar(frac);seek(chainage);if(typeof buildVidTrack==='function'){buildVidTrack();updateVidHud();}
}
/* ============================================================
   Build 162 — gap-aware NSV playback engine.
   The survey video holds ONLY surveyed footage (gap stretches were
   not recorded), so video-time maps to SURVEYED distance, not total
   chainage. buildTravelPlan() cuts the road into ordered video/gap
   segments. During a video segment the footage drives the chainage;
   when it reaches the end of a surveyed stretch we PAUSE the video,
   animate the chainage + vehicle across the gap at the same visual
   speed, then RESUME into the next stretch. Roads with full coverage
   (no gaps) fall back to the original linear mapping untouched. */
let _gapRAF=null,_segIdx=0;
function _local2ch(cl){return dir==='rev'?(cur.len-cl):cl;}   /* local (ascending, travel order) -> actual chainage */
function _ch2local(ch){return dir==='rev'?(cur.len-ch):ch;}   /* actual chainage -> local */
function buildTravelPlan(){
  if(!cur||!(cur.len>0)||!video.duration||isNaN(video.duration))return;
  const D=video.duration,len=cur.len;
  let rs=(typeof roadCoverageRanges==='function'?roadCoverageRanges(cur.road):[])
    .map(r=>[Math.max(0,Math.min(len,r[0])),Math.max(0,Math.min(len,r[1]))]).filter(r=>r[1]-r[0]>1e-6);
  cur._planDir=dir;
  if(!rs.length){cur.hasGaps=false;cur._plan=null;cur._m=len/D;return;}          /* no condition data -> linear */
  if(dir==='rev')rs=rs.map(r=>[len-r[1],len-r[0]]);
  rs.sort((a,b)=>a[0]-b[0]);
  let S=0;rs.forEach(r=>S+=r[1]-r[0]);
  cur._m=S/D;                                                                     /* surveyed metres per second of footage */
  cur.hasGaps=(len-S)>Math.max(1,len*0.005);
  if(!cur.hasGaps){cur._plan=null;return;}
  const segs=[];let acc=0,cursor=0;
  for(let i=0;i<rs.length;i++){
    const a=rs[i][0],b=rs[i][1];
    if(a>cursor+1e-6)segs.push({type:'gap',clStart:cursor,clEnd:a,tFreeze:acc/S*D});
    segs.push({type:'video',clStart:a,clEnd:b,tStart:acc/S*D,tEnd:(acc+(b-a))/S*D});
    acc+=(b-a);cursor=b;
  }
  if(cursor<len-1e-6)segs.push({type:'gap',clStart:cursor,clEnd:len,tFreeze:D});
  cur._plan=segs;
}
function _applyLocal(cl){
  const frac=cur.len>0?(_local2ch(cl)/cur.len):0;
  setChainage(frac);placeCar(frac);updateVidHud();
  if(follow&&curCarLL&&typeof followTo==='function')followTo(curCarLL,260);
}
function _stopGapAnim(){if(_gapRAF){cancelAnimationFrame(_gapRAF);_gapRAF=null;}}
function _startGapAnim(gapSeg,resumeAfter){
  _stopGapAnim();
  try{video.pause();}catch(e){}                                                  /* footage stays frozen while the gap animates */
  const m=cur._m||1,rate=video.playbackRate||1,gapLen=gapSeg.clEnd-gapSeg.clStart;
  let durMs=(gapLen/m)*1000/rate;if(!(durMs>0)||!isFinite(durMs))durMs=Math.min(5000,Math.max(500,gapLen));
  const t0=performance.now();
  (function frame(now){
    const p=Math.min(1,(now-t0)/durMs);
    _applyLocal(gapSeg.clStart+p*(gapSeg.clEnd-gapSeg.clStart));
    if(p<1){_gapRAF=requestAnimationFrame(frame);return;}
    _gapRAF=null;
    if(_segIdx+1<cur._plan.length){
      _segIdx+=1;const ns=cur._plan[_segIdx];
      if(ns&&ns.type==='video'){seeking=true;try{video.currentTime=ns.tStart;}catch(e){}setTimeout(()=>seeking=false,60);if(resumeAfter){try{video.play();}catch(e){}}}
    }
  })(performance.now());
}
function seekPlan(ch){
  const segs=cur._plan;if(!segs)return;
  const cl=_ch2local(ch);let idx=segs.length-1;
  for(let i=0;i<segs.length;i++){if(cl>=segs[i].clStart-1e-6&&cl<=segs[i].clEnd+1e-6){idx=i;break;}}
  _stopGapAnim();_segIdx=idx;const s=segs[idx];
  if(s.type==='video'){
    const fr=(s.clEnd>s.clStart)?(cl-s.clStart)/(s.clEnd-s.clStart):0;
    seeking=true;video.currentTime=Math.max(0,Math.min(1,(s.tStart+fr*(s.tEnd-s.tStart))/video.duration))*video.duration;setTimeout(()=>seeking=false,50);
  }else{
    seeking=true;try{video.currentTime=s.tFreeze;}catch(e){}setTimeout(()=>seeking=false,50);try{video.pause();}catch(e){}
  }
  _applyLocal(cl);
}
function planTick(){
  if(!cur||!cur._plan||seeking||_gapRAF)return;
  const segs=cur._plan,t=video.currentTime;let s=segs[_segIdx];
  if(!(s&&s.type==='video'&&t>=s.tStart-0.06&&t<=s.tEnd+0.06)){                   /* scrub / jump -> re-derive segment */
    for(let i=0;i<segs.length;i++){if(segs[i].type==='video'&&t>=segs[i].tStart-1e-6&&t<=segs[i].tEnd+1e-6){_segIdx=i;break;}}
    s=segs[_segIdx];
  }
  if(!s||s.type!=='video')return;
  if(t>=s.tEnd-1e-3){
    const next=segs[_segIdx+1];
    if(next&&next.type==='gap'){
      const wasPlaying=!video.paused;seeking=true;try{video.currentTime=s.tEnd;}catch(e){}setTimeout(()=>seeking=false,50);try{video.pause();}catch(e){}
      _segIdx+=1;_startGapAnim(next,wasPlaying);return;
    }
    _applyLocal(s.clEnd);return;
  }
  const fr=(s.tEnd>s.tStart)?(t-s.tStart)/(s.tEnd-s.tStart):0;
  _applyLocal(s.clStart+fr*(s.clEnd-s.clStart));
}
function seek(ch){
  if(!cur||!video.duration||isNaN(video.duration))return;
  if(cur._planDir!==dir)buildTravelPlan();
  if(cur.hasGaps){seekPlan(ch);return;}
  const fch=cur.len>0?ch/cur.len:0;const tf=dir==='fwd'?fch:(1-fch);
  seeking=true;video.currentTime=Math.max(0,Math.min(tf,1))*video.duration;setTimeout(()=>seeking=false,50);
}
video.addEventListener('loadedmetadata',()=>{video.playbackRate=playSpeed;buildTravelPlan();seek(lastChainage);});
video.addEventListener('play',()=>{follow=true;if(curCarLL)followTo(curCarLL,700);
  if(cur&&cur.hasGaps&&!_gapRAF){const s=cur._plan&&cur._plan[_segIdx];if(s&&s.type==='gap')_startGapAnim(s,true);}});
video.addEventListener('pause',()=>{follow=false;});
video.addEventListener('timeupdate',()=>{
  if(!cur||!video.duration||seeking)return;
  if(cur._planDir!==dir)buildTravelPlan();
  if(cur.hasGaps){planTick();return;}
  const tf=video.currentTime/video.duration;const fch=dir==='fwd'?tf:(1-tf);
  setChainage(fch);placeCar(fch);updateVidHud();if(follow&&curCarLL)followTo(curCarLL,260);
});
function closeDock(){document.getElementById('dock').classList.remove('open','loaded');if(marker)marker.remove();if(typeof _stopGapAnim==='function')_stopGapAnim();_segIdx=0;cur=null;if(video){try{video.pause();}catch(e){}}/* Build 87 — closing the dock also switches "Video on click" OFF, so it won't pop back up on the next map click. Setting .checked here does not fire 'change', so no recursion. */['videoMode','videoMode2'].forEach(id=>{const el=document.getElementById(id);if(el)el.checked=false;});syncVClick();}
/* Build 88 — reflect "Video on click" state on <body class="vclick-on">.
   CSS force-hides #dock whenever this class is absent, so the dock can NEVER
   stay visible while Video-on-click is off, regardless of how it was opened. */
function syncVClick(){const on=!!(((document.getElementById('videoMode')||{}).checked)||((document.getElementById('videoMode2')||{}).checked));document.body.classList.toggle('vclick-on',on);}
/* the dock is never shown unless a video is actively loaded:
   start closed, and close it whenever "Video on click" is switched off */
(function(){
  const dk=document.getElementById('dock'); if(dk) dk.classList.remove('open','loaded');
  ['videoMode','videoMode2'].forEach(id=>{
    const el=document.getElementById(id);
    if(el) el.addEventListener('change',()=>{ syncVClick(); if(!el.checked) closeDock(); });
  });
  syncVClick();
})();

/* ============================================================
   Build 82 — live-track HUD overlay on the survey video.
   Shows a mini road track with the moving vehicle dot + the IRI
   value, visible even when the video is fullscreened (we fullscreen
   the .vid container, not the bare <video>, so HTML overlays survive).
   ============================================================ */
/* Build 161 — NSV survey-gap detection. A "gap" is any chainage range on the
   current road not covered by any condition segment (from_ch..to_ch): the
   video keeps playing there (it's a straight time/length proportion, see
   seek()), but there is no condition data for it, i.e. no real NSV coverage. */
function roadCoverageRanges(road){
  const arr=(typeof segsByRoad!=='undefined'&&segsByRoad[road])?segsByRoad[road]:[];
  const ranges=[];
  arr.forEach(f=>{const p=f.properties||{};const a=+p.from_ch,b=+p.to_ch;if(isNaN(a)||isNaN(b))return;ranges.push([Math.min(a,b),Math.max(a,b)]);});
  ranges.sort((x,y)=>x[0]-y[0]);
  const merged=[];
  ranges.forEach(r=>{if(merged.length&&r[0]<=merged[merged.length-1][1]+1e-6)merged[merged.length-1][1]=Math.max(merged[merged.length-1][1],r[1]);else merged.push(r.slice());});
  return merged;
}
function roadGapRanges(road,totalLen){
  const cov=roadCoverageRanges(road);const gaps=[];let cursor=0;
  cov.forEach(r=>{if(r[0]>cursor+1e-6)gaps.push([cursor,r[0]]);cursor=Math.max(cursor,r[1]);});
  if(cursor<totalLen-1e-6)gaps.push([cursor,totalLen]);
  return gaps;
}
/* Ordered surveyed/gap pieces across the whole road (chainage 0..len), for the
   coverage bar in the "Now playing" dock. */
function coveragePieces(road,len){
  const gaps=roadGapRanges(road,len).slice().sort((a,b)=>a[0]-b[0]);
  const pieces=[];let cursor=0;
  gaps.forEach(g=>{if(g[0]>cursor+1e-6)pieces.push({a:cursor,b:g[0],gap:false});pieces.push({a:g[0],b:g[1],gap:true});cursor=g[1];});
  if(cursor<len-1e-6)pieces.push({a:cursor,b:len,gap:false});
  if(!pieces.length&&len>0)pieces.push({a:0,b:len,gap:false});
  return pieces;
}
function renderCoverageSummary(){
  const box=document.getElementById('dCoverage');if(!box||!cur||!(cur.len>0))return;
  const bar=document.getElementById('dCovBar'),pctEl=document.getElementById('dCovPct'),gapsEl=document.getElementById('dCovGaps');
  const gaps=roadGapRanges(cur.road,cur.len);
  const gapLen=gaps.reduce((s,g)=>s+(g[1]-g[0]),0);
  const surveyed=Math.max(0,cur.len-gapLen);
  const pct=Math.round((surveyed/cur.len)*100);
  const nf=n=>Math.round(n).toLocaleString();
  box.style.display='';
  if(pctEl){pctEl.textContent=pct+'% surveyed';pctEl.classList.toggle('warn',gaps.length>0);}
  if(bar){bar.innerHTML=coveragePieces(cur.road,cur.len).map(p=>{
    const w=((p.b-p.a)/cur.len*100).toFixed(3);
    const t=p.gap?('No survey · '+nf(p.a)+'–'+nf(p.b)+' m'):('Surveyed · '+nf(p.a)+'–'+nf(p.b)+' m');
    return '<i class="'+(p.gap?'cb-gap':'cb-ok')+'" style="flex:'+w+' 0 0" title="'+t+'"></i>';
  }).join('');}
  if(gapsEl){
    if(!gaps.length)gapsEl.innerHTML='<span class="cov-ok">✓ Full NSV coverage — no gaps.</span>';
    else gapsEl.innerHTML='Gaps ('+gaps.length+'): '+gaps.map(g=>'<b>'+nf(g[0])+'–'+nf(g[1])+' m</b>').join(', ');
  }
}
function buildVidTrack(){
  const svg=document.getElementById('hudTrack');if(!svg||!cur||!cur.line)return;
  const g=cur.line.geometry;const coords=(g&&g.coordinates)||[];
  if(coords.length<2){cur._trk=null;svg.innerHTML='';return;}
  let minX=Infinity,minY=Infinity,maxX=-Infinity,maxY=-Infinity;
  coords.forEach(c=>{if(c[0]<minX)minX=c[0];if(c[0]>maxX)maxX=c[0];if(c[1]<minY)minY=c[1];if(c[1]>maxY)maxY=c[1];});
  /* build 130 — large route map; the place names sit at the ACTUAL geometry ends
     and are tagged origin/destination by travel direction (Forward/Reverse). */
  const W=300,H=240,padX=20,padTop=30,padBot=30;
  const spanX=(maxX-minX)||1e-9,spanY=(maxY-minY)||1e-9;
  const s=Math.min((W-2*padX)/spanX,(H-padTop-padBot)/spanY);
  const drawW=spanX*s,drawH=spanY*s;
  const offX=(W-drawW)/2,offY=padTop+((H-padTop-padBot)-drawH)/2;
  cur._trk=function(lng,lat){return [offX+(lng-minX)*s, offY+(maxY-lat)*s];};
  const pts=coords.map(c=>{const xy=cur._trk(c[0],c[1]);return xy[0].toFixed(1)+','+xy[1].toFixed(1);}).join(' ');
  const xe=function(t){return String(t==null?'':t).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');};
  const clip=function(t){t=String(t||'');return t.length>20?t.slice(0,19)+'\u2026':t;};
  const p0=cur._trk(coords[0][0],coords[0][1]);
  const pN=cur._trk(coords[coords.length-1][0],coords[coords.length-1][1]);
  const fwd=(dir!=='rev');
  const oPt=fwd?p0:pN, dPt=fwd?pN:p0;
  const oLoc=fwd?(cur.startLoc||''):(cur.endLoc||'');
  const dLoc=fwd?(cur.endLoc||''):(cur.startLoc||'');
  function lbl(pt,txt,color,tag){
    const above=pt[1]>H/2;
    const y=above?(pt[1]-12):(pt[1]+18);
    let x=Math.max(54,Math.min(W-54,pt[0]));
    return '<circle cx="'+pt[0].toFixed(1)+'" cy="'+pt[1].toFixed(1)+'" r="4.5" fill="'+color+'" stroke="#0b1322" stroke-width="1.2"></circle>'+
      '<text x="'+x.toFixed(1)+'" y="'+y.toFixed(1)+'" text-anchor="middle" font-family="Inter,sans-serif" font-size="12.5" font-weight="800" fill="'+color+'">'+xe(clip(txt||tag))+'</text>'+
      '<text x="'+x.toFixed(1)+'" y="'+(above?(y-13):(y+13)).toFixed(1)+'" text-anchor="middle" font-family="Inter,sans-serif" font-size="8.5" font-weight="800" letter-spacing="1" fill="#8aa0bd">'+tag+'</text>';
  }
  let gapSvg='';
  if(cur.len>0){
    roadGapRanges(cur.road,cur.len).forEach(g=>{
      const steps=10,gp=[];
      for(let i=0;i<=steps;i++){
        const ch=g[0]+(g[1]-g[0])*(i/steps);
        const distKm=Math.max(0,Math.min(cur.geoLenKm,(ch/cur.len)*cur.geoLenKm));
        try{const pt=turf.along(cur.line,distKm,{units:'kilometers'});const xy=cur._trk(pt.geometry.coordinates[0],pt.geometry.coordinates[1]);gp.push(xy[0].toFixed(1)+','+xy[1].toFixed(1));}catch(e){}
      }
      if(gp.length>1)gapSvg+='<polyline points="'+gp.join(' ')+'" fill="none" stroke="#e24b4a" stroke-width="5" stroke-linecap="round" stroke-dasharray="2,6" opacity="0.95"><title>No NSV survey: '+Math.round(g[0])+'–'+Math.round(g[1])+' m</title></polyline>';
    });
  }
  svg.innerHTML=
    '<polyline points="'+pts+'" fill="none" stroke="#9fb4c8" stroke-width="3.4" stroke-linecap="round" stroke-linejoin="round" opacity="0.9"></polyline>'+
    gapSvg+
    lbl(oPt,oLoc,'#7ee6b0','START')+
    lbl(dPt,dLoc,'#ffd27a','END')+
    '<g id="hudCar"><rect id="hudCarBody" x="-4.5" y="-8" width="9" height="16" rx="2.6" fill="#16a06b" stroke="#ffffff" stroke-width="1.3"></rect><rect x="-3.4" y="-6" width="6.8" height="3.4" rx="1" fill="#dff3ea"></rect><rect x="-3" y="3" width="6" height="2.6" rx="0.8" fill="#0b1322" opacity="0.45"></rect></g>';
  syncHudInfo();updateRouteLabel();
}
function syncHudInfo(){
  if(typeof cur==='undefined'||!cur)return;
  var r=document.getElementById('hudRoad');if(r)r.textContent=cur.name||'\u2014';
  var c=document.getElementById('hudClass');if(c){var ab=(cur.cls||'').toString().toUpperCase();var lbl=ab+(cur.rnum?(' '+cur.rnum):'');c.textContent=lbl||'\u2014';c.style.display=lbl?'':'none';}
  var s=document.getElementById('hudSec');if(s)s.textContent=cur.road||'\u2014';
  var fch=function(v){if(v==null||v==='')return null;var n=parseFloat(String(v).replace(/,/g,''));return isNaN(n)?String(v):Math.round(n).toLocaleString()+' m';};
  var sc=document.getElementById('hudStartCh');if(sc){var t=fch(cur.startCh);if(t){sc.textContent='Start CH '+t;sc.style.display='';}else sc.style.display='none';}
  var ec=document.getElementById('hudEndCh');if(ec){var t2=fch(cur.endCh);if(t2){ec.textContent='End CH '+t2;ec.style.display='';}else ec.style.display='none';}
}
function pciBand2(p){if(p==null||isNaN(p))return null;if(p>=85)return{l:'Good',c:'#2ba66a'};if(p>=70)return{l:'Satisfactory',c:'#86c232'};if(p>=55)return{l:'Fair',c:'#f2c200'};if(p>=40)return{l:'Poor',c:'#e8843c'};return{l:'Very Poor',c:'#e24b4a'};}
function _pciFrom(c,keys){if(!c)return null;for(var i=0;i<keys.length;i++){if(c[keys[i]]!=null&&c[keys[i]]!=='')return +c[keys[i]];}return null;}
function _pciCard(eye,v){
  var ok=(v!=null&&v>=0);
  var b=(ok&&typeof pciBand==='function')?pciBand(v):null;
  var pc=b?b.color:'#3a465c';
  var light=b&&/^#(f2c200|7cb518|86c232|f08c00)$/i.test(b.color);
  var tc=light?'#241f00':'#fff';
  return '<div class="vp-card" style="--pc:'+pc+'"><div class="vp-eye">'+eye+'</div><div class="vp-val">'+(ok?Math.round(v):'\u2013')+'<span class="vp-100">/100</span></div>'+(b?('<span class="vp-band" style="background:'+b.color+';color:'+tc+'">'+b.label+'</span>'):'<span class="vp-band" style="background:rgba(255,255,255,.1);color:#9fb2cc">PCI not linked</span>')+'</div>';
}
function pciCardsHTML(cls,comp,worst){var clsTxt=(typeof decodeVal==='function')?decodeVal('Road Class',cls):(cls||'');clsTxt=String(clsTxt||'').replace(/[<>]/g,'');
  return (clsTxt?'<div class="vp-cls">'+clsTxt+'</div>':'')+'<div class="vp-cards">'+_pciCard('Composite PCI',comp)+_pciCard('Worst-lane PCI',worst)+'</div>';}
var _pciCache={};
/* Persistent PCI: exact segment at chainage -> nearest segment -> cached/aggregate.
   Once a road has any PCI, it never blinks back to "not linked". */
function roadPciResolve(road,ch){
  var compKeys=['pci','composite_pci','comp_pci','avg_pci','road_pci'];
  var worstKeys=['worst_pci','worst_lane_pci','pci_worst'];
  function rd(p,keys){if(!p)return null;for(var i=0;i<keys.length;i++){if(p[keys[i]]!=null&&p[keys[i]]!=='')return +p[keys[i]];}return null;}
  var segs=(typeof segsByRoad!=='undefined'&&segsByRoad[road])?segsByRoad[road]:[];
  var cache=_pciCache[road]||(_pciCache[road]={comp:null,worst:null});
  // nearest segment to current chainage (0 distance = contains it)
  var best=null,bestDist=Infinity;
  for(var i=0;i<segs.length;i++){var p=segs[i].properties||{};
    var a=+(p.from_ch!=null?p.from_ch:p.Rd_Str_cha),b=+(p.to_ch!=null?p.to_ch:p.Rd_End_cha);
    if(isNaN(a)||isNaN(b))continue;var lo=Math.min(a,b),hi=Math.max(a,b);
    var d=(ch>=lo&&ch<=hi)?0:Math.min(Math.abs(ch-lo),Math.abs(ch-hi));
    if(d<bestDist){bestDist=d;best=p;}}
  var comp=rd(best,compKeys),worst=rd(best,worstKeys);
  if(comp!=null)cache.comp=comp;if(worst!=null)cache.worst=worst;
  // seed cache from the whole road so PCI is available from the very first frame
  if(cache.comp==null||cache.worst==null){
    var aC=[],aW=[];for(var j=0;j<segs.length;j++){var pp=segs[j].properties||{};var cc=rd(pp,compKeys),ww=rd(pp,worstKeys);if(cc!=null)aC.push(cc);if(ww!=null)aW.push(ww);}
    if(cache.comp==null&&aC.length)cache.comp=Math.round(aC.reduce(function(x,y){return x+y;},0)/aC.length);
    if(cache.worst==null&&aW.length)cache.worst=Math.min.apply(null,aW);
  }
  // road-level attribute fallback (if PCI is stored on the road feature itself)
  if((cache.comp==null||cache.worst==null)&&typeof ROADS!=='undefined'&&ROADS[road]){
    var rp=(ROADS[road].properties)||ROADS[road];
    if(cache.comp==null)cache.comp=rd(rp,compKeys);
    if(cache.worst==null)cache.worst=rd(rp,worstKeys);
  }
  return {comp:(comp!=null?comp:cache.comp),worst:(worst!=null?worst:cache.worst)};
}
function attrsHTML(cur){
  if(!cur)return '';
  var dv=function(param,v){return (typeof decodeVal==='function')?decodeVal(param,v):(v||'');};
  var items=[['Type',dv('Road Type',cur.rtype)],['Carriageway',cur.carriage||''],['Construction',dv('Construction Type',cur.cons)]].filter(function(a){return a[1];});
  if(!items.length)return '';
  return items.map(function(a){return '<span class="vh-attr"><i>'+a[0]+'</i>'+String(a[1]).replace(/[<>]/g,'')+'</span>';}).join('');
}
function chainDataHTML(c,ch){
  var o={},k;
  if(c){for(k in c)o[k]=c[k];}                                    /* condition range AT this chainage */
  var ke=(window.KL&&KL.atExact&&cur)?KL.atExact(cur.road,ch):{}; /* FWD/point layers, range-matched */
  for(k in ke){if(o[k]==null||o[k]==='')o[k]=ke[k];}
  var pick=function(keys){for(var i=0;i<keys.length;i++){if(o[keys[i]]!=null&&o[keys[i]]!=='')return o[keys[i]];}return null;};
  var rows=[
    ['FWD D0', (function(){var r=(window.FWD&&FWD.at&&cur)?FWD.at(cur.road,ch):null;return (r&&r.d0!=null)?String(r.d0):null;})(), ' µm'],
    ['Traffic location', pick(['traffic_loc','traffic_location','aadt_loc','traffic_pt','aadt']), ''],
    ['Soil sub-grade', pick(['soil_subgrade','subgrade','cbr','soil_pt']), ''],
    ['Bituminous core', pick(['bit_core','bituminous_core','core_thk','bt_thk','core_pt']), '']
  ].filter(function(r){return r[1]!=null;});
  if(!rows.length)return '';
  var H='<div class="vh-cap">Chainage data &middot; '+Math.round(ch).toLocaleString()+' m</div>';
  rows.forEach(function(r){var v=r[1];if(typeof v==='number')v=(+v).toFixed(1);H+='<div class="vh-crow"><span class="ck">'+r[0]+'</span><span class="cv">'+String(v).replace(/[<>]/g,'')+r[2]+'</span></div>';});
  return H;
}
function condMatrixHTML(c,ch){
  if(!c)return '<div class="vh-cap">Condition</div><div class="vh-nod">No survey at this chainage.</div>';
  var lv=null;try{lv=(typeof c.lane_vals==='string')?JSON.parse(c.lane_vals):c.lane_vals;}catch(e){}
  var lanes=lv?Object.keys(lv).sort():[];
  var mets=[['iri','IRI'],['crack','Crack'],['rutting','Rut'],['ravelling','Ravel']].filter(function(m){return c['avg_'+m[0]]!=null||c[m[0]]!=null;});
  var col=function(mk,x){return (x!=null&&typeof PMAP!=='undefined'&&PMAP[mk]&&typeof rating==='function')?rating(mk,x):'';};
  var H='<div class="vh-cap">Condition &middot; at '+Math.round(ch).toLocaleString()+' m</div><table class="vh-tbl"><tr><th></th>';
  if(lanes.length)lanes.forEach(function(L){H+='<th>'+L+'</th>';});
  H+='<th class="avg">Avg</th></tr>';
  mets.forEach(function(m){var mk=m[0];H+='<tr><th>'+m[1]+'</th>';
    if(lanes.length)lanes.forEach(function(L){var x=(lv[L]&&lv[L][mk]!=null)?+lv[L][mk]:null;var cc=col(mk,x);H+='<td class="vc"'+(cc?(' style="background:'+cc+'52"'):'')+'>'+(x!=null?(+x).toFixed(2):'\u2013')+'</td>';});
    var av=(c['avg_'+mk]!=null)?+c['avg_'+mk]:(c[mk]!=null?+c[mk]:null);var ac=col(mk,av);
    H+='<td class="vc avg"'+(ac?(' style="background:'+ac+'52"'):'')+'>'+(av!=null?av.toFixed(2):'\u2013')+'</td></tr>';});
  H+='</table>';return H;}
function updateVidHud(){
  if(!cur)return;
  if(typeof syncHudInfo==='function')syncHudInfo();
  if(typeof updateRouteLabel==='function')updateRouteLabel();
  var rn=document.getElementById('hudRoad');if(rn)rn.textContent=cur.name||'\u2014';
  var hd=document.getElementById('hudDir');if(hd)hd.textContent=(dir==='rev')?'Reverse':'Forward';
  var dc=document.getElementById('dCh');var chTxt=dc?dc.textContent:'0';var chNum=parseFloat(String(chTxt).replace(/,/g,''))||0;
  var hc=document.getElementById('hudCh');if(hc)hc.textContent='CH '+Math.round(chNum).toLocaleString()+' m';
  var c=(typeof condAt==='function'&&cur.road)?condAt(cur.road,chNum):null;
  var gapBanner=document.getElementById('hudGapBanner');if(gapBanner)gapBanner.classList.toggle('show',!c);
  var iv=c?((c.avg_iri!=null)?+c.avg_iri:(c.iri!=null?+c.iri:NaN)):NaN;
  var col=(!isNaN(iv)&&typeof rating==='function')?rating('iri',iv):'#3a465c';
  var hi=document.getElementById('hudIri');if(hi){hi.textContent='AVG IRI '+(isNaN(iv)?'\u2013':iv.toFixed(2));hi.style.background=col;}
  var pe=document.getElementById('hudPci');if(pe){var pr=(window.KL&&KL.pci)?KL.pci(cur.road,chNum):roadPciResolve(cur.road,chNum);pe.innerHTML=pciCardsHTML(cur.cls,pr.comp,pr.worst);}
  var ce=document.getElementById('hudCond');if(ce)ce.innerHTML=condMatrixHTML(c,chNum);
  var ae=document.getElementById('hudAttrs');if(ae)ae.innerHTML=attrsHTML(cur);
  var che=document.getElementById('hudChain');if(che)che.innerHTML=chainDataHTML(c,chNum);
  var car=document.getElementById('hudCar');
  if(car&&typeof cur._trk==='function'&&curCarLL){var xy=cur._trk(curCarLL[0],curCarLL[1]);car.setAttribute('transform','translate('+xy[0].toFixed(1)+','+xy[1].toFixed(1)+') rotate('+(Number(window.curCarBrg)||0).toFixed(0)+')');var body=document.getElementById('hudCarBody');if(body)body.setAttribute('fill',isNaN(iv)?'#16a06b':col);}
}
function toggleVidFs(){
  const el=document.querySelector('#dock .vid');if(!el)return;
  const fsEl=document.fullscreenElement||document.webkitFullscreenElement||null;
  if(fsEl){(document.exitFullscreen||document.webkitExitFullscreen||function(){}).call(document);}
  else{const req=el.requestFullscreen||el.webkitRequestFullscreen||el.msRequestFullscreen;if(req)req.call(el);
    if(typeof buildVidTrack==='function'){buildVidTrack();updateVidHud();}}
}
