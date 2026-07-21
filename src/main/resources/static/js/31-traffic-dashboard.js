/* ============================================================
   KLRAMS viewer · 31-traffic-dashboard.js
   Traffic Dashboard tab — per survey period, state-wide and
   district-wise classified traffic-count figures:
     · KPIs (stations, total daily traffic, busiest station, peak hour)
     · Top-10 stations by ADT (Station / Section / Road / Class / No.)
     · Vehicle-class mix (donut) for the scope
     · 24-hour traffic profile with the network peak hour highlighted
   Backend: /api/traffic-dashboard/summary — one record per station
   with adt, by_class, by_hour, peak. Reuses .kpi / .dcard / .amx /
   .svy-* styling and the cDonutCard helper (20-asset-dashboard.js).
   Loaded as an ordered classic script from map.html.
   ============================================================ */

let tdbData=null;        // /api/traffic-dashboard/summary payload
let tdbPeriodId=null;    // selected period id
let tdbDistrict=null;    // selected district or null = all (state-wide)

function tdbCol(i){return (typeof DPAL!=='undefined'&&DPAL.length)?DPAL[i%DPAL.length]:'#d4a02e';}
function tdbClsCol(label,i){return (typeof CLASS_COL!=='undefined'&&CLASS_COL[label])||tdbCol(i);}

let tdbLoading=false;
function renderTrafficDash(){
  const body=document.getElementById('dashBody');
  if(tdbData){tdbPaint();return;}
  body.innerHTML='<div class="dash-loading">Loading traffic figures…</div>';
  if(tdbLoading)return;
  tdbLoading=true;
  fetch('/api/traffic-dashboard/summary').then(r=>{
    if(!r.ok)throw new Error('HTTP '+r.status);
    if(r.redirected||(r.headers.get('content-type')||'').indexOf('json')<0)throw new Error('SESSION');
    return r.json();
  }).then(d=>{
    tdbLoading=false;
    if(!d||!Array.isArray(d.periods))throw new Error('unexpected response');
    tdbData=d;
    if(!tdbPeriodId)tdbPeriodId=(d.default_period&&d.default_period.id)||(d.periods[0]&&d.periods[0].id);
    if(dashTabCur==='traffic')tdbPaint();
  }).catch(e=>{
    tdbLoading=false;tdbData=null;
    if(dashTabCur!=='traffic')return;
    body.innerHTML=e.message==='SESSION'
      ?'<div class="dash-loading">Your session has expired (the server was restarted). '+
       '<a href="/login.html" style="color:#15976a;font-weight:700">Sign in again</a></div>'
      :'<div class="dash-loading">Could not load traffic figures ('+escH(e.message)+'). '+
       '<a href="#" onclick="renderTrafficDash();return false" style="color:#15976a;font-weight:700">Retry</a></div>';
  });
}

function tdbPeriodObj(){
  const ps=(tdbData&&Array.isArray(tdbData.periods))?tdbData.periods:[];
  return ps.find(p=>p.id===tdbPeriodId)||ps[0]||null;
}
function tdbSetPeriod(id){tdbPeriodId=id;tdbDistrict=null;tdbPaint();}
function tdbSetDistrict(name){tdbDistrict=(tdbDistrict===name)?null:name;tdbPaint();}

/* stations in the current scope (all, or one district) */
function tdbScopeStations(p){
  const all=(p.stations||[]);
  return tdbDistrict?all.filter(s=>s.district===tdbDistrict):all;
}

/* districts present in the period, with station counts, for the chip row */
function tdbDistricts(p){
  const m={};
  (p.stations||[]).forEach(s=>{const d=s.district||'(unmapped)';m[d]=(m[d]||0)+1;});
  return Object.keys(m).map(k=>({district:k,n:m[k]})).sort((a,b)=>a.district.localeCompare(b.district));
}

/* aggregate 24-hour daily-average profile over a set of stations */
function tdbProfile(stns){
  const prof=new Array(24).fill(0);
  stns.forEach(s=>{const bh=s.by_hour||[],d=Math.max(1,+s.days||1);for(let i=0;i<24;i++)prof[i]+=(+bh[i]||0)/d;});
  return prof;
}

/* aggregate vehicle-class daily-average split over a set of stations */
function tdbClassRows(stns){
  const m={};
  stns.forEach(s=>{const bc=s.by_class||{},d=Math.max(1,+s.days||1);Object.keys(bc).forEach(k=>{m[k]=(m[k]||0)+(+bc[k]||0)/d;});});
  return Object.keys(m).map(k=>({label:k,n:Math.round(m[k])})).sort((a,b)=>b.n-a.n);
}

