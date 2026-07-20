/* ============================================================
   KLRAMS viewer · 30-condition-dashboard.js
   Condition Dashboard tab — state-wide and district-wise
   Low / High / Mean for a single raw condition parameter
   (IRI, Cracking, Pothole, Rutting, Texture, Patch work,
   Ravelling), split by pavement surface type (Flexible /
   Cement Concrete / Paver Block) and by road class (SH / MDR),
   for one survey period.

   A "Segment list" view lists every stretch whose condition
   value passes a threshold (≥ / ≤ an entered value), with
   Section label, Road Class, Road Name, Road Number, Chainage,
   Lane km and XSP — state-wide or for one district.

   Backend: /api/condition-dashboard/{summary,table}
   (ConditionDashboardController). Reuses .svy-bar / .kpi / .dcard
   / .amx styling and escH / qq helpers from the other dashboards.
   Loaded as an ordered classic script from map.html.
   ============================================================ */

let cdData=null;                 // /summary payload
let cdParam='iri';               // selected condition parameter
let cdBasis='avg';               // 'avg' (lane average) | 'worst' (worst lane)
let cdPeriodId=null;             // selected survey period id
let cdDistrict=null;             // selected district or null = state-wide
let cdView='summary';            // 'summary' | 'table'
let cdLoading=false;

/* value formatter — 2 decimals, dot for empty groups */
function cdFmt(v){return (v==null||v==='')?'<span class="z">·</span>':(+v).toFixed(2);}
function cdUnit(){return cdData?escH(cdData.param_unit||''):'';}
function cdBasisLabel(){return cdBasis==='worst'?'Worst lane':'Lane average';}

function renderCondDash(){
  const body=document.getElementById('dashBody');
  cdView='summary';
  if(cdData){cdPaint();return;}
  body.innerHTML='<div class="dash-loading">Loading condition figures…</div>';
  cdFetchSummary();
}

function cdFetchSummary(){
  if(cdLoading)return;
  cdLoading=true;
  const qs='param='+encodeURIComponent(cdParam)+'&basis='+cdBasis+(cdPeriodId?('&period_id='+cdPeriodId):'');
  fetch('/api/condition-dashboard/summary?'+qs).then(r=>{
    if(!r.ok)throw new Error('HTTP '+r.status);
    if(r.redirected||(r.headers.get('content-type')||'').indexOf('json')<0)throw new Error('SESSION');
    return r.json();
  }).then(d=>{
    cdLoading=false;
    if(!d||!d.statewide)throw new Error('unexpected response');
    cdData=d;
    if(!cdPeriodId)cdPeriodId=(d.default_period&&d.default_period.id)||(d.periods[0]&&d.periods[0].id);
    if(dashTabCur==='cond'&&cdView==='summary')cdPaint();
  }).catch(e=>{
    cdLoading=false;cdData=null;
    if(dashTabCur!=='cond')return;
    const body=document.getElementById('dashBody');
    body.innerHTML=e.message==='SESSION'
      ?'<div class="dash-loading">Your session has expired (the server was restarted). '+
       '<a href="/login.html" style="color:#15976a;font-weight:700">Sign in again</a></div>'
      :'<div class="dash-loading">Could not load condition figures ('+escH(e.message)+'). '+
       '<a href="#" onclick="renderCondDash();return false" style="color:#15976a;font-weight:700">Retry</a></div>';
  });
}

/* re-fetch on a parameter / basis / period change (each is a different SQL scope) */
function cdReload(){cdData=null;cdFetchSummary();
  document.getElementById('dashBody').innerHTML='<div class="dash-loading">Loading condition figures…</div>';}
function cdSetParam(k){if(k===cdParam)return;cdParam=k;cdReload();}
function cdSetBasis(b){if(b===cdBasis)return;cdBasis=b;cdReload();}
function cdSetPeriod(id){if(id===cdPeriodId)return;cdPeriodId=id;cdDistrict=null;cdReload();}
function cdSetDistrict(name){cdDistrict=(cdDistrict===name)?null:name;
  if(cdView==='table'){cdTbl=null;cdPaintTable();}else{cdPaint();}}

