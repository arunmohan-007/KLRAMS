/* ============================================================
   KLRAMS viewer · 28-fwd-dashboard.js
   FWD Dashboard tab — Falling Weight Deflectometer figures per
   survey period: point counts, D0 deflection statistics and the
   lower-to-higher D0 profile split by road class (SH / MDR),
   district-wise ranges, plus pavement & air temperature
   min / max / mean (overall and district-wise).
   Data: /api/fwd-dashboard/summary (FwdDashboardController).
   Reuses .kpi / .dcard / .rbars / .svy-* / .amx styling and the
   count-chart helpers (cDonutCard) from 20-asset-dashboard.js.
   Loaded as an ordered classic script from map.html.
   ============================================================ */

let fdbData=null;        // /api/fwd-dashboard/summary payload
let fdbPeriodId=null;    // selected period id
let fdbDistrict=null;    // selected district or null = all
let fdbSurface='all';    // 'all' | 'flexible' | 'rigid' | 'unknown' pavement type
let fdbLoading=false;
let fdbUnmap={};         // period id -> /api/fwd-dashboard/unmapped rows

const FDB_SURF_LBL={all:'All pavements',flexible:'Flexible (BT)',rigid:'Rigid (CC)',unknown:'Unclassified'};

/* window.__klRole is set by map.html's profile-chip script once /api/me
   resolves; 'kl-role-ready' fires at the same time so the Delete button can
   appear without a page reload if the FWD tab was opened before it resolved. */
function isSuperAdmin(){return window.__klRole==='SUPER_ADMIN';}
document.addEventListener('kl-role-ready',function(){if(dashTabCur==='fwd')fdbPaint();});

function fdbCol(cls,i){return (typeof CLASS_COL!=='undefined'&&CLASS_COL[cls])||DPAL[(i||0)%DPAL.length];}
function fdbClsFull(l){return (typeof CLASS_SHORT!=='undefined'&&CLASS_SHORT[l])||l;}

function renderFwdDash(){
  const body=document.getElementById('dashBody');
  if(fdbData){fdbPaint();return;}
  body.innerHTML='<div class="dash-loading">Loading FWD figures…</div>';
  if(fdbLoading)return;
  fdbLoading=true;
  fetch('/api/fwd-dashboard/summary').then(r=>{
    if(!r.ok)throw new Error('HTTP '+r.status);
    if(r.redirected||(r.headers.get('content-type')||'').indexOf('json')<0)throw new Error('SESSION');
    return r.json();
  }).then(d=>{
    fdbLoading=false;
    if(!d||!Array.isArray(d.periods))throw new Error('unexpected response');
    fdbData=d;
    if(!fdbPeriodId)fdbPeriodId=(d.default_period&&d.default_period.id)||(d.periods[0]&&d.periods[0].id);
    if(dashTabCur==='fwd')fdbPaint();
  }).catch(e=>{
    fdbLoading=false;fdbData=null;
    if(dashTabCur!=='fwd')return;
    body.innerHTML=e.message==='SESSION'
      ?'<div class="dash-loading">Your session has expired (the server was restarted). '+
       '<a href="/login.html" style="color:#15976a;font-weight:700">Sign in again</a></div>'
      :'<div class="dash-loading">Could not load FWD figures ('+escH(e.message)+'). '+
       '<a href="#" onclick="renderFwdDash();return false" style="color:#15976a;font-weight:700">Retry</a></div>';
  });
}

function fdbPeriodObj(){
  const ps=(fdbData&&Array.isArray(fdbData.periods))?fdbData.periods:[];
  return ps.find(p=>p.id===fdbPeriodId)||ps[0]||null;
}
function fdbSetPeriod(id){fdbPeriodId=id;fdbDistrict=null;fdbSurface='all';fdbPaint();}
function fdbSetDistrict(name){fdbDistrict=(fdbDistrict===name)?null:name;fdbPaint();}
function fdbSetSurface(s){fdbSurface=(fdbSurface===s)?'all':s;fdbDistrict=null;fdbPaint();}

/* the selected pavement-type variant of the period (own stats, edges, districts) */
function fdbVariant(p){return (p.variants&&(p.variants[fdbSurface]||p.variants.all))||p;}

/* scope = selected district's entry, or the whole period */
function fdbScope(p){
  if(!fdbDistrict)return p;
  return (p.districts||[]).find(d=>d.district===fdbDistrict)||p;
}

/* D0 values in this data are millimetres (≈0.3 mm); older uploads may be
   microns (≈300) — pick the unit from the period-wide maximum. */
function fdbUnit(p){return (p.d0&&p.d0.max>10)?'μm':'mm';}
function fdbF(v,unit){if(v==null||isNaN(v))return'—';return unit==='mm'?(+v).toFixed(3):String(Math.round(+v));}
function fdbFt(v,unit){if(v==null||isNaN(v))return'—';return unit==='mm'?(+v).toFixed(2):String(Math.round(+v));}

