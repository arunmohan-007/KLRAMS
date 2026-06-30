/* ============================================================
   KLRAMS viewer · 19-asset-register.js
   • Asset Register — a separate full-screen module (#regScreen)
     with three registers: Road, Culvert, Bridge. Per-register
     search + District filter, attribute-wise filter on Road,
     and PDF + Excel export.
   • Full-screen helpers for the PCI and Road Condition screens.
   ============================================================ */

const ID_KEYS=['assetid','asset_id','assetno','assetcode','id','objectid','gid','fid','uid','code','culvertid','bridgeid','structureid','strid','crid','brid','no'];
let REG_ROADS=null, REG_CULV=null, REG_BRID=null;
let REG_CULV_GJ=null, REG_BRID_GJ=null;
let regTab='road', regSearch='', regClass='', regDistrict='', regAttr='', regAttrVal='', regAttrOp='=';

function regSectionKey(p){ try{var v=pickProp(p,ROAD_KEYS);return (v!=null&&v!=='')?String(v):(p&&p.road!=null?String(p.road):'');}catch(e){return (p&&p.road!=null)?String(p.road):'';} }
function regAssetId(p){ var v=pickProp(p,ID_KEYS); return (v!=null&&v!=='')?String(v):''; }
function regNum(v){ return (v!=null&&v!=='')?(isNaN(+v)?v:+v):null; }
function roadProps(sec){ return (ROADS[sec]&&ROADS[sec].properties)||{}; }
function regCount(gj){ const m={};((gj&&gj.features)||[]).forEach(f=>{const k=regSectionKey(f.properties||{});if(k)m[k]=(m[k]||0)+1;});return m; }
function regChainExtent(sec){ let mn=Infinity,mx=-Infinity; if(typeof DATA!=='undefined'&&DATA&&DATA.features){DATA.features.forEach(f=>{const p=f.properties;if(String(p.road)!==String(sec))return;const a=+p.from_ch,b=+p.to_ch;if(!isNaN(a))mn=Math.min(mn,a);if(!isNaN(b))mx=Math.max(mx,b);});} return (mn!==Infinity)?[Math.round(mn),Math.round(mx)]:[null,null]; }

/* ---- build row sets ---- */
function buildRoadRows(){
  const culv=regCount(REG_CULV_GJ), brid=regCount(REG_BRID_GJ), rows=[];
  Object.keys(ROADS).forEach(key=>{
    const p=roadProps(key), ext=regChainExtent(key);
    const rdS=regNum(p.Rd_Str_cha), rdE=regNum(p.Rd_End_cha);
    rows.push({sec:key,name:p.Road_Name||p.name||'',num:p.Road_Num||'',cls:p.Road_Class||'',clsLabel:dec('Road_Class',p.Road_Class||'')||'',
      sCh:ext[0]!=null?ext[0]:rdS,eCh:ext[1]!=null?ext[1]:rdE,rdSCh:rdS,rdECh:rdE,
      sLoc:p.Rd_Str_Loc||'',eLoc:p.Rd_End_Loc||'',district:p.District||'',culv:culv[key]||0,brid:brid[key]||0,props:p});
  });
  rows.sort((a,b)=>String(a.sec).localeCompare(String(b.sec),undefined,{numeric:true}));
  return rows;
}
function regNumOrNull(v){if(v==null||v==='')return null;const n=Number(String(v).replace(/,/g,''));return isNaN(n)?null:n;}
function regAddCh(a,b){const x=regNumOrNull(a),y=regNumOrNull(b);if(x==null&&y==null)return null;return (x||0)+(y||0);}
function regSubCh(end,start){const x=regNumOrNull(end),y=regNumOrNull(start);if(x==null||y==null)return null;return Math.abs(Math.round(x-y));}
function buildCulvRows(){
  const rows=[]; ((REG_CULV_GJ&&REG_CULV_GJ.features)||[]).forEach(f=>{const p=f.properties||{};const sec=regSectionKey(p);const rp=roadProps(sec);
    const startCh=regNum(pickProp(p,FROM_KEYS)), rdSCh=regNum(rp.Rd_Str_cha);
    rows.push({sec:sec,name:rp.Road_Name||'',assetId:regAssetId(p),startCh:startCh,rdSCh:rdSCh,district:rp.District||'',cls:rp.Road_Class||'',clsLabel:dec('Road_Class',rp.Road_Class||'')||'',rdChain:regAddCh(rdSCh,startCh)});});
  rows.sort((a,b)=>String(a.sec).localeCompare(String(b.sec),undefined,{numeric:true}));
  return rows;
}
function bridgeLen(p,geom,sCh,eCh){
  if(sCh!=null&&eCh!=null&&!isNaN(+sCh)&&!isNaN(+eCh))return Math.abs(Math.round(+eCh-+sCh));
  try{ if(geom&&geom.type==='LineString'&&typeof turf!=='undefined')return Math.round(turf.length({type:'Feature',geometry:geom},{units:'kilometers'})*1000);}catch(e){}
  const L=pickProp(p,['length','len','bridgelength','spanlength','lengthm','totallength']); return regNum(L);
}
function buildBridRows(){
  const rows=[]; ((REG_BRID_GJ&&REG_BRID_GJ.features)||[]).forEach(f=>{const p=f.properties||{};const sec=regSectionKey(p);const rp=roadProps(sec);
    const sCh=regNum(pickProp(p,FROM_KEYS)),eCh=regNum(pickProp(p,TO_KEYS)),rdSCh=regNum(rp.Rd_Str_cha);
    rows.push({sec:sec,name:rp.Road_Name||'',assetId:regAssetId(p),startCh:sCh,endCh:eCh,rdSCh:rdSCh,rdECh:regNum(rp.Rd_End_cha),bStartCh:regAddCh(rdSCh,sCh),bEndCh:regAddCh(rdSCh,eCh),length:regSubCh(eCh,sCh),district:rp.District||'',cls:rp.Road_Class||'',clsLabel:dec('Road_Class',rp.Road_Class||'')||''});});
  rows.sort((a,b)=>String(a.sec).localeCompare(String(b.sec),undefined,{numeric:true}));
  return rows;
}