function cdPeriodObj(){const ps=(cdData&&cdData.periods)||[];return ps.find(p=>p.id===cdPeriodId)||ps[0]||null;}
function cdDistricts(){return (cdData&&cdData.districts)||[];}
/* stats for the current scope: {overall, by_surface, by_class, matrix?} */
function cdScope(){
  if(!cdDistrict)return cdData.statewide;
  return cdDistricts().find(d=>d.district===cdDistrict)||{overall:{},by_surface:[],by_class:[]};
}

/* ---- shared controls bar (params · basis · period · districts) ---- */
function cdControls(){
  const params=(cdData.params||[]).map(p=>
    '<button type="button" class="svy-pill'+(p.key===cdParam?' on':'')+'" onclick="cdSetParam(\''+p.key+'\')">'+
    '<span class="svy-pill-cap">Parameter</span><span class="svy-pill-yr">'+escH(p.label)+'</span></button>').join('');
  const basis='<div class="cd-toggle">'+
    ['avg','worst'].map(b=>'<button type="button" class="cd-tg'+(b===cdBasis?' on':'')+'" onclick="cdSetBasis(\''+b+'\')">'+
      (b==='avg'?'Lane average':'Worst lane')+'</button>').join('')+'</div>';
  const periods=(cdData.periods||[]).map(pp=>
    '<button type="button" class="svy-pill'+(pp.id===cdPeriodId?' on':'')+'" title="'+escH(pp.range||'')+'" onclick="cdSetPeriod('+(+pp.id)+')">'+
    '<span class="svy-pill-cap">Survey Period'+(pp.is_active?' · current':'')+'</span><span class="svy-pill-yr">'+escH(pp.name)+'</span></button>').join('');
  const dists='<button type="button" class="svy-chip'+(cdDistrict?'':' on')+'" onclick="cdSetDistrict(null)">All Districts</button>'+
    cdDistricts().map(d=>'<button type="button" class="svy-chip'+(d.district===cdDistrict?' on':'')+'" onclick="cdSetDistrict(\''+qq(d.district)+'\')">'+escH(d.district)+'</button>').join('');
  const views='<div class="cd-toggle cd-views">'+
    '<button type="button" class="cd-tg'+(cdView==='summary'?' on':'')+'" onclick="cdShowSummary()">Overview</button>'+
    '<button type="button" class="cd-tg'+(cdView==='table'?' on':'')+'" onclick="cdShowTable()">Segment list</button></div>';
  return '<div class="svy-bar cd-bar"><div class="svy-years">'+params+'</div>'+basis+views+'</div>'+
         '<div class="svy-bar cd-bar"><div class="svy-years">'+periods+'</div></div>'+
         '<div class="svy-bar cd-bar"><div class="svy-dists">'+dists+'</div></div>';
}

