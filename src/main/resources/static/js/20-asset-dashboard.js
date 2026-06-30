/* ============================================================
   KLRAMS viewer · 20-asset-dashboard.js
   Separate dashboards for Culverts and Bridges:
   district-wise + road-class-wise counts, totals, and colourful
   diagrams (donuts, ranked bars, a district × class matrix).
   Reuses the .dcard / .donut / .rbars / .kpi styling from the
   network overview; adds count-based chart helpers (not km).
   Loaded after 19-asset-register.js so REG_CULV / REG_BRID and
   the build helpers are in scope.
   ============================================================ */

/* ---- count-based chart helpers (parallel to km donut/bars) ---- */
function fmtN(n){return String(Math.round(+n||0));}
function adColor(label,i){return (typeof CLASS_COL!=='undefined'&&CLASS_COL[label])||DPAL[i%DPAL.length];}
function adClsFull(l){return (typeof CLASS_SHORT!=='undefined'&&CLASS_SHORT[l])||(typeof dec==='function'&&dec('Road_Class',l))||l||'\u2014';}

function cDonut(rows,opts){
  opts=opts||{};const tot=rows.reduce((s,r)=>s+(+r.n||0),0)||1;let cum=0;
  const segs=rows.map((r,i)=>{
    const pct=(+r.n||0)/tot*100;const off=((25-cum)%100+100)%100;cum+=pct;
    const col=(opts.colorFn||((l,j)=>DPAL[j%DPAL.length]))(r.label,i);
    return '<circle class="seg" cx="21" cy="21" r="15.91549" fill="none" stroke="'+col+'" stroke-width="5.2" pathLength="100" stroke-dasharray="'+pct.toFixed(3)+' '+(100-pct).toFixed(3)+'" stroke-dashoffset="'+off.toFixed(3)+'"></circle>';
  }).join('');
  return '<div class="donut"><svg viewBox="0 0 42 42" class="donut-svg" aria-hidden="true">'+
    '<circle cx="21" cy="21" r="15.91549" fill="none" stroke="#eef2f7" stroke-width="5.2"></circle>'+segs+'</svg>'+
    '<div class="center"><div class="cbig">'+(opts.centerBig!=null?opts.centerBig:fmtN(tot))+'</div><div class="csmall">'+(opts.centerSmall||'total')+'</div></div></div>';
}
function cDonutCard(title,sub,rows,opts){
  opts=opts||{};rows=rows||[];const tot=rows.reduce((s,r)=>s+(+r.n||0),0)||1;
  const legend=rows.map((r,i)=>{
    const col=(opts.colorFn||((l,j)=>DPAL[j%DPAL.length]))(r.label,i);const n=+r.n||0,pc=n/tot*100;
    const nm=opts.full?opts.full(r.label):r.label;
    return '<div class="dleg"><span class="sw" style="background:'+col+'"></span>'+
      '<span class="nm" title="'+escH(nm)+'">'+escH(nm)+'</span>'+
      '<span class="vl">'+fmtN(n)+'<span class="pc"> · '+pc.toFixed(0)+'%</span></span></div>';
  }).join('');
  return '<div class="dcard"><div class="dcard-head"><h3>'+title+'</h3><span class="totchip">'+fmtN(tot)+'</span></div>'+
    '<div class="sub">'+sub+'</div>'+
    '<div class="donut-wrap">'+cDonut(rows,opts)+'<div class="donut-legend">'+(legend||'<div class="sub">No data.</div>')+'</div></div></div>';
}
function cBars(rows,opts){
  opts=opts||{};
  if(!rows||!rows.length)return '<div class="sub" style="padding:6px 0 2px">No data available.</div>';
  const sorted=rows.slice().sort((x,y)=>(+y.n||0)-(+x.n||0));
  const max=Math.max.apply(null,sorted.map(r=>+r.n||0).concat([0.0001]));
  return '<div class="rbars">'+sorted.map((r,i)=>{
    const n=+r.n||0,pct=Math.max(3,n/max*100);
    const col=(opts.colorFn||((l,j)=>DPAL[j%DPAL.length]))(r.label,i);
    const nm=opts.full?opts.full(r.label):r.label;
    return '<div class="rbar"><div class="rk'+(i<3?' medal':'')+'">'+String(i+1).padStart(2,'0')+'</div>'+
      '<div><div class="rb-top"><span class="rb-nm" title="'+escH(nm)+'">'+escH(nm)+'</span><span class="rb-vl">'+fmtN(n)+'</span></div>'+
      '<div class="rb-track"><div class="rb-fill" style="width:'+pct.toFixed(1)+'%;background:linear-gradient(90deg,'+col+','+col+'cc)"></div></div></div></div>';
  }).join('')+'</div>';
}
function adGroup(rows,keyFn){
  const m={};rows.forEach(r=>{let k=keyFn(r);if(k==null||k==='')k='\u2014';m[k]=(m[k]||0)+1;});
  return Object.keys(m).map(k=>({label:k,n:m[k]})).sort((a,b)=>b.n-a.n);
}

