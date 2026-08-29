/* ============================================================
   KLRAMS viewer · mobile-app.js
   Native-app gestures for the GIS viewer on phones: draggable bottom
   sheets with snap points, a viewport-height lock that survives the
   collapsing browser chrome, and back-button dismissal.
   Pairs with css/mobile-app.css. Loaded last, after the older
   #mobile-ux inline script whose open/close behaviour it builds on.
   ============================================================ */
(function(){
  var mq = window.matchMedia('(max-width:820px)');
  var LANDSCAPE = window.matchMedia('(max-width:900px) and (max-height:480px) and (orientation:landscape)');
  function phone(){ return mq.matches && !LANDSCAPE.matches; }

  /* ---- 1. viewport height ------------------------------------------------
     100vh on mobile Safari/Chrome measures the *expanded* viewport, so the
     bottom of the app hides behind the URL bar. visualViewport reports what
     is actually on screen. dvh covers modern browsers; this is the fallback
     and also keeps the sheet maths honest while the keyboard is open. */
  function syncVH(){
    var h = (window.visualViewport && window.visualViewport.height) || window.innerHeight;
    document.documentElement.style.setProperty('--kl-app-h', h + 'px');
  }
  syncVH();
  window.addEventListener('resize', syncVH);
  window.addEventListener('orientationchange', function(){ setTimeout(syncVH, 240); });
  if (window.visualViewport) window.visualViewport.addEventListener('resize', syncVH);

  /* ---- 2. draggable sheet ------------------------------------------------ */
  function viewportH(){
    return (window.visualViewport && window.visualViewport.height) || window.innerHeight;
  }

  /* Height the sheet may occupy: everything between the top bar and the tab
     bar. Snapping against the raw viewport instead would let "full" slide the
     sheet under the top bar. */
  function sheetSpace(){
    var cs = getComputedStyle(document.documentElement);
    var top = parseFloat(cs.getPropertyValue('--kl-topbar-h')) || 52;
    var rail = document.getElementById('iconrail');
    var tab = rail ? rail.getBoundingClientRect().height : 64;
    return Math.max(180, viewportH() - top - tab - 12);
  }

  /* Peek (a few rows), half, and full. Dragging below the peek dismisses. */
  function snapsFor(){
    var h = sheetSpace();
    return [Math.round(h * 0.36), Math.round(h * 0.62), Math.round(h)];
  }

  function nearest(list, v){
    return list.reduce(function(a, b){ return Math.abs(b - v) < Math.abs(a - v) ? b : a; });
  }

  function makeSheet(el, cssVar, opts){
    opts = opts || {};
    var startY = 0, startH = 0, dragging = false, moved = false, pid = null;

    function setH(px){
      document.documentElement.style.setProperty(cssVar, px + 'px');
      document.body.classList.toggle('kl-sheet-full', px > sheetSpace() * 0.9);
    }

    /* Only the top strip of the sheet grabs. Anywhere else keeps its normal
       scrolling, otherwise the layer list becomes impossible to scroll. */
    function inHandle(e){
      var r = el.getBoundingClientRect();
      if (e.clientY - r.top <= 34) return true;
      /* also allow the drag when the scroll area is already at the top and the
         gesture pulls downward — the standard "pull the sheet closed" feel */
      var sc = el.querySelector('.fpane.active, .fpane, #riBody');
      return !!(sc && sc.scrollTop <= 0);
    }

    el.addEventListener('pointerdown', function(e){
      if (!phone() || e.pointerType === 'mouse' && e.button !== 0) return;
      if (!inHandle(e)) return;
      dragging = true; moved = false; pid = e.pointerId;
      startY = e.clientY;
      startH = el.getBoundingClientRect().height;
    });

    el.addEventListener('pointermove', function(e){
      if (!dragging || e.pointerId !== pid) return;
      var dy = startY - e.clientY;
      if (!moved){
        if (Math.abs(dy) < 5) return;
        moved = true;
        el.classList.add('kl-dragging');
        try { el.setPointerCapture(pid); } catch (err) {}
      }
      var h = Math.max(80, Math.min(sheetSpace(), startH + dy));
      setH(h);
      e.preventDefault();
    }, {passive:false});

    function end(e){
      if (!dragging || (e && e.pointerId !== pid)) return;
      dragging = false;
      el.classList.remove('kl-dragging');
      try { el.releasePointerCapture(pid); } catch (err) {}
      if (!moved) return;
      var h = el.getBoundingClientRect().height;
      var s = snapsFor();
      if (h < s[0] * 0.62 && opts.onDismiss){ opts.onDismiss(); return; }
      setH(nearest(s, h));
    }
    el.addEventListener('pointerup', end);
    el.addEventListener('pointercancel', end);

    return {
      reset: function(){ setH(snapsFor()[opts.defaultSnap || 1]); },
      clear: function(){ document.body.classList.remove('kl-sheet-full'); }
    };
  }

  /* ---- 3. wire the two sheets ------------------------------------------- */
  function ready(fn){
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  ready(function(){
    var panes = document.getElementById('fpanes');
    var ri = document.getElementById('roadInspector');

    if (panes){
      var sheet = makeSheet(panes, '--kl-sheet-h', {
        defaultSnap: 1,
        onDismiss: function(){ if (window.klClosePanes) window.klClosePanes(); }
      });
      /* Re-snap to the default every time a tool opens the sheet, so a pane
         left at "full" does not swallow the map on the next, shorter pane.
         Only react to the open/closed flip — the drag toggles a class on this
         same element, and re-snapping mid-gesture would fight the finger. */
      var wasHidden = panes.classList.contains('hidden');
      new MutationObserver(function(){
        var hidden = panes.classList.contains('hidden');
        if (hidden === wasHidden) return;
        wasHidden = hidden;
        if (!phone()) return;
        if (hidden) sheet.clear(); else sheet.reset();
      }).observe(panes, {attributes:true, attributeFilter:['class']});
      if (phone() && !panes.classList.contains('hidden')) sheet.reset();
    }

    if (ri){
      var riSheet = makeSheet(ri, '--kl-ri-h', {
        defaultSnap: 1,
        onDismiss: function(){ if (window.hideInspector) window.hideInspector(); }
      });
      var riWasOpen = ri.classList.contains('open');
      new MutationObserver(function(){
        var open = ri.classList.contains('open');
        if (open === riWasOpen) return;
        riWasOpen = open;
        if (phone() && open) riSheet.reset();
      }).observe(ri, {attributes:true, attributeFilter:['class']});
    }

    /* ---- 4. hardware back closes the open sheet before leaving the page --- */
    function anyOpen(){
      return (panes && !panes.classList.contains('hidden')) ||
             (ri && ri.classList.contains('open'));
    }
    var guarded = false;
    function guard(){
      if (!phone() || guarded || !anyOpen()) return;
      guarded = true;
      history.pushState({klSheet:1}, '');
    }
    window.addEventListener('popstate', function(e){
      guarded = false;
      if (!phone() || !anyOpen()) return;
      if (ri && ri.classList.contains('open') && window.hideInspector) window.hideInspector();
      else if (window.klClosePanes) window.klClosePanes();
      guard();
    });
    document.addEventListener('pointerup', function(){ setTimeout(guard, 60); }, true);

    /* ---- 5. keyboard dismissal -------------------------------------------
       Tapping the map with the search keyboard up should put it away, the way
       every native map app behaves. */
    var si = document.getElementById('searchInput');
    if (si){
      si.addEventListener('keydown', function(e){ if (e.key === 'Enter') si.blur(); });
      var mapEl = document.getElementById('map');
      if (mapEl) mapEl.addEventListener('pointerdown', function(){
        if (document.activeElement === si) si.blur();
      });
    }
  });

  /* ---- 6. map gestures -------------------------------------------------- */
  ready(function waitMap(){
    if (typeof map === 'undefined' || !map || !map.touchZoomRotate)
      return setTimeout(waitMap, 200);
    if (!phone()) return;
    /* One finger pans the map; two fingers zoom/rotate. Without this a
       one-finger drag inside an embedded map can scroll the page instead. */
    map.touchZoomRotate.enable();
    map.touchPitch && map.touchPitch.disable();
    map.dragRotate && map.dragRotate.disable();
  });

  /* ---- 7. tap tolerance --------------------------------------------------
     A road line is a few pixels wide and a fingertip covers about forty, so
     on a phone the popup only opens after zooming in far enough to make the
     line as fat as the finger. That is the whole complaint: the data is
     there, the aim isn't.

     So when a tap lands on nothing, ask the map what is NEAR it — one box
     query the width of a fingertip — and aim the tap at the closest of
     those features, on its own geometry. The tap is then re-dispatched
     THERE, as a normal map click, so every popup in the viewer — roads,
     condition, PCI, IRI, assets, traffic, user layers — keeps its own
     handler and none of the sixteen modules needs to know this exists.

     Aiming at the geometry rather than sampling blind rings around the tap
     is what makes this reliable: a ring of sample points 30 px out steps
     around its circle in ~18 px strides, and a road drawn 2 px wide fits
     between two strides comfortably — the feature is right there and the
     search walks straight past it.

     Which layers count comes from KLActive, the same list the active-layer
     chip offers, so a layer the user has switched off (or excluded with the
     chip) is not silently tapped instead. */
  var TAP_R = 36;                     // CSS px: how far a fingertip may miss by
  var PROBE = [0, 4, 8, 13];          // px around the aim point (see probe())
  var PROBE_STEPS = 8;
  var MAX_CANDIDATES = 3;

  function coarse(){
    try { return window.matchMedia('(pointer:coarse)').matches || phone(); }
    catch (e) { return phone(); }
  }

  function tapTargets(){
    if (!window.KLActive || !KLActive.layers) return [];
    return KLActive.layers().filter(function(id){
      var l;
      try { l = map.getLayer(id); } catch (e) { return false; }
      if (!l) return false;
      /* Fills are the size of a district: nobody misses one with a finger,
         and treating one as a target would hijack every tap that merely
         lands inside a boundary. */
      if (l.type === 'fill' || l.type === 'background' || l.type === 'raster') return false;
      try { if (map.getLayoutProperty(id, 'visibility') === 'none') return false; } catch (e) {}
      return KLActive.allows(id);
    });
  }

  function hitAt(p, layers){
    try { return map.queryRenderedFeatures([p.x, p.y], {layers: layers}).length > 0; }
    catch (e) { return false; }
  }

  /**
   * Closest point of `f`'s geometry to `pt`, in screen px.
   *
   * Query results carry the feature's geometry in lng/lat, so this is the
   * one place the search can be exact instead of hopeful.
   */
  function aimAt(pt, f){
    var g = f && f.geometry;
    if (!g) return null;
    var best = null;

    function consider(x, y){
      var d = Math.hypot(x - pt.x, y - pt.y);
      if (!best || d < best.d) best = {p: {x: x, y: y}, d: d};
    }
    function vertex(c){ var P = map.project(c); consider(P.x, P.y); }
    function path(cs){
      if (cs.length === 1) return vertex(cs[0]);
      for (var i = 1; i < cs.length; i++){
        var A = map.project(cs[i - 1]), B = map.project(cs[i]);
        var vx = B.x - A.x, vy = B.y - A.y, L2 = vx * vx + vy * vy;
        var t = L2 ? ((pt.x - A.x) * vx + (pt.y - A.y) * vy) / L2 : 0;
        t = t < 0 ? 0 : t > 1 ? 1 : t;
        consider(A.x + t * vx, A.y + t * vy);
      }
    }
    function each(list, fn){ for (var i = 0; i < list.length; i++) fn(list[i]); }

    if (g.type === 'Point') vertex(g.coordinates);
    else if (g.type === 'MultiPoint') each(g.coordinates, vertex);
    else if (g.type === 'LineString') path(g.coordinates);
    else if (g.type === 'MultiLineString' || g.type === 'Polygon') each(g.coordinates, path);
    else if (g.type === 'MultiPolygon') each(g.coordinates, function(poly){ each(poly, path); });
    return best;
  }

  /**
   * A point that really hits `layer`, at or just around `aim`.
   *
   * The aim point sits on the feature's geometry, which is where most
   * layers are drawn — but not all: the condition lanes, PCI and the IRI
   * roll-up are drawn with a line-offset of up to 8 px, so their paint is
   * beside their geometry, not on it. Hence the small rings.
   */
  function probe(aim, layer){
    for (var i = 0; i < PROBE.length; i++){
      var r = PROBE[i];
      if (!r){ if (hitAt(aim, [layer])) return aim; continue; }
      for (var k = 0; k < PROBE_STEPS; k++){
        var a = k * 2 * Math.PI / PROBE_STEPS;
        var p = {x: aim.x + r * Math.cos(a), y: aim.y + r * Math.sin(a)};
        if (p.x < 0 || p.y < 0) continue;
        if (hitAt(p, [layer])) return p;
      }
    }
    return null;
  }

  /** Where to re-aim a tap that landed on nothing, or null to leave it be. */
  function nearestTarget(pt, layers){
    var fs;
    try {
      fs = map.queryRenderedFeatures(
        [[pt.x - TAP_R, pt.y - TAP_R], [pt.x + TAP_R, pt.y + TAP_R]], {layers: layers});
    } catch (e) { return null; }
    if (!fs || !fs.length) return null;

    /* Closest first, so the popup that opens is the feature the thumb was
       plainly going for — not whichever one the renderer listed first. */
    var cands = [];
    for (var i = 0; i < fs.length && cands.length < 60; i++){
      var a = aimAt(pt, fs[i]);
      /* The query box is square, so its corners reach 1.4x further than its
         sides: without this the tolerance would depend on which way the
         road happens to run. Judge the distance itself. */
      if (a && a.d <= TAP_R) cands.push({p: a.p, d: a.d, layer: fs[i].layer.id});
    }
    cands.sort(function(x, y){ return x.d - y.d; });

    for (var k = 0; k < cands.length && k < MAX_CANDIDATES; k++){
      var hit = probe(cands[k].p, cands[k].layer);
      if (hit) return hit;
    }
    return null;
  }

  ready(function waitTap(){
    if (typeof map === 'undefined' || !map || !map.queryRenderedFeatures)
      return setTimeout(waitTap, 200);

    map.on('click', function(e){
      if (!coarse() || e._klTap) return;
      /* The measure tool wants the raw point the finger touched, not the
         nearest feature — and it suppresses popups anyway. */
      if (typeof measureMode !== 'undefined' && measureMode) return;

      var layers = tapTargets();
      if (!layers.length) return;
      if (hitAt(e.point, layers)) return;         // landed on it already

      var p = nearestTarget(e.point, layers);
      if (!p) return;

      /* The re-dispatched point MUST be a real MapLibre Point, not a plain
         {x, y}: every popup handler in the viewer reaches this through
         queryRenderedFeatures(e.point, ...), and MapLibre does not recognise
         a duck-typed object as a point — it decides the argument must be an
         options bag and queries the WHOLE VIEWPORT instead. Every clickable
         layer on screen then answers the tap, with whatever feature the
         query happened to list first. map.project returns the real class. */
      var ll = map.unproject([p.x, p.y]);
      map.fire('click', {
        point: map.project(ll),
        lngLat: ll,
        originalEvent: e.originalEvent,
        _klTap: 1
      });
    });
  });
})();
