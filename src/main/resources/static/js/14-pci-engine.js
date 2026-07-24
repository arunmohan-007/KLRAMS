/* ============================================================
   KLRAMS viewer · 14-pci-engine.js   (build 75)
   IRC:82-2023 PCI engine: per-parameter indices, editable weights, rating bands, PCI map layers and popup.

   BUILD 75 CORRECTNESS FIX — lane aggregation order.
   PCI is a NON-LINEAR function of the distresses, so the order of
   "aggregate" vs "compute PCI" matters. The earlier build aggregated each
   distress parameter ACROSS lanes first (worst = per-parameter MAX, avg =
   per-parameter mean) and then ran one PCI on those mixed numbers. For the
   worst case that invents a lane that is simultaneously worst at every
   parameter — a lane that does not exist — and understates PCI.

   Correct definition, now implemented:
     • Worst-Lane PCI = MIN of the per-lane PCIs (a PCI per lane, take the worst).
     • Composite PCI  = area-weighted distress average: pool the lane distresses
       across the carriageway, then compute ONE PCI from the pooled distress.
   Per-lane distresses come from lane_vals (CC / CL1 / CL2 / CR1 / CR2).
   When a stretch has no lane breakdown, it falls back to the segment values.
   ============================================================ */
/* ===== IRC:82-2023 Pavement Condition Index (PCI) module ===== */
const PCI_PARAMS=[{key:'crack',label:'Cracking'},{key:'ravelling',label:'Ravelling'},{key:'pothole',label:'Pothole'},{key:'patch_work',label:'Patch work'},{key:'rutting',label:'Rut depth'},{key:'iri',label:'IRI (roughness)'}];
const PCI_W_DEFAULT={crack:0.16,ravelling:0.12,pothole:0.08,patch_work:0.10,rutting:0.14,iri:0.40};
const PCI_W=Object.assign({},PCI_W_DEFAULT);
const PCI_BANDS=[{min:90,hi:100,label:'Excellent',color:'#157f3c',rec:'Routine maintenance'},{min:80,hi:90,label:'Good',color:'#7cb518',rec:'Preventive maintenance'},{min:60,hi:80,label:'Satisfactory',color:'#f2c200',rec:'Resurfacing (structural check)'},{min:40,hi:60,label:'Fair',color:'#f08c00',rec:'Minor rehabilitation'},{min:20,hi:40,label:'Poor',color:'#e8590c',rec:'Major rehab / overlay'},{min:0,hi:20,label:'Fail',color:'#c92a2a',rec:'Reconstruction'}];
function pciBand(v){for(const b of PCI_BANDS){if(v>=b.min)return b;}return PCI_BANDS[PCI_BANDS.length-1];}
function indIndex(key,v){const t=PMAP[key];if(!t||v==null||isNaN(v))return null;v=Math.max(0,+v);const f=+t.fair,po=+t.poor;if(!(po>f&&f>0))return null;if(v<=f)return 100-(v/f)*20;if(v<=po)return 80-((v-f)/(po-f))*40;const cap=2*po;if(v<=cap)return 40-((v-po)/po)*40;return 0;}

/* ---- per-lane helpers (build 75) ---- */
const PCI_LANE_KEYS=['CC','CL1','CL2','CR1','CR2'];
/* Return { laneName: { param:value, ... }, ... } for a segment.
   Prefers lane_vals (jsonb from the backend); falls back to the flattened
   "<lane>_<param>" properties that 07-data-loaders writes; if neither is
   present returns {} and the caller uses the segment-level fallback. */