/* nice ceiling for an axis maximum */
function fdbNiceMax(v){
  if(!(v>0))return 1;
  const mag=Math.pow(10,Math.floor(Math.log10(v)));
  const c=[1,1.2,1.5,2,2.5,3,4,5,6,8,10];
  for(let i=0;i<c.length;i++){if(mag*c[i]>=v-1e-9)return mag*c[i];}
  return mag*10;
}

/* ---- chart: D0 sorted lower → higher, one curve per road class ---- */
function fdbProfileChart(scope,unit){
  const prof=scope.profile||{};
  const keys=Object.keys(prof).filter(k=>Array.isArray(prof[k])&&prof[k].length);
  if(!keys.length)return '<div class="sub" style="padding:10px 0 4px">No D0 values in this scope.</div>';
  const W=680,H=286,L=58,R=16,T=14,B=46,iw=W-L-R,ih=H-T-B;
  let ymax=0;keys.forEach(k=>prof[k].forEach(v=>{if(+v>ymax)ymax=+v;}));
  ymax=fdbNiceMax(ymax);
  let g='';
  for(let i=0;i<=5;i++){
    const t=ymax*i/5,y=T+ih-(i/5)*ih;
    g+='<line class="fdb-grid" x1="'+L+'" y1="'+y.toFixed(1)+'" x2="'+(W-R)+'" y2="'+y.toFixed(1)+'"/>'+
       '<text class="fdb-ax" x="'+(L-8)+'" y="'+(y+3.5).toFixed(1)+'" text-anchor="end">'+fdbFt(t,unit)+'</text>';
  }
  [0,25,50,75,100].forEach(pc=>{
    const x=L+pc/100*iw;
    g+='<line class="fdb-grid v" x1="'+x.toFixed(1)+'" y1="'+T+'" x2="'+x.toFixed(1)+'" y2="'+(T+ih)+'"/>'+
       '<text class="fdb-ax" x="'+x.toFixed(1)+'" y="'+(T+ih+16)+'" text-anchor="middle">'+pc+'%</text>';
  });
  let series='';
  keys.forEach((k,i)=>{
    const arr=prof[k],col=fdbCol(k,i),n=arr.length;
    const pts=arr.map((v,j)=>{
      const x=L+(n===1?0.5:j/(n-1))*iw,y=T+ih-Math.min(+v/ymax,1)*ih;
      return x.toFixed(1)+' '+y.toFixed(1);
    });
    series+='<path d="M '+L+' '+(T+ih)+' L '+pts.join(' L ')+' L '+(W-R)+' '+(T+ih)+' Z" fill="'+col+'" opacity=".08"/>'+
      '<path d="M '+pts.join(' L ')+'" fill="none" stroke="'+col+'" stroke-width="2.6" stroke-linejoin="round" stroke-linecap="round"/>';
    /* median marker */
    const mj=Math.floor((n-1)/2),mx=L+(n===1?0.5:mj/(n-1))*iw,my=T+ih-Math.min(+arr[mj]/ymax,1)*ih;
    series+='<circle cx="'+mx.toFixed(1)+'" cy="'+my.toFixed(1)+'" r="4" fill="'+col+'" stroke="#fff" stroke-width="1.6"><title>'+escH(k)+' median '+fdbF(arr[mj],unit)+' '+unit+'</title></circle>';
  });
  const legend='<div class="fdb-legend">'+keys.map((k,i)=>{
    const st=((scope.classes||[]).find(c=>c.cls===k)||{}).d0;
    return '<span class="fdb-leg"><i style="background:'+fdbCol(k,i)+'"></i>'+escH(fdbClsFull(k))+
      (st?'<b>'+fdbF(st.mean,unit)+' '+unit+' mean</b>':'')+'</span>';
  }).join('')+'</div>';
  return legend+'<div class="fdb-chart"><svg viewBox="0 0 '+W+' '+H+'" role="img">'+g+series+
    '<text class="fdb-axt" transform="rotate(-90 14 '+(T+ih/2)+')" x="14" y="'+(T+ih/2)+'" text-anchor="middle">D0 deflection ('+unit+')</text>'+
    '<text class="fdb-axt" x="'+(L+iw/2)+'" y="'+(H-6)+'" text-anchor="middle">Test points ranked lower → higher (share of points)</text></svg></div>';
}

