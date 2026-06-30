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
  </div>`;
  const compClass=donutCard('Network by road class','Share of total length by classification',cls,{full:l=>CLASS_SHORT[l]||dec('Road_Class',l)});
  const ownTot=(d.by_owner||[]).reduce((s,r)=>s+(+r.km||0),0);
  const owners=`<div class="dcard"><div class="dcard-head"><h3>Network by current owner</h3><span class="totchip">${fmtKm(ownTot)} km</span></div><div class="sub">Length under each owning agency, ranked</div>${rankedBars(d.by_owner,{full:l=>dec('Current_Ow',l)})}</div>`;
  const comp=`<div class="comp-row">${compClass}${owners}</div>`;
  const distList=`<div class="dcard"><div class="dcard-head"><h3>Districts</h3><span class="totchip">${(d.by_district||[]).length} listed</span></div><div class="sub">Select a district to see its PWD sections &amp; owners</div>${rankedBars(d.by_district,{click:'selectDistrict',selName:selDistrict})}</div>`;
  const detail=`<div id="distDetail">${districtDetailHtml()}</div>`;
  const exp=`<div class="exp-row">${distList}${detail}</div>`;
  const note=`<div class="dash-note"><b>About these figures.</b> Lengths use the measured length of each road segment. A road is split into many segments wherever owner, PWD section, carriageway or lane type changes, so the dashboard reports <b>length only</b>, never road counts. <b>Dual carriageways</b> (Section labels …A / …B, Single_Du = Dual) are counted <b>once</b> using the <b>average</b> of the two measured lengths. Shapefile digital length is ${d.dig_km} km; corrected network length is ${d.total_km} km.</div>`;
  document.getElementById('dashBody').innerHTML=kpi+comp+exp+note;
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