/* ---- column specs ---- */
function roadCols(){return [
 {l:'Sl',n:true,g:(r,i)=>i+1},{l:'Section Label',cls:'m',g:r=>r.sec},{l:'Road Name',g:r=>r.name},{l:'Road No.',cls:'m',g:r=>r.num},
 {l:'Class',g:r=>r.clsLabel||r.cls},{l:'Start Ch',n:true,g:r=>r.sCh},{l:'End Ch',n:true,g:r=>r.eCh},
 {l:'Road Start Ch',n:true,g:r=>r.rdSCh},{l:'Road End Ch',n:true,g:r=>r.rdECh},
 {l:'Start Location',g:r=>r.sLoc},{l:'End Location',g:r=>r.eLoc},{l:'District',g:r=>r.district},
 {l:'Culverts',n:true,g:r=>r.culv},{l:'Bridges',n:true,g:r=>r.brid}];}
function culvCols(){return [
 {l:'Sl',n:true,g:(r,i)=>i+1},{l:'Asset Id',cls:'m',g:r=>r.assetId},{l:'Section Label',cls:'m',g:r=>r.sec},
 {l:'District',g:r=>r.district},{l:'Road Name',g:r=>r.name},
 {l:'Chainage',n:true,g:r=>r.startCh},{l:'Road Chainage',n:true,g:r=>r.rdChain}];}
function bridCols(){return [
 {l:'Sl',n:true,g:(r,i)=>i+1},{l:'Asset Id',cls:'m',g:r=>r.assetId},{l:'Section Label',cls:'m',g:r=>r.sec},{l:'District',g:r=>r.district},{l:'Road Name',g:r=>r.name},
 {l:'Start Chainage',n:true,g:r=>r.startCh},{l:'End Chainage',n:true,g:r=>r.endCh},
 {l:'Bridge Start Chainage',n:true,g:r=>r.bStartCh},{l:'Bridge End Chainage',n:true,g:r=>r.bEndCh},
 {l:'Length of Bridge (m)',n:true,g:r=>r.length}];}