/* ---- chart: D0 histogram, grouped bars per class ---- */
function fdbHistChart(scope,edges,unit){
  const hist=scope.hist||{};
  const keys=Object.keys(hist).filter(k=>Array.isArray(hist[k])&&hist[k].some(v=>v>0));
  if(!keys.length||!Array.isArray(edges)||edges.length<2)
    return '<div class="sub" style="padding:10px 0 4px">No D0 values in this scope.</div>';
  const bins=edges.length-1;
  const W=680,H=286,L=44,R=12,T=14,B=46,iw=W-L-R,ih=H-T-B;
  let cmax=0;keys.forEach(k=>hist[k].forEach(v=>{if(v>cmax)cmax=v;}));
  cmax=Math.max(1,Math.ceil(fdbNiceMax(cmax)));
  let g='';
  for(let i=0;i<=4;i++){
    const t=Math.round(cmax*i/4),y=T+ih-(i/4)*ih;
    g+='<line class="fdb-grid" x1="'+L+'" y1="'+y.toFixed(1)+'" x2="'+(W-R)+'" y2="'+y.toFixed(1)+'"/>'+
       '<text class="fdb-ax" x="'+(L-8)+'" y="'+(y+3.5).toFixed(1)+'" text-anchor="end">'+t+'</text>';
  }
  const lblEvery=Math.max(1,Math.ceil(bins/7));
  for(let b=0;b<=bins;b++){
    const x=L+b/bins*iw;
    if(b%lblEvery===0||b===bins)
      g+='<text class="fdb-ax" x="'+x.toFixed(1)+'" y="'+(T+ih+16)+'" text-anchor="middle">'+fdbFt(edges[b],unit)+'</text>';
  }
  let bars='';
  const slot=iw/bins,pad=Math.min(6,slot*.18),bw=(slot-2*pad)/keys.length;
  for(let b=0;b<bins;b++){
    keys.forEach((k,i)=>{
      const v=hist[k][b]||0;if(!v)return;
      const x=L+b*slot+pad+i*bw,h=v/cmax*ih,y=T+ih-h;
      bars+='<rect x="'+x.toFixed(1)+'" y="'+y.toFixed(1)+'" width="'+Math.max(bw-1,1).toFixed(1)+'" height="'+h.toFixed(1)+'" rx="2" fill="'+fdbCol(k,i)+'" opacity=".88">'+
        '<title>'+escH(k)+' · '+fdbFt(edges[b],unit)+'–'+fdbFt(edges[b+1],unit)+' '+unit+' : '+v+' points</title></rect>';
    });
  }
  const legend='<div class="fdb-legend">'+keys.map((k,i)=>'<span class="fdb-leg"><i style="background:'+fdbCol(k,i)+'"></i>'+escH(fdbClsFull(k))+'</span>').join('')+'</div>';
  return legend+'<div class="fdb-chart"><svg viewBox="0 0 '+W+' '+H+'" role="img">'+g+bars+
    '<text class="fdb-axt" transform="rotate(-90 14 '+(T+ih/2)+')" x="14" y="'+(T+ih/2)+'" text-anchor="middle">Number of test points</text>'+
    '<text class="fdb-axt" x="'+(L+iw/2)+'" y="'+(H-6)+'" text-anchor="middle">D0 deflection ('+unit+')</text></svg></div>';
}

/* ---- district ranked bars (count based, clickable) ---- */
function fdbBars(rows){
  if(!rows||!rows.length)return '<div class="sub" style="padding:6px 0 2px">No data available.</div>';
  const sorted=rows.slice().sort((x,y)=>(+y.n||0)-(+x.n||0));
  const max=Math.max.apply(null,sorted.map(r=>+r.n||0).concat([0.0001]));
  return '<div class="rbars">'+sorted.map((r,i)=>{
    const n=+r.n||0,pct=Math.max(3,n/max*100),sel=r.label===fdbDistrict;
    return '<div class="rbar click'+(sel?' sel':'')+'" onclick="fdbSetDistrict(\''+qq(r.label)+'\')">'+
      '<div class="rk'+(i<3?' medal':'')+'">'+String(i+1).padStart(2,'0')+'</div>'+
      '<div><div class="rb-top"><span class="rb-nm" title="'+escH(r.label)+'">'+escH(r.label)+'</span><span class="rb-vl">'+fmtN(n)+'</span></div>'+
      '<div class="rb-track"><div class="rb-fill" style="width:'+pct.toFixed(1)+'%;background:linear-gradient(90deg,#3f9aa3,#3f9aa3cc)"></div></div></div></div>';
  }).join('')+'</div>';
}

/* ---- district-wise D0 range rows (min ─ mean ● ─ max dumbbells) ---- */
function fdbD0Districts(p,unit){
  const dists=(p.districts||[]).filter(d=>d.d0);
  if(!dists.length)return '<div class="sub" style="padding:6px 0 2px">No D0 values yet.</div>';
  const gmin=p.d0?p.d0.min:0,gmax=p.d0?p.d0.max:1,span=(gmax-gmin)||1;
  const rows=[];
  dists.slice().sort((a,b)=>(a.d0.mean||0)-(b.d0.mean||0)).forEach(d=>{
    (d.classes||[]).forEach((c,i)=>{
      if(!c.d0)return;
      const col=fdbCol(c.cls,i),st=c.d0;
      const lo=((st.min-gmin)/span*100),wd=Math.max((st.max-st.min)/span*100,1.2),dot=((st.mean-gmin)/span*100);
      rows.push('<div class="fdb-dumb'+(d.district===fdbDistrict?' sel':'')+'" onclick="fdbSetDistrict(\''+qq(d.district)+'\')" title="'+escH(d.district)+' · '+escH(c.cls)+' — '+c.points+' points">'+
        '<div class="fdb-dumb-lab"><span class="fdb-cls-chip" style="background:'+col+'">'+escH(c.cls)+'</span><span class="fdb-dumb-nm">'+escH(d.district)+'</span></div>'+
        '<div class="fdb-track"><i class="fdb-band" style="left:'+lo.toFixed(1)+'%;width:'+wd.toFixed(1)+'%;background:'+col+'"></i>'+
        '<i class="fdb-dot" style="left:'+dot.toFixed(1)+'%;background:'+col+'"></i></div>'+
        '<div class="fdb-dumb-vals">'+fdbF(st.min,unit)+' · <b>'+fdbF(st.mean,unit)+'</b> · '+fdbF(st.max,unit)+'</div></div>');
    });
  });
  return rows.join('')+
    '<div class="fdb-scale"><span>'+fdbF(gmin,unit)+' '+unit+'</span><span>min · <b>mean</b> · max — scale spans the period range</span><span>'+fdbF(gmax,unit)+' '+unit+'</span></div>';
}

