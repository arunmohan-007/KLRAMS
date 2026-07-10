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
    .then(()=>{setupSearch();setupLocationSearch();})
    .then(()=>{
      /* Background-preload the condition/PCI segments AFTER roads, catalog and
         search are ready, so login stays fast but the Road Condition layer is
         already in memory — turning the toggle on is then instant instead of
         waiting for a fresh fetch. Fire-and-forget on a short delay so it never
         competes with the initial map paint; syncLazyVis() (and addCondLayers,
         which now honours the toggle) keep the layer hidden while showCond is off. */
      if(typeof loadSegments==='function'&&!map.getSource('segs')){
        setTimeout(()=>{try{loadSegments();}catch(e){}},1500);
      }
    });
});

