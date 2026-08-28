/* ============================================================
   KLRAMS viewer · 14-pci-engine.js   (build 171)
   IRC:82-2023 PCI engine: per-parameter indices, editable weights, rating bands, PCI map layers and popup.

   BUILD 171 — PCI is now STORED. SegmentService computes both PCIs with the
   default weights once per upload and ships them as pci_def_avg / pci_def_worst.
   segPCI() returns those directly while the weights are untouched, and only
   computes in the browser once a weight is edited (that view is temporary and
   never written back). The maths below therefore still has to match the Java
   port in PciCalculator.java exactly.

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

/* Are the weights still the IRC:82-2023 defaults? Only then may we use the PCI
   the backend stored at Build Segments time (pci_def_avg / pci_def_worst). */
function pciWeightsAtDefault(){return PCI_PARAMS.every(pp=>Math.abs((+PCI_W[pp.key]||0)-PCI_W_DEFAULT[pp.key])<1e-9);}

/* segPCI — the single source of truth used by generatePCI AND 10-pci-report.js.
   basis 'avg'  -> Composite PCI (area-weighted distress average across lanes)
   basis 'worst'-> Worst-Lane PCI (min of per-lane PCIs)

   BUILD 171 — stored PCI. At the default weights the value is read straight off
   the segment (SegmentService.storeDefaultPci computed it once, at upload time),
   so a 33k-segment map costs no scoring work at all. Editing a weight makes the
   stored number wrong for that view, so we fall through to the full in-browser
   computation for as long as the weights are off their defaults. Segments from a
   database built before this feature carry no stored value and also fall through,
   so nothing depends on the columns being populated. */