/* ================= OVERVIEW ================= */
function cdShowSummary(){cdView='summary';cdPaint();}
function cdPaint(){
  const body=document.getElementById('dashBody');if(!body)return;
  if(!cdData){renderCondDash();return;}
  const p=cdPeriodObj();
  const scope=cdScope();
  const ov=scope.overall||{};
  const scopeLbl=cdDistrict?escH(cdDistrict):'All districts';
  const unit=cdUnit();
  const pName=escH((p&&p.name)||'');
  const noData=!(ov.segments>0);

  const kpiDefs=[
    {cap:'Lowest value',   k:'low',  col:'#15976a', sub:'Best-reading stretch'},
    {cap:'Highest value',  k:'high', col:'#c2603f', sub:'Worst-reading stretch'},
    {cap:'Mean (length-weighted)', k:'mean', col:'#2a5d9c', sub:cdBasisLabel()+' · '+escH(cdData.param_label)},
    {cap:'Stretches',      k:'segments', col:'#6b4e9e', sub:'Condition segments scored', int:true},
    {cap:'Lane length',    k:'lane_km', col:'#d4a02e', sub:'Surveyed lane-km', km:true}
  ];
  const kpi='<div class="kpi-row cd-kpis">'+kpiDefs.map((m,i)=>{
    let val;
    if(m.int)val=fmtN(ov[m.k]||0);
    else if(m.km)val=fmtKm(ov[m.k]||0);
    else val=(ov[m.k]==null?'–':(+ov[m.k]).toFixed(2));
    const u=(m.int)?'':(m.km?' lane km':' '+unit);
    return '<div class="kpi'+(i===2?' feature':'')+'" style="--kc:'+m.col+'">'+(i===2?'<div class="ringmark"></div>':'')+
      '<div class="kcap">'+m.cap+'</div>'+
      '<div class="kv">'+val+'<span class="u">'+u+'</span></div>'+
      '<div class="kl">'+m.sub+'</div></div>';
  }).join('')+'</div>';

  const surfCard=cdBreakdownCard('Surface type',scope.by_surface||[],'surface',unit);
  const classCard=cdBreakdownCard('Road class',scope.by_class||[],'road_class',unit);

  /* district-wise Low/High/Mean matrix (state scope only lists every district) */
  const dists=cdDistricts();
  let mrows='';
  dists.forEach(d=>{const o=d.overall||{};
    mrows+='<tr'+(d.district===cdDistrict?' class="svy-sel"':'')+' onclick="cdSetDistrict(\''+qq(d.district)+'\')" style="cursor:pointer">'+
      '<td>'+escH(d.district)+'</td>'+
      '<td class="n">'+cdFmt(o.low)+'</td><td class="n">'+cdFmt(o.high)+'</td>'+
      '<td class="n">'+cdFmt(o.mean)+'</td><td class="n">'+fmtKm(o.lane_km||0)+'</td>'+
      '<td class="n">'+fmtN(o.segments||0)+'</td></tr>';});
  const stAll=cdData.statewide.overall||{};
  mrows+='<tr class="amx-tot"><td><b>State-wide</b></td>'+
    '<td class="n"><b>'+cdFmt(stAll.low)+'</b></td><td class="n"><b>'+cdFmt(stAll.high)+'</b></td>'+
    '<td class="n"><b>'+cdFmt(stAll.mean)+'</b></td><td class="n"><b>'+fmtKm(stAll.lane_km||0)+'</b></td>'+
    '<td class="n"><b>'+fmtN(stAll.segments||0)+'</b></td></tr>';
  const matrix='<div class="dcard"><div class="dcard-head"><h3>District-wise '+escH(cdData.param_label)+'</h3>'+
    '<span class="totchip">'+dists.length+' district'+(dists.length===1?'':'s')+'</span></div>'+
    '<div class="sub">Low / High / Mean ('+cdBasisLabel().toLowerCase()+', '+escH(unit)+') per district in '+pName+' — click a row to focus</div>'+
    '<div class="amx-wrap"><table class="amx"><tr><th>District</th><th class="n">Low</th><th class="n">High</th>'+
    '<th class="n">Mean</th><th class="n">Lane km</th><th class="n">Stretches</th></tr>'+mrows+'</table></div></div>';

  const note='<div class="dash-note"><b>About these figures.</b> The condition value is <b>'+escH(cdData.param_label)+
    (unit?(' ('+escH(unit)+')'):'')+'</b> per stretch from the '+pName+' survey — taken as the <b>'+cdBasisLabel().toLowerCase()+
    '</b> across the carriageway lanes. <b>Low</b> and <b>High</b> are the minimum and maximum stretch values in the group; '+
    '<b>Mean</b> is length-weighted (a long stretch counts more than a short one). Surface type comes from the road\'s '+
    'construction type (Flexible / Cement Concrete / Paver Block); other types are grouped as <b>Other</b>. '+
    'Use <b>Segment list</b> to see individual stretches above or below a chosen value.</div>';

  const heading='<div class="cd-scope">Showing <b>'+escH(cdData.param_label)+'</b> · '+cdBasisLabel()+' · '+scopeLbl+
    (noData?' — <span style="color:#c2603f">no scored stretches in this scope</span>':'')+'</div>';

  body.innerHTML=cdControls()+heading+kpi+'<div class="comp-row">'+surfCard+classCard+'</div>'+cdTopSection()+matrix+note;
}