/* ---- temperature panels + district table ---- */
function fdbTempPanel(name,t,col){
  if(!t)return '<div class="fdb-temp-panel"><div class="fdb-temp-name">'+name+'</div><div class="fdb-temp-none">No data</div></div>';
  const lo=Math.floor(Math.min(t.min,t.mean))-2,hi=Math.ceil(Math.max(t.max,t.mean))+2,sp=(hi-lo)||1;
  const l=((t.min-lo)/sp*100),w=Math.max((t.max-t.min)/sp*100,1.5),d=((t.mean-lo)/sp*100);
  return '<div class="fdb-temp-panel" style="--tc:'+col+'">'+
    '<div class="fdb-temp-name">'+name+'</div>'+
    '<div class="fdb-temp-big">'+t.mean.toFixed(1)+'<span class="u">°C mean</span></div>'+
    '<div class="fdb-track"><i class="fdb-band" style="left:'+l.toFixed(1)+'%;width:'+w.toFixed(1)+'%;background:'+col+'"></i>'+
    '<i class="fdb-dot" style="left:'+d.toFixed(1)+'%;background:'+col+'"></i></div>'+
    '<div class="fdb-temp-mm"><span>min '+t.min.toFixed(1)+' °C</span><span>max '+t.max.toFixed(1)+' °C</span></div>'+
    '<div class="fdb-temp-n">'+t.n+' readings</div></div>';
}
function fdbTempCard(p){
  const s=fdbScope(p);
  const pav=s.temps&&s.temps.pavement,air=s.temps&&s.temps.air;
  const scopeLbl=fdbDistrict?escH(fdbDistrict):'all districts';
  if(!pav&&!air){
    return '<div class="dcard"><div class="dcard-head"><h3>Pavement &amp; air temperature</h3></div>'+
      '<div class="fdb-empty"><b>No temperature columns in this period’s FWD upload.</b><br>'+
      'Add <b>Pavement Temperature</b> and <b>Air Temperature</b> columns to the FWD import file '+
      '(any spelling containing “pavement/surface temp” and “air temp” works) and re-import — '+
      'the min / max / mean figures and the district-wise table will appear here automatically.</div></div>';
  }
  const panels='<div class="fdb-temp-grid">'+
    fdbTempPanel('Pavement temperature',pav,'#c2603f')+
    fdbTempPanel('Air temperature',air,'#3f9aa3')+'</div>';
  const dists=(p.districts||[]).filter(d=>d.temps&&(d.temps.pavement||d.temps.air));
  let table='';
  if(dists.length){
    let head='<tr><th rowspan="2">District</th><th class="n" colspan="3"><span class="amx-dot" style="background:#c2603f"></span>Pavement °C</th>'+
      '<th class="n" colspan="3"><span class="amx-dot" style="background:#3f9aa3"></span>Air °C</th></tr>'+
      '<tr><th class="n">min</th><th class="n">mean</th><th class="n">max</th><th class="n">min</th><th class="n">mean</th><th class="n">max</th></tr>';
    let rows='';
    dists.forEach(d=>{
      const tp=d.temps.pavement,ta=d.temps.air;
      const c=t=>t==null?'<span class="z">·</span>':t.toFixed(1);
      rows+='<tr'+(d.district===fdbDistrict?' class="svy-sel"':'')+' onclick="fdbSetDistrict(\''+qq(d.district)+'\')" style="cursor:pointer"><td>'+escH(d.district)+'</td>'+
        '<td class="n">'+c(tp&&tp.min)+'</td><td class="n"><b>'+c(tp&&tp.mean)+'</b></td><td class="n">'+c(tp&&tp.max)+'</td>'+
        '<td class="n">'+c(ta&&ta.min)+'</td><td class="n"><b>'+c(ta&&ta.mean)+'</b></td><td class="n">'+c(ta&&ta.max)+'</td></tr>';
    });
    table='<div class="amx-wrap" style="margin-top:14px"><table class="amx">'+head+rows+'</table></div>';
  }
  return '<div class="dcard"><div class="dcard-head"><h3>Pavement &amp; air temperature</h3>'+
    '<span class="totchip">'+scopeLbl+'</span></div>'+
    '<div class="sub">Temperatures recorded at each FWD drop — mean with min–max band, then district-wise. D0 is temperature-sensitive, so these accompany every deflection reading.</div>'+
    panels+table+'</div>';
}

