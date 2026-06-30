/* ============================================================
   KLRAMS viewer · 21-report-hub.js
   Report Hub — a full-screen module that makes the survey CSV
   datasets available as filterable, exportable reports:
     • FWD (D0 deflection)
     • Road Condition Data (segments)
     • Sub-Grade Soil
     • Bituminous Core
     • Pavement Crust
   Every report joins Road Name + PWD Section from the Road
   Network shapefile, matched on Section Label, and exports to
   Excel and PDF. Loaded after 19/20 so the join helpers
   (regSectionKey, roadProps) and ROADS/loadRoads are in scope.
   ============================================================ */

const RH_SETS=[
  {key:'fwd',      label:'FWD',                kind:'asset',    type:'fwd',             title:'FWD (D0 Deflection) Report', file:'fwd-report'},
  {key:'cond',     label:'Road Condition Data',kind:'segments',                        title:'Road Condition Data Report', file:'road-condition-report'},
  {key:'subgrade', label:'Sub-Grade Soil',     kind:'asset',    type:'subgrade',        title:'Sub-Grade Soil Report',      file:'subgrade-soil-report'},
  {key:'core',     label:'Bituminous Core',    kind:'asset',    type:'bituminous_core', title:'Bituminous Core Report',     file:'bituminous-core-report'},
  {key:'crust',    label:'Pavement Crust',     kind:'asset',    type:'pavement_crust',  title:'Pavement Crust Report',      file:'pavement-crust-report'},
  {key:'flood',    label:'Flood Susceptibility',kind:'client',                          title:'Flood Susceptibility Report',file:'flood-susceptibility-report'}
];
let rhTab='fwd', rhSearch='', rhDistrict='', rhRoad='', rhSec='', rhCache={};

/* ---- open / close ---- */
function openReportHub(){
  const s=document.getElementById('reportHub'); if(!s)return;
  ['dashboard','pciScreen','condScreen','regScreen'].forEach(id=>{const e=document.getElementById(id);if(e)e.classList.remove('open');});
  s.classList.add('open');
  rhScreenTab(rhTab||'fwd');
}
function closeReportHub(){ const s=document.getElementById('reportHub'); if(s)s.classList.remove('open'); }
function rhSet(k){return RH_SETS.find(x=>x.key===k)||RH_SETS[0];}
function rhScreenTab(k){
  rhTab=k; rhSearch=''; rhDistrict=''; rhRoad=''; rhSec='';
  RH_SETS.forEach(s=>{const b=document.getElementById('rhTab_'+s.key);if(b)b.classList.toggle('on',s.key===k);});
  rhRenderTab(k);
}
function rhSetSearch(v){rhSearch=v;rhRender();const si=document.getElementById('rhSearch');if(si){si.focus();si.setSelectionRange(si.value.length,si.value.length);}}
function rhSetDistrict(v){rhDistrict=v;rhRender();}
function rhSetRoad(v){rhRoad=v;rhRender();}
function rhSetSec(v){rhSec=v;rhRender();}