/* ---- ensure culvert/bridge rows are built (mirrors openRegScreen) ---- */
function assetDashEnsure(){
  return Promise.resolve()
    .then(()=>(!ROADS||!Object.keys(ROADS).length)?loadRoads():null)
    .then(()=>REG_CULV_GJ?null:fetch('/api/assets/culvert/geojson').then(r=>r.json()).then(gj=>{REG_CULV_GJ=gj||{features:[]};}).catch(()=>{REG_CULV_GJ={features:[]};}))
    .then(()=>REG_BRID_GJ?null:fetch('/api/assets/bridge/geojson').then(r=>r.json()).then(gj=>{REG_BRID_GJ=gj||{features:[]};}).catch(()=>{REG_BRID_GJ={features:[]};}))
    .then(()=>{ if(!REG_CULV)REG_CULV=buildCulvRows(); if(!REG_BRID)REG_BRID=buildBridRows(); });
}

/* ---- district × class matrix ---- */
function assetMatrix(rows,byDist,byClass,isC){
  const classes=byClass.map(c=>c.label), dists=byDist.map(d=>d.label);
  if(!dists.length||!classes.length)return '';
  const cnt={};rows.forEach(r=>{const d=r.district||'\u2014',c=r.cls||'\u2014';cnt[d+'||'+c]=(cnt[d+'||'+c]||0)+1;});
  let head='<tr><th>District \\ Class</th>'+classes.map((c,i)=>'<th class="n"><span class="amx-dot" style="background:'+adColor(c,i)+'"></span>'+escH(adClsFull(c))+'</th>').join('')+'<th class="n">Total</th></tr>';
  let body='';
  byDist.forEach(dr=>{const d=dr.label;
    body+='<tr><td>'+escH(d)+'</td>'+classes.map(c=>{const v=cnt[d+'||'+c]||0;return '<td class="n">'+(v||'<span class="z">·</span>')+'</td>';}).join('')+'<td class="n"><b>'+dr.n+'</b></td></tr>';});
  body+='<tr class="amx-tot"><td><b>Total</b></td>'+classes.map(c=>{const v=(byClass.find(x=>x.label===c)||{}).n||0;return '<td class="n"><b>'+v+'</b></td>';}).join('')+'<td class="n"><b>'+rows.length+'</b></td></tr>';
  return '<div class="dcard"><div class="dcard-head"><h3>District &times; road class</h3><span class="totchip">'+rows.length+' total</span></div>'+
    '<div class="sub">Count of '+(isC?'culverts':'bridges')+' by district and classification</div>'+
    '<div class="amx-wrap"><table class="amx"><thead>'+head+'</thead><tbody>'+body+'</tbody></table></div></div>';
}