/* ---- flexible vs rigid comparison card ---- */
function fdbSurfCard(p){
  const mix=p.surface_mix||{};
  if(!(+mix.flexible||+mix.rigid))return '';   // no pavement-type info in this period
  const rows=['flexible','rigid','unknown'].map(k=>{
    const v=p.variants&&p.variants[k];if(!v)return '';
    const s=fdbScope(v),st=s.d0,u=fdbUnit(v);
    return '<tr'+(fdbSurface===k?' class="svy-sel"':'')+' onclick="fdbSetSurface(\''+k+'\')" style="cursor:pointer">'+
      '<td><b>'+FDB_SURF_LBL[k]+'</b></td><td class="n"><b>'+fmtN(s.points||0)+'</b></td>'+
      (st?'<td class="n">'+fdbF(st.min,u)+'</td><td class="n"><b>'+fdbF(st.mean,u)+'</b></td>'+
          '<td class="n">'+fdbF(st.p50,u)+'</td><td class="n">'+fdbF(st.p90,u)+'</td>'+
          '<td class="n">'+fdbF(st.max,u)+'</td><td class="n">'+u+'</td>'
         :'<td class="n" colspan="6"><span class="z">no D0 values</span></td>')+'</tr>';
  }).join('');
  return '<div class="dcard"><div class="dcard-head"><h3>Flexible vs rigid pavement</h3>'+
    '<span class="totchip">'+(fdbDistrict?escH(fdbDistrict):'all districts')+'</span></div>'+
    '<div class="sub">Rigid (CC / PQC) slabs deflect far less than flexible (BT) pavement, so their deflection figures are '+
    'kept separate — each type has its own statistics, profile scale and histogram bins. Click a row (or use the pavement '+
    'chips above) to switch the whole dashboard to that type.</div>'+
    '<div class="amx-wrap"><table class="amx"><tr><th>Pavement</th><th class="n">Points</th><th class="n">D0 min</th>'+
    '<th class="n">mean</th><th class="n">median</th><th class="n">90th</th><th class="n">max</th><th class="n">unit</th></tr>'+
    rows+'</table></div></div>';
}

/* ---- unmapped points card — the rows behind OTHER / (unmapped) ---- */
function fdbUnmappedCard(p){
  const all=(p.variants&&p.variants.all)||p;
  const oth=((all.classes||[]).find(c=>c.cls==='OTHER')||{}).points||0;
  if(!oth)return '';
  const rows=fdbUnmap[p.id];
  let inner;
  if(!rows){
    inner='<div class="sub" style="padding:8px 0">Loading the point list…</div>';
    fetch('/api/fwd-dashboard/unmapped?period_id='+(+p.id)).then(r=>{
      if(!r.ok)throw new Error('HTTP '+r.status);
      return r.json();
    }).then(d=>{
      fdbUnmap[p.id]=Array.isArray(d)?d:[];
      if(dashTabCur==='fwd')fdbPaint();
    }).catch(()=>{
      fdbUnmap[p.id]=[];
      if(dashTabCur==='fwd')fdbPaint();
    });
  }else if(!rows.length){
    inner='<div class="sub" style="padding:8px 0">Could not load the point list — reload the dashboard to retry.</div>';
  }else{
    const reasons={no_road:'Section label not in road network',blank_class:'Road has no Road_Class value'};
    const ch=v=>v==null?'·':(+v).toFixed(3);
    const noRoad=rows.filter(r=>r.reason==='no_road').length;
    let body='';
    rows.forEach((r,i)=>{
      body+='<tr><td class="n">'+(i+1)+'</td>'+
        '<td><b>'+escH(r.section_label||'')+'</b></td>'+
        '<td class="n">'+ch(r.start_chainage)+(r.end_chainage!=null?' – '+ch(r.end_chainage):'')+'</td>'+
        '<td class="n">'+(r.lat!=null?(+r.lat).toFixed(5)+', '+(+r.lng).toFixed(5):'<span class="z">·</span>')+'</td>'+
        '<td>'+(reasons[r.reason]||escH(r.reason||''))+'</td>'+
        '<td>'+(r.suggestion?'<b>'+escH(r.suggestion)+'</b>':'<span class="z">·</span>')+'</td></tr>';
    });
    const delBtn=(noRoad&&isSuperAdmin())?'<button type="button" class="btn danger" style="margin-top:10px" onclick="fdbDeleteOrphans('+(+p.id)+')">'+
      'Delete '+noRoad+' unmatched point'+(noRoad===1?'':'s')+'</button>':'';
    inner='<div class="amx-wrap"><table class="amx">'+
      '<tr><th class="n">#</th><th>Section label (as imported)</th><th class="n">Chainage (km)</th>'+
      '<th class="n">GPS (lat, lng)</th><th>Why unmapped</th><th>Suggested section</th></tr>'+body+'</table></div>'+
      '<div class="sub" style="margin-top:10px">To fix: correct the <b>Section_Label</b> in the FWD Excel to the exact '+
      'road-network label (a suggestion appears when only case / spacing differs) and re-import the file for this '+
      'period in the Data Console — re-uploading replaces the affected sections. If the section is genuinely missing '+
      'from the network, add it to the road network first; if the road exists but has no class, fill in its Road_Class.'+
      (noRoad?' Import now places points strictly by Section_Label + chainage (no GPS fallback), so any row still '+
       'showing "Section label not in road network" is either leftover from before this fix or a genuine data error — '+
       'once you\'ve corrected and re-imported the real data, a Super Admin can permanently delete the leftovers below '+
       '(or from Site Control → Data Cleanup).':'')+
      '</div>'+delBtn;
  }
  return '<div class="dcard"><div class="dcard-head"><h3>Points not mapped to district &amp; road class</h3>'+
    '<span class="totchip">'+fmtN(oth)+'</span></div>'+
    '<div class="sub">These points either don’t match any road section, or matched a road with no Road_Class set — '+
    'so they count as <b>OTHER</b> / <b>(unmapped)</b> in every figure above.</div>'+
    inner+'</div>';
}