/* ---- open / load ---- */
function openRegScreen(tab){
  const s=document.getElementById('regScreen'); if(!s)return;
  ['dashboard','pciScreen','condScreen','reportHub'].forEach(id=>{const e=document.getElementById(id);if(e)e.classList.remove('open');});
  s.classList.add('open');
  document.getElementById('regBody').innerHTML='<div class="dash-loading">Preparing Asset Register&hellip;</div>';
  const needRoads=!ROADS||!Object.keys(ROADS).length;
  Promise.resolve()
    .then(()=>needRoads?loadRoads():null)
    .then(()=>REG_CULV_GJ?null:fetch('/api/assets/culvert/geojson').then(r=>r.json()).then(gj=>{REG_CULV_GJ=gj||{features:[]};}).catch(()=>{REG_CULV_GJ={features:[]};}))
    .then(()=>REG_BRID_GJ?null:fetch('/api/assets/bridge/geojson').then(r=>r.json()).then(gj=>{REG_BRID_GJ=gj||{features:[]};}).catch(()=>{REG_BRID_GJ={features:[]};}))
    .then(()=>{ REG_ROADS=buildRoadRows(); REG_CULV=buildCulvRows(); REG_BRID=buildBridRows(); regScreenTab(tab||regTab||'road'); })
    .catch(e=>{document.getElementById('regBody').innerHTML='<div class="dash-loading">Could not build register: '+((e&&e.message)||e)+'</div>';});
}
function closeRegScreen(){ const s=document.getElementById('regScreen'); if(s)s.classList.remove('open'); }
function regScreenTab(t){
  regTab=t;
  [['regTabRoad','road'],['regTabCulv','culvert'],['regTabBrid','bridge']].forEach(([id,k])=>{const b=document.getElementById(id);if(b)b.classList.toggle('on',t===k);});
  if(t==='culvert')renderCulvReg(); else if(t==='bridge')renderBridReg(); else renderRoadReg();
}

/* ---- filtering ---- */
function regDistrictOptions(allRows){const set={};(allRows||[]).forEach(r=>{if(r.district)set[r.district]=1;});return '<option value="">All districts</option>'+Object.keys(set).sort().map(d=>'<option value="'+escH(d)+'"'+(regDistrict===d?' selected':'')+'>'+escH(d)+'</option>').join('');}
function regClassOptions(){const set={};(REG_ROADS||[]).forEach(r=>{if(r.cls)set[r.cls]=1;});return '<option value="">All classes</option>'+Object.keys(set).sort().map(c=>'<option value="'+escH(c)+'"'+(regClass===c?' selected':'')+'>'+escH(dec('Road_Class',c)||c)+'</option>').join('');}
function regAttrOptions(){const set={};(REG_ROADS||[]).forEach(r=>{Object.keys(r.props||{}).forEach(k=>{if(k.charAt(0)!=='_')set[k]=1;});});return '<option value="">&mdash; filter attribute &mdash;</option>'+Object.keys(set).sort().map(k=>'<option value="'+escH(k)+'"'+(regAttr===k?' selected':'')+'>'+escH(k)+'</option>').join('');}
function applyCommon(rows,fields){const q=(regSearch||'').toLowerCase();if(q)rows=rows.filter(r=>fields.map(f=>r[f]).join(' ').toLowerCase().indexOf(q)>=0);if(regDistrict)rows=rows.filter(r=>String(r.district)===regDistrict);return rows;}
function regAttrIsNumeric(attr){if(!attr)return false;let seen=false;const rows=REG_ROADS||[];for(let i=0;i<rows.length;i++){const pv=(rows[i].props||{})[attr];if(pv==null||pv==='')continue;seen=true;if(isNaN(Number(String(pv).replace(/,/g,''))))return false;}return seen;}
function regAttrDistinct(attr){const set={};const rows=REG_ROADS||[];rows.forEach(r=>{const pv=(r.props||{})[attr];if(pv!=null&&pv!=='')set[String(pv)]=1;});let arr=Object.keys(set);const num=regAttrIsNumeric(attr);arr.sort(num?function(a,b){return Number(a)-Number(b);}:function(a,b){return a.localeCompare(b);});return arr;}
function filteredRoadRows(){
  let rows=applyCommon(REG_ROADS||[],['sec','name','num']);
  if(regClass)rows=rows.filter(r=>String(r.cls)===regClass);
  if(regAttr&&regAttrVal!=null&&regAttrVal!==''){
    if(regAttrIsNumeric(regAttr)){
      const target=Number(regAttrVal);
      if(!isNaN(target)){rows=rows.filter(r=>{const pv=(r.props||{})[regAttr];if(pv==null||pv==='')return false;const n=Number(String(pv).replace(/,/g,''));if(isNaN(n))return false;switch(regAttrOp){case'!=':return n!==target;case'<':return n<target;case'<=':return n<=target;case'>':return n>target;case'>=':return n>=target;default:return n===target;}});}
    } else {
      rows=rows.filter(r=>{const pv=(r.props||{})[regAttr];return String(pv==null?'':pv)===regAttrVal;});
    }
  }
  return rows;
}
function filteredCulvRows(){return applyCommon(REG_CULV||[],['sec','name','assetId']);}
function filteredBridRows(){return applyCommon(REG_BRID||[],['sec','name','assetId']);}

