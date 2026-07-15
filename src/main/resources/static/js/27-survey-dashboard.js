/* ============================================================
   KLRAMS viewer · 27-survey-dashboard.js
   Survey Dashboard tab — year-wise (financial year) and
   district-wise volumes for every field survey stream:
   NSV condition (lane-km), FWD points, traffic stations,
   sub-grade soil tests and bituminous cores.
   Reuses .kpi / .dcard / .rbars / .donut / .amx styling and the
   count-chart helpers (cDonutCard, cBars) from 20-asset-dashboard.js.
   Loaded as an ordered classic script from map.html.
   ============================================================ */

let svyData=null;          // /api/survey-dashboard/summary payload
let svyYear=null;          // selected "2025-26" label
let svyDistrict=null;      // selected district or null = all

const SVY_MET=[
  {k:'nsv_lane_km',      name:'NSV Survey',          unit:'lane km', col:'#15976a',
   sub:'Network Survey Vehicle — road condition (IRI, cracks, potholes…) measured lane-by-lane'},
  {k:'fwd_points',       name:'FWD Points',          unit:'points',  col:'#2a5d9c',
   sub:'Falling Weight Deflectometer — structural strength test locations'},
  {k:'traffic_stations', name:'Traffic Stations',    unit:'stations',col:'#d4a02e',
   sub:'Classified traffic count stations'},
  {k:'subgrade_tests',   name:'Sub-Grade Soil Tests',unit:'tests',   col:'#6b4e9e',
   sub:'Soil sampling — CBR, density, moisture, gradation'},
  {k:'bituminous_cores', name:'Bituminous Cores',    unit:'cores',   col:'#c2603f',
   sub:'Pavement core cuts — layer thickness & density'}];

function svyFmt(k,v){return k==='nsv_lane_km'?fmtKm(v):fmtN(v);}

let svyLoading=false;
function renderSurveyDash(){
  const body=document.getElementById('dashBody');
  if(svyData){svyPaint();return;}
  body.innerHTML='<div class="dash-loading">Loading survey figures…</div>';
  if(svyLoading)return;
  svyLoading=true;
  fetch('/api/survey-dashboard/summary').then(r=>{
    if(!r.ok)throw new Error('HTTP '+r.status);
    /* a redirect to login.html means the session expired (e.g. server restart) */
    if(r.redirected||(r.headers.get('content-type')||'').indexOf('json')<0)throw new Error('SESSION');
    return r.json();
  }).then(d=>{
    svyLoading=false;
    if(!d||!Array.isArray(d.years))throw new Error('unexpected response');
    svyData=d;                       // only cache a payload of the right shape
    if(!svyYear)svyYear=d.default_year||(d.years[0]&&d.years[0].year);
    if(dashTabCur==='survey')svyPaint();
  }).catch(e=>{
    svyLoading=false;svyData=null;   // never poison the cache with an error body
    if(dashTabCur!=='survey')return;
    body.innerHTML=e.message==='SESSION'
      ?'<div class="dash-loading">Your session has expired (the server was restarted). '+
       '<a href="/login.html" style="color:#15976a;font-weight:700">Sign in again</a></div>'
      :'<div class="dash-loading">Could not load survey figures ('+escH(e.message)+'). '+
       '<a href="#" onclick="renderSurveyDash();return false" style="color:#15976a;font-weight:700">Retry</a></div>';
  });
}

function svyYearObj(){
  const ys=(svyData&&Array.isArray(svyData.years))?svyData.years:[];
  return ys.find(y=>y.year===svyYear)||ys[0]||null;
}
function svySetYear(y){svyYear=y;svyDistrict=null;svyPaint();}
function svySetDistrict(name){svyDistrict=(svyDistrict===name)?null:name;svyPaint();}

/* scope = selected district's metric map, or the year totals */
function svyScope(y){
  if(!svyDistrict)return y.totals||{};
  return (y.districts||[]).find(d=>d.district===svyDistrict)||{};
}

