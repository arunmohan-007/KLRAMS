/* ============================================================
   KLRAMS viewer · 11-dashboard-charts.js
   Dashboard charts (donuts, ranked bars), shared HTML/number formatters, overview render and floating panes.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
/* ===== dashboard charts (drop-in, build 29) ===== */
const DPAL=['#15976a','#2a5d9c','#d4a02e','#6b4e9e','#3f9aa3','#c2603f','#5a7d3c','#a8557e','#3b7d8c','#8a93a6'];
const CLASS_COL={SH:'#15976a',MDR:'#2a5d9c',ODR:'#d4a02e',NH:'#c2603f'};
const CLASS_SHORT={SH:'State Highway',MDR:'Major Dist. Road',ODR:'Other Dist. Road',NH:'National Highway'};
/* Construction type: user-facing labels (RGD shown as "Cement Concrete") and colours. */
const CONS_LBL={FLX:'Flexible',RGD:'Cement Concrete',PVB:'Paver Block',CMP:'Composite',WBM:'WBM',GRV:'Gravel',ERT:'Earthen'};
const CONS_COL={FLX:'#2a5d9c',RGD:'#c2603f',PVB:'#6b4e9e',CMP:'#3f9aa3',WBM:'#d4a02e',GRV:'#5a7d3c',ERT:'#a8557e'};
function consLbl(l){return CONS_LBL[l]||dec('Cons_Type',l);}
function consCol(l,i){return CONS_COL[l]||DPAL[i%DPAL.length];}
function dColor(label,i){return CLASS_COL[label]||DPAL[i%DPAL.length];}
function escH(s){return String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));}
function qq(s){return String(s).replace(/\\/g,'\\\\').replace(/'/g,"\\'");}
function fmtKm(n){return (Math.round((+n||0)*10)/10).toFixed(1);}

function donut(rows,opts){
  opts=opts||{};const tot=rows.reduce((s,r)=>s+(+r.km||0),0)||1;let cum=0;
  const segs=rows.map((r,i)=>{
    const pct=(+r.km||0)/tot*100;const off=((25-cum)%100+100)%100;cum+=pct;
    const col=(opts.colorFn||dColor)(r.label,i);
    return `<circle class="seg" cx="21" cy="21" r="15.91549" fill="none" stroke="${col}" stroke-width="5.2" pathLength="100" stroke-dasharray="${pct.toFixed(3)} ${(100-pct).toFixed(3)}" stroke-dashoffset="${off.toFixed(3)}"></circle>`;
  }).join('');
  return `<div class="donut"><svg viewBox="0 0 42 42" class="donut-svg" aria-hidden="true">`+
    `<circle cx="21" cy="21" r="15.91549" fill="none" stroke="#eef2f7" stroke-width="5.2"></circle>${segs}</svg>`+
    `<div class="center"><div class="cbig">${opts.centerBig!=null?opts.centerBig:fmtKm(tot)}</div><div class="csmall">${opts.centerSmall||'km total'}</div></div></div>`;
}
function donutCard(title,sub,rows,opts){
  opts=opts||{};rows=rows||[];const tot=rows.reduce((s,r)=>s+(+r.km||0),0)||1;
  const legend=rows.map((r,i)=>{
    const col=(opts.colorFn||dColor)(r.label,i);const km=+r.km||0,pc=km/tot*100;
    const nm=opts.full?opts.full(r.label):r.label;
    const clickable=!!opts.legendClick;
    const click=clickable?` onclick="${opts.legendClick}('${qq(r.label)}')" title="${escH(nm)} — click to list sections"`:'';
    return `<div class="dleg${clickable?' click':''}"${click}><span class="sw" style="background:${col}"></span>`+
      `<span class="nm"${clickable?'':` title="${escH(nm)}"`}>${escH(nm)}</span>`+
      `<span class="vl">${fmtKm(km)}<span class="pc"> · ${pc.toFixed(0)}%</span></span></div>`;
  }).join('');
  return `<div class="dcard"><div class="dcard-head"><h3>${title}</h3><span class="totchip">${fmtKm(tot)} km</span></div>`+
    `<div class="sub">${sub}</div>`+
    `<div class="donut-wrap">${donut(rows,opts)}<div class="donut-legend">${legend||'<div class="sub">No data.</div>'}</div></div></div>`;
}
function rankedBars(rows,opts){
  opts=opts||{};
  if(!rows||!rows.length)return '<div class="sub" style="padding:6px 0 2px">No data available.</div>';
  const sorted=rows.slice().sort((x,y)=>(+y.km||0)-(+x.km||0));
  const max=Math.max(...sorted.map(r=>+r.km||0),0.0001);
  return `<div class="rbars">`+sorted.map((r,i)=>{
    const km=+r.km||0,pct=Math.max(3,km/max*100);
    const col=(opts.colorFn||dColor)(r.label,i);
    const sel=opts.selName!=null&&r.label===opts.selName;
    const click=opts.click?` onclick="${opts.click}('${qq(r.label)}')"`:'';
    const cls='rbar'+(opts.click?' click':'')+(sel?' sel':'');
    const nm=opts.full?opts.full(r.label):r.label;
    return `<div class="${cls}"${click}><div class="rk${i<3?' medal':''}">${String(i+1).padStart(2,'0')}</div>`+
      `<div><div class="rb-top"><span class="rb-nm" title="${escH(nm)}">${escH(nm)}</span><span class="rb-vl">${fmtKm(km)} km</span></div>`+
      `<div class="rb-track"><div class="rb-fill" style="width:${pct.toFixed(1)}%;background:linear-gradient(90deg,${col},${col}cc)"></div></div></div></div>`;
  }).join('')+`</div>`;
}

/* ---- Longest roads (Top 10) ----
   SH: one SH number can run under several Road_Names, so the backend sums
   corrected length per Road_Num and lists every name under it.
   MDR: summed per Road_Name. Overall or filtered to one district. */
let lrDistricts=[];let lrCache={};
function longestSection(){
  const n=lrDistricts.length;
  const label=n?(n===1?lrDistricts[0]:n+' districts selected'):'All Districts — overall';
  return `<div class="dcard lr-card">
    <div class="lr-head">
      <div>
        <div class="dcard-head" style="justify-content:flex-start;gap:10px"><h3>Longest roads</h3><span class="totchip">Top 10</span></div>
        <div class="sub">State Highways ranked by road number (all names under a number are listed) · MDRs by road name — corrected length</div>
      </div>
      <button type="button" class="valbtn lr-distbtn"${n?' has':''} onclick="lrToggleDistPop(this)" title="Scope: all districts (overall) or pick one/more">
        <span class="vb-txt">${escH(label)}</span><i class="vb-arr">▾</i>
      </button>
    </div>
    <div id="lrBody"><div class="dash-loading">Loading longest roads…</div></div>
  </div>`;
}
/* District picker: same checklist-popup pattern as the Road-network filter
   value picker (05-road-network.js nfOpenValPop) — search box, All/Clear,
   scrollable checkboxes, live count — reused here for a consistent, roomier
   UI than a native <select multiple> listbox. */
let _lrPopOpen=false,_lrQuery='';
function lrCloseDistPop(){const p=document.getElementById('lrDistPop');if(p)p.remove();_lrPopOpen=false;_lrQuery='';}
function lrRefreshDistBtn(){
  const btn=document.querySelector('.lr-distbtn');if(!btn)return;
  const n=lrDistricts.length;
  btn.querySelector('.vb-txt').textContent=n?(n===1?lrDistricts[0]:n+' districts selected'):'All Districts — overall';
  btn.classList.toggle('has',n>0);
}
function lrDistPopList(){
  const p=document.getElementById('lrDistPop');if(!p)return;
  const dists=(dashData.by_district||[]).map(r=>r.label).slice().sort((a,b)=>a.localeCompare(b));
  const q=_lrQuery.trim().toLowerCase();
  const items=dists.filter(v=>!q||v.toLowerCase().indexOf(q)>=0);
  p.querySelector('#lrpCnt').textContent=lrDistricts.length+' selected · '+dists.length+' district'+(dists.length===1?'':'s');
  p.querySelector('#lrpList').innerHTML=items.length
    ?items.map(v=>'<label class="nvp-it"><span>'+escH(v)+'</span><input type="checkbox" value="'+escH(v)+'"'+(lrDistricts.indexOf(v)>=0?' checked':'')+'></label>').join('')
    :'<div class="nvp-empty">No districts match “'+escH(_lrQuery)+'”.</div>';
  p.querySelectorAll('#lrpList input').forEach(cb=>{cb.onchange=()=>{
    if(cb.checked){if(lrDistricts.indexOf(cb.value)<0)lrDistricts.push(cb.value);}else{lrDistricts=lrDistricts.filter(v=>v!==cb.value);}
    lrEnsure();lrRefreshDistBtn();lrDistPopList();
  };});
}
function lrOpenDistPop(anchor){
  lrCloseDistPop();_lrPopOpen=true;_lrQuery='';
  const p=document.createElement('div');p.className='nvp';p.id='lrDistPop';
  p.innerHTML='<div class="nvp-top"><input type="text" class="nvp-q" placeholder="Search districts…" autocomplete="off">'
    +'<button type="button" class="nvp-all">All</button><button type="button" class="nvp-clear">Clear</button></div>'
    +'<div class="nvp-list" id="lrpList"></div><div class="nvp-cnt" id="lrpCnt"></div>';
  document.body.appendChild(p);
  const r=anchor.getBoundingClientRect(),W=Math.max(240,Math.min(360,window.innerWidth-24));
  p.style.width=W+'px';
  p.style.left=Math.max(8,Math.min(r.right-W,window.innerWidth-W-8))+'px';
  p.style.top=(r.bottom+5)+'px';
  p.style.maxHeight=Math.max(160,Math.min(320,window.innerHeight-r.bottom-16))+'px';
  p.querySelector('.nvp-clear').onclick=()=>{lrDistricts=[];lrEnsure();lrRefreshDistBtn();lrDistPopList();};
  p.querySelector('.nvp-all').onclick=()=>{lrDistricts=(dashData.by_district||[]).map(r=>r.label).slice();lrEnsure();lrRefreshDistBtn();lrDistPopList();};
  const q=p.querySelector('.nvp-q');
  q.oninput=e=>{_lrQuery=e.target.value;lrDistPopList();};
  lrDistPopList();
  setTimeout(()=>{try{q.focus();}catch(e){}},0);
}
function lrToggleDistPop(anchor){if(_lrPopOpen){lrCloseDistPop();}else{lrOpenDistPop(anchor);}}
document.addEventListener('mousedown',function(e){
  if(_lrPopOpen&&!e.target.closest('#lrDistPop')&&!e.target.closest('.lr-distbtn'))lrCloseDistPop();
},true);
document.addEventListener('scroll',function(e){
  const p=document.getElementById('lrDistPop');
  if(p&&!(e.target&&p.contains&&e.target.nodeType===1&&p.contains(e.target)))lrCloseDistPop();
},true);
window.addEventListener('resize',lrCloseDistPop);
function lrEnsure(){
  const key=lrDistricts.length?lrDistricts.slice().sort().join(','):'*';
  if(lrCache[key]&&lrCache[key]!=='loading'){lrPaint(lrCache[key]);return;}
  const body=document.getElementById('lrBody');
  if(body)body.innerHTML='<div class="dash-loading">Loading longest roads…</div>';
  if(lrCache[key]==='loading')return;
  lrCache[key]='loading';
  fetch('/api/dashboard/longest'+(lrDistricts.length?'?district='+encodeURIComponent(lrDistricts.join(',')):''))
    .then(r=>r.json())
    .then(d=>{lrCache[key]=d;if((lrDistricts.length?lrDistricts.slice().sort().join(','):'*')===key)lrPaint(d);})
    .catch(()=>{delete lrCache[key];const b=document.getElementById('lrBody');if(b)b.innerHTML='<div class="dash-loading">Could not load longest roads.</div>';});
}
function lrRows(rows,cls){
  if(!rows||!rows.length)return `<div class="lr-empty">No ${cls==='sh'?'State Highway':'MDR'} data${lrDistricts.length?' in '+escH(lrDistricts.join(', ')):''}.</div>`;
  const max=Math.max(...rows.map(r=>+r.km||0),0.0001);
  const col=cls==='sh'?CLASS_COL.SH:CLASS_COL.MDR;
  return '<div class="lr-rows">'+rows.map((r,i)=>{
    const km=+r.km||0,w=Math.max(3,km/max*100);
    const pill=(cls==='sh'&&r.num!=null&&r.num!=='')?`<span class="lr-num">SH ${escH(r.num)}</span>`:'';
    const dists=(!lrDistricts.length&&r.districts)?`<div class="lr-dists" title="${escH(r.districts)}">${escH(r.districts)}</div>`:'';
    return `<div class="lr-row"><div class="lr-rank r${i+1}">${i+1}</div><div class="lr-main">
      <div class="lr-top">${pill}<span class="lr-nm" title="${escH(r.names)}">${escH(r.names)}</span><span class="lr-km">${fmtKm(km)}<span class="u">km</span></span></div>
      <div class="lr-track"><i style="width:${w.toFixed(1)}%;background:linear-gradient(90deg,${col},${col}b3)"></i></div>${dists}</div></div>`;
  }).join('')+'</div>';
}
function lrPaint(d){
  const body=document.getElementById('lrBody');if(!body)return;
  body.innerHTML=`<div class="lr-grid">
    <div><div class="lr-col-head"><span class="lr-chip sh">SH</span>Longest State Highways</div>${lrRows(d.sh,'sh')}</div>
    <div><div class="lr-col-head"><span class="lr-chip mdr">MDR</span>Longest Major District Roads</div>${lrRows(d.mdr,'mdr')}</div>
  </div>`;
}

/* ---- Construction-type section finder (click a type in the donut) ---- */
let _consSecRows=[],_consSecQ='';
function consSecClose(){const b=document.getElementById('consSecBack');if(b)b.remove();}
function consSecPaint(){
  const list=document.getElementById('consSecList'),cnt=document.getElementById('consSecCnt');
  if(!list)return;
  const q=_consSecQ.trim().toLowerCase();
  const items=_consSecRows.filter(r=>!q||[r.section_la,r.road_name,r.district,r.pwd_sec].some(v=>String(v||'').toLowerCase().indexOf(q)>=0));
  if(cnt)cnt.textContent=items.length+' of '+_consSecRows.length+' section'+(_consSecRows.length===1?'':'s');
  list.innerHTML=items.length?items.map(r=>{
    const meta=[r.road_name,r.district,r.pwd_sec].filter(Boolean).map(escH).join(' · ');
    return `<div class="nvp-it csm-it"><span class="csm-l"><span class="csm-lbl" title="${escH(r.section_la||'')}">${escH(r.section_la||'(no section label)')}</span>`+
      `<span class="csm-m" title="${escH(meta)}">${meta||'&mdash;'}</span></span>`+
      `<span class="csm-km">${fmtKm(r.km)} km</span></div>`;
  }).join(''):`<div class="nvp-empty">No sections${_consSecQ?' match “'+escH(_consSecQ)+'”':''}.</div>`;
}
function consShowSections(type){
  consSecClose();_consSecRows=[];_consSecQ='';
  const label=consLbl(type);
  const scoped=(typeof ovScope!=='undefined'&&ovScope!=='state')?ovScope:null;
  const back=document.createElement('div');back.className='csm-back';back.id='consSecBack';
  back.innerHTML='<div class="nvp csm-panel">'
    +'<div class="nvp-top"><div class="csm-title">Sections · <b>'+escH(label)+'</b>'+(scoped?' · '+escH(scoped):'')+'</div>'
    +'<button type="button" class="nvp-clear csm-x">Close</button></div>'
    +'<div class="nvp-top"><input type="text" class="nvp-q" placeholder="Search section label / road / district…" autocomplete="off"></div>'
    +'<div class="nvp-list" id="consSecList"><div class="dash-loading">Loading sections…</div></div>'
    +'<div class="nvp-cnt" id="consSecCnt"></div></div>';
  document.body.appendChild(back);
  back.addEventListener('mousedown',e=>{if(e.target===back)consSecClose();});
  back.querySelector('.csm-x').onclick=consSecClose;
  const q=back.querySelector('.nvp-q');q.oninput=e=>{_consSecQ=e.target.value;consSecPaint();};
  fetch('/api/dashboard/cons-type-sections?type='+encodeURIComponent(type)+(scoped?'&district='+encodeURIComponent(scoped):''))
    .then(r=>r.json())
    .then(rows=>{_consSecRows=rows||[];consSecPaint();})
    .catch(()=>{const l=document.getElementById('consSecList');if(l)l.innerHTML='<div class="nvp-empty">Could not load sections.</div>';});
  setTimeout(()=>{try{q.focus();}catch(e){}},0);
}
document.addEventListener('keydown',function(e){if(e.key==='Escape')consSecClose();});

function shMdrDistrictTable(rows,shDistinct,mdrDistinct){
  rows=rows||[];
  if(!rows.length)return '';
  const body=rows.map(r=>`<tr><td>${escH(r.district)}</td>`+
    `<td class="n"><b>${r.sh_total_count||0}</b></td>`+
    `<td class="n">${r.sh_numbered_count||0}</td>`+
    `<td class="n">${r.sh_unnumbered_count||0}</td>`+
    `<td class="n"><b>${r.mdr_count||0}</b></td></tr>`).join('');
  return `<div class="dcard"><div class="dcard-head"><h3>State Highways &amp; MDRs by district</h3><span class="totchip">State-wide: ${shDistinct||0} SH &middot; ${mdrDistinct||0} MDR</span></div>`+
    `<div class="sub">SH counted by distinct Road Number (unnumbered SH stretches grouped by Road Name instead); MDR counted by distinct Road Name. <b>State-wide distinct total: ${shDistinct||0} SH · ${mdrDistinct||0} MDR.</b> A road running through several districts is counted <b>once in each district it passes through</b>, so the per-district figures below deliberately have <b>no total row</b> — they do not add up to the state-wide distinct count.</div>`+
    `<div class="amx-wrap"><table class="amx"><thead><tr><th>District</th><th class="n">SH (total)</th><th class="n">SH (numbered)</th><th class="n">SH (by name)</th><th class="n">MDR</th></tr></thead><tbody>${body}</tbody></table></div></div>`;
}
/* District-wise corrected length by construction type (pivot of the flat rows). */
function consTypeMatrix(flat,byCons){
  flat=flat||[];byCons=byCons||[];
  const cols=byCons.map(c=>c.label);            // column order = state-wide, largest first
  if(!cols.length)return '';
  const cell={};const distSet=[];
  flat.forEach(r=>{const d=r.district;if(!(d in cell)){cell[d]={};distSet.push(d);}cell[d][r.cons_type]=+r.km||0;});
  distSet.sort();
  const colTot={};cols.forEach(c=>colTot[c]=0);let grand=0;
  let head='<tr><th>District</th>'+cols.map((c,i)=>`<th class="n"><span class="amx-dot" style="background:${consCol(c,i)}"></span>${escH(consLbl(c))}</th>`).join('')+'<th class="n">Total</th></tr>';
  let body=distSet.map(d=>{
    let rt=0;const tds=cols.map(c=>{const v=(cell[d]&&cell[d][c])||0;colTot[c]+=v;rt+=v;return `<td class="n">${v?fmtKm(v):'<span class="z">·</span>'}</td>`;}).join('');
    grand+=rt;
    return `<tr><td>${escH(d)}</td>${tds}<td class="n"><b>${fmtKm(rt)}</b></td></tr>`;
  }).join('');
  body+='<tr class="amx-tot"><td><b>All districts</b></td>'+cols.map(c=>`<td class="n"><b>${fmtKm(colTot[c])}</b></td>`).join('')+`<td class="n"><b>${fmtKm(grand)}</b></td></tr>`;
  return '<div class="dcard"><div class="dcard-head"><h3>Length by construction type &middot; district-wise</h3>'+
    `<span class="totchip">${fmtKm(grand)} km</span></div>`+
    '<div class="sub">Corrected length (km) per construction type by district; dual carriageways averaged once. The <b>All districts</b> row is the state-wide total.</div>'+
    `<div class="amx-wrap"><table class="amx"><thead>${head}</thead><tbody>${body}</tbody></table></div></div>`;
}
/* ---- Network Overview: State-wide vs per-district scope (FWD-style) ----
   ovScope is 'state' (correct distinct/total figures) or a district name
   (every card re-scoped to that district via /api/dashboard/district). */
let ovScope='state';
let distCache={};
function ovLoadDistrict(name){
  if(distCache[name]&&distCache[name]!=='loading')return;
  distCache[name]='loading';
  fetch('/api/dashboard/district?name='+encodeURIComponent(name)).then(r=>r.json()).then(d=>{
    distCache[name]=d;if(ovScope===name)renderDashboard();
  }).catch(()=>{distCache[name]={total_km:0,by_class:[],by_owner:[],by_pwd_sec:[],by_cons_type:[],sh_total_count:0,sh_numbered_count:0,sh_unnumbered_count:0,mdr_count:0};if(ovScope===name)renderDashboard();});
}
function ovSetScope(name){
  ovScope=name;
  if(name!=='state')ovLoadDistrict(name);
  renderDashboard();
  try{document.getElementById('dashBody').scrollTop=0;}catch(e){}
}
function ovScopeBar(districts){
  const chip=(name,label)=>`<button type="button" class="svy-chip${ovScope===name?' on':''}" onclick="ovSetScope('${qq(name)}')">${escH(label)}</button>`;
  return `<div class="svy-bar"><div class="svy-dists">`+
    chip('state','State-wide')+
    districts.slice().sort((a,b)=>a.localeCompare(b)).map(nm=>chip(nm,nm)).join('')+
    `</div></div>`;
}
function renderDashboard(){
  const d=dashData;
  document.getElementById('dashScope').textContent='Network Overview';
  const districts=(d.by_district||[]).map(r=>r.label);
  const bar=ovScopeBar(districts);
  let inner;
  if(ovScope==='state'){inner=ovStateView(d);}
  else{
    const dd=distCache[ovScope];
    inner=(!dd||dd==='loading')?`<div class="dash-loading">Loading ${escH(ovScope)}…</div>`:ovDistrictView(dd,d);
  }
  document.getElementById('dashBody').innerHTML=bar+inner;
  if(ovScope==='state')lrEnsure();
}
function ovStateView(d){
  const cls=(d.by_class||[]).slice();
  const sh=(cls.find(c=>c.label==='SH')?.km||0),mdr=(cls.find(c=>c.label==='MDR')?.km||0);
  const ctot=cls.reduce((s,c)=>s+(+c.km||0),0)||1;
  const spark=cls.map((c,i)=>`<i style="width:${((+c.km||0)/ctot*100).toFixed(1)}%;background:${dColor(c.label,i)}"></i>`).join('');
  const kpi=`<div class="kpi-row">
    <div class="kpi feature"><div class="ringmark"></div><div class="kcap">Total road network</div><div class="kv">${fmtKm(d.total_km)}<span class="u">km</span></div><div class="kl">Corrected length · dual carriageways averaged once</div><div class="spark">${spark}</div></div>
    <div class="kpi" style="--kc:#8a93a6"><div class="kcap">As-drawn length</div><div class="kv">${fmtKm(d.raw_km)}<span class="u">km</span></div><div class="kl">Before dual-carriageway correction</div></div>
    <div class="kpi" style="--kc:#15976a"><div class="kcap">State Highway</div><div class="kv">${fmtKm(sh)}<span class="u">km</span></div><div class="kl">SH network length</div></div>
    <div class="kpi" style="--kc:#2a5d9c"><div class="kcap">Major District Road</div><div class="kv">${fmtKm(mdr)}<span class="u">km</span></div><div class="kl">MDR network length</div></div>
    <div class="kpi" style="--kc:#c9762a"><div class="kcap">State Highways</div><div class="kv">${d.sh_total_count||0}<span class="u">nos.</span></div><div class="kl">${d.sh_numbered_count||0} by Road Number${d.sh_unnumbered_count?' + '+d.sh_unnumbered_count+' by Road Name (no Road Number)':''}</div></div>
    <div class="kpi" style="--kc:#6b4e9e"><div class="kcap">Major District Roads</div><div class="kv">${d.mdr_count||0}<span class="u">nos.</span></div><div class="kl">By distinct Road Name</div></div>
  </div>`;
  const compClass=donutCard('Network by road class','Share of total length by classification',cls,{full:l=>CLASS_SHORT[l]||dec('Road_Class',l)});
  const ownTot=(d.by_owner||[]).reduce((s,r)=>s+(+r.km||0),0);
  const owners=`<div class="dcard"><div class="dcard-head"><h3>Network by current owner</h3><span class="totchip">${fmtKm(ownTot)} km</span></div><div class="sub">Length under each owning agency, ranked</div>${rankedBars(d.by_owner,{full:l=>dec('Current_Ow',l)})}</div>`;
  const comp=`<div class="comp-row">${compClass}${owners}</div>`;
  const consDonut=donutCard('Network by construction type','Corrected length by pavement construction type — click a type to list its section labels',(d.by_cons_type||[]),{full:consLbl,colorFn:consCol,legendClick:'consShowSections'});
  const consMatrix=consTypeMatrix(d.cons_type_by_district,d.by_cons_type);
  const consRow=`<div class="comp-row">${consDonut}${consMatrix}</div>`;
  const lr=longestSection();
  const distList=`<div class="dcard"><div class="dcard-head"><h3>Districts by network length</h3><span class="totchip">${(d.by_district||[]).length} districts</span></div><div class="sub">Corrected length per district — click a district to open its District-wide view</div>${rankedBars(d.by_district,{click:'ovSetScope'})}</div>`;
  const shMdrTable=shMdrDistrictTable(d.sh_mdr_by_district,d.sh_total_count,d.mdr_count);
  const note=`<div class="dash-note"><b>About these figures.</b> Lengths use the measured length of each road segment; <b>dual carriageways</b> (Section labels …A / …B, Single_Du = Dual) are counted <b>once</b> using the <b>average</b> of the two measured lengths. <b>Road counts</b> (SH / MDR) are distinct-road counts — SH by Road Number, MDR by Road Name. Shapefile digital length is ${d.dig_km} km; corrected network length is ${d.total_km} km. Use the <b>State-wide / district</b> chips above to focus every card on one district.</div>`;
  return kpi+comp+consRow+lr+distList+shMdrTable+note;
}
function ovDistrictView(dd,d){
  const name=ovScope;
  const cls=(dd.by_class||[]).slice();
  const sh=(cls.find(c=>c.label==='SH')?.km||0),mdr=(cls.find(c=>c.label==='MDR')?.km||0);
  const ctot=cls.reduce((s,c)=>s+(+c.km||0),0)||1;
  const spark=cls.map((c,i)=>`<i style="width:${((+c.km||0)/ctot*100).toFixed(1)}%;background:${dColor(c.label,i)}"></i>`).join('');
  const stateTot=+d.total_km||0,pct=stateTot?(dd.total_km/stateTot*100):0;
  const kpi=`<div class="kpi-row">
    <div class="kpi feature"><div class="ringmark"></div><div class="kcap">${escH(name)} network</div><div class="kv">${fmtKm(dd.total_km)}<span class="u">km</span></div><div class="kl">${pct.toFixed(1)}% of the state network · corrected length</div><div class="spark">${spark}</div></div>
    <div class="kpi" style="--kc:#15976a"><div class="kcap">State Highway</div><div class="kv">${fmtKm(sh)}<span class="u">km</span></div><div class="kl">SH length in ${escH(name)}</div></div>
    <div class="kpi" style="--kc:#2a5d9c"><div class="kcap">Major District Road</div><div class="kv">${fmtKm(mdr)}<span class="u">km</span></div><div class="kl">MDR length in ${escH(name)}</div></div>
    <div class="kpi" style="--kc:#c9762a"><div class="kcap">State Highways</div><div class="kv">${dd.sh_total_count||0}<span class="u">nos.</span></div><div class="kl">Roads present here${dd.sh_unnumbered_count?' · incl. '+dd.sh_unnumbered_count+' by name':''} — a road spanning districts also counts elsewhere</div></div>
    <div class="kpi" style="--kc:#6b4e9e"><div class="kcap">Major District Roads</div><div class="kv">${dd.mdr_count||0}<span class="u">nos.</span></div><div class="kl">Distinct Road Names present here</div></div>
  </div>`;
  const compClass=donutCard('Network by road class',`Share of length by classification — ${escH(name)}`,cls,{full:l=>CLASS_SHORT[l]||dec('Road_Class',l)});
  const consDonut=donutCard('Network by construction type',`Corrected length by construction type — click a type to list ${escH(name)} sections`,(dd.by_cons_type||[]),{full:consLbl,colorFn:consCol,legendClick:'consShowSections'});
  const comp=`<div class="comp-row">${compClass}${consDonut}</div>`;
  const ownTot=(dd.by_owner||[]).reduce((s,r)=>s+(+r.km||0),0);
  const owners=`<div class="dcard"><div class="dcard-head"><h3>Network by current owner</h3><span class="totchip">${fmtKm(ownTot)} km</span></div><div class="sub">Length under each owning agency in ${escH(name)}, ranked</div>${rankedBars(dd.by_owner,{full:l=>dec('Current_Ow',l)})}</div>`;
  const pwd=`<div class="dcard"><div class="dcard-head"><h3>PWD sections</h3><span class="totchip">${(dd.by_pwd_sec||[]).length} sections</span></div><div class="sub">Length by PWD maintenance section in ${escH(name)}</div>${rankedBars(dd.by_pwd_sec,{})}</div>`;
  const exp=`<div class="comp-row">${owners}${pwd}</div>`;
  const note=`<div class="dash-note"><b>${escH(name)} — district view.</b> Every card above is scoped to ${escH(name)} using corrected length (dual carriageways averaged once). <b>Road counts</b> (SH / MDR) count distinct roads <b>present in this district</b>; a State Highway that runs through several districts is counted here and in every other district it passes through, so district counts do not add up to the state-wide distinct totals. Switch back with the <b>State-wide</b> chip above.</div>`;
  return kpi+comp+exp+note;
}
function openPane(id){
  if(id==='dashboard'){document.getElementById('fpanes').classList.add('hidden');document.querySelectorAll('#iconrail .railbtn').forEach(b=>b.classList.toggle('active',b.dataset.pane==='dashboard'));return;}
  document.getElementById('dashboard').classList.remove('open');
  document.querySelectorAll('#fpanes .fpane').forEach(p=>p.classList.toggle('active',p.id==='pane-'+id));
  document.querySelectorAll('#iconrail .railbtn').forEach(b=>b.classList.toggle('active',b.dataset.pane===id));
  document.getElementById('fpanes').classList.remove('hidden');
  document.getElementById('iconrail').classList.remove('panes-hidden');
}
function togglePanes(){
  const fp=document.getElementById('fpanes');
  fp.classList.toggle('hidden');
}
