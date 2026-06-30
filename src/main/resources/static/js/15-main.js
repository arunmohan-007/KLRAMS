/* ============================================================
   KLRAMS viewer · 15-main.js
   Application bootstrap — wires the search once the map style has loaded.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
map.on('load',()=>{
  /* build 120 — preload the survey road network so road-name search works
     immediately and the (invisible) hit layer is clickable for Video-on-click,
     even before the "Road network" toggle is switched on. Styling stays hidden
     until the toggle is turned on; loadRoads(true) skips the auto-fit. */
  Promise.resolve(typeof loadRoads==='function'?loadRoads(true):null)
    .then(()=>loadCatalog())
    .then(()=>{setupSearch();setupLocationSearch();});
});