/* ---- 24-hour traffic profile chart (bars, peak hour highlighted) ---- */
function tdbHourChart(prof){
  let peakH=-1,peakV=0;prof.forEach((v,i)=>{if(v>peakV){peakV=v;peakH=i;}});
  const W=680,H=248,L=48,R=14,T=16,B=34,iw=W-L-R,ih=H-T-B;
  let ymax=Math.max.apply(null,prof.concat([1]));
  const nm=Math.pow(10,Math.floor(Math.log10(ymax)));
  ymax=Math.ceil(ymax/nm)*nm;
  let g='';
  for(let i=0;i<=4;i++){
    const t=ymax*i/4,y=T+ih-(i/4)*ih;
    g+='<line class="fdb-grid" x1="'+L+'" y1="'+y.toFixed(1)+'" x2="'+(W-R)+'" y2="'+y.toFixed(1)+'"/>'+
       '<text class="fdb-ax" x="'+(L-8)+'" y="'+(y+3.5).toFixed(1)+'" text-anchor="end">'+fmtN(t)+'</text>';
  }
  const slot=iw/24,bw=Math.max(3,slot*0.62);
  let bars='';
  for(let i=0;i<24;i++){
    const v=prof[i]||0,h=ymax>0?v/ymax*ih:0,x=L+i*slot+(slot-bw)/2,y=T+ih-h;
    const on=(i===peakH),col=on?'#c2603f':'#d4a02e';
    bars+='<rect x="'+x.toFixed(1)+'" y="'+y.toFixed(1)+'" width="'+bw.toFixed(1)+'" height="'+Math.max(h,0).toFixed(1)+'" rx="2" fill="'+col+'" opacity="'+(on?'1':'.85')+'">'+
      '<title>'+pad2(i)+':00–'+pad2((i+1)%24)+':00 · '+fmtN(v)+' veh/hr</title></rect>';
    if(i%3===0)g+='<text class="fdb-ax" x="'+(x+bw/2).toFixed(1)+'" y="'+(T+ih+16)+'" text-anchor="middle">'+pad2(i)+'</text>';
  }
  return '<div class="fdb-chart"><svg viewBox="0 0 '+W+' '+H+'" role="img">'+g+bars+
    '<text class="fdb-axt" transform="rotate(-90 14 '+(T+ih/2)+')" x="14" y="'+(T+ih/2)+'" text-anchor="middle">Veh / hour (avg day)</text>'+
    '<text class="fdb-axt" x="'+(L+iw/2)+'" y="'+(H-4)+'" text-anchor="middle">Hour of day</text></svg></div>';
}
function pad2(n){return (n<10?'0':'')+n;}