function regSetSearch(v){regSearch=v;regScreenTab(regTab);const si=document.getElementById('regSearch');if(si){si.focus();si.setSelectionRange(si.value.length,si.value.length);}}
function regSetClass(v){regClass=v;regScreenTab(regTab);}
function regSetDistrict(v){regDistrict=v;regScreenTab(regTab);}
function regSetAttr(v){regAttr=v;regAttrVal='';regAttrOp='=';regScreenTab(regTab);}
function regSetAttrVal(v){regAttrVal=v;regScreenTab(regTab);const si=document.getElementById('regAttrVal');if(si){si.focus();try{if(si.tagName==='INPUT'&&si.type!=='number')si.setSelectionRange(si.value.length,si.value.length);}catch(e){}}}
function regSetAttrOp(v){regAttrOp=v;regScreenTab(regTab);}

/* ---- generic table render ---- */
function cf(v){return (v==null||v==='')?'\u2013':escH(v);}
function renderReg(cols,rows,extra,countText){
  const body=document.getElementById('regBody');
  const head='<tr>'+cols.map(c=>'<th'+(c.n?' class="n"':'')+'>'+escH(c.l)+'</th>').join('')+'</tr>';
  let tb=''; rows.forEach((r,i)=>{tb+='<tr>'+cols.map(c=>'<td'+(c.n?' class="n"':(c.cls?' class="'+c.cls+'"':''))+'>'+cf(c.g(r,i))+'</td>').join('')+'</tr>';});
  const toolbar='<div class="reg-bar">'
    +'<input id="regSearch" class="reg-search" placeholder="Search&hellip;" value="'+escH(regSearch)+'" oninput="regSetSearch(this.value)">'
    +(extra||'')
    +'<span class="reg-count">'+countText+'</span>'
    +'<span class="reg-exp"><button class="btn ghost" onclick="exportRegExcel()">Excel</button><button class="btn ghost" onclick="printReg()">PDF</button></span>'
    +'</div>';
  body.innerHTML=toolbar+'<div class="reg-tablewrap"><table class="reg-table"><thead>'+head+'</thead><tbody>'
    +(tb||'<tr><td colspan="'+cols.length+'" style="text-align:center;color:#8a93a3;padding:18px">No rows match.</td></tr>')+'</tbody></table></div>';
  const si=document.getElementById('regSearch'); if(si&&regSearch){si.focus();si.setSelectionRange(si.value.length,si.value.length);}
}
function regAttrValControl(){
  if(!regAttr){return '<input id="regAttrVal" class="reg-search" style="max-width:170px;min-width:130px" placeholder="attribute value&hellip;" disabled>';}
  if(regAttrIsNumeric(regAttr)){
    const ops=[['=','='],['\u2260','!='],['<','<'],['\u2264','<='],['>','>'],['\u2265','>=']];
    const opsel='<select id="regAttrOp" class="reg-sel" style="max-width:62px;min-width:56px;text-align:center" onchange="regSetAttrOp(this.value)">'+ops.map(o=>'<option value="'+o[1]+'"'+(regAttrOp===o[1]?' selected':'')+'>'+o[0]+'</option>').join('')+'</select>';
    const inp='<input id="regAttrVal" type="number" class="reg-search" style="max-width:118px;min-width:92px" placeholder="value&hellip;" value="'+escH(regAttrVal)+'" oninput="regSetAttrVal(this.value)">';
    return opsel+inp;
  }
  const vals=regAttrDistinct(regAttr);
  return '<select id="regAttrVal" class="reg-sel" style="max-width:210px;min-width:140px" onchange="regSetAttrVal(this.value)"><option value="">All values</option>'+vals.map(v=>'<option value="'+escH(v)+'"'+(regAttrVal===v?' selected':'')+'>'+escH(v)+'</option>').join('')+'</select>';
}
function renderRoadReg(){
  const rows=filteredRoadRows();
  const extra='<select class="reg-sel" onchange="regSetClass(this.value)">'+regClassOptions()+'</select>'
    +'<select class="reg-sel" onchange="regSetDistrict(this.value)">'+regDistrictOptions(REG_ROADS)+'</select>'
    +'<select class="reg-sel" onchange="regSetAttr(this.value)">'+regAttrOptions()+'</select>'
    +regAttrValControl();
  renderReg(roadCols(),rows,extra,rows.length+' roads');
}
function renderCulvReg(){
  const rows=filteredCulvRows();
  const extra='<select class="reg-sel" onchange="regSetDistrict(this.value)">'+regDistrictOptions(REG_CULV)+'</select>';
  renderReg(culvCols(),rows,extra,rows.length+' culverts');
}
function renderBridReg(){
  const rows=filteredBridRows();
  const extra='<select class="reg-sel" onchange="regSetDistrict(this.value)">'+regDistrictOptions(REG_BRID)+'</select>';
  renderReg(bridCols(),rows,extra,rows.length+' bridges');
}