function pciLaneDists(props){
  const out={};
  let lv=props.lane_vals;
  if(typeof lv==='string'){try{lv=JSON.parse(lv);}catch(e){lv=null;}}
  if(lv&&typeof lv==='object'&&Object.keys(lv).length){
    Object.keys(lv).forEach(L=>{const o=lv[L]||{};const d={};PCI_PARAMS.forEach(pp=>{const v=o[pp.key];if(v!=null&&v!=='')d[pp.key]=+v;});out[L]=d;});
    return out;
  }
  PCI_LANE_KEYS.forEach(L=>{let any=false;const d={};PCI_PARAMS.forEach(pp=>{const v=props[L+'_'+pp.key];if(v!=null&&v!==''){d[pp.key]=+v;any=true;}});if(any)out[L]=d;});
  return out;
}
/* Weighted IRC:82-2023 PCI from one raw distress object. */
function pciFromDist(dist){let sw=0,acc=0;PCI_PARAMS.forEach(pp=>{const w=+PCI_W[pp.key]||0;if(w<=0)return;const raw=dist?dist[pp.key]:null;if(raw==null||raw==='')return;const I=indIndex(pp.key,+raw);if(I==null)return;acc+=w*I;sw+=w;});return sw>0?acc/sw:null;}
/* Segment-level fallback distress object when there is no lane breakdown:
   worst -> the segment MAX columns (iri, crack, ...); avg -> the avg_* columns. */
function pciAggDist(props,basis){const d={};PCI_PARAMS.forEach(pp=>{const k=pp.key;let v=(basis==='worst')?props[k]:((props['avg_'+k]!=null&&props['avg_'+k]!=='')?props['avg_'+k]:props[k]);if(v!=null&&v!=='')d[k]=+v;});return d;}
/* List of {lane, pci} for every lane that yields a PCI. */
function pciLanePcis(props){const L=pciLaneDists(props);const r=[];Object.keys(L).forEach(k=>{const v=pciFromDist(L[k]);if(v!=null)r.push({lane:k,pci:v});});return r;}
/* Representative distress profile actually shown in the popup for a basis. */
function pciRepr(props,basis){
  const L=pciLaneDists(props);const keys=Object.keys(L);
  if(keys.length){
    if(basis==='worst'){const lp=pciLanePcis(props);if(lp.length){let w=lp[0];lp.forEach(x=>{if(x.pci<w.pci)w=x;});return {dist:L[w.lane]||{},lane:w.lane};}}
    const d={};PCI_PARAMS.forEach(pp=>{let s=0,n=0;keys.forEach(k=>{const v=L[k][pp.key];if(v!=null){s+=+v;n++;}});if(n)d[pp.key]=s/n;});
    return {dist:d,lane:keys.join(', ')};
  }
  return {dist:pciAggDist(props,basis),lane:(props.xsp_list||'')};
}

/* segPCI — the single source of truth used by generatePCI AND 10-pci-report.js.
   basis 'avg'  -> Composite PCI (area-weighted distress average across lanes)
   basis 'worst'-> Worst-Lane PCI (min of per-lane PCIs) */
function segPCI(props,basis){
  const lanes=pciLanePcis(props);
  if(lanes.length){
    if(basis==='worst')return Math.min.apply(null,lanes.map(x=>x.pci));
    /* Composite PCI — area-weighted distress average: pool the lane distresses across
       the carriageway (equal-area lanes -> mean per parameter), then compute ONE PCI. */
    const Ld=pciLaneDists(props);const keys=Object.keys(Ld);const d={};
    PCI_PARAMS.forEach(pp=>{let s=0,n=0;keys.forEach(k=>{const v=Ld[k][pp.key];if(v!=null&&v!==''){s+=+v;n++;}});if(n)d[pp.key]=s/n;});
    const v=pciFromDist(d);
    return (v!=null)?v:(lanes.reduce((s,x)=>s+x.pci,0)/lanes.length);
  }
  return pciFromDist(pciAggDist(props,basis));
}