/* ---- build rows (join Road Name + PWD Section by Section Label) ---- */
function rhConsolidateLanes(out){
  const laneRe=/^(CL\d+|CR\d+|CC)(?:[_|](.+))?$/i;
  const KM={iri:1,crack:1,cracking:1,pothole:1,rutting:1,rut:1,texture:1,patchwork:1,patch:1,ravelling:1,raveling:1};
  const entries=[];
  Object.keys(out).forEach(k=>{const m=k.match(laneRe);if(m)entries.push({lane:m[1].toUpperCase(),metric:(m[2]||''),key:k});});
  if(!entries.length)return out;
  const lanes=[]; entries.forEach(e=>{if(lanes.indexOf(e.lane)<0)lanes.push(e.lane);});
  lanes.sort((a,b)=>{const ia=RH_LANE_ORDER.indexOf(a),ib=RH_LANE_ORDER.indexOf(b);return ((ia<0?99:ia)-(ib<0?99:ib))||a.localeCompare(b);});
  const laneNum={}; lanes.forEach((L,i)=>laneNum[L]='L'+(i+1));
  const res={};
  Object.keys(out).forEach(k=>{
    if(laneRe.test(k))return;
    if(/^(avg|average|mean)([_|]|$)/i.test(k))return;
    const ck=k.toLowerCase().replace(/[^a-z0-9]/g,'');
    if(KM[ck])return;
    res[k]=out[k];
  });
  entries.forEach(e=>{ res[laneNum[e.lane]+(e.metric?('|'+e.metric):'')]=out[e.key]; });
  return res;
}
function rhFlatten(props){
  const out={};
  Object.keys(props||{}).forEach(k=>{
    if(k==='lane_vals'){
      let lv=props[k]; if(typeof lv==='string'){try{lv=JSON.parse(lv);}catch(e){lv=null;}}
      if(lv&&typeof lv==='object'){Object.keys(lv).forEach(sp=>{const o=lv[sp];if(o&&typeof o==='object'){Object.keys(o).forEach(m=>{if(o[m]!=null)out[sp+'_'+m]=o[m];});}else if(o!=null&&o!=='')out[sp]=o;});}
      return;
    }
    out[k]=props[k];
  });
  return rhConsolidateLanes(out);
}
function rhBuildRows(gj){
  const rows=[]; ((gj&&gj.features)||[]).forEach(f=>{const p=f.properties||{};const sec=regSectionKey(p);const rp=roadProps(sec);
    rows.push({sec:sec, road:rp.Road_Name||'', pwd:rp.PWD_Sec||'', district:rp.District||'', data:rhFlatten(p)});});
  rows.sort((a,b)=>String(a.sec).localeCompare(String(b.sec),undefined,{numeric:true}));
  return rows;
}
function rhEnsure(set){
  return Promise.resolve()
    .then(()=>(!ROADS||!Object.keys(ROADS).length)?loadRoads():null)
    .then(()=>{
      if(rhCache[set.key])return rhCache[set.key];
      if(set.kind==='client'){
        const src=(typeof CLIMATE_ROWS!=='undefined'&&CLIMATE_ROWS)||[];
        const rows=src.map(function(r){return {sec:r.sec,road:r.name||(roadProps(r.sec).Road_Name||''),pwd:roadProps(r.sec).PWD_Sec||'',district:roadProps(r.sec).District||'',data:r.props||{}};});
        rhCache[set.key]=rows; return rows;
      }
      const url=set.kind==='segments'?'/api/segments/geojson':'/api/assets/'+set.type+'/geojson';
      return fetch(url).then(r=>r.json()).then(gj=>{const rows=rhBuildRows(gj);rhCache[set.key]=rows;return rows;}).catch(()=>{rhCache[set.key]=[];return [];});
    });
}

/* ---- columns (Sl, Section Label, Road Name, PWD Section, + dataset columns) ---- */
function rhKeyNumeric(rows,key){let seen=false;for(let i=0;i<rows.length;i++){const v=(rows[i].data||{})[key];if(v==null||v==='')continue;seen=true;if(isNaN(Number(String(v).replace(/,/g,''))))return false;}return seen;}
function rhDataKeys(rows){
  const seen={},order=[];
  rows.forEach(r=>Object.keys(r.data||{}).forEach(key=>{if(!seen[key]){seen[key]=1;order.push(key);}}));
  const skip={lane_vals:1,geometry:1};
  const isRoadKey=key=>{const c=String(key).toLowerCase().replace(/[^a-z0-9]/g,'');return (typeof ROAD_KEYS!=='undefined'&&ROAD_KEYS.indexOf(c)>=0)||c==='dscale'||c==='d0scale';};
  const chainish=[],rest=[];
  order.forEach(key=>{
    if(!key||key.charAt(0)==='_'||skip[key]||isRoadKey(key))return;
    const c=String(key).toLowerCase().replace(/[^a-z0-9]/g,'');
    if(c.indexOf('chain')>=0||c==='from'||c==='to'||c==='startm'||c==='endm'||c==='frch'||c==='tch')chainish.push(key); else rest.push(key);
  });
  return chainish.concat(rest);
}
const RH_LANE_ORDER=['CL1','CL2','CL3','CL4','CC','CR1','CR2','CR3','CR4'];
function rhPrettyMetric(m){if(!m)return '';const map={iri:'IRI',crack:'Crack',cracking:'Crack',rut:'Rutting',rutting:'Rutting',pothole:'Pothole',ravelling:'Ravelling',raveling:'Ravelling',patch:'Patch Work',patchwork:'Patch Work',texture:'Texture'};const k=String(m).toLowerCase().replace(/[^a-z0-9]/g,'');return map[k]||String(m).replace(/_/g,' ').replace(/\b\w/g,c=>c.toUpperCase());}
function rhMetricOrder(metric){const ck=String(metric).toLowerCase().replace(/[^a-z0-9]/g,'');const pref=['iri','crack','cracking','pothole','rutting','rut','texture','patchwork','patch','ravelling','raveling'];const i=pref.indexOf(ck);return i<0?99:i;}
function rhColumns(rows){
  const cols=[
    {l:'Sl',n:true,g:(r,i)=>i+1},
    {l:'Section Label',cls:'m',g:r=>r.sec},
    {l:'Road Name',g:r=>r.road},
    {l:'District',g:r=>r.district},
    {l:'PWD Section',cls:'m',g:r=>r.pwd}
  ];
  const keys=rhDataKeys(rows);
  const laneCols=[],other=[];
  keys.forEach(k=>{const m=String(k).match(/^L(\d+)\|(.+)$/);if(m)laneCols.push({key:k,lane:+m[1],metric:m[2]});else other.push(k);});
  other.forEach(k=>cols.push({l:k,n:rhKeyNumeric(rows,k),g:r=>{const v=(r.data||{})[k];return (v==null||v==='')?'':v;}}));
  laneCols.sort((a,b)=>(rhMetricOrder(a.metric)-rhMetricOrder(b.metric))||String(a.metric).localeCompare(String(b.metric))||(a.lane-b.lane));
  laneCols.forEach(lc=>cols.push({l:'Lane '+lc.lane+' '+rhPrettyMetric(lc.metric),n:rhKeyNumeric(rows,lc.key),g:r=>{const v=(r.data||{})[lc.key];return (v==null||v==='')?'':v;}}));
  return cols;
}