/* ---- Top 10 SH / Top 5 MDR by area-weighted parameter value ---- */
let cdTop=null, cdTopKey=null, cdTopLoadingKey=null;
function cdScopeKey(){return cdParam+'|'+cdBasis+'|'+cdPeriodId+'|'+(cdDistrict||'');}
function cdFetchTop(key){
  if(cdTopLoadingKey===key)return;
  cdTopLoadingKey=key;
  const qs='param='+encodeURIComponent(cdParam)+'&basis='+cdBasis+(cdPeriodId?('&period_id='+cdPeriodId):'')+
    (cdDistrict?('&district='+encodeURIComponent(cdDistrict)):'');
  fetch('/api/condition-dashboard/top-roads?'+qs).then(r=>{
    if(!r.ok)throw new Error('HTTP '+r.status);return r.json();
  }).then(d=>{cdTopLoadingKey=null;cdTop=d;cdTopKey=key;if(dashTabCur==='cond'&&cdView==='summary')cdPaint();})
   .catch(e=>{cdTopLoadingKey=null;cdTop={sh:[],mdr:[],error:e.message};cdTopKey=key;if(dashTabCur==='cond'&&cdView==='summary')cdPaint();});
}
function cdTopSection(){
  const key=cdScopeKey();
  if(cdTopKey!==key){
    cdFetchTop(key);
    return '<div class="dcard"><div class="dcard-head"><h3>Worst-ranked roads</h3></div>'+
      '<div class="dash-loading" style="padding:22px">Ranking roads…</div></div>';
  }
  const unit=cdUnit();
  return '<div class="comp-row">'+
    cdTopCard('Top 10 State Highways','sh',(cdTop&&cdTop.sh)||[],unit)+
    cdTopCard('Top 5 Major District Roads','mdr',(cdTop&&cdTop.mdr)||[],unit)+'</div>';
}
function cdTopCard(title,cls,rows,unit){
  const lbl=escH(cdData.param_label);
  let body='';
  if(!rows.length){body='<tr><td colspan="5"><span class="z">No ranked roads in this scope.</span></td></tr>';}
  else rows.forEach((r,i)=>{
    const nm=(cls==='sh'&&r.road_num)
      ? '<b>'+escH(r.road_num)+'</b> · '+escH(r.road_names||'')
      : escH(r.road_names||r.road_key||'(unnamed)');
    body+='<tr><td class="n cd-rank">'+(i+1)+'</td>'+
      '<td>'+nm+'<div class="cd-top-sub">'+escH(r.districts||'')+'</div></td>'+
      '<td class="n"><b>'+cdFmt(r.value)+'</b></td>'+
      '<td class="n">'+fmtKm(r.lane_km||0)+'</td>'+
      '<td class="n">'+fmtN(r.segments||0)+'</td></tr>';
  });
  return '<div class="dcard"><div class="dcard-head"><h3>'+escH(title)+'</h3>'+
    '<span class="totchip">'+lbl+' · area-wtd</span></div>'+
    '<div class="sub">Highest area-weighted '+lbl+' ('+escH(unit)+'), '+cdBasisLabel().toLowerCase()+' — '+
    (cdDistrict?escH(cdDistrict):'state-wide')+'. SH ranked by road number (else name), MDR by name.</div>'+
    '<div class="amx-wrap"><table class="amx"><tr><th class="n">#</th><th>Road</th>'+
    '<th class="n">'+lbl+'</th><th class="n">Lane km</th><th class="n">Stretches</th></tr>'+body+'</table></div></div>';
}

