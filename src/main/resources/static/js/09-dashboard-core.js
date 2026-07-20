/* ============================================================
   KLRAMS viewer · 09-dashboard-core.js
   Dashboard open/close and tab switching.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
// ===== dashboard =====
let dashData=null;
let dashView='hub';   // 'hub' = tile picker, 'detail' = one dashboard full-screen
function closeDashboard(){
  if(dashView==='detail'){dashBackToHub();return;}
  document.getElementById('dashboard').classList.remove('open');
}
function dashBackToHub(){
  dashView='hub';
  document.getElementById('dashHub').style.display='';
  document.getElementById('dashBody').style.display='none';
  const sc=document.getElementById('dashScope'),sb=document.getElementById('dashSub'),cb=document.getElementById('dashCloseBtn');
  if(sc)sc.textContent='Dashboards';
  if(sb)sb.textContent='Choose a dashboard to explore network, pavement, asset and survey figures.';
  if(cb)cb.title='Back to map';
}
let dashTabCur='overview';
function loadDashboard(){
  const dash=document.getElementById('dashboard');dash.classList.add('open');
  dashBackToHub();
}
function setDashTab(which){
  dashTabCur=which;
  const sc=document.getElementById('dashScope'),sb=document.getElementById('dashSub');
  if(which==='pcia'){if(sc)sc.textContent='PCI Analysis';if(sb)sb.textContent='IRC:82-2023 Pavement Condition Index · share of network length by rating, and rating split by road class.';}
  else if(which==='pci'){if(sc)sc.textContent='PCI Report';if(sb)sb.textContent='IRC:82-2023 Pavement Condition Index · PWD-section-wise — Section Label, Road Name and PCI class.';}
  else if(which==='culv'){if(sc)sc.textContent='Culvert Dashboard';if(sb)sb.textContent='Culverts by district and road class — total count and distribution.';}
  else if(which==='brid'){if(sc)sc.textContent='Bridge Dashboard';if(sb)sb.textContent='Bridges by district and road class — count, total length and distribution.';}
  else if(which==='survey'){if(sc)sc.textContent='Survey Dashboard';if(sb)sb.textContent='Year-wise field survey volumes — NSV lane-km, FWD points, traffic stations, soil tests and bituminous cores, with district-wise breakdown.';}
  else if(which==='fwd'){if(sc)sc.textContent='FWD Dashboard';if(sb)sb.textContent='Falling Weight Deflectometer — test points, D0 deflection lower-to-higher by SH/MDR and district, pavement & air temperature min/max/mean.';}
  else if(which==='cond'){if(sc)sc.textContent='Condition Dashboard';if(sb)sb.textContent='Road-condition parameters — state-wide & district-wise Low / High / Mean by surface type (Flexible / Cement Concrete / Paver Block) and road class (SH / MDR), with a threshold segment list.';}
  else{if(sc)sc.textContent='Road Network Overview';if(sb)sb.textContent='Kerala PWD — network length, classification and ownership at a glance.';}
}
function dashTab(which){
  dashView='detail';
  document.getElementById('dashHub').style.display='none';
  document.getElementById('dashBody').style.display='';
  const cb=document.getElementById('dashCloseBtn');if(cb)cb.title='Back to dashboards';
  setDashTab(which);
  if(which==='pci'){renderPciReport();}
  else if(which==='pcia'){renderPciAnalysis();}
  else if(which==='culv'){renderAssetDash('culvert');}
  else if(which==='brid'){renderAssetDash('brid');}
  else if(which==='survey'){renderSurveyDash();}
  else if(which==='fwd'){renderFwdDash();}
  else if(which==='cond'){renderCondDash();}
  else{
    if(dashData){renderDashboard();return;}
    document.getElementById('dashBody').innerHTML='<div class="dash-loading">Loading network figures…</div>';
    fetch('/api/dashboard/summary').then(r=>r.json()).then(d=>{dashData=d;if(dashTabCur==='overview')renderDashboard();})
      .catch(e=>{document.getElementById('dashBody').innerHTML='<div class="dash-loading">Could not load dashboard: '+e.message+'</div>';});
  }
}