/* ---- filtering + render ---- */
function rhFilteredRows(){
  const set=rhSet(rhTab); let rows=(rhCache[set.key]||[]).slice();
  const q=(rhSearch||'').toLowerCase();
  if(q)rows=rows.filter(r=>{
    if((String(r.sec)+' '+r.road+' '+r.pwd).toLowerCase().indexOf(q)>=0)return true;
    const d=r.data||{}; for(const k in d){if(String(d[k]).toLowerCase().indexOf(q)>=0)return true;} return false;
  });
  if(rhRoad)rows=rows.filter(r=>String(r.road)===rhRoad);
  if(rhDistrict)rows=rows.filter(r=>String(r.district)===rhDistrict);
  if(rhSec)rows=rows.filter(r=>String(r.sec)===rhSec);
  return rows;
}
function rhDistrictOptions(rows){const set={};(rows||[]).forEach(r=>{if(r.district)set[r.district]=1;});return '<option value="">All districts</option>'+Object.keys(set).sort().map(d=>'<option value="'+escH(d)+'"'+(rhDistrict===d?' selected':'')+'>'+escH(d)+'</option>').join('');}
function rhRoadOptions(rows){const set={};(rows||[]).forEach(r=>{if(r.road)set[r.road]=1;});return '<option value="">All road names</option>'+Object.keys(set).sort((a,b)=>String(a).localeCompare(String(b))).map(d=>'<option value="'+escH(d)+'"'+(rhRoad===d?' selected':'')+'>'+escH(d)+'</option>').join('');}
function rhSecOptions(rows){const set={};(rows||[]).forEach(r=>{if(r.sec)set[r.sec]=1;});return '<option value="">All section labels</option>'+Object.keys(set).sort((a,b)=>String(a).localeCompare(String(b),undefined,{numeric:true})).map(d=>'<option value="'+escH(d)+'"'+(rhSec===d?' selected':'')+'>'+escH(d)+'</option>').join('');}
function rhToolbar(allRows,count){
  return '<div class="reg-bar">'
    +'<input id="rhSearch" class="reg-search" placeholder="Search any value&hellip;" value="'+escH(rhSearch)+'" oninput="rhSetSearch(this.value)">'
    +'<select class="reg-sel" onchange="rhSetRoad(this.value)" title="Filter by road name">'+rhRoadOptions(allRows)+'</select>'
    +'<select class="reg-sel" onchange="rhSetDistrict(this.value)" title="Filter by district">'+rhDistrictOptions(allRows)+'</select>'
    +'<select class="reg-sel" onchange="rhSetSec(this.value)" title="Filter by section label">'+rhSecOptions(allRows)+'</select>'
    +'<span class="reg-count">'+(count!=null?count:(allRows||[]).length)+' rows</span>'
    +'<span class="reg-exp"><button class="btn ghost" onclick="rhExportExcel()">Excel</button><button class="btn ghost" onclick="rhPrint()">PDF</button></span>'
    +'</div>';
}
function rhCell(v){return (v==null||v==='')?'\u2013':escH(v);}
function rhRenderTab(k){
  const set=rhSet(k);
  const body=document.getElementById('rhBody'); if(!body)return;
  body.innerHTML='<div class="dash-loading">Loading '+escH(set.label)+'&hellip;</div>';
  rhEnsure(set).then(()=>{ if(rhTab!==k)return; rhRender(); }).catch(()=>{ if(rhTab===k)body.innerHTML='<div class="dash-loading">Could not load '+escH(set.label)+'.</div>'; });
}
function rhRender(){
  const set=rhSet(rhTab); const allRows=rhCache[set.key]||[]; const rows=rhFilteredRows();
  const body=document.getElementById('rhBody'); if(!body)return;
  if(!allRows.length){body.innerHTML=rhToolbar(allRows,0)+'<div class="dash-loading">No '+escH(set.label)+' data found yet. '+(set.kind==='client'?'Import the flood CSV in the Climate module, then reopen this report.':'Upload it in the Data Console, then reopen this report.')+'</div>';return;}
  const cols=rhColumns(allRows);
  const head='<tr>'+cols.map(c=>'<th'+(c.n?' class="n"':'')+'>'+escH(c.l)+'</th>').join('')+'</tr>';
  let tb='';rows.forEach((r,i)=>{tb+='<tr>'+cols.map(c=>'<td'+(c.n?' class="n"':(c.cls?' class="'+c.cls+'"':''))+'>'+rhCell(c.g(r,i))+'</td>').join('')+'</tr>';});
  body.innerHTML=rhToolbar(allRows,rows.length)+'<div class="reg-tablewrap"><table class="reg-table"><thead>'+head+'</thead><tbody>'+(tb||'<tr><td colspan="'+cols.length+'" style="text-align:center;color:#8a93a3;padding:18px">No rows match.</td></tr>')+'</tbody></table></div>';
  const si=document.getElementById('rhSearch'); if(si&&rhSearch){si.focus();si.setSelectionRange(si.value.length,si.value.length);}
}

