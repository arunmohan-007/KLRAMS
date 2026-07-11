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
      /* Build 169 — STRICT sequential preload order, one download at a time so
         nothing competes for bandwidth on slow links:
           1. Road Network  (already fully loaded above — this chain runs after it)
           2. Condition Data (segments)
           3. FWD            (map layer + chainage lookup share one download;
                              24-fwd.js no longer self-boots on its own timer)
           4. PCI            (pure CPU compute over the segments, no download)
         Every step creates its layers hidden; the toggles just flip visibility. */
      const _preloadFwd=()=>{try{
        if(typeof ASSETS==='undefined'||typeof loadAsset!=='function')return Promise.resolve();
        const _fa=ASSETS.find(x=>x.type==='fwd');
        return Promise.resolve((_fa&&!map.getSource(_fa.layer))?loadAsset(_fa):null)
          .then(()=>{try{if(window.FWD&&FWD.load)return FWD.load();}catch(e){}});
      }catch(e){return Promise.resolve();}};
      const _preloadPci=()=>{setTimeout(()=>{try{
        if(typeof generatePCI==='function'&&typeof DATA!=='undefined'&&DATA&&!map.getLayer('pci-avg'))generatePCI(true);
      }catch(e){}},300);};
      /* Build 170 — if the user jumps straight into NSV footage, hold the
         remaining background steps while the video is actually PLAYING: the FWD
         download would fight the video stream for bandwidth ("Buffering…") and
         the 33k-segment PCI compute would jank the main thread mid-playback.
         Resumes when the video pauses/ends/closes. The HUD still pulls FWD
         on-demand itself (FWD.at lazy-load) if it needs deflections sooner. */
      const _videoIdle=()=>new Promise(res=>{(function chk(){try{
        const v=document.getElementById('video'),dk=document.getElementById('dock');
        if(dk&&dk.classList.contains('open')&&v&&v.src&&!v.paused&&!v.ended)return setTimeout(chk,4000);
      }catch(e){}res();})();});
      const _afterSegs=()=>{_videoIdle().then(_preloadFwd).then(_videoIdle,_videoIdle).then(_preloadPci,_preloadPci);};
      if(typeof loadSegments==='function'&&!map.getSource('segs')){
        setTimeout(()=>{try{loadSegments().then(_afterSegs,_afterSegs);}catch(e){}},1500);
      }else{
        setTimeout(_afterSegs,1500);
      }
    });
});