const PCI_LAYERS=[{id:'pci-avg',prop:'pci_avg',basis:'avg',off:-1,tg:'showPciAvg'},{id:'pci-worst',prop:'pci_worst',basis:'worst',off:1,tg:'showPciWorst'}];
function pciBasisLabel(b){return b==='worst'?'Worst-Lane PCI':'Composite PCI';}
function pciColorExpr(prop){return ['case',['<',['coalesce',['get',prop],-1],0],'#b9c2cc',['step',['get',prop],'#c92a2a',20,'#e8590c',40,'#f08c00',60,'#f2c200',80,'#7cb518',90,'#157f3c']];}
function setPciStatus(t){const el=document.getElementById('pciStatus');if(el)el.textContent=t||'';}
function renderPciWeights(){
  const box=document.getElementById('pciWeights');if(!box)return;
  box.innerHTML=PCI_PARAMS.map(pp=>{const t=PMAP[pp.key]||{};const hint='Good&lt;'+t.fair+' &middot; Poor&gt;'+t.poor;return `<div class="wrow"><span class="wn">${pp.label}<small>${hint}</small></span><input type="number" step="0.01" min="0" id="w_${pp.key}" value="${PCI_W[pp.key]}"><span class="wu">w</span></div>`;}).join('');
  PCI_PARAMS.forEach(pp=>{const el=document.getElementById('w_'+pp.key);if(el)el.addEventListener('input',()=>{PCI_W[pp.key]=+el.value||0;updatePciSum();if(map.getLayer('pci-avg')||map.getLayer('pci-worst'))generatePCI();});});
  updatePciSum();
}
function updatePciSum(){let sum=0;PCI_PARAMS.forEach(pp=>sum+=(+PCI_W[pp.key]||0));const el=document.getElementById('pciSum');if(!el)return;el.innerHTML='&Sigma; weights = <b>'+sum.toFixed(2)+'</b>';el.classList.toggle('warn',Math.abs(sum-1)>0.005);}
function resetPciWeights(){Object.assign(PCI_W,PCI_W_DEFAULT);renderPciWeights();if(map.getLayer('pci-avg')||map.getLayer('pci-worst'))generatePCI();}
function renderPciLegend(){const el=document.getElementById('pciLegend');if(!el)return;el.innerHTML=PCI_BANDS.map(b=>`<div class="lg"><span class="bar" style="background:${b.color}"></span> ${b.label} <span class="rng">${b.min}\u2013${b.hi}</span></div>`).join('')+'<div class="lg"><span class="bar" style="background:#b9c2cc"></span> No data <span class="rng"></span></div>';}
function renderPciSummary(d){const el=document.getElementById('pciSummary');if(!el)return;if(!d||(d.avg==null&&d.worst==null)){el.innerHTML='';return;}
  function rw(lab,v){if(v==null)return '<div class="rec" style="margin-top:5px">'+lab+': \u2013</div>';const b=pciBand(v);return '<div style="display:flex;align-items:center;gap:8px;margin-top:6px"><span class="big" style="font-size:22px">'+v.toFixed(1)+'</span><span class="band" style="margin-left:0;background:'+b.color+'">'+b.label+'</span><span class="rec" style="margin:0">'+lab+'</span></div>';}
  el.innerHTML='<div class="pci-summary"><div class="eyebrow" style="margin:0 0 2px">Network average PCI</div>'+rw('Composite',d.avg)+rw('Worst-Lane',d.worst)+'<div class="rec" style="margin-top:7px">'+(d.nA||0)+' of '+(d.total||0)+' segments scored</div></div>';}
function pciPopup(lngLat,props,basis){
  basis=basis||'avg';const prop=(basis==='worst')?'pci_worst':'pci_avg';
  const v=+props[prop];const b=(v>=0)?pciBand(v):null;
  const rep=pciRepr(props,basis);
  let rows='';PCI_PARAMS.forEach(pp=>{const raw=rep.dist[pp.key];const has=!(raw==null||raw==='');const I=has?indIndex(pp.key,+raw):null;rows+='<tr><td class="k">'+pp.label+'</td><td class="v">'+(has?(+(+raw).toFixed(2)):'\u2013')+(I==null?'':' \u2192 '+I.toFixed(0))+'</td></tr>';});
  const laneNote=(basis==='worst'&&rep.lane)?(' &middot; worst lane '+rep.lane):((basis!=='worst'&&rep.lane&&rep.lane.indexOf(',')>=0)?(' &middot; avg of '+rep.lane):'');
  const head=b?('<div style="font-size:22px;font-weight:700;color:#0e2038">'+v.toFixed(1)+'<span style="font-size:12px;color:#64718a;font-weight:500"> /100</span> <span style="background:'+b.color+';color:#fff;font-size:11px;font-weight:700;border-radius:20px;padding:2px 9px;margin-left:4px">'+b.label+'</span></div><div style="font-size:11.5px;color:#64718a;margin:3px 0 8px">'+b.rec+'</div>'):'<div style="color:#64718a">No PCI at this segment</div>';
  const av=+props.pci_avg,wv=+props.pci_worst;
  const cmp='<div style="font-size:10.5px;color:#64718a;margin-top:6px;border-top:1px solid #eef1f5;padding-top:5px">Composite <b>'+((av>=0)?av.toFixed(1):'\u2013')+'</b> &middot; Worst-Lane <b>'+((wv>=0)?wv.toFixed(1):'\u2013')+'</b></div>';
  new maplibregl.Popup({maxWidth:'290px'}).setLngLat(lngLat).setHTML('<div class="pop"><div class="sec">'+pciBasisLabel(basis)+laneNote+' &middot; '+(props.road||'')+'</div>'+head+'<table>'+rows+'</table>'+cmp+'</div>').addTo(map);
}
/* Build 168 \u2014 silent=true is the background login preload (15-main.js): compute
   PCI and create the layers hidden, but DON'T auto-tick the Composite toggle
   (that auto-tick exists for the explicit "Generate PCI" button, so a click
   always shows something). Toggling a PCI switch on is then an instant
   visibility flip instead of a full 33k-segment recompute at click time. */