/* Permanently deletes FWD rows whose Section_Label matches no road, for one
   period — the SUPER_ADMIN-only DELETE /api/assets/fwd/orphans endpoint. Rows
   with a matched road but blank Road_Class are never touched by this (that's a
   road-data gap, not a bad import); it only removes what the table above
   already lists with reason "no_road". */
function fdbDeleteOrphans(pid){
  const rows=fdbUnmap[pid]||[];
  const n=rows.filter(r=>r.reason==='no_road').length;
  if(!n)return;
  if(!confirm('Permanently delete '+n+' FWD point'+(n===1?'':'s')+' whose Section_Label matches no road?\n\n'+
    'This cannot be undone. Only do this after confirming the section really is wrong (not just a road missing from '+
    'the network) — otherwise correct the label in the source file and re-import instead.'))return;
  fetch('/api/assets/fwd/orphans?periodId='+(+pid),{method:'DELETE'}).then(r=>{
    if(!r.ok)throw new Error('HTTP '+r.status);
    return r.json();
  }).then(d=>{
    delete fdbUnmap[pid];
    fdbData=null;             // counts changed — force a full re-fetch of the summary
    if(dashTabCur==='fwd')renderFwdDash();
  }).catch(e=>alert('Delete failed: '+e.message));
}

/* ---- district × metrics matrix ---- */
function fdbMatrix(p,unit){
  const dists=p.districts||[];
  if(!dists.length)return '';
  const cls=['SH','MDR'];
  const clsN=(d,c)=>{const e=(d.classes||[]).find(x=>x.cls===c);return e?e.points:0;};
  let head='<tr><th>District</th><th class="n">Points</th>'+
    cls.map((c,i)=>'<th class="n"><span class="amx-dot" style="background:'+fdbCol(c,i)+'"></span>'+c+'</th>').join('')+
    '<th class="n">D0 min</th><th class="n">D0 mean</th><th class="n">D0 max</th><th class="n">Pav °C</th><th class="n">Air °C</th></tr>';
  let rows='';
  dists.forEach(d=>{
    const tp=d.temps&&d.temps.pavement,ta=d.temps&&d.temps.air;
    rows+='<tr'+(d.district===fdbDistrict?' class="svy-sel"':'')+' onclick="fdbSetDistrict(\''+qq(d.district)+'\')" style="cursor:pointer">'+
      '<td>'+escH(d.district)+'</td><td class="n"><b>'+fmtN(d.points)+'</b></td>'+
      cls.map(c=>{const v=clsN(d,c);return '<td class="n">'+(v?fmtN(v):'<span class="z">·</span>')+'</td>';}).join('')+
      '<td class="n">'+(d.d0?fdbF(d.d0.min,unit):'<span class="z">·</span>')+'</td>'+
      '<td class="n"><b>'+(d.d0?fdbF(d.d0.mean,unit):'<span class="z">·</span>')+'</b></td>'+
      '<td class="n">'+(d.d0?fdbF(d.d0.max,unit):'<span class="z">·</span>')+'</td>'+
      '<td class="n">'+(tp?tp.mean.toFixed(1):'<span class="z">·</span>')+'</td>'+
      '<td class="n">'+(ta?ta.mean.toFixed(1):'<span class="z">·</span>')+'</td></tr>';
  });
  const t=p;
  rows+='<tr class="amx-tot"><td><b>Total / overall</b></td><td class="n"><b>'+fmtN(t.points)+'</b></td>'+
    cls.map(c=>{const e=(t.classes||[]).find(x=>x.cls===c);return '<td class="n"><b>'+(e?fmtN(e.points):0)+'</b></td>';}).join('')+
    '<td class="n"><b>'+(t.d0?fdbF(t.d0.min,unit):'—')+'</b></td><td class="n"><b>'+(t.d0?fdbF(t.d0.mean,unit):'—')+'</b></td>'+
    '<td class="n"><b>'+(t.d0?fdbF(t.d0.max,unit):'—')+'</b></td>'+
    '<td class="n"><b>'+((t.temps&&t.temps.pavement)?t.temps.pavement.mean.toFixed(1):'—')+'</b></td>'+
    '<td class="n"><b>'+((t.temps&&t.temps.air)?t.temps.air.mean.toFixed(1):'—')+'</b></td></tr>';
  return '<div class="dcard"><div class="dcard-head"><h3>District-wise FWD summary</h3>'+
    '<span class="totchip">'+dists.length+' district'+(dists.length===1?'':'s')+'</span></div>'+
    '<div class="sub">Points by road class, D0 statistics ('+unit+') and mean temperatures — click a row to focus the dashboard</div>'+
    '<div class="amx-wrap"><table class="amx">'+head+rows+'</table></div></div>';
}