/* ---- export (Excel + PDF), filtered ---- */
function rhMatrix(){
  const set=rhSet(rhTab); const rows=rhFilteredRows(); const cols=rhColumns(rhCache[set.key]||rows);
  const header=cols.map(c=>c.l);
  const data=rows.map((r,i)=>cols.map(c=>{const v=c.g(r,i);return v==null?'':v;}));
  return {header,data,title:set.title,file:set.file,cols};
}
function rhExportExcel(){
  const {header,data,file}=rhMatrix();
  const esc=s=>String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  let html='<table border="1"><thead><tr>'+header.map(h=>'<th>'+esc(h)+'</th>').join('')+'</tr></thead><tbody>';
  data.forEach(row=>{html+='<tr>'+row.map(c=>'<td>'+esc(c)+'</td>').join('')+'</tr>';});
  html+='</tbody></table>';
  const blob=new Blob(['<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel"><head><meta charset="utf-8"></head><body>'+html+'</body></html>'],{type:'application/vnd.ms-excel'});
  const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=file+'.xls';document.body.appendChild(a);a.click();
  setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove();},600);
}
function rhPrint(){
  const {header,data,title,cols}=rhMatrix();
  const esc=s=>String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  const dt=new Date().toLocaleString('en-IN',{dateStyle:'medium',timeStyle:'short'});
  let rowsHtml=''; data.forEach(row=>{rowsHtml+='<tr>'+row.map((c,i)=>'<td'+(cols[i].n?' class="n"':'')+'>'+esc(c===''?'-':c)+'</td>').join('')+'</tr>';});
  const w=window.open('','_blank'); if(!w)return;
  w.document.write('<html><head><title>'+esc(title)+'</title><style>'
    +'body{font-family:Arial,Helvetica,sans-serif;margin:16px;color:#16202e}h1{font-size:15px;margin:0 0 2px}.sub{color:#5a6b82;font-size:11px;margin-bottom:11px}'
    +'table{border-collapse:collapse;width:100%;font-size:9px}th,td{border:1px solid #c2cad6;padding:3px 5px;text-align:left}th{background:#0e2038;color:#fff}td.n,th.n{text-align:right}tr:nth-child(even){background:#f3f6fa}'
    +'@page{size:A4 landscape;margin:9mm}'
    +'</style></head><body><h1>'+esc(title)+' &mdash; KLRAMS</h1><div class="sub">Kerala PWD &middot; RMMS Cell, KHRI &middot; '+esc(dt)+' &middot; '+data.length+' rows</div>'
    +'<table><thead><tr>'+header.map((h,i)=>'<th'+(cols[i].n?' class="n"':'')+'>'+esc(h)+'</th>').join('')+'</tr></thead><tbody>'+rowsHtml+'</tbody></table>'
    +'<scr'+'ipt>setTimeout(function(){window.print();},300);</scr'+'ipt></body></html>');
  w.document.close();
}
