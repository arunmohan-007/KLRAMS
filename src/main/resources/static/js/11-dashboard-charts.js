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
    return `<div class="dleg"><span class="sw" style="background:${col}"></span>`+
      `<span class="nm" title="${escH(nm)}">${escH(nm)}</span>`+
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

function shMdrDistrictTable(rows){
  rows=rows||[];
  if(!rows.length)return '';
  const totSh=rows.reduce((s,r)=>s+(+r.sh_total_count||0),0);
  const totMdr=rows.reduce((s,r)=>s+(+r.mdr_count||0),0);
  let body=rows.map(r=>`<tr><td>${escH(r.district)}</td>`+
    `<td class="n"><b>${r.sh_total_count||0}</b></td>`+
    `<td class="n">${r.sh_numbered_count||0}</td>`+
    `<td class="n">${r.sh_unnumbered_count||0}</td>`+
    `<td class="n"><b>${r.mdr_count||0}</b></td></tr>`).join('');
  body+=`<tr class="amx-tot"><td><b>Total</b></td><td class="n"><b>${totSh}</b></td><td class="n">&mdash;</td><td class="n">&mdash;</td><td class="n"><b>${totMdr}</b></td></tr>`;
  return `<div class="dcard"><div class="dcard-head"><h3>State Highways &amp; MDRs by district</h3><span class="totchip">${totSh} SH &middot; ${totMdr} MDR</span></div>`+
    `<div class="sub">SH counted by distinct Road Number (unnumbered SH stretches grouped by Road Name instead); MDR counted by distinct Road Name. A road running through several districts counts once in each district it passes through, so district totals can exceed the state-wide count.</div>`+
    `<div class="amx-wrap"><table class="amx"><thead><tr><th>District</th><th class="n">SH (total)</th><th class="n">SH (numbered)</th><th class="n">SH (by name)</th><th class="n">MDR</th></tr></thead><tbody>${body}</tbody></table></div></div>`;
}
let selDistrict=null;
function renderDashboard(){
  const d=dashData;
  document.getElementById('dashScope').textContent='Network Overview';
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
  const distList=`<div class="dcard"><div class="dcard-head"><h3>Districts</h3><span class="totchip">${(d.by_district||[]).length} listed</span></div><div class="sub">Select a district to see its PWD sections &amp; owners</div>${rankedBars(d.by_district,{click:'selectDistrict',selName:selDistrict})}</div>`;
  const detail=`<div id="distDetail">${districtDetailHtml()}</div>`;
  const exp=`<div class="exp-row">${distList}${detail}</div>`;
  const lr=longestSection();
  const shMdrTable=shMdrDistrictTable(d.sh_mdr_by_district);
  const note=`<div class="dash-note"><b>About these figures.</b> Lengths use the measured length of each road segment. A road is split into many segments wherever owner, PWD section, carriageway or lane type changes, so the dashboard reports <b>length only</b>, never road counts. <b>Dual carriageways</b> (Section labels …A / …B, Single_Du = Dual) are counted <b>once</b> using the <b>average</b> of the two measured lengths. Shapefile digital length is ${d.dig_km} km; corrected network length is ${d.total_km} km.</div>`;
  document.getElementById('dashBody').innerHTML=kpi+comp+lr+exp+shMdrTable+note;
  lrEnsure();
}
let distCache={};
function districtDetailHtml(){
  const mapIco='<svg width="34" height="34" viewBox="0 0 24 24" fill="none" stroke="#9fb4c8" stroke-width="1.5" stroke-linejoin="round"><path d="M9 3 3 5v16l6-2 6 2 6-2V3l-6 2-6-2Z"/><path d="M9 3v16M15 5v16"/></svg>';
  if(!selDistrict) return `<div class="detail-empty"><div class="empty-ico">${mapIco}</div><div>Select a district from the list to see its <b>PWD-section</b> and <b>owner</b> breakdown.</div></div>`;
  const dd=distCache[selDistrict];
  if(dd==='loading') return `<div class="detail-card"><div class="dash-loading">Loading ${escH(selDistrict)}…</div></div>`;
  if(!dd) return `<div class="detail-card"><div class="dash-loading">No data for ${escH(selDistrict)}.</div></div>`;
  return `<div class="detail-card">
    <div class="detail-head"><div><div class="detail-eyebrow">District</div><div class="detail-name">${escH(selDistrict)}</div></div>
      <div class="detail-total">${fmtKm(dd.total_km)}<span class="u">km</span></div></div>
    <div class="detail-sub">SH &amp; MDR count</div>
    <div class="sub" style="margin:0 0 10px">State Highways: <b>${dd.sh_total_count||0}</b> (${dd.sh_numbered_count||0} by Road Number${dd.sh_unnumbered_count?' + '+dd.sh_unnumbered_count+' by Road Name':''}) &nbsp;&middot;&nbsp; Major District Roads: <b>${dd.mdr_count||0}</b></div>
    <div class="detail-sub">PWD sections</div>${rankedBars(dd.by_pwd_sec,{})}
    <div class="detail-sub">Current owner</div>${rankedBars(dd.by_owner,{full:l=>dec('Current_Ow',l)})}
  </div>`;
}
function selectDistrict(name){
  selDistrict=name;
  if(distCache[name]&&distCache[name]!=='loading'){renderDashboard();return;}
  distCache[name]='loading';renderDashboard();
  fetch('/api/dashboard/district?name='+encodeURIComponent(name)).then(r=>r.json()).then(d=>{
    distCache[name]=d;
    if(selDistrict===name)renderDashboard();
  }).catch(e=>{distCache[name]={total_km:0,by_pwd_sec:[],by_owner:[]};if(selDistrict===name)renderDashboard();});
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
