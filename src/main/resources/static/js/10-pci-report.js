/* ============================================================
   KLRAMS viewer · 10-pci-report.js
   PCI report: per-section and per-chainage tables, distribution bar, CSV and PDF export.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
/* ---- PCI report (PWD-section-wise) ---- */
let lastPciReport=null;
const PCI_ORDER=['Excellent','Good','Satisfactory','Fair','Poor','Fail'];
function pciClassColor(label){const b=PCI_BANDS.find(x=>x.label===label);return b?b.color:'#b9c2cc';}
function syncLazyVis(){const cOn=!!((document.getElementById('showCond')||{}).checked);const _rt=document.getElementById('showRoads');if(map.getLayer('roadnet-casing'))map.setLayoutProperty('roadnet-casing','visibility',(_rt&&_rt.checked)?'visible':'none');[['roadnet','showRoads'],['pci-avg','showPciAvg'],['pci-worst','showPciWorst']].forEach(([l,t])=>{const tg=document.getElementById(t);if(map.getLayer(l))map.setLayoutProperty(l,'visibility',(tg&&tg.checked)?'visible':'none');});['seg-CC','seg-CL1','seg-CL2','seg-CR1','seg-CR2'].forEach(l=>{if(map.getLayer(l))map.setLayoutProperty(l,'visibility',cOn?'visible':'none');});}
function pciRptBody(){return document.getElementById(window.PCI_RPT_TARGET||'dashBody');}
function renderPciReport(){
  const body=pciRptBody();
  const needRoads=!ROADS||!Object.keys(ROADS).length;
  const needSegs=!DATA||!DATA.features||!DATA.features.length;
  if(needRoads||needSegs){
    body.innerHTML='<div class="dash-loading">Preparing PCI report — loading road &amp; condition data…</div>';
    Promise.resolve().then(()=>needRoads?loadRoads():null).then(()=>needSegs?loadSegments():null).then(()=>{
      syncLazyVis();
      if(dashTabCur!=='pci')return;
      if(!DATA||!DATA.features||!DATA.features.length){body.innerHTML='<div class="dash-loading">No condition segments yet. Build them in the Data console first.</div>';return;}
      renderPciReportNow();
    }).catch(e=>{body.innerHTML='<div class="dash-loading">Could not prepare PCI report: '+((e&&e.message)||e)+'</div>';});
    return;
  }
  renderPciReportNow();
}
let pciRptMode='pwd';let pciRptBasis='avg';
function setPciRptMode(m){pciRptMode=m;renderPciReportNow();}
function setPciRptBasis(b){pciRptBasis=b;renderPciReportNow();}
function segEnds(f){const g=f&&f.geometry;if(!g||!g.coordinates||!g.coordinates.length)return [null,null];let cs=g.coordinates;if(g.type==='MultiLineString'){cs=[];g.coordinates.forEach(a=>a.forEach(pt=>cs.push(pt)));}if(!cs.length)return [null,null];return [cs[0],cs[cs.length-1]];}
function llc(lat,lng){return (lat==null||lng==null||isNaN(lat)||isNaN(lng))?'\u2013':(+lat).toFixed(6)+', '+(+lng).toFixed(6);}
function pciReportData(basis){
  PCI_PARAMS.forEach(pp=>{const el=document.getElementById('w_'+pp.key);if(el)PCI_W[pp.key]=+el.value||0;});
  const sections={};
  (DATA.features||[]).forEach(f=>{
    const p=f.properties;
    const L=Math.max(0,(+p.to_ch||0)-(+p.from_ch||0))||1;
    const area=L*pavementWidthM(p.road);
    /* Composite basis -> pool the segment's area-weighted distress into the section,
       then compute ONE PCI per section. Worst-Lane basis -> area-weighted avg of
       segment Worst-Lane PCIs (unchanged). */
    let wpci=null,rep=null;
    if(basis==='worst'){wpci=segPCI(p,'worst');if(wpci==null)return;}
    else{rep=(pciRepr(p,'avg')||{}).dist||{};let any=false;PCI_PARAMS.forEach(pp=>{const v=rep[pp.key];if(v!=null&&v!=='')any=true;});if(!any)return;}
    const road=(p.road==null?'\u2014':String(p.road));
    const rd=ROADS[p.road];const rp=rd?rd.properties:{};
    const pwd=(rp.PWD_Sec!=null&&String(rp.PWD_Sec).trim()!=='')?String(rp.PWD_Sec):'Unassigned';
    const district=(rp.District!=null&&String(rp.District).trim()!=='')?String(rp.District):'Unassigned';
    const ee=segEnds(f);const fr=+p.from_ch||0,to=+p.to_ch||0;
    let sct=sections[road];
    if(!sct)sct=sections[road]={label:road,name:(rp.name||rp.Road_Name||''),num:(rp.Road_Num||''),pwd:pwd,district:district,rclass:(rp.Road_Class!=null?String(rp.Road_Class).trim().toUpperCase():''),len:0,w:0,wlen:0,area:0,dsum:{},dwt:{},minCh:Infinity,maxCh:-Infinity,slat:null,slng:null,elat:null,elng:null};
    sct.len+=L;sct.area+=area;
    if(basis==='worst'){sct.w+=wpci*L;sct.wlen+=L;}
    else{PCI_PARAMS.forEach(pp=>{const v=rep[pp.key];if(v!=null&&v!==''){sct.dsum[pp.key]=(sct.dsum[pp.key]||0)+(+v)*L;sct.dwt[pp.key]=(sct.dwt[pp.key]||0)+L;}});}
    if(fr<sct.minCh){sct.minCh=fr;if(ee[0]){sct.slat=ee[0][1];sct.slng=ee[0][0];}}
    if(to>sct.maxCh){sct.maxCh=to;if(ee[1]){sct.elat=ee[1][1];sct.elng=ee[1][0];}}
  });
  const secs=Object.values(sections);
  secs.forEach(sc=>{
    if(basis==='worst'){sc.pci=sc.wlen?sc.w/sc.wlen:null;}
    else{const d={};PCI_PARAMS.forEach(pp=>{if(sc.dwt[pp.key])d[pp.key]=sc.dsum[pp.key]/sc.dwt[pp.key];});sc.pci=pciFromDist(d);}
    sc.band=sc.pci!=null?pciBand(sc.pci):null;
  });
  const segM={};let segTot=0,segW=0,segAr=0;
  (DATA.features||[]).forEach(f=>{const v=segPCI(f.properties,basis);if(v==null)return;const L=Math.max(0,(+f.properties.to_ch||0)-(+f.properties.from_ch||0))||1;const a=L*pavementWidthM(f.properties.road);segM[pciBand(v).label]=(segM[pciBand(v).label]||0)+L;segTot+=L;segW+=v*a;segAr+=a;});
  return {sections:secs,seg:{m:segM,tot:segTot,w:segW,area:segAr}};
}
/* Area weighting: pavement width (m) from the road-network Pavement_W code (1-5).
   Dual-carriageway correction: a dual road is drawn as TWO centrelines (A/B) but
   the shapefile's Pavement_W describes the ENTIRE road (e.g. a 4-lane dual gets
   code 5 on both halves). Each A/B line is only ONE carriageway, so use half the
   banded width per line — otherwise the road's area is counted twice. */