function tdbPaint(){
  const body=document.getElementById('dashBody');if(!body)return;
  const p=tdbPeriodObj();
  if(!p){body.innerHTML='<div class="dash-loading">No traffic data uploaded yet.</div>';return;}

  const pName=escH(p.name||'');
  const dists=tdbDistricts(p);
  const stns=tdbScopeStations(p);
  const scopeLbl=tdbDistrict?escH(tdbDistrict):'All districts';

  if(!(p.stations||[]).length){
    const pills=tdbPills(p,dists);
    body.innerHTML=pills+'<div class="dash-note">No traffic count stations carry data for '+pName+'. '+
      'Import the station and count CSVs for this survey period in the Data Console, then reopen this tab.</div>';
    return;
  }

  /* ---- KPIs ---- */
  const adtSum=stns.reduce((s,x)=>s+(+x.adt||0),0);
  const busiest=stns.slice().sort((a,b)=>(+b.adt||0)-(+a.adt||0))[0]||null;
  const prof=tdbProfile(stns);
  let peakH=-1,peakV=0;prof.forEach((v,i)=>{if(v>peakV){peakV=v;peakH=i;}});
  const peakT=peakH>=0?(pad2(peakH)+':00–'+pad2((peakH+1)%24)+':00'):'—';

  const kpi='<div class="kpi-row svy-kpis">'+
    tdbKpi('feature','#d4a02e','Count Stations',fmtN(stns.length),scopeLbl+' · '+pName,true)+
    tdbKpi('','#15976a','Total Daily Traffic',fmtN(adtSum),'Σ ADT across stations (veh/day)')+
    tdbKpi('','#2a5d9c','Busiest Station',busiest?fmtN(busiest.adt):'—',busiest?escH(busiest.name)+' · '+escH(busiest.road||''):'No counts')+
    tdbKpi('','#c2603f','Network Peak Hour',peakT,peakV?(fmtN(peakV)+' veh/hr on the busiest hour'):'—')+
    '</div>';

  /* ---- Top 10 by ADT ---- */
  const top=stns.slice().sort((a,b)=>(+b.adt||0)-(+a.adt||0)).slice(0,10);
  let trows='';
  top.forEach((s,i)=>{
    trows+='<tr>'+
      '<td class="n"><span class="rk'+(i<3?' medal':'')+'" style="display:inline-block;min-width:20px">'+String(i+1).padStart(2,'0')+'</span></td>'+
      '<td>'+escH(s.name||'')+'</td>'+
      '<td>'+escH(s.section||'—')+'</td>'+
      '<td>'+escH(s.road||'—')+'</td>'+
      '<td>'+escH(s.road_class||'—')+'</td>'+
      '<td>'+escH(s.road_num||'—')+'</td>'+
      '<td class="n"><b>'+fmtN(s.adt)+'</b></td>'+
      '<td class="n">'+(s.peak_v?fmtN(s.peak_v):'—')+'</td>'+
      '<td>'+escH(s.peak_t||'—')+'</td>'+
      '</tr>';
  });
  const topCard='<div class="dcard"><div class="dcard-head"><h3>Top '+top.length+' stations by ADT</h3>'+
    '<span class="totchip">'+scopeLbl+'</span></div>'+
    '<div class="sub">Highest average daily traffic (ADT = survey total ÷ survey days) in '+pName+
    ' — '+scopeLbl+'. Road name, class and number come from the matched PWD road section.</div>'+
    '<div class="amx-wrap"><table class="amx tdb-top"><thead>'+
    '<tr><th class="n">#</th><th>Station Name</th><th>Section Label</th><th>Road Name</th>'+
    '<th>Road Class</th><th>Road No.</th><th class="n">ADT</th><th class="n">Peak/hr</th><th>Peak time</th></tr>'+
    '</thead><tbody>'+(trows||'<tr><td colspan="9" class="sub">No stations with counts in this scope.</td></tr>')+
    '</tbody></table></div></div>';

  /* ---- vehicle-class mix (donut) ---- */
  const clsRows=tdbClassRows(stns);
  const mixCard=cDonutCard('Vehicle-class mix','Average daily vehicles by classification in '+pName+' — '+scopeLbl,
    clsRows,{colorFn:tdbClsCol,centerSmall:'veh/day'});

  /* ---- 24-hour profile ---- */
  const hourCard='<div class="dcard"><div class="dcard-head"><h3>24-hour traffic profile</h3>'+
    '<span class="totchip">Peak '+escH(peakT)+'</span></div>'+
    '<div class="sub">Average-day volume by hour, summed over '+fmtN(stns.length)+' station'+(stns.length===1?'':'s')+
    ' — the peak hour is highlighted</div>'+tdbHourChart(prof)+'</div>';

  /* ---- district ranking (state-wide only) ---- */
  let distCard='';
  if(!tdbDistrict&&dists.length>1){
    const drows=dists.map(d=>{
      const ds=(p.stations||[]).filter(x=>x.district===d.district);
      return {label:d.district,n:ds.reduce((s,x)=>s+(+x.adt||0),0)};
    });
    distCard='<div class="dcard"><div class="dcard-head"><h3>Total daily traffic by district</h3>'+
      '<span class="totchip">'+fmtN(adtSum)+' veh/day</span></div>'+
      '<div class="sub">Σ ADT of all count stations per district in '+pName+' — click a district chip above to focus</div>'+
      cBars(drows,{colorFn:(l,i)=>tdbCol(i)})+'</div>';
  }

  const note='<div class="dash-note"><b>About these figures.</b> <b>ADT</b> (Average Daily Traffic) is the surveyed total divided by the number of survey days. '+
    'Each dual-carriageway station stored as an A/B pair is merged back into one station. '+
    'Road Name, Class and Number are read from the PWD road section the station falls on (matched by Section Label); '+
    'stations whose section is not in the road network show under <b>(unmapped)</b>. '+
    'Every figure is scoped to the selected survey period and district.</div>';

  body.innerHTML=tdbPills(p,dists)+kpi+topCard+'<div class="comp-row">'+mixCard+hourCard+'</div>'+distCard+note;
}

function tdbKpi(extra,col,cap,val,sub,ring){
  return '<div class="kpi'+(extra?' '+extra:'')+'" style="--kc:'+col+'">'+(ring?'<div class="ringmark"></div>':'')+
    '<div class="kcap">'+cap+'</div>'+
    '<div class="kv">'+val+'</div>'+
    '<div class="kl">'+sub+'</div></div>';
}

function tdbPills(p,dists){
  return '<div class="svy-bar"><div class="svy-years">'+
    (tdbData.periods||[]).map(pp=>'<button type="button" class="svy-pill'+(pp.id===tdbPeriodId?' on':'')+'" title="'+escH(pp.range||'')+'" onclick="tdbSetPeriod('+(+pp.id)+')">'+
      '<span class="svy-pill-cap">Survey Period'+(pp.is_active?' · current':'')+'</span><span class="svy-pill-yr">'+escH(pp.name)+'</span></button>').join('')+
    '</div><div class="svy-dists"><button type="button" class="svy-chip'+(tdbDistrict?'':' on')+'" onclick="tdbDistrict=null;tdbPaint()">All Districts</button>'+
    dists.map(d=>'<button type="button" class="svy-chip'+(d.district===tdbDistrict?' on':'')+'" onclick="tdbSetDistrict(\''+qq(d.district)+'\')">'+escH(d.district)+' <span class="svy-chip-n">'+d.n+'</span></button>').join('')+
    '</div></div>';
}