function svyPaint(){
  const body=document.getElementById('dashBody');if(!body)return;
  const y=svyYearObj();
  if(!y){
    body.innerHTML='<div class="dash-loading">No survey data uploaded yet.</div>';return;
  }
  const s=svyScope(y);
  const dists=y.districts||[];

  /* ---- controls: year pills + district chips ---- */
  const pills='<div class="svy-bar"><div class="svy-years">'+
    (svyData.years||[]).map(yy=>'<button type="button" class="svy-pill'+(yy.year===svyYear?' on':'')+'" onclick="svySetYear(\''+qq(yy.year)+'\')">'
      +'<span class="svy-pill-cap">Survey Year</span><span class="svy-pill-yr">'+escH(yy.year)+'</span></button>').join('')+
    '</div><div class="svy-dists"><button type="button" class="svy-chip'+(svyDistrict?'':' on')+'" onclick="svyDistrict=null;svyPaint()">All Districts</button>'+
    dists.map(d=>'<button type="button" class="svy-chip'+(d.district===svyDistrict?' on':'')+'" onclick="svySetDistrict(\''+qq(d.district)+'\')">'+escH(d.district)+'</button>').join('')+
    '</div></div>';

  /* ---- KPI cards ---- */
  const scopeLbl=svyDistrict?escH(svyDistrict):'All districts';
  const mTot=y.totals||{};
  const kpi='<div class="kpi-row svy-kpis">'+SVY_MET.map((m,i)=>{
    const v=+s[m.k]||0, tot=+mTot[m.k]||0;
    const pc=(svyDistrict&&tot)?Math.round(v/tot*100):null;
    return '<div class="kpi'+(i===0?' feature':'')+'" style="--kc:'+m.col+'">'+(i===0?'<div class="ringmark"></div>':'')+
      '<div class="kcap">'+m.name+'</div>'+
      '<div class="kv">'+svyFmt(m.k,v)+'<span class="u">'+m.unit+'</span></div>'+
      '<div class="kl">'+(pc!=null?scopeLbl+' · '+pc+'% of state total':m.sub)+'</div>'+
      (svyDistrict?'<div class="spark"><i style="width:'+(pc||0)+'%;background:'+m.col+'"></i><i style="width:'+(100-(pc||0))+'%;background:#e5ebf2"></i></div>':'')+
      '</div>';
  }).join('')+'</div>';

  /* ---- charts ---- */
  const nsvRows=dists.map(d=>({label:d.district,km:+d.nsv_lane_km||0}));
  const nsvCard='<div class="dcard"><div class="dcard-head"><h3>NSV condition survey by district</h3>'+
    '<span class="totchip">'+fmtKm(mTot.nsv_lane_km)+' lane km</span></div>'+
    '<div class="sub">Lane-km surveyed in '+escH(y.year)+' — click a district to focus the whole dashboard</div>'+
    rankedBars(nsvRows,{click:'svySetDistrict',selName:svyDistrict,colorFn:()=> '#15976a'})+'</div>';

  const trafficRows=dists.map(d=>({label:d.district,n:+d.traffic_stations||0}));
  const mixCard='<div class="dcard"><div class="dcard-head"><h3>Traffic stations by district</h3>'+
    '<span class="totchip">'+fmtN(mTot.traffic_stations)+'</span></div>'+
    '<div class="sub">Classified traffic count stations per district in '+escH(y.year)+'</div>'+
    cBars(trafficRows,{colorFn:()=> '#d4a02e'})+'</div>';

  const fwdRows=dists.map(d=>({label:d.district,n:+d.fwd_points||0}));
  const fwdCard='<div class="dcard"><div class="dcard-head"><h3>FWD points by district</h3>'+
    '<span class="totchip">'+fmtN(mTot.fwd_points)+'</span></div>'+
    '<div class="sub">Deflection test points per district in '+escH(y.year)+'</div>'+
    cBars(fwdRows,{colorFn:()=> '#2a5d9c'})+'</div>';

  /* ---- district × survey matrix ---- */
  let head='<tr><th>District \\ Survey</th>'+SVY_MET.map(m=>'<th class="n"><span class="amx-dot" style="background:'+m.col+'"></span>'+m.name+(m.k==='nsv_lane_km'?' (lane km)':'')+'</th>').join('')+'</tr>';
  let rows='';
  dists.forEach(d=>{
    rows+='<tr'+(d.district===svyDistrict?' class="svy-sel"':'')+' onclick="svySetDistrict(\''+qq(d.district)+'\')" style="cursor:pointer">'+
      '<td>'+escH(d.district)+'</td>'+
      SVY_MET.map(m=>{const v=+d[m.k]||0;return '<td class="n">'+(v?svyFmt(m.k,v):'<span class="z">·</span>')+'</td>';}).join('')+'</tr>';
  });
  rows+='<tr class="amx-tot"><td><b>Total</b></td>'+SVY_MET.map(m=>'<td class="n"><b>'+svyFmt(m.k,mTot[m.k])+'</b></td>').join('')+'</tr>';
  const matrix='<div class="dcard"><div class="dcard-head"><h3>District-wise survey coverage</h3>'+
    '<span class="totchip">'+dists.length+' district'+(dists.length===1?'':'s')+'</span></div>'+
    '<div class="sub">Every survey stream by district for '+escH(y.year)+' — click a row to focus</div>'+
    '<div class="amx-wrap"><table class="amx">'+head+rows+'</table></div></div>';

  const note='<div class="dash-note"><b>About these figures.</b> The survey year is the financial year (April–March) taken from each record’s survey date; '+
    'streams uploaded without a per-row date (NSV condition, traffic stations) are shown under the active survey cycle. '+
    '<b>NSV lane-km</b> is the sum of surveyed chainage over every lane strip (XSP), so a two-lane road surveyed end-to-end counts twice its length. '+
    'Point tests (FWD, soil, core) and stations are counted per record. Records whose section label is not in the road network appear as <b>(unmapped)</b>.</div>';

  body.innerHTML=pills+kpi+'<div class="comp-row">'+mixCard+nsvCard+'</div>'+fwdCard+matrix+note;
}
