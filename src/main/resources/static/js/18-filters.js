/* ============================================================
   KLRAMS viewer · 18-filters.js
   New filters for the unified Filter folder — FWD (D0 microns),
   PCI (value range, both layers), Traffic (min ADT) — plus the
   layer-off lock handling shared by every filter section.
   (Network and Road-Condition filters keep their original logic
   in 03/05; their controls are simply relocated into the folder.)
   ============================================================ */

/* ---------- FWD: filter by D0 deflection (microns) ---------- */
function applyFwdFilter(){
  if(!map.getLayer('as-fwd')) return;
  const mn=parseFloat(document.getElementById('fwdMin').value);
  const mx=parseFloat(document.getElementById('fwdMax').value);
  const conds=[];
  if(!isNaN(mn)) conds.push(['>=',['to-number',['get','__d0']],mn]);
  if(!isNaN(mx)) conds.push(['<=',['to-number',['get','__d0']],mx]);
  const lineFilter = conds.length ? ['all'].concat(conds) : null;
  ['as-fwd','as-fwd-icon'].forEach(id=>{ if(map.getLayer(id)) map.setFilter(id, lineFilter); });
  if(map.getLayer('as-fwd-pt'))
    map.setFilter('as-fwd-pt', ['all',['==',['geometry-type'],'Point']].concat(conds));
}
function clearFwdFilter(){
  document.getElementById('fwdMin').value='';
  document.getElementById('fwdMax').value='';
  ['as-fwd','as-fwd-icon'].forEach(id=>{ if(map.getLayer(id)) map.setFilter(id, null); });
  if(map.getLayer('as-fwd-pt')) map.setFilter('as-fwd-pt', ['==',['geometry-type'],'Point']);
}

/* ---------- PCI: filter both layers by value range ---------- */
function applyPciFilter(){
  const mn=parseFloat(document.getElementById('pciMin').value);
  const mx=parseFloat(document.getElementById('pciMax').value);
  [['pci-avg','pci_avg'],['pci-worst','pci_worst']].forEach(([id,prop])=>{
    if(!map.getLayer(id)) return;
    const conds=['all',['!=',['get',prop],-1]];
    if(!isNaN(mn)) conds.push(['>=',['get',prop],mn]);
    if(!isNaN(mx)) conds.push(['<=',['get',prop],mx]);
    map.setFilter(id, conds);
  });
}
function clearPciFilter(){
  document.getElementById('pciMin').value='';
  document.getElementById('pciMax').value='';
  ['pci-avg','pci-worst'].forEach(id=>{ if(map.getLayer(id)) map.setFilter(id, null); });
}

/* ---------- Traffic: filter by minimum ADT ---------- */
function trafficComputeAdt(){
  if(typeof TRAFFIC_STN==='undefined' || !TRAFFIC_STN.features) return;
  const counts=(typeof TRAFFIC_COUNTS!=='undefined')?TRAFFIC_COUNTS:{};
  TRAFFIC_STN.features.forEach(f=>{
    const c=counts[f.properties.name];
    f.properties.__adt = c ? Math.round((c.total||0)/(c.days||1)) : -1;
  });
  if(map.getSource('trafficstn')) map.getSource('trafficstn').setData(TRAFFIC_STN);
}
function applyTrafficFilter(){
  if(!map.getLayer('trafficstn-lyr')) return;
  trafficComputeAdt();
  const mn=parseFloat(document.getElementById('trfMin').value);
  if(isNaN(mn)) map.setFilter('trafficstn-lyr', null);
  else map.setFilter('trafficstn-lyr', ['all',['!=',['get','__adt'],-1],['>=',['get','__adt'],mn]]);
}
function clearTrafficFilter(){
  document.getElementById('trfMin').value='';
  if(map.getLayer('trafficstn-lyr')) map.setFilter('trafficstn-lyr', null);
}

/* ---------- layer-off locks ---------- */
function fLayerOn(id){ const e=document.getElementById(id); return e?e.checked:false; }
function enableLayer(id){
  const e=document.getElementById(id);
  if(e && !e.checked){ e.checked=true; e.dispatchEvent(new Event('change')); }
  setTimeout(refreshFilterLocks,80);
}
function refreshFilterLocks(){
  [['fsecNet','showRoads'],['fsecCond','showCond'],['fsecTrf','showTraffic'],
   ['fsecFwd','showFwd'],['fsecPci',null]].forEach(([sec,layer])=>{
    const s=document.getElementById(sec); if(!s) return;
    const on = (sec==='fsecPci') ? (fLayerOn('showPciAvg')||fLayerOn('showPciWorst')) : fLayerOn(layer);
    s.classList.toggle('locked', !on);
  });
}
(function(){
  if(typeof map==='undefined') return;
  ['showRoads','showCond','showTraffic','showFwd','showPciAvg','showPciWorst'].forEach(id=>{
    const e=document.getElementById(id);
    if(e) e.addEventListener('change',()=>setTimeout(refreshFilterLocks,40));
  });
})();