var PVMT_W_M={'1':4.5,'2':6.25,'3':8.5,'4':11.5,'5':14};
function pavementWidthM(sec){var rd=ROADS[sec];var rp=rd&&rd.properties;var code=rp?rp.Pavement_W:null;var w=(code==null||code==='')?7:(PVMT_W_M[String(code).trim()]||7);var dual=rp&&rp.Single_Du!=null&&String(rp.Single_Du).trim().toLowerCase()==='dual';return dual?w/2:w;}
function groupBy(secs,field){const g={};secs.forEach(s=>{const k=s[field]||'Unassigned';(g[k]=g[k]||[]).push(s);});return g;}
function bandDist(secs){const m={};let tot=0;secs.forEach(s=>{if(s.pci==null)return;tot+=s.len;m[s.band.label]=(m[s.band.label]||0)+s.len;});return {m,tot};}
function stackBar(m,tot){
  let bar='<div class="rpt-stack">',leg='<div class="rpt-leg">';
  PCI_ORDER.forEach(lb=>{const len=m[lb]||0;if(len<=0)return;const pct=tot?len/tot*100:0;const c=pciClassColor(lb);
    bar+='<i style="width:'+pct.toFixed(2)+'%;background:'+c+'" title="'+lb+'"></i>';
    leg+='<span><i style="background:'+c+'"></i>'+lb+' '+(len/1000).toFixed(1)+' km ('+pct.toFixed(0)+'%)</span>';});
  return bar+'</div>'+leg+'</div>';
}
function pciHead(mode){
  if(mode==='district')return '<tr><th>Road No.</th><th>Road Name</th><th>Section Label</th><th class="num">Length (km)</th><th class="num">PCI</th><th>Class</th><th>Start (lat, lng)</th><th>End (lat, lng)</th></tr>';
  return '<tr><th>Section Label</th><th>Road Name</th><th class="num">Length (km)</th><th class="num">PCI</th><th>Class</th><th>Start (lat, lng)</th><th>End (lat, lng)</th></tr>';
}
function pciRowCells(s,mode){
  const len='<td class="num">'+(s.len/1000).toFixed(2)+'</td>';
  const pci='<td class="num">'+(s.pci!=null?s.pci.toFixed(1):'\u2013')+'</td>';
  const cls='<td>'+(s.band?'<span class="pci-chip" style="background:'+s.band.color+'">'+s.band.label+'</span>':'\u2013')+'</td>';
  const ll='<td class="mono" style="font-size:10px;white-space:nowrap">'+llc(s.slat,s.slng)+'</td><td class="mono" style="font-size:10px;white-space:nowrap">'+llc(s.elat,s.elng)+'</td>';
  if(mode==='district')return '<td class="mono">'+escH(s.num||'\u2013')+'</td><td>'+escH(s.name||'\u2014')+'</td><td class="mono">'+escH(s.label)+'</td>'+len+pci+cls+ll;
  return '<td class="mono">'+escH(s.label)+'</td><td>'+escH(s.name||'\u2014')+'</td>'+len+pci+cls+ll;
}
let pciRptView='section';let pciRptFilt='all';
function setPciRptView(v){pciRptView=v;renderPciReportNow();}
function setPciRptFilt(f){pciRptFilt=f;renderPciReportNow();}
function pciStretchData(basis){const rows=[];(DATA.features||[]).forEach(f=>{const p=f.properties;const pci=segPCI(p,basis);if(pci==null)return;const rd=ROADS[p.road];const rp=rd?rd.properties:{};const ee=segEnds(f);rows.push({label:(p.road==null?'\u2014':String(p.road)),name:(rp.name||rp.Road_Name||''),num:(rp.Road_Num||''),pwd:(rp.PWD_Sec&&String(rp.PWD_Sec).trim()!=='')?String(rp.PWD_Sec):'Unassigned',district:(rp.District&&String(rp.District).trim()!=='')?String(rp.District):'Unassigned',fr:Math.round(+p.from_ch||0),to:Math.round(+p.to_ch||0),len:Math.max(0,(+p.to_ch||0)-(+p.from_ch||0))||0,pci:pci,band:pciBand(pci),slat:ee[0]?ee[0][1]:null,slng:ee[0]?ee[0][0]:null,elat:ee[1]?ee[1][1]:null,elng:ee[1]?ee[1][0]:null});});return rows;}
function stretchHead(mode){return (mode==='district')?'<tr><th>Road No.</th><th>Road Name</th><th>Section Label</th><th>Chainage (m)</th><th class="num">Len</th><th class="num">PCI</th><th>Class</th><th>Start (lat, lng)</th><th>End (lat, lng)</th></tr>':'<tr><th>Section Label</th><th>Road Name</th><th>Chainage (m)</th><th class="num">Len</th><th class="num">PCI</th><th>Class</th><th>Start (lat, lng)</th><th>End (lat, lng)</th></tr>';}
function stretchRow(s,mode){const ch='<td class="mono">'+s.fr+'\u2013'+s.to+'</td>';const len='<td class="num">'+Math.round(s.len)+'</td>';const pci='<td class="num">'+s.pci.toFixed(1)+'</td>';const cls='<td><span class="pci-chip" style="background:'+s.band.color+'">'+s.band.label+'</span></td>';const ll='<td class="mono" style="font-size:10px;white-space:nowrap">'+llc(s.slat,s.slng)+'</td><td class="mono" style="font-size:10px;white-space:nowrap">'+llc(s.elat,s.elng)+'</td>';return (mode==='district')?'<td class="mono">'+escH(s.num||'\u2013')+'</td><td>'+escH(s.name||'\u2014')+'</td><td class="mono">'+escH(s.label)+'</td>'+ch+len+pci+cls+ll:'<td class="mono">'+escH(s.label)+'</td><td>'+escH(s.name||'\u2014')+'</td>'+ch+len+pci+cls+ll;}
const cmpSecChain=(a,b)=>{const L=String(a.label||'').localeCompare(String(b.label||''),undefined,{numeric:true});if(L)return L;const N=String(a.name||'').localeCompare(String(b.name||''),undefined,{numeric:true});if(N)return N;return (a.fr||0)-(b.fr||0);};
function mergeStretches(rows){const bySec={};rows.forEach(r=>{(bySec[r.label]=bySec[r.label]||[]).push(r);});const out=[];Object.keys(bySec).forEach(lab=>{const list=bySec[lab].slice().sort((a,b)=>a.fr-b.fr);let cur=null;list.forEach(r=>{if(cur&&r.band.label===cur.band.label&&r.fr===cur.to){cur.to=r.to;cur.len+=r.len;cur._w+=r.pci*r.len;cur.n++;cur.elat=r.elat;cur.elng=r.elng;}else{if(cur)out.push(cur);cur={label:r.label,name:r.name,num:r.num,pwd:r.pwd,district:r.district,fr:r.fr,to:r.to,len:r.len,band:r.band,_w:r.pci*r.len,n:1,slat:r.slat,slng:r.slng,elat:r.elat,elng:r.elng};}});if(cur)out.push(cur);});out.forEach(o=>{o.pci=o.len?o._w/o.len:0;});return out;}
function renderPciReportNow(){
  const body=pciRptBody();
  const basis=pciRptBasis;const rd=pciReportData(basis);const sections=rd.sections;
  if(!sections.length){body.innerHTML='<div class="dash-loading">No PCI could be computed \u2014 check that condition segments carry distress values.</div>';return;}
  const mode=pciRptMode;const field=(mode==='district')?'district':'pwd';
  const m=rd.seg.m,tot=rd.seg.tot;
  const avg=(function(){let w=0,a=0;sections.forEach(s=>{if(s.pci!=null){w+=s.pci*s.area;a+=s.area;}});return a?w/a:null;})();const ab=avg!=null?pciBand(avg):null;
  const view=pciRptView,filt=pciRptFilt;
  const groups=groupBy(sections,field);
  let sgroups=null;
  if(view==='stretch'){let rows=mergeStretches(pciStretchData(basis));if(filt==='pf')rows=rows.filter(r=>r.band.label==='Poor'||r.band.label==='Fail');sgroups=groupBy(rows,field);}
  lastPciReport={groups,sgroups,avg,sections,seg:rd.seg,generated:new Date(),mode:mode,basis:basis,view:view,filt:filt};
  const dl='<svg viewBox="0 0 24 24"><path d="M12 3v12"/><path d="M7 11l5 5 5-5"/><path d="M5 21h14"/></svg>';
  const pr='<svg viewBox="0 0 24 24"><path d="M6 9V3h12v6"/><path d="M6 18H4a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v4a2 2 0 0 1-2 2h-2"/><path d="M6 14h12v7H6z"/></svg>';
  let html='<div class="rpt-bar"><span style="display:flex;gap:10px;flex-wrap:wrap">'+
    '<span class="segmented"><button class="'+(mode==='pwd'?'on':'')+'" onclick="setPciRptMode(\'pwd\')">By PWD section</button><button class="'+(mode==='district'?'on':'')+'" onclick="setPciRptMode(\'district\')">By district</button></span>'+
    '<span class="segmented"><button class="'+(basis==='avg'?'on':'')+'" onclick="setPciRptBasis(\'avg\')">Composite</button><button class="'+(basis==='worst'?'on':'')+'" onclick="setPciRptBasis(\'worst\')">Worst-Lane</button></span>'+
    '<span class="segmented"><button class="'+(view==='section'?'on':'')+'" onclick="setPciRptView(\'section\')">Section average</button><button class="'+(view==='stretch'?'on':'')+'" onclick="setPciRptView(\'stretch\')">Chainage stretch</button></span>'+
    (view==='stretch'?('<span class="segmented"><button class="'+(filt==='all'?'on':'')+'" onclick="setPciRptFilt(\'all\')">All classes</button><button class="'+(filt==='pf'?'on':'')+'" onclick="setPciRptFilt(\'pf\')">Poor &amp; Fail</button></span>'):'')+
    '</span><span style="display:flex;gap:8px">'+
    '<button class="rpt-btn" onclick="exportPciCsv()">'+dl+'Download CSV</button>'+
    '<button class="rpt-btn primary" onclick="printPciReport()">'+pr+'Generate report (PDF)</button></span></div>';
  const gword=(mode==='district')?'districts':'PWD sections';
  html+='<div class="rpt-summary"><div class="rs-top"><span class="big">'+(avg!=null?avg.toFixed(1):'\u2013')+'</span>'+(ab?'<span class="pci-chip" style="background:'+ab.color+'">'+ab.label+'</span>':'')+'<span class="rs-cap">'+pciBasisLabel(basis)+' &middot; network average (area-weighted) \u00b7 '+sections.length+' road sections across '+Object.keys(groups).length+' '+gword+' \u00b7 '+(tot/1000).toFixed(1)+' km surveyed</span></div>'+stackBar(m,tot)+'<div class="rpt-note">'+(view==='stretch'?('Contiguous chainage stretches of the <b>same class</b> are merged into one row, sorted by section then chainage'+(filt==='pf'?'; showing <b>Poor &amp; Fail</b> only':'')+'.'):'Class split is by <b>surveyed length (chainage)</b>. Rows below are each road section\u2019s <b>area-weighted average</b> PCI \u2014 switch to <b>Chainage stretch</b> to list individual Poor stretches.')+'</div></div>';
  if(view==='stretch'){
    const gk=Object.keys(sgroups||{}).sort();
    if(!gk.length)html+='<div class="dash-loading">No stretches match this filter.</div>';
    gk.forEach(g=>{const list=sgroups[g].slice().sort(cmpSecChain);let glen=0;list.forEach(x=>glen+=x.len);const npf=list.filter(x=>x.band.label==='Poor'||x.band.label==='Fail').length;
      html+='<div class="pwd-card"><div class="pwd-head"><span class="pn">'+escH(g)+'</span><span class="pm">'+list.length+' bands \u00b7 '+(glen/1000).toFixed(1)+' km'+(npf?' \u00b7 <b style="color:#c0392b">'+npf+' Poor/Fail</b>':'')+'</span></div><div style="overflow-x:auto"><table class="rpt-table"><thead>'+stretchHead(mode)+'</thead><tbody>'+list.map(x=>'<tr>'+stretchRow(x,mode)+'</tr>').join('')+'</tbody></table></div></div>';});
  }else{
    Object.keys(groups).sort().forEach(g=>{const list=groups[g].slice().sort(cmpSecChain);let glen=0,gw=0,gar=0;list.forEach(x=>{if(x.pci!=null){glen+=x.len;gw+=x.pci*x.area;gar+=x.area;}});const gavg=gar?gw/gar:null;const gb=gavg!=null?pciBand(gavg):null;
      html+='<div class="pwd-card"><div class="pwd-head"><span class="pn">'+escH(g)+'</span><span class="pm">'+list.length+' sections \u00b7 '+(glen/1000).toFixed(1)+' km \u00b7 avg '+(gavg!=null?gavg.toFixed(1):'\u2013')+(gb?' <span class="pci-chip" style="background:'+gb.color+'">'+gb.label+'</span>':'')+'</span></div><div style="overflow-x:auto"><table class="rpt-table"><thead>'+pciHead(mode)+'</thead><tbody>'+list.map(x=>'<tr>'+pciRowCells(x,mode)+'</tr>').join('')+'</tbody></table></div></div>';});
  }
  body.innerHTML=html;
}
function exportPciCsv(){
  if(!lastPciReport)return;const mode=lastPciReport.mode,view=lastPciReport.view;
  let header,rowf,grp;
  const cf=v=>(v==null||isNaN(v))?'':(+v).toFixed(6);
  if(view==='stretch'){
    grp=lastPciReport.sgroups||{};
    if(mode==='district'){header=['District','Road Number','Road Name','Section Label','Chainage_from_m','Chainage_to_m','Length_m','PCI','Class','Start_Lat','Start_Lng','End_Lat','End_Lng'];rowf=(g,s)=>[g,s.num,s.name,s.label,s.fr,s.to,Math.round(s.len),s.pci.toFixed(1),s.band.label,cf(s.slat),cf(s.slng),cf(s.elat),cf(s.elng)];}
    else{header=['PWD Section','Section Label','Road Name','Chainage_from_m','Chainage_to_m','Length_m','PCI','Class','Start_Lat','Start_Lng','End_Lat','End_Lng'];rowf=(g,s)=>[g,s.label,s.name,s.fr,s.to,Math.round(s.len),s.pci.toFixed(1),s.band.label,cf(s.slat),cf(s.slng),cf(s.elat),cf(s.elng)];}
  }else{
    grp=lastPciReport.groups;
    if(mode==='district'){header=['District','Road Number','Road Name','Section Label','Length_km','PCI','Class','Start_Lat','Start_Lng','End_Lat','End_Lng'];rowf=(g,s)=>[g,s.num,s.name,s.label,(s.len/1000).toFixed(3),s.pci!=null?s.pci.toFixed(1):'',s.band?s.band.label:'',cf(s.slat),cf(s.slng),cf(s.elat),cf(s.elng)];}
    else{header=['PWD Section','Section Label','Road Name','Length_km','PCI','Class','Start_Lat','Start_Lng','End_Lat','End_Lng'];rowf=(g,s)=>[g,s.label,s.name,(s.len/1000).toFixed(3),s.pci!=null?s.pci.toFixed(1):'',s.band?s.band.label:'',cf(s.slat),cf(s.slng),cf(s.elat),cf(s.elng)];}
  }
  const rows=[header];
  Object.keys(grp).sort().forEach(g=>{grp[g].slice().sort(cmpSecChain).forEach(s=>rows.push(rowf(g,s)));});
  const csv=rows.map(r=>r.map(c=>{c=String(c==null?'':c);return /[",\n]/.test(c)?'"'+c.replace(/"/g,'""')+'"':c;}).join(',')).join('\n');
  const blob=new Blob(['\ufeff'+csv],{type:'text/csv;charset=utf-8'});const url=URL.createObjectURL(blob);
  const a=document.createElement('a');a.href=url;a.download='KLRAMS_PCI_'+(view==='stretch'?'Stretch_':'')+(lastPciReport.basis==='worst'?'WorstLane':'Composite')+'_'+(mode==='district'?'District':'PWD')+'_'+new Date().toISOString().slice(0,10)+'.csv';
  document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),1500);
}
function printPciReport(){
  if(!lastPciReport)return;
  const {groups,avg,sections,generated,mode,seg,basis,view}=lastPciReport;
  const ab=avg!=null?pciBand(avg):null;const m=seg.m,tot=seg.tot;
  const dt=generated.toLocaleString('en-IN',{dateStyle:'medium',timeStyle:'short'});
  const grpWord=((mode==='district')?'District-wise':'PWD-section-wise')+' \u00b7 '+pciBasisLabel(basis)+' \u00b7 '+(view==='stretch'?('Chainage stretches'+(lastPciReport.filt==='pf'?' (Poor & Fail)':'')):'Section averages');
  const wrow=PCI_PARAMS.map(pp=>'<span style="margin-right:14px">'+pp.label+' <b>'+(+PCI_W[pp.key]||0).toFixed(2)+'</b></span>').join('');
  let dist='';PCI_ORDER.forEach(lb=>{const len=m[lb]||0;if(len<=0)return;dist+='<span style="display:inline-block;margin-right:14px"><span style="display:inline-block;width:10px;height:10px;border-radius:2px;background:'+pciClassColor(lb)+';margin-right:5px"></span>'+lb+' '+(len/1000).toFixed(1)+' km ('+(tot?(len/tot*100):0).toFixed(0)+'%)</span>';});
  let thead,body='';
  const cf5=v=>(v==null||isNaN(v))?'\u2013':(+v).toFixed(5);const llp=(a,b)=>cf5(a)+', '+cf5(b);
  if(view==='stretch'){
    thead=(mode==='district')?'<tr><th>Road No.</th><th>Road Name</th><th>Section Label</th><th>Chainage (m)</th><th class="n">Len</th><th class="n">PCI</th><th>Class</th><th>Start (lat,lng)</th><th>End (lat,lng)</th></tr>':'<tr><th>Section Label</th><th>Road Name</th><th>Chainage (m)</th><th class="n">Len</th><th class="n">PCI</th><th>Class</th><th>Start (lat,lng)</th><th>End (lat,lng)</th></tr>';
    const sr=s=>{const ch='<td class="m">'+s.fr+'\u2013'+s.to+'</td>';const len='<td class="n">'+Math.round(s.len)+'</td>';const pci='<td class="n">'+s.pci.toFixed(1)+'</td>';const chip='<td><span class="chip" style="background:'+s.band.color+'">'+s.band.label+'</span></td>';const ll='<td class="m" style="font-size:8.5px">'+llp(s.slat,s.slng)+'</td><td class="m" style="font-size:8.5px">'+llp(s.elat,s.elng)+'</td>';return (mode==='district')?'<tr><td class="m">'+escH(s.num||'\u2013')+'</td><td>'+escH(s.name||'\u2014')+'</td><td class="m">'+escH(s.label)+'</td>'+ch+len+pci+chip+ll+'</tr>':'<tr><td class="m">'+escH(s.label)+'</td><td>'+escH(s.name||'\u2014')+'</td>'+ch+len+pci+chip+ll+'</tr>';};
    const sg=lastPciReport.sgroups||{};
    Object.keys(sg).sort().forEach(g=>{const list=sg[g].slice().sort(cmpSecChain);let glen=0;list.forEach(x=>glen+=x.len);const npf=list.filter(x=>x.band.label==='Poor'||x.band.label==='Fail').length;
      body+='<h3 class="pwd">'+escH(g)+' <small>'+list.length+' bands \u00b7 '+(glen/1000).toFixed(1)+' km'+(npf?' \u00b7 '+npf+' Poor/Fail':'')+'</small></h3><table><thead>'+thead+'</thead><tbody>'+list.map(sr).join('')+'</tbody></table>';});
  }else{
  thead=(mode==='district')?'<tr><th>Road No.</th><th>Road Name</th><th>Section Label</th><th class="n">Length (km)</th><th class="n">PCI</th><th>Class</th><th>Start (lat,lng)</th><th>End (lat,lng)</th></tr>':'<tr><th>Section Label</th><th>Road Name</th><th class="n">Length (km)</th><th class="n">PCI</th><th>Class</th><th>Start (lat,lng)</th><th>End (lat,lng)</th></tr>';
  function pr_row(s){const len='<td class="n">'+(s.len/1000).toFixed(2)+'</td>';const pci='<td class="n">'+(s.pci!=null?s.pci.toFixed(1):'\u2013')+'</td>';const chip='<td><span class="chip" style="background:'+(s.band?s.band.color:'#b9c2cc')+'">'+(s.band?s.band.label:'\u2013')+'</span></td>';const ll='<td class="m" style="font-size:8.5px">'+llp(s.slat,s.slng)+'</td><td class="m" style="font-size:8.5px">'+llp(s.elat,s.elng)+'</td>';
    if(mode==='district')return '<tr><td class="m">'+escH(s.num||'\u2013')+'</td><td>'+escH(s.name||'\u2014')+'</td><td class="m">'+escH(s.label)+'</td>'+len+pci+chip+ll+'</tr>';
    return '<tr><td class="m">'+escH(s.label)+'</td><td>'+escH(s.name||'\u2014')+'</td>'+len+pci+chip+ll+'</tr>';}
  Object.keys(groups).sort().forEach(g=>{
    const list=groups[g].slice().sort(cmpSecChain);
    let glen=0,gw=0,gar=0;list.forEach(s=>{if(s.pci!=null){glen+=s.len;gw+=s.pci*s.area;gar+=s.area;}});
    const gavg=gar?gw/gar:null;const gb=gavg!=null?pciBand(gavg):null;
    body+='<h3 class="pwd">'+escH(g)+' <small>'+list.length+' sections \u00b7 '+(glen/1000).toFixed(1)+' km \u00b7 avg PCI '+(gavg!=null?gavg.toFixed(1):'\u2013')+(gb?' ('+gb.label+')':'')+'</small></h3>';
    body+='<table><thead>'+thead+'</thead><tbody>'+list.map(pr_row).join('')+'</tbody></table>';
  });
  }
  const css='*{box-sizing:border-box}body{font-family:Calibri,Segoe UI,Arial,sans-serif;color:#16202e;margin:0;padding:34px 40px}'+
    '.brand{display:flex;justify-content:space-between;align-items:flex-start;border-bottom:3px solid #00C070;padding-bottom:12px;margin-bottom:18px}'+
    '.brand .t{font-size:21px;font-weight:700;color:#0A0A0A}.brand .s{font-size:12px;color:#5b6b80;margin-top:3px}'+
    '.brand .r{font-size:11px;color:#5b6b80;text-align:right}'+
    '.kpi{display:flex;gap:24px;align-items:baseline;margin:6px 0 10px}.kpi .v{font-size:30px;font-weight:700}'+
    '.chip{display:inline-block;color:#fff;font-size:10.5px;font-weight:700;border-radius:20px;padding:2px 9px}'+
    '.meta{font-size:11px;color:#5b6b80;margin:4px 0 14px;line-height:1.6}'+
    'h3.pwd{font-size:13.5px;margin:18px 0 6px;color:#0A0A0A;border-left:4px solid #1B5E3F;padding-left:9px}h3.pwd small{font-weight:400;color:#5b6b80;font-size:11px}'+
    'table{width:100%;border-collapse:collapse;font-size:11px;margin-bottom:6px}'+
    'th{background:#0A0A0A;color:#fff;text-align:left;padding:6px 9px;font-size:10px;text-transform:uppercase;letter-spacing:.4px}'+
    'th.n,td.n{text-align:right}td{padding:5px 9px;border-bottom:1px solid #e6ebf1}td.m{font-family:Consolas,monospace}'+
    'tr{page-break-inside:avoid}thead{display:table-header-group}'+
    '.foot{margin-top:18px;border-top:1px solid #e6ebf1;padding-top:8px;font-size:10px;color:#8a97a8;display:flex;justify-content:space-between}'+
    '@page{size:A4 landscape;margin:12mm}';
  const doc='<!doctype html><html><head><meta charset="utf-8"><title>KLRAMS PCI Report</title><style>'+css+'</style></head><body>'+
    '<div class="brand"><div><div class="t">KLRAMS \u2014 Pavement Condition Index Report</div><div class="s">Kerala Road Asset Management System \u00b7 Kerala PWD \u00b7 '+grpWord+'</div></div>'+
    '<div class="r">Generated '+escH(dt)+'<br>Method: IRC:82-2023 PCI &middot; area-weighted</div></div>'+
    '<div class="kpi"><div><div class="v">'+(avg!=null?avg.toFixed(1):'\u2013')+(ab?' <span class="chip" style="background:'+ab.color+'">'+ab.label+'</span>':'')+'</div><div class="s" style="font-size:11px;color:#5b6b80">Network average PCI (area-weighted)</div></div></div>'+
    '<div class="meta"><b>Coverage:</b> '+sections.length+' road sections \u00b7 '+Object.keys(groups).length+' '+((mode==='district')?'districts':'PWD sections')+' \u00b7 '+(tot/1000).toFixed(1)+' km scored.<br><b>Class distribution:</b> '+dist+'<br><b>Weightages (IRC:82-2023 Table 5.4):</b> '+wrow+'</div>'+
    body+
    '<div class="foot"><span>KLRAMS \u00b7 Fist Innovations Pvt. Ltd.</span><span>PCI per IRC:82-2023 \u2014 for maintenance planning</span></div>'+
    '</body></html>';
  const w=window.open('','_blank');
  if(!w){alert('Please allow pop-ups to generate the printable report.');return;}
  w.document.open();w.document.write(doc);w.document.close();w.focus();
  setTimeout(()=>{try{w.print();}catch(e){}},400);
}

/* ============================================================
   PCI Analysis (build 81) — dashboard tab.
   (1) Share of network length by PCI rating (Excellent…Fail).
   (2) PCI-rating split by road class, SH / MDR / ODR / NH each separate.
   Computed at segment level (matches the report's area-weighting).
   ============================================================ */
let pciaBasis='avg';
function setPciaBasis(b){pciaBasis=b;renderPciAnalysisNow();}
/* Lowest-PCI ROADS by class (SH / MDR), statewide and district-wise.
   Grouping mirrors ConditionDashboardController.topRoads():
     • SH  -> group by Road Number, falling back to Road Name when a section
              carries no number;
     • MDR -> group by Road Name.
   A road's PCI is the area-weighted average of its sections' PCIs (area =
   section length x carriageway width), the same aggregation the report uses to
   roll sections up into district / PWD-section averages. */
function pciRoadKey(s,cls){
  const num=(s.num!=null?String(s.num).trim():'');
  const nm=(s.name!=null?String(s.name).trim():'');
  return (cls==='SH')?(num||nm):nm;
}
function pciRoadsFor(secs,cls){
  const g={};
  secs.forEach(function(s){
    if(s.rclass!==cls||s.pci==null||!s.area)return;
    const key=pciRoadKey(s,cls);if(!key)return;
    let r=g[key];if(!r)r=g[key]={key:key,num:'',name:'',dset:{},w:0,area:0,len:0,nsec:0};
    r.w+=s.pci*s.area;r.area+=s.area;r.len+=s.len;r.nsec++;
    const num=(s.num!=null?String(s.num).trim():'');if(num&&!r.num)r.num=num;
    const nm=(s.name!=null?String(s.name).trim():'');if(nm&&!r.name)r.name=nm;
    if(s.district)r.dset[s.district]=(r.dset[s.district]||0)+s.area;
  });
  return Object.values(g).map(function(r){
    r.pci=r.area?r.w/r.area:null;r.band=r.pci!=null?pciBand(r.pci):null;
    r.district=Object.keys(r.dset).sort().join(', ');
    return r;
  });
}
function pciLowestRoads(secs,cls,n){return pciRoadsFor(secs,cls).filter(function(r){return r.pci!=null;}).sort(function(a,b){return a.pci-b.pci;}).slice(0,n);}
function pciLowTable(list,cls,showDist){
  if(!list||!list.length)return '<div class="sub" style="padding:6px 2px">No '+cls+' roads in scope.</div>';
  const nh=(cls==='SH')?'<th>Road No.</th>':'';
  const dh=showDist?'<th>District</th>':'';
  const rows=list.map(function(r,i){
    const chip=r.band?'<span class="pci-chip" style="background:'+r.band.color+'">'+r.band.label+'</span>':'–';
    const nc=(cls==='SH')?'<td class="mono">'+escH(r.num||'–')+'</td>':'';
    const dc=showDist?'<td>'+escH(r.district||'–')+'</td>':'';
    return '<tr><td class="num">'+(i+1)+'</td>'+nc+'<td>'+escH(r.name||'—')+'</td>'+dc+'<td class="num">'+r.nsec+'</td><td class="num">'+(r.len/1000).toFixed(2)+'</td><td class="num">'+r.pci.toFixed(1)+'</td><td>'+chip+'</td></tr>';
  }).join('');
  return '<div style="overflow-x:auto"><table class="rpt-table"><thead><tr><th class="num">#</th>'+nh+'<th>Road Name</th>'+dh+'<th class="num">Sections</th><th class="num">Length (km)</th><th class="num">PCI</th><th>Class</th></tr></thead><tbody>'+rows+'</tbody></table></div>';
}
function pciLowestBlock(secs){
  const shLow=pciLowestRoads(secs,'SH',5),mdrLow=pciLowestRoads(secs,'MDR',5);
  let html='<div class="dash-eyebrow" style="margin:18px 0 8px">Lowest-PCI roads &mdash; statewide</div>'+
    '<div class="comp-row">'+
      '<div class="dcard"><div class="dcard-head"><h3>State Highways</h3><span class="totchip">SH &middot; lowest 5</span></div>'+
        '<div class="sub">5 lowest area-weighted-PCI SH roads across Kerala &middot; grouped by road number</div>'+pciLowTable(shLow,'SH',true)+'</div>'+
      '<div class="dcard"><div class="dcard-head"><h3>Major District Roads</h3><span class="totchip">MDR &middot; lowest 5</span></div>'+
        '<div class="sub">5 lowest area-weighted-PCI MDR roads across Kerala &middot; grouped by road name</div>'+pciLowTable(mdrLow,'MDR',true)+'</div>'+
    '</div>';
  /* district-wise: each district's 5 worst SH + 5 worst MDR roads (a road that
     spans districts is grouped within each district it touches). */
  const byDist={};secs.forEach(function(s){if(s.rclass!=='SH'&&s.rclass!=='MDR')return;const k=s.district||'Unassigned';(byDist[k]=byDist[k]||[]).push(s);});
  const dkeys=Object.keys(byDist).sort();
  if(dkeys.length){
    html+='<div class="dash-eyebrow" style="margin:18px 0 8px">Lowest-PCI roads &mdash; district-wise</div>';
    dkeys.forEach(function(dk){
      const shD=pciLowestRoads(byDist[dk],'SH',5),mdrD=pciLowestRoads(byDist[dk],'MDR',5);
      const nSH=pciRoadsFor(byDist[dk],'SH').length,nMDR=pciRoadsFor(byDist[dk],'MDR').length;
      html+='<div class="dcard" style="margin-bottom:12px"><div class="dcard-head"><h3>'+escH(dk)+'</h3><span class="totchip">'+nSH+' SH &middot; '+nMDR+' MDR roads</span></div>'+
        '<div class="sub" style="margin:8px 0 4px;font-weight:600">State Highways &mdash; lowest 5</div>'+pciLowTable(shD,'SH',false)+
        '<div class="sub" style="margin:12px 0 4px;font-weight:600">Major District Roads &mdash; lowest 5</div>'+pciLowTable(mdrD,'MDR',false)+
        '</div>';
    });
  }
  return html;
}
function pciAnalysisData(basis){
  /* pick up any edited weights, like the report does */
  PCI_PARAMS.forEach(pp=>{const el=document.getElementById('w_'+pp.key);if(el)PCI_W[pp.key]=+el.value||0;});
  const overall={};let tot=0,wsum=0,atot=0;const byClass={};const clsTot={},clsW={},clsA={};
  (DATA.features||[]).forEach(f=>{
    const p=f.properties;const v=segPCI(p,basis);if(v==null)return;
    const L=Math.max(0,(+p.to_ch||0)-(+p.from_ch||0))||1;const a=L*pavementWidthM(p.road);
    const band=pciBand(v).label;
    overall[band]=(overall[band]||0)+L;tot+=L;wsum+=v*a;atot+=a;
    const rd=ROADS[p.road];const rc=rd&&rd.properties?rd.properties.Road_Class:null;
    const cls=(rc!=null&&String(rc).trim()!=='')?String(rc).trim():'Other';
    (byClass[cls]=byClass[cls]||{})[band]=(byClass[cls][band]||0)+L;
    clsTot[cls]=(clsTot[cls]||0)+L;clsW[cls]=(clsW[cls]||0)+v*a;clsA[cls]=(clsA[cls]||0)+a;
  });
  return {overall,tot,w:wsum,atot:atot,byClass,clsTot,clsW,clsA};
}
function renderPciAnalysis(){
  const body=document.getElementById('dashBody');
  const needRoads=!ROADS||!Object.keys(ROADS).length;
  const needSegs=!DATA||!DATA.features||!DATA.features.length;
  if(needRoads||needSegs){
    body.innerHTML='<div class="dash-loading">Preparing PCI analysis — loading road &amp; condition data…</div>';
    Promise.resolve().then(()=>needRoads?loadRoads(true):null).then(()=>needSegs?loadSegments():null).then(()=>{
      if(dashTabCur!=='pcia')return;
      if(!DATA||!DATA.features||!DATA.features.length){body.innerHTML='<div class="dash-loading">No condition segments yet. Build them in the Data console first.</div>';return;}
      renderPciAnalysisNow();
    }).catch(e=>{body.innerHTML='<div class="dash-loading">Could not prepare PCI analysis: '+((e&&e.message)||e)+'</div>';});
    return;
  }
  renderPciAnalysisNow();
}
function renderPciAnalysisNow(){
  const body=document.getElementById('dashBody');if(!body)return;
  const basis=pciaBasis;const d=pciAnalysisData(basis);
  if(!d.tot){body.innerHTML='<div class="dash-loading">No PCI could be computed \u2014 check that condition segments carry distress values.</div>';return;}
  const avg=d.atot?d.w/d.atot:null;const ab=avg!=null?pciBand(avg):null;
  const order=['SH','MDR','ODR','NH'];
  const classes=Object.keys(d.byClass).sort((a,b)=>{const ia=order.indexOf(a),ib=order.indexOf(b);return ((ia<0?99:ia)-(ib<0?99:ib))||String(a).localeCompare(String(b));});
  const nm=cls=>(typeof CLASS_SHORT!=='undefined'&&CLASS_SHORT[cls])||dec('Road_Class',cls)||cls;
  const clsAvgs=classes.map(cls=>({cls:cls,avg:d.clsA[cls]?d.clsW[cls]/d.clsA[cls]:null})).filter(x=>x.avg!=null);
  const best=clsAvgs.slice().sort((a,b)=>b.avg-a.avg)[0];
  const worst=clsAvgs.slice().sort((a,b)=>a.avg-b.avg)[0];
  const bands=PCI_ORDER.filter(lb=>(d.overall[lb]||0)>0).length;
  let html='<div class="rpt-bar"><span class="segmented">'+
    '<button class="'+(basis==='avg'?'on':'')+'" onclick="setPciaBasis(\'avg\')">Composite</button>'+
    '<button class="'+(basis==='worst'?'on':'')+'" onclick="setPciaBasis(\'worst\')">Worst-Lane</button></span></div>';
  const hero='<div class="dcard pcia-hero">'+
    '<div class="dcard-head"><h3>Network condition</h3>'+(ab?'<span class="pci-chip" style="background:'+ab.color+'">'+ab.label+'</span>':'')+'</div>'+
    '<div class="pcia-big">'+(avg!=null?avg.toFixed(1):'\u2013')+'<span class="u">avg PCI</span></div>'+
    '<div class="sub">'+pciBasisLabel(basis)+' &middot; area-weighted &middot; '+(d.tot/1000).toFixed(1)+' km surveyed</div>'+
    '<div class="pcia-stats">'+
      '<div class="ps"><div class="ps-k">Surveyed</div><div class="ps-v">'+(d.tot/1000).toFixed(1)+'<small>km</small></div></div>'+
      '<div class="ps"><div class="ps-k">Rating bands</div><div class="ps-v">'+bands+'</div></div>'+
      (best?'<div class="ps"><div class="ps-k">Best class</div><div class="ps-v sm">'+escH(best.cls)+'<small>'+best.avg.toFixed(0)+'</small></div></div>':'')+
      (worst&&clsAvgs.length>1?'<div class="ps"><div class="ps-k">Most distressed</div><div class="ps-v sm">'+escH(worst.cls)+'<small>'+worst.avg.toFixed(0)+'</small></div></div>':'')+
    '</div></div>';
  const rows=PCI_ORDER.filter(lb=>(d.overall[lb]||0)>0).map(lb=>({label:lb,km:(d.overall[lb]||0)/1000}));
  const donutHtml=donutCard('Network length by PCI rating','Share of surveyed length in each IRC:82-2023 rating band',rows,{colorFn:pciClassColor,centerSmall:'km surveyed',full:l=>l});
  html+='<div class="comp-row pcia-top">'+hero+donutHtml+'</div>';
  let cards='';
  classes.forEach(cls=>{
    const ct=d.clsTot[cls]||0;const cAvg=ct?(d.clsW[cls]/ct):null;const cb=cAvg!=null?pciBand(cAvg):null;
    cards+='<div class="dcard"><div class="dcard-head"><h3>'+escH(nm(cls))+'</h3><span class="totchip">'+fmtKm(ct/1000)+' km</span></div>'+
      '<div class="sub">'+escH(cls)+' &middot; avg PCI '+(cAvg!=null?cAvg.toFixed(1):'\u2013')+(cb?' <span class="pci-chip" style="background:'+cb.color+'">'+cb.label+'</span>':'')+'</div>'+
      stackBar(d.byClass[cls],ct)+'</div>';
  });
  html+='<div class="dash-eyebrow" style="margin:18px 0 8px">PCI rating by road class</div><div class="comp-row">'+(cards||'<div class="sub">No class data.</div>')+'</div>';
  /* Lowest-PCI SH/MDR sections — statewide + district-wise (section-level, area-weighted). */
  try{const secData=pciReportData(basis).sections;if(secData&&secData.length)html+=pciLowestBlock(secData);}catch(e){}
  body.innerHTML=html;
}