/* ---- export (active register, filtered) ---- */
function regCurrent(){
  if(regTab==='culvert')return {cols:culvCols(),rows:filteredCulvRows(),title:'Culvert Register',file:'culvert-register'};
  if(regTab==='bridge')return {cols:bridCols(),rows:filteredBridRows(),title:'Bridge Register',file:'bridge-register'};
  return {cols:roadCols(),rows:filteredRoadRows(),title:'Road Asset Register',file:'road-asset-register'};
}
function regMatrix(){const cur=regCurrent();const header=cur.cols.map(c=>c.l);const data=cur.rows.map((r,i)=>cur.cols.map(c=>{const v=c.g(r,i);return v==null?'':v;}));return {header,data,title:cur.title,file:cur.file,cols:cur.cols};}
function exportRegExcel(){
  const {header,data,file}=regMatrix();
  const esc=s=>String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  let html='<table border="1"><thead><tr>'+header.map(h=>'<th>'+esc(h)+'</th>').join('')+'</tr></thead><tbody>';
  data.forEach(row=>{html+='<tr>'+row.map(c=>'<td>'+esc(c)+'</td>').join('')+'</tr>';});
  html+='</tbody></table>';
  const blob=new Blob(['<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel"><head><meta charset="utf-8"></head><body>'+html+'</body></html>'],{type:'application/vnd.ms-excel'});
  const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download=file+'.xls';document.body.appendChild(a);a.click();
  setTimeout(()=>{URL.revokeObjectURL(a.href);a.remove();},600);
}
function printReg(){
  const {header,data,title,cols}=regMatrix();
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

/* ---- full-screen PCI screen (weightages + report), map hidden ---- */
function openPciScreen(tab){
  const s=document.getElementById('pciScreen'); if(!s)return;
  ['dashboard','condScreen','regScreen','reportHub'].forEach(id=>{const e=document.getElementById(id);if(e)e.classList.remove('open');});
  s.classList.add('open');
  if(typeof renderPciLegend==='function')renderPciLegend();
  pciScreenTab(tab||'weights');
}
function closePciScreen(){ const s=document.getElementById('pciScreen'); if(s)s.classList.remove('open'); window.PCI_RPT_TARGET='dashBody'; }
function pciScreenTab(t){
  const bW=document.getElementById('pciTabW'), bR=document.getElementById('pciTabR');
  if(bW)bW.classList.toggle('on',t==='weights');
  if(bR)bR.classList.toggle('on',t==='report');
  const vW=document.getElementById('pciViewWeights'), vR=document.getElementById('pciViewReport');
  if(vW)vW.style.display=(t==='weights')?'':'none';
  if(vR)vR.style.display=(t==='report')?'':'none';
  if(t==='report'){ window.PCI_RPT_TARGET='pciScreenReport'; dashTabCur='pci'; renderPciReport(); }
  else { window.PCI_RPT_TARGET='dashBody'; if(typeof renderPciWeights==='function')renderPciWeights(); }
}
function openPciReport(){ openPciScreen('report'); }

/* ---- full-screen Road Condition Data screen (map hidden) ---- */
function openCondScreen(){
  const s=document.getElementById('condScreen'); if(!s)return;
  ['dashboard','pciScreen','regScreen','reportHub'].forEach(id=>{const e=document.getElementById(id);if(e)e.classList.remove('open');});
  s.classList.add('open');
}
function closeCondScreen(){ const s=document.getElementById('condScreen'); if(s)s.classList.remove('open'); }