function generatePCI(silent){
  if(!DATA){setPciStatus('Loading segments\u2026');loadSegments().then(()=>{if(DATA&&DATA.features&&DATA.features.length)generatePCI(silent);else setPciStatus('No condition segments yet. Build them in the Data console.');});return;}
  PCI_PARAMS.forEach(pp=>{const el=document.getElementById('w_'+pp.key);if(el)PCI_W[pp.key]=+el.value||0;});
  let nA=0,lenA=0,pA=0,nW=0,lenW=0,pW=0;
  DATA.features.forEach(f=>{const L=Math.max(0,(+f.properties.to_ch||0)-(+f.properties.from_ch||0))||1;
    const va=segPCI(f.properties,'avg');f.properties.pci_avg=(va==null)?-1:Math.round(va*10)/10;if(va!=null){nA++;lenA+=L;pA+=va*L;}
    const vw=segPCI(f.properties,'worst');f.properties.pci_worst=(vw==null)?-1:Math.round(vw*10)/10;if(vw!=null){nW++;lenW+=L;pW+=vw*L;}});
  if(map.getSource('segs'))map.getSource('segs').setData(DATA);
  PCI_LAYERS.forEach(L=>{
    if(!map.getLayer(L.id)){
      map.addLayer({id:L.id,type:'line',source:'segs',layout:{'line-cap':'round'},paint:{'line-color':pciColorExpr(L.prop),'line-width':['interpolate',['linear'],['zoom'],10,3.5,16,8],'line-offset':['interpolate',['linear'],['zoom'],10,L.off*1.5,16,L.off*3.5]}});
      map.on('click',L.id,e=>{if(e.features.length)pciPopup(e.lngLat,e.features[0].properties,L.basis);});
      map.on('mouseenter',L.id,()=>map.getCanvas().style.cursor='pointer');
      map.on('mouseleave',L.id,()=>map.getCanvas().style.cursor='');
    }else{map.setPaintProperty(L.id,'line-color',pciColorExpr(L.prop));}
  });
  const ta=document.getElementById('showPciAvg'),tw=document.getElementById('showPciWorst');
  if(!silent&&ta&&tw&&!ta.checked&&!tw.checked)ta.checked=true;
  map.setLayoutProperty('pci-avg','visibility',(ta&&ta.checked)?'visible':'none');
  map.setLayoutProperty('pci-worst','visibility',(tw&&tw.checked)?'visible':'none');
  renderPciSummary({avg:lenA?pA/lenA:null,worst:lenW?pW/lenW:null,nA:nA,nW:nW,total:DATA.features.length});
  setPciStatus('\u2713 PCI generated (Composite & Worst-Lane) for '+nA+' of '+DATA.features.length+' segments.');
}
(function initPci(){
  renderPciWeights();renderPciLegend();
  [['showPciAvg','pci-avg'],['showPciWorst','pci-worst']].forEach(([tgid,lid])=>{const tg=document.getElementById(tgid);if(tg)tg.addEventListener('change',e=>{if(e.target.checked){if(!map.getLayer(lid)){generatePCI();}else{map.setLayoutProperty(lid,'visibility','visible');}}else{if(map.getLayer(lid))map.setLayoutProperty(lid,'visibility','none');}});});
})();
