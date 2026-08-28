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
})();