function segPCI(props,basis){
  if(pciWeightsAtDefault()){
    const s=props[(basis==='worst')?'pci_def_worst':'pci_def_avg'];
    if(s!=null&&s!=='')return +s;
  }
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
/* Which property the PCI layers actually paint from.

   GeoJSON mode: pci_avg / pci_worst, which generatePCI() stamps onto each
   feature and pushes back through setData.

   Tile mode: pci_def_avg / pci_def_worst, the values SegmentService stored at
   Build Segments time and the tile already carries. A vector source has no
   setData, so nothing can be stamped onto it — but nothing needs to be, because
   at the default weights segPCI() returns exactly those stored numbers anyway.
   The consequence is better than parity: the PCI layers render with no download
   at all, where before they cost the whole network plus a 33k-segment scoring
   pass in the browser.

   Editing a weight makes the stored value wrong for that view. That path still
   needs per-segment recomputation and is not served here — see the status text
   in generatePCI(). */
function pciProp(basis){
  const worst=(basis==='worst');
  if(TILES_ON)return worst?'pci_def_worst':'pci_def_avg';
  return worst?'pci_worst':'pci_avg';
}
function pciBasisLabel(b){return b==='worst'?'Worst-Lane PCI':'Composite PCI';}
/* to-number: MVT can surface numeric props as strings; without it the step
   expression fails open and the layer paints as a single flat colour (or grey). */
function pciColorExpr(prop){
  return ['case',
    ['!',['has',prop]],'#b9c2cc',
    ['<',['to-number',['get',prop]],0],'#b9c2cc',
    ['step',['to-number',['get',prop]],'#c92a2a',20,'#e8590c',40,'#f08c00',60,'#f2c200',80,'#7cb518',90,'#157f3c']];
}
function setPciStatus(t){const el=document.getElementById('pciStatus');if(el)el.textContent=t||'';}
function renderPciWeights(){
  const box=document.getElementById('pciWeights');if(!box)return;
  box.innerHTML=PCI_PARAMS.map(pp=>{const t=PMAP[pp.key]||{};const hint='Good&lt;'+t.fair+' &middot; Poor&gt;'+t.poor;return `<div class="wrow"><span class="wn">${pp.label}<small>${hint}</small></span><input type="number" step="0.01" min="0" id="w_${pp.key}" value="${PCI_W[pp.key]}"><span class="wu">w</span></div>`;}).join('');
  PCI_PARAMS.forEach(pp=>{const el=document.getElementById('w_'+pp.key);if(el)el.addEventListener('input',()=>{PCI_W[pp.key]=+el.value||0;updatePciSum();if(map.getLayer('pci-avg')||map.getLayer('pci-worst'))generatePCI();});});
  updatePciSum();
}
function updatePciSum(){let sum=0;PCI_PARAMS.forEach(pp=>sum+=(+PCI_W[pp.key]||0));const el=document.getElementById('pciSum');if(!el)return;el.innerHTML='&Sigma; weights = <b>'+sum.toFixed(2)+'</b>';el.classList.toggle('warn',Math.abs(sum-1)>0.005);}
function resetPciWeights(){Object.assign(PCI_W,PCI_W_DEFAULT);renderPciWeights();if(map.getLayer('pci-avg')||map.getLayer('pci-worst'))generatePCI();}
function renderPciLegend(){
  const el=document.getElementById('pciLegend');
  if(el)el.innerHTML=PCI_BANDS.map(b=>`<div class="lg"><span class="bar" style="background:${b.color}"></span> ${b.label} <span class="rng">${b.min}\u2013${b.hi}</span></div>`).join('')+'<div class="lg"><span class="bar" style="background:#b9c2cc"></span> No data <span class="rng"></span></div>';
  /* Layers-panel scale (same pattern as FWD / Avg IRI). Show when either PCI toggle is on. */
  const mapEl=document.getElementById('pciMapLegend');
  if(mapEl){
    mapEl.innerHTML='<div class="fl-hd"><span class="fl-t">PCI</span><span class="fl-u">IRC:82-2023</span></div>'+
      PCI_BANDS.map(b=>'<div class="fl-r"><span class="sw" style="background:'+b.color+'"></span>'+
        '<span class="fl-l">'+b.label+'</span><span class="fl-v">'+b.min+'\u2013'+b.hi+'</span></div>').join('')+
      '<div class="fl-r"><span class="sw" style="background:#b9c2cc"></span><span class="fl-l">No data</span><span class="fl-v"></span></div>';
  }
  syncPciMapLegend();
}
function syncPciMapLegend(){
  const lg=document.getElementById('pciMapLegend');if(!lg)return;
  const a=document.getElementById('showPciAvg'),w=document.getElementById('showPciWorst');
  lg.style.display=((a&&a.checked)||(w&&w.checked))?'block':'none';
}
function renderPciSummary(d){const el=document.getElementById('pciSummary');if(!el)return;if(!d||(d.avg==null&&d.worst==null)){el.innerHTML='';return;}
  function rw(lab,v){if(v==null)return '<div class="rec" style="margin-top:5px">'+lab+': \u2013</div>';const b=pciBand(v);return '<div style="display:flex;align-items:center;gap:8px;margin-top:6px"><span class="big" style="font-size:22px">'+v.toFixed(1)+'</span><span class="band" style="margin-left:0;background:'+b.color+'">'+b.label+'</span><span class="rec" style="margin:0">'+lab+'</span></div>';}
  el.innerHTML='<div class="pci-summary"><div class="eyebrow" style="margin:0 0 2px">Network average PCI</div>'+rw('Composite',d.avg)+rw('Worst-Lane',d.worst)+'<div class="rec" style="margin-top:7px">'+(d.nA||0)+' of '+(d.total||0)+' segments scored</div></div>';}
function pciPopup(lngLat,props,basis){
  basis=basis||'avg';
  /* segPCI(), not props.pci_avg/pci_worst directly: those flat properties are
     only ever stamped in GeoJSON mode (generatePCI). In tile mode (default)
     the feature carries pci_def_avg/pci_def_worst instead, and reading the
     wrong key silently showed "No PCI at this segment" on every click. */
  const vRaw=segPCI(props,basis);const v=(vRaw!=null&&!isNaN(vRaw))?vRaw:-1;
  const b=(v>=0)?pciBand(v):null;
  const rep=pciRepr(props,basis);
  let rows='';PCI_PARAMS.forEach(pp=>{const raw=rep.dist[pp.key];const has=!(raw==null||raw==='');const I=has?indIndex(pp.key,+raw):null;rows+='<tr><td class="k">'+pp.label+'</td><td class="v">'+(has?(+(+raw).toFixed(2)):'\u2013')+(I==null?'':' \u2192 '+I.toFixed(0))+'</td></tr>';});
  const laneNote=(basis==='worst'&&rep.lane)?(' &middot; worst lane '+rep.lane):((basis!=='worst'&&rep.lane&&rep.lane.indexOf(',')>=0)?(' &middot; avg of '+rep.lane):'');
  const head=b?('<div style="font-size:22px;font-weight:700;color:#0e2038">'+v.toFixed(1)+'<span style="font-size:12px;color:#64718a;font-weight:500"> /100</span> <span style="background:'+b.color+';color:#fff;font-size:11px;font-weight:700;border-radius:20px;padding:2px 9px;margin-left:4px">'+b.label+'</span></div><div style="font-size:11.5px;color:#64718a;margin:3px 0 8px">'+b.rec+'</div>'):'<div style="color:#64718a">No PCI at this segment</div>';
  const avRaw=segPCI(props,'avg'),wvRaw=segPCI(props,'worst');
  const av=(avRaw!=null&&!isNaN(avRaw))?avRaw:-1,wv=(wvRaw!=null&&!isNaN(wvRaw))?wvRaw:-1;
  const cmp='<div style="font-size:10.5px;color:#64718a;margin-top:6px;border-top:1px solid #eef1f5;padding-top:5px">Composite <b>'+((av>=0)?av.toFixed(1):'\u2013')+'</b> &middot; Worst-Lane <b>'+((wv>=0)?wv.toFixed(1):'\u2013')+'</b></div>';
  new maplibregl.Popup({maxWidth:'290px'}).setLngLat(lngLat).setHTML('<div class="pop"><div class="sec">'+pciBasisLabel(basis)+laneNote+' &middot; '+(props.road||'')+'</div>'+head+'<table>'+rows+'</table>'+cmp+'</div>').addTo(map);
}
/* Build 168 \u2014 silent=true is the background login preload (15-main.js): compute
   PCI and create the layers hidden, but DON'T auto-tick the Composite toggle
   (that auto-tick exists for the explicit "Generate PCI" button, so a click
   always shows something). Toggling a PCI switch on is then an instant
   visibility flip instead of a full 33k-segment recompute at click time. */
/* Layer creation, shared by both paths. In tile mode the source is a vector
   source so every layer must name the layer inside the tile, or MapLibre
   renders nothing and says nothing. */
function addPciLayers(){
  if(TILES_ON&&typeof ensureSegSource==='function')ensureSegSource();
  PCI_LAYERS.forEach(L=>{
    const prop=pciProp(L.basis);
    if(!map.getLayer(L.id)){
      const spec={id:L.id,type:'line',source:'segs',layout:{'line-cap':'round'},paint:{'line-color':pciColorExpr(prop),'line-width':['interpolate',['linear'],['zoom'],10,3.5,16,8],'line-offset':['interpolate',['linear'],['zoom'],10,L.off*1.5,16,L.off*3.5]}};
      if(TILES_ON)spec['source-layer']=SEG_TILE_LAYER;
      map.addLayer(spec);
      map.on('click',L.id,e=>{if(e.features.length)pciPopup(e.lngLat,e.features[0].properties,L.basis);});
      map.on('mouseenter',L.id,()=>map.getCanvas().style.cursor='pointer');
      map.on('mouseleave',L.id,()=>map.getCanvas().style.cursor='');
    }else{map.setPaintProperty(L.id,'line-color',pciColorExpr(prop));}
  });
}
/* The explicit "Generate PCI" button auto-ticks Composite so a click always
   shows something; the background preload (silent) must not. */
function showPciToggles(silent){
  const ta=document.getElementById('showPciAvg'),tw=document.getElementById('showPciWorst');
  if(!silent&&ta&&tw&&!ta.checked&&!tw.checked)ta.checked=true;
  return ta;
}
/* Match layer visibility to the checkboxes. Shared by both tile and GeoJSON
   paths — tile mode used to skip this and left pci-* at MapLibre's default
   (visible), so PCI painted with both toggles off and could not be turned off. */
function applyPciVisibility(silent){
  const ta=showPciToggles(silent),tw=document.getElementById('showPciWorst');
  if(map.getLayer('pci-avg'))map.setLayoutProperty('pci-avg','visibility',(ta&&ta.checked)?'visible':'none');
  if(map.getLayer('pci-worst'))map.setLayoutProperty('pci-worst','visibility',(tw&&tw.checked)?'visible':'none');
}
function generatePCI(silent){
  /* Tile mode: the layers paint from the stored PCI the tile already carries,
     so they can be built with nothing downloaded. Only the NETWORK SUMMARY
     needs every segment, and that is not worth a multi-megabyte fetch nobody
     asked for -- it fills in once something else (the PCI report, an export)
     has loaded them. */
  if(TILES_ON&&!Segs.collection()){addPciLayers();applyPciVisibility(silent);
    setPciStatus('PCI shown from stored values. Open the PCI report for network totals.');
    renderPciSummary(null);syncPciMapLegend();return;}
  if(!Segs.collection()){setPciStatus('Loading segments\u2026');Segs.ensure().then(()=>{if(Segs.loaded())generatePCI(silent);else setPciStatus('No condition segments yet. Build them in the Data console.');});return;}
  PCI_PARAMS.forEach(pp=>{const el=document.getElementById('w_'+pp.key);if(el)PCI_W[pp.key]=+el.value||0;});
  let nA=0,lenA=0,pA=0,nW=0,lenW=0,pW=0;
  Segs.all().forEach(f=>{const L=Math.max(0,(+f.properties.to_ch||0)-(+f.properties.from_ch||0))||1;
    const va=segPCI(f.properties,'avg');f.properties.pci_avg=(va==null)?-1:Math.round(va*10)/10;if(va!=null){nA++;lenA+=L;pA+=va*L;}
    const vw=segPCI(f.properties,'worst');f.properties.pci_worst=(vw==null)?-1:Math.round(vw*10)/10;if(vw!=null){nW++;lenW+=L;pW+=vw*L;}});
  if(!TILES_ON&&map.getSource('segs'))map.getSource('segs').setData(Segs.collection());
  addPciLayers();
  applyPciVisibility(silent);
  renderPciSummary({avg:lenA?pA/lenA:null,worst:lenW?pW/lenW:null,nA:nA,nW:nW,total:Segs.count()});
  setPciStatus('\u2713 PCI generated (Composite & Worst-Lane) for '+nA+' of '+Segs.count()+' segments.');
  syncPciMapLegend();
}
(function initPci(){
  renderPciWeights();renderPciLegend();
  [['showPciAvg','pci-avg'],['showPciWorst','pci-worst']].forEach(([tgid,lid])=>{const tg=document.getElementById(tgid);if(tg)tg.addEventListener('change',e=>{if(e.target.checked){if(!map.getLayer(lid)){generatePCI();}else{map.setLayoutProperty(lid,'visibility','visible');}}else{if(map.getLayer(lid))map.setLayoutProperty(lid,'visibility','none');}syncPciMapLegend();});});
})();