/* one Low/High/Mean breakdown table (by surface or by class) for the current scope */
function cdBreakdownCard(title,rows,keyName,unit){
  let body='';
  rows.forEach(r=>{
    const has=r.segments>0;
    body+='<tr'+(has?'':' class="cd-empty"')+'><td>'+escH(r[keyName])+'</td>'+
      '<td class="n">'+cdFmt(r.low)+'</td><td class="n">'+cdFmt(r.high)+'</td>'+
      '<td class="n">'+cdFmt(r.mean)+'</td><td class="n">'+fmtKm(r.lane_km||0)+'</td>'+
      '<td class="n">'+fmtN(r.segments||0)+'</td></tr>';
  });
  return '<div class="dcard"><div class="dcard-head"><h3>By '+title+'</h3></div>'+
    '<div class="sub">Low / High / Mean ('+escH(unit)+') for this scope</div>'+
    '<div class="amx-wrap"><table class="amx"><tr><th>'+escH(title)+'</th><th class="n">Low</th><th class="n">High</th>'+
    '<th class="n">Mean</th><th class="n">Lane km</th><th class="n">Stretches</th></tr>'+body+'</table></div></div>';
}

/* ================= SEGMENT LIST ================= */
let cdTblOp='gte', cdTblValue='', cdTblSurface='', cdTblClass='', cdTbl=null, cdTblLoading=false;
function cdShowTable(){cdView='table';cdTbl=null;cdPaintTable();}

function cdPaintTable(){
  const body=document.getElementById('dashBody');if(!body)return;
  const unit=cdUnit();
  const surfaces=['','Flexible','Cement Concrete','Paver Block'];
  const classes=['','SH','MDR'];
  const controls='<div class="dcard cd-filter"><div class="dcard-head"><h3>Segment list — '+escH(cdData.param_label)+
    ' '+(cdDistrict?('· '+escH(cdDistrict)):'· State-wide')+'</h3></div>'+
    '<div class="cd-form">'+
      '<label>Condition value <select id="cdOp">'+
        '<option value="gte"'+(cdTblOp==='gte'?' selected':'')+'>&ge; at least</option>'+
        '<option value="gt"'+(cdTblOp==='gt'?' selected':'')+'>&gt; greater than</option>'+
        '<option value="lte"'+(cdTblOp==='lte'?' selected':'')+'>&le; at most</option>'+
        '<option value="lt"'+(cdTblOp==='lt'?' selected':'')+'>&lt; less than</option>'+
      '</select></label>'+
      '<label><input type="number" step="0.01" id="cdVal" placeholder="value" value="'+escH(cdTblValue)+'"> '+escH(unit)+'</label>'+
      '<label>Surface <select id="cdSurf">'+surfaces.map(s=>'<option value="'+escH(s)+'"'+(s===cdTblSurface?' selected':'')+'>'+(s||'All')+'</option>').join('')+'</select></label>'+
      '<label>Road class <select id="cdCls">'+classes.map(c=>'<option value="'+escH(c)+'"'+(c===cdTblClass?' selected':'')+'>'+(c||'All')+'</option>').join('')+'</select></label>'+
      '<button type="button" class="cd-run" onclick="cdRunTable()">Show segments</button>'+
    '</div>'+
    '<div class="sub">Lists stretches from '+escH((cdPeriodObj()||{}).name||'')+' whose '+escH(cdData.param_label)+
    ' ('+cdBasisLabel().toLowerCase()+') passes the test. Change district with the chips above.</div></div>';

  let result='';
  if(cdTblLoading){result='<div class="dash-loading">Finding segments…</div>';}
  else if(cdTbl){
    const rows=cdTbl.rows||[];
    if(!rows.length){result='<div class="dcard"><div class="dash-loading" style="padding:26px">No stretches match this condition.</div></div>';}
    else{
      const opTxt={'>=':'≥','>':'>','<=':'≤','<':'<'}[cdTbl.op]||cdTbl.op;
      let tr='';
      rows.forEach(r=>{
        tr+='<tr><td>'+escH(r.section_label||'')+'</td>'+
          '<td>'+escH(r.road_class||'')+'</td>'+
          '<td>'+escH(r.road_name||'')+'</td>'+
          '<td>'+escH(r.road_num==null?'':r.road_num)+'</td>'+
          '<td class="n">'+cdCh(r.from_ch)+' – '+cdCh(r.to_ch)+'</td>'+
          '<td class="n">'+fmtKm(r.lane_km||0)+'</td>'+
          '<td>'+escH(r.xsp||'')+'</td>'+
          '<td class="n"><b>'+(r.value==null?'–':(+r.value).toFixed(2))+'</b></td></tr>';
      });
      const trunc=cdTbl.total>cdTbl.returned?(' · showing first '+cdTbl.returned):'';
      result='<div class="dcard"><div class="dcard-head"><h3>'+fmtN(cdTbl.total)+' stretch'+(cdTbl.total===1?'':'es')+
        ' with '+escH(cdData.param_label)+' '+opTxt+' '+(+cdTbl.value)+' '+escH(unit)+'</h3>'+
        '<span class="totchip">'+cdBasisLabel()+trunc+'</span></div>'+
        '<div class="amx-wrap"><table class="amx cd-seglist"><tr>'+
        '<th>Section label</th><th>Road class</th><th>Road name</th><th>Road no.</th>'+
        '<th class="n">Chainage (m)</th><th class="n">Lane km</th><th>XSP</th><th class="n">'+escH(cdData.param_label)+'</th></tr>'+
        tr+'</table></div></div>';
    }
  }else{
    result='<div class="dcard"><div class="dash-loading" style="padding:26px">Enter a value and press '+
      '<b>Show segments</b> to list matching stretches.</div></div>';
  }
  body.innerHTML=cdControls()+controls+result;
}

