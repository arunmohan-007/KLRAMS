/* ============================================================
   KLRAMS viewer · 09-dashboard-core.js
   Dashboard open/close and tab switching.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
// ===== dashboard =====
let dashData=null;
function closeDashboard(){document.getElementById('dashboard').classList.remove('open');}
let dashTabCur='overview';
function loadDashboard(){
  const dash=document.getElementById('dashboard');dash.classList.add('open');
  setDashTab('overview');
  if(dashData){renderDashboard();return;}
  document.getElementById('dashBody').innerHTML='<div class="dash-loading">Loading network figures…</div>';
  fetch('/api/dashboard/summary').then(r=>r.json()).then(d=>{dashData=d;if(dashTabCur==='overview')renderDashboard();})
    .catch(e=>{document.getElementById('dashBody').innerHTML='<div class="dash-loading">Could not load dashboard: '+e.message+'</div>';});
}
function setDashTab(which){
  dashTabCur=which;
  document.querySelectorAll('.dash-tab').forEach(b=>b.classList.toggle('on',b.dataset.tab===which));
  const sc=document.getElementById('dashScope'),sb=document.getElementById('dashSub');
  if(which==='pcia'){if(sc)sc.textContent='PCI Analysis';if(sb)sb.textContent='IRC:82-2023 Pavement Condition Index · share of network length by rating, and rating split by road class.';}
  else if(which==='pci'){if(sc)sc.textContent='PCI Report';if(sb)sb.textContent='IRC:82-2023 Pavement Condition Index · PWD-section-wise — Section Label, Road Name and PCI class.';}
  else if(which==='culv'){if(sc)sc.textContent='Culvert Dashboard';if(sb)sb.textContent='Culverts by district and road class — total count and distribution.';}
  else if(which==='brid'){if(sc)sc.textContent='Bridge Dashboard';if(sb)sb.textContent='Bridges by district and road class — count, total length and distribution.';}
  else if(which==='survey'){if(sc)sc.textContent='Survey Dashboard';if(sb)sb.textContent='Year-wise field survey volumes — NSV lane-km, FWD points, traffic stations, soil tests and bituminous cores, with district-wise breakdown.';}
  else{if(sc)sc.textContent='Road Network Overview';if(sb)sb.textContent='Kerala PWD — network length, classification and ownership at a glance.';}
}
function dashTab(which){
  setDashTab(which);
  if(which==='pci'){renderPciReport();}
  else if(which==='pcia'){renderPciAnalysis();}
  else if(which==='culv'){renderAssetDash('culvert');}
  else if(which==='brid'){renderAssetDash('brid');}
  else if(which==='survey'){renderSurveyDash();}
  else{if(dashData)renderDashboard();else document.getElementById('dashBody').innerHTML='<div class="dash-loading">Loading network figures…</div>';}
}
