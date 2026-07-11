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
      /* Build 167 — also background-preload the FWD map layer AFTER the segments
         finish, so switching "FWD (D0)" on is an instant visibility flip instead
         of a fetch at click time. addAssetLayer honours the toggle state, so the
         preloaded layer stays hidden until showFwd is turned on. The download is
         shared with 24-fwd.js via fwdGeojsonFetch (one fetch per login). */
      const _preloadFwd=()=>{try{
        if(typeof ASSETS==='undefined'||typeof loadAsset!=='function')return;
        const _fa=ASSETS.find(x=>x.type==='fwd');
        if(_fa&&!map.getSource(_fa.layer))loadAsset(_fa);
      }catch(e){}};
      /* Build 168 — precompute PCI silently once the segments are in memory, so
         the Composite/Worst-Lane PCI toggles flip on instantly instead of running
         the full 33k-segment recompute at click time. silent=true keeps both
         layers hidden and does NOT auto-tick the Composite toggle. Deferred a
         beat so the segment paint finishes first (PCI is pure CPU, no download).
         FWD (network-bound) starts in parallel — they don't compete. */
      const _preloadPci=()=>{setTimeout(()=>{try{
        if(typeof generatePCI==='function'&&typeof DATA!=='undefined'&&DATA&&!map.getLayer('pci-avg'))generatePCI(true);
      }catch(e){}},800);};
      const _preloadAfterSegs=()=>{_preloadFwd();_preloadPci();};
      if(typeof loadSegments==='function'&&!map.getSource('segs')){
        setTimeout(()=>{try{loadSegments().then(_preloadAfterSegs,_preloadAfterSegs);}catch(e){}},1500);
      }else{
        setTimeout(_preloadAfterSegs,1500);
      }
    });
});