function fdbPaint(){
  const body=document.getElementById('dashBody');if(!body)return;
  const p=fdbPeriodObj();
  if(!p){body.innerHTML='<div class="dash-loading">No FWD survey data uploaded yet.</div>';return;}
  const v=fdbVariant(p);
  const unit=fdbUnit(v);
  const s=fdbScope(v);
  const dists=v.districts||[];
  const pName=escH(p.name||'');

  /* ---- controls: period pills + pavement-type chips + district chips ---- */
  const mix=p.surface_mix||{};
  const surfChips=(+mix.flexible||+mix.rigid)
    ?'<div class="svy-dists">'+['all','flexible','rigid','unknown'].map(k=>{
        if(k==='all')return '<button type="button" class="svy-chip'+(fdbSurface==='all'?' on':'')+'" onclick="fdbSetSurface(\'all\')">'+FDB_SURF_LBL.all+'</button>';
        const n=+mix[k]||0;
        if(!n)return k==='unknown'?'':'<span class="svy-chip" style="opacity:.45;cursor:default" title="No '+FDB_SURF_LBL[k]+' points in this period">'+FDB_SURF_LBL[k]+' · none</span>';
        return '<button type="button" class="svy-chip'+(fdbSurface===k?' on':'')+'" onclick="fdbSetSurface(\''+k+'\')">'+FDB_SURF_LBL[k]+' · '+fmtN(n)+'</button>';
      }).join('')+'</div>'
    :'';
  const pills='<div class="svy-bar"><div class="svy-years">'+
    (fdbData.periods||[]).map(pp=>'<button type="button" class="svy-pill'+(pp.id===fdbPeriodId?' on':'')+'" title="'+escH(pp.range||'')+'" onclick="fdbSetPeriod('+(+pp.id)+')">'+
      '<span class="svy-pill-cap">Survey Period'+(pp.is_active?' · current':'')+'</span><span class="svy-pill-yr">'+escH(pp.name)+'</span></button>').join('')+
    '</div>'+surfChips+'<div class="svy-dists"><button type="button" class="svy-chip'+(fdbDistrict?'':' on')+'" onclick="fdbDistrict=null;fdbPaint()">All Districts</button>'+
    dists.map(d=>'<button type="button" class="svy-chip'+(d.district===fdbDistrict?' on':'')+'" onclick="fdbSetDistrict(\''+qq(d.district)+'\')">'+escH(d.district)+'</button>').join('')+
    '</div></div>';

  if(!p.points){
    body.innerHTML=pills+'<div class="dash-loading">No FWD points imported for '+pName+' yet — upload the FWD survey in the Data Console.</div>';
    return;
  }

  /* ---- KPI cards ---- */
  const surfLbl=fdbSurface!=='all'?FDB_SURF_LBL[fdbSurface]:null;
  const scopeLbl=(surfLbl?surfLbl+' · ':'')+(fdbDistrict?escH(fdbDistrict):'All districts');
  const d0=s.d0,tp=s.temps&&s.temps.pavement,ta=s.temps&&s.temps.air;
  const pcTot=(fdbDistrict&&v.points)?Math.round((s.points||0)/v.points*100):null;
  const kpi='<div class="kpi-row svy-kpis">'+
    '<div class="kpi feature"><div class="ringmark"></div><div class="kcap">FWD test points</div>'+
    '<div class="kv">'+fmtN(s.points||0)+'<span class="u">points</span></div>'+
    '<div class="kl">'+(pcTot!=null?scopeLbl+' · '+pcTot+'% of '+(surfLbl?surfLbl:'period')+' total'
      :(surfLbl?surfLbl+' drops in '+pName:'Falling Weight Deflectometer drops in '+pName))+'</div></div>'+
    '<div class="kpi" style="--kc:#2a5d9c"><div class="kcap">Mean D0</div>'+
    '<div class="kv">'+(d0?fdbF(d0.mean,unit):'—')+'<span class="u">'+unit+'</span></div>'+
    '<div class="kl">Median '+(d0?fdbF(d0.p50,unit):'—')+' · characteristic (90th) '+(d0?fdbF(d0.p90,unit):'—')+'</div></div>'+
    '<div class="kpi" style="--kc:#15976a"><div class="kcap">D0 range</div>'+
    '<div class="kv">'+(d0?fdbF(d0.min,unit):'—')+'–'+(d0?fdbF(d0.max,unit):'—')+'<span class="u">'+unit+'</span></div>'+
    '<div class="kl">Lowest (strongest) to highest (weakest) deflection</div></div>'+
    '<div class="kpi" style="--kc:#c2603f"><div class="kcap">Pavement temp</div>'+
    '<div class="kv">'+(tp?tp.mean.toFixed(1):'—')+'<span class="u">°C</span></div>'+
    '<div class="kl">'+(tp?('min '+tp.min.toFixed(1)+' · max '+tp.max.toFixed(1)+' °C'):'Not in this upload')+'</div></div>'+
    '<div class="kpi" style="--kc:#3f9aa3"><div class="kcap">Air temp</div>'+
    '<div class="kv">'+(ta?ta.mean.toFixed(1):'—')+'<span class="u">°C</span></div>'+
    '<div class="kl">'+(ta?('min '+ta.min.toFixed(1)+' · max '+ta.max.toFixed(1)+' °C'):'Not in this upload')+'</div></div></div>';

  /* ---- charts ---- */
  const profCard='<div class="dcard"><div class="dcard-head"><h3>D0 — lower to higher by road class</h3>'+
    '<span class="totchip">'+scopeLbl+'</span></div>'+
    '<div class="sub">Every D0 value sorted ascending — SH vs MDR. A curve that stays low and flat means a structurally strong network; the steep tail is the weak-pavement fraction. Dot = median.</div>'+
    fdbProfileChart(s,unit)+'</div>';

  const histCard='<div class="dcard"><div class="dcard-head"><h3>D0 distribution</h3>'+
    '<span class="totchip">'+scopeLbl+'</span></div>'+
    '<div class="sub">Number of FWD points per D0 band ('+unit+'), split by road class.</div>'+
    fdbHistChart(s,v.hist_edges,unit)+'</div>';

  const clsRows=(s.classes||[]).map(c=>({label:c.cls,n:c.points}));
  const donutCardH=cDonutCard('Points by road class',
    'Share of FWD test points on each classification — '+scopeLbl.toLowerCase(),
    clsRows,{colorFn:fdbCol,full:fdbClsFull,centerSmall:'points'});

  const distBarsCard='<div class="dcard"><div class="dcard-head"><h3>FWD points by district</h3>'+
    '<span class="totchip">'+fmtN(v.points)+'</span></div>'+
    '<div class="sub">Deflection test points per district in '+pName+(surfLbl?' — '+surfLbl.toLowerCase()+' only':'')+' — click a district to focus the whole dashboard</div>'+
    fdbBars(dists.map(d=>({label:d.district,n:d.points})))+'</div>';

  const dumbCard='<div class="dcard"><div class="dcard-head"><h3>District-wise D0 — lower to higher</h3>'+
    '<span class="totchip">'+(surfLbl?surfLbl+' · ':'')+unit+'</span></div>'+
    '<div class="sub">Each row is one district &amp; class, sorted from the lowest mean D0 (strongest) to the highest (weakest). The band spans min–max; the dot marks the mean.</div>'+
    fdbD0Districts(v,unit)+'</div>';

  const note='<div class="dash-note"><b>About these figures.</b> <b>D0</b> is the deflection under the FWD loading plate — the primary indicator of pavement structural strength: lower D0 = stronger pavement. '+
    'Every point is tagged with the survey period chosen at import in the Data Console'+(p.range?(' — '+pName+' covers '+escH(p.range)):'')+'. '+
    'Road class and district come from the road network via each point’s section label; unmatched sections appear as <b>(unmapped)</b>. '+
    '<b>Flexible</b> (BT) and <b>rigid</b> (CC / PQC) pavements are reported separately — rigid slabs deflect far less, so mixing them would distort every statistic; '+
    'the pavement type comes from the FWD file’s pavement-type column when present, otherwise from the road network’s construction / surface type. '+
    'The <b>characteristic deflection</b> shown is the 90th percentile. Pavement and air temperatures are read from the uploaded FWD file when present — D0 rises with pavement temperature, so readings are normally corrected to a standard temperature before overlay design (IRC:115).</div>';

  body.innerHTML=pills+kpi+fdbSurfCard(p)+
    '<div class="comp-row">'+profCard+histCard+'</div>'+
    '<div class="comp-row">'+donutCardH+distBarsCard+'</div>'+
    fdbUnmappedCard(p)+dumbCard+fdbTempCard(v)+fdbMatrix(v,unit)+note;
}