/* ---- main render ---- */
function renderAssetDash(kind){
  const isC=(kind==='culvert');
  const body=document.getElementById('dashBody');
  body.innerHTML='<div class="dash-loading">Loading '+(isC?'culvert':'bridge')+' figures…</div>';
  assetDashEnsure().then(()=>{
    if(dashTabCur!==(isC?'culv':'brid'))return;
    const rows=(isC?REG_CULV:REG_BRID)||[];
    body.innerHTML=assetDashHtml(isC,rows);
  }).catch(e=>{ body.innerHTML='<div class="dash-loading">Could not load '+(isC?'culvert':'bridge')+' data: '+((e&&e.message)||e)+'</div>'; });
}
function assetDashHtml(isC,rows){
  const total=rows.length;
  if(!total)return '<div class="dash-loading">No '+(isC?'culverts':'bridges')+' found in the data.</div>';
  const byClass=adGroup(rows,r=>r.cls);
  const byDist=adGroup(rows,r=>r.district);
  const unknownDist=rows.filter(r=>!r.district).length;
  const topDist=byDist[0];
  const word=isC?'culverts':'bridges', Word=isC?'Culvert':'Bridge';
  /* KPIs */
  let kpi;
  if(isC){
    kpi='<div class="kpi-row">'+
      '<div class="kpi feature" style="--kc:#2a5d9c"><div class="ringmark"></div><div class="kcap">Total culverts</div><div class="kv">'+total+'</div><div class="kl">Across the surveyed network</div></div>'+
      '<div class="kpi" style="--kc:#15976a"><div class="kcap">Districts covered</div><div class="kv">'+byDist.length+'</div><div class="kl">Distinct districts</div></div>'+
      '<div class="kpi" style="--kc:#d4a02e"><div class="kcap">Road classes</div><div class="kv">'+byClass.length+'</div><div class="kl">NH / SH / MDR / ODR present</div></div>'+
      '<div class="kpi" style="--kc:#6b4e9e"><div class="kcap">Top district</div><div class="kv" style="font-size:19px">'+(topDist?escH(topDist.label):'\u2014')+'</div><div class="kl">'+(topDist?topDist.n+' culverts':'')+'</div></div>'+
      '</div>';
  } else {
    const totLen=rows.reduce((s,r)=>s+(+r.length||0),0);
    kpi='<div class="kpi-row">'+
      '<div class="kpi feature" style="--kc:#c2603f"><div class="ringmark"></div><div class="kcap">Total bridges</div><div class="kv">'+total+'</div><div class="kl">Across the surveyed network</div></div>'+
      '<div class="kpi" style="--kc:#2a5d9c"><div class="kcap">Total bridge length</div><div class="kv">'+fmtN(totLen)+'<span class="u">m</span></div><div class="kl">Sum of End \u2212 Start chainage</div></div>'+
      '<div class="kpi" style="--kc:#15976a"><div class="kcap">Districts covered</div><div class="kv">'+byDist.length+'</div><div class="kl">Distinct districts</div></div>'+
      '<div class="kpi" style="--kc:#d4a02e"><div class="kcap">Road classes</div><div class="kv">'+byClass.length+'</div><div class="kl">NH / SH / MDR / ODR present</div></div>'+
      '</div>';
  }
  /* donuts: by class + by district */
  const classCard=cDonutCard('By road class','Share of '+word+' by classification',byClass,{colorFn:adColor,full:adClsFull,centerSmall:word});
  const distRows=byDist.slice(0,9);
  if(byDist.length>9){const rest=byDist.slice(9).reduce((s,r)=>s+r.n,0);distRows.push({label:'Other districts',n:rest});}
  const distCard=cDonutCard('By district','Share of '+word+' by district',distRows,{centerSmall:word});
  const comp='<div class="comp-row">'+classCard+distCard+'</div>';
  /* ranked bars */
  const distBars='<div class="dcard"><div class="dcard-head"><h3>Districts ranked</h3><span class="totchip">'+byDist.length+' listed</span></div><div class="sub">'+Word+' count per district</div>'+cBars(byDist,{})+'</div>';
  const classBars='<div class="dcard"><div class="dcard-head"><h3>Road class ranked</h3><span class="totchip">'+byClass.length+' classes</span></div><div class="sub">'+Word+' count per road class</div>'+cBars(byClass,{colorFn:adColor,full:adClsFull})+'</div>';
  const ranks='<div class="comp-row">'+distBars+classBars+'</div>';
  /* matrix */
  const matrix=assetMatrix(rows,byDist,byClass,isC);
  /* note */
  const note=unknownDist?'<div class="dash-note"><b>Note.</b> '+unknownDist+' '+(unknownDist===1?word.slice(0,-1):word)+' could not be matched to a road section (blank district / class) and are grouped under \u201c\u2014\u201d.</div>':'';
  return kpi+comp+ranks+matrix+note;
}