function cdCh(v){return v==null?'–':Math.round(+v);}

function cdRunTable(){
  cdTblOp=(document.getElementById('cdOp')||{}).value||cdTblOp;
  cdTblValue=(document.getElementById('cdVal')||{}).value||'';
  cdTblSurface=(document.getElementById('cdSurf')||{}).value||'';
  cdTblClass=(document.getElementById('cdCls')||{}).value||'';
  if(cdTblValue===''){cdTbl={rows:[],total:0,returned:0,op:'>=',value:0};cdPaintTable();return;}
  cdTblLoading=true;cdPaintTable();
  const qs='param='+encodeURIComponent(cdParam)+'&basis='+cdBasis+'&op='+cdTblOp+
    '&value='+encodeURIComponent(cdTblValue)+(cdPeriodId?('&period_id='+cdPeriodId):'')+
    (cdDistrict?('&district='+encodeURIComponent(cdDistrict)):'')+
    (cdTblSurface?('&surface='+encodeURIComponent(cdTblSurface)):'')+
    (cdTblClass?('&road_class='+encodeURIComponent(cdTblClass)):'');
  fetch('/api/condition-dashboard/table?'+qs).then(r=>{
    if(!r.ok)throw new Error('HTTP '+r.status);
    if(r.redirected||(r.headers.get('content-type')||'').indexOf('json')<0)throw new Error('SESSION');
    return r.json();
  }).then(d=>{cdTblLoading=false;cdTbl=d;cdPaintTable();})
   .catch(e=>{cdTblLoading=false;cdTbl={rows:[],total:0,returned:0,op:'>=',value:0};cdPaintTable();
     const b=document.getElementById('dashBody');
     if(b)b.insertAdjacentHTML('beforeend','<div class="dash-note" style="color:#c2603f">Could not load segments ('+escH(e.message)+').</div>');});
}
