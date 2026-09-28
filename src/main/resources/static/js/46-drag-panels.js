/* 46-drag-panels — lets the user drag two on-map boxes anywhere over the
   viewer and remembers where they left them: the Active Map Layers chip
   (#activeLayerBox) and the Road Network filter Summary card
   (#netScopeCard). Both start life fixed by top/left CSS in map.html;
   this only takes over once the user actually grabs one.

   Position is stored per box in localStorage, the same pattern
   maplegend.js already uses for #mapLegend, so a saved position survives
   reload and re-render (renderNetScopeCard() only rewrites the card's
   inner HTML, never the card element itself, so a style.left/top set
   here is never clobbered by a later refresh).
   ============================================================ */
(function () {
  'use strict';

  /**
   * @param {string} elId        the absolutely-positioned box to move
   * @param {Element} handle     the element pointer-drags are read from
   * @param {string} storageKey  localStorage key for the saved position
   * @param {object} [opts]
   * @param {string} [opts.ignore]  a selector for descendants of `handle`
   *                                that must keep their own click/press
   *                                behaviour (e.g. the minimize/close
   *                                buttons) instead of starting a drag
   * @param {boolean} [opts.suppressClick]  when the handle is itself a
   *                                clickable control (a button that opens
   *                                a menu), mark it so the caller's own
   *                                click handler can tell a drag from a
   *                                tap and skip firing right after one
   */
  function makeDraggable(elId, handle, storageKey, opts) {
    opts = opts || {};
    var el = document.getElementById(elId);
    var container = document.getElementById('main');
    if (!el || !handle || !container) return;

    var dragging = false, moved = false, offX = 0, offY = 0, startX = 0, startY = 0;

    function clamp(left, top) {
      var maxL = container.clientWidth - el.offsetWidth, maxT = container.clientHeight - el.offsetHeight;
      return { left: Math.max(0, Math.min(left, Math.max(0, maxL))), top: Math.max(0, Math.min(top, Math.max(0, maxT))) };
    }

    try {
      var pos = JSON.parse(localStorage.getItem(storageKey) || 'null');
      if (pos && isFinite(pos.left) && isFinite(pos.top)) {
        el.style.left = pos.left + 'px'; el.style.top = pos.top + 'px';
        el.style.right = 'auto'; el.style.bottom = 'auto';
      }
    } catch (e) { }

    handle.addEventListener('pointerdown', function (e) {
      if (opts.ignore && e.target.closest && e.target.closest(opts.ignore)) return;
      var cRect = container.getBoundingClientRect(), eRect = el.getBoundingClientRect();
      offX = e.clientX - eRect.left; offY = e.clientY - eRect.top;
      startX = e.clientX; startY = e.clientY;
      dragging = true; moved = false;
      try { handle.setPointerCapture(e.pointerId); } catch (err) { }
    });

    handle.addEventListener('pointermove', function (e) {
      if (!dragging) return;
      /* A real click still has to reach the handle's own listener (opening
         the Active Map Layers menu), so nothing is repositioned until the
         pointer has actually travelled — a plain tap never crosses this. */
      if (!moved && (Math.abs(e.clientX - startX) > 4 || Math.abs(e.clientY - startY) > 4)) {
        moved = true;
        var cRect0 = container.getBoundingClientRect(), eRect0 = el.getBoundingClientRect();
        el.style.left = (eRect0.left - cRect0.left) + 'px'; el.style.top = (eRect0.top - cRect0.top) + 'px';
        el.style.right = 'auto'; el.style.bottom = 'auto';
        el.classList.add('kl-dragging');
      }
      if (!moved) return;
      var cRect = container.getBoundingClientRect();
      var c = clamp(e.clientX - cRect.left - offX, e.clientY - cRect.top - offY);
      el.style.left = c.left + 'px'; el.style.top = c.top + 'px';
    });

    function endDrag(e) {
      if (!dragging) return;
      dragging = false;
      el.classList.remove('kl-dragging');
      try { handle.releasePointerCapture(e.pointerId); } catch (err) { }
      if (moved) {
        try { localStorage.setItem(storageKey, JSON.stringify({ left: parseFloat(el.style.left), top: parseFloat(el.style.top) })); } catch (err) { }
        if (opts.suppressClick) handle.dataset.klDragged = '1';
      }
      moved = false;
    }
    handle.addEventListener('pointerup', endDrag);
    handle.addEventListener('pointercancel', endDrag);
  }

  function start() {
    var albBtn = document.getElementById('albBtn');
    if (albBtn) makeDraggable('activeLayerBox', albBtn, 'klActiveLayerBoxPos', { suppressClick: true });

    var nscHead = document.querySelector('#netScopeCard .nsc-head');
    if (nscHead) makeDraggable('netScopeCard', nscHead, 'klNetScopeCardPos', { ignore: '.nsc-min,.nsc-x' });
  }

  if (document.readyState !== 'loading') start(); else document.addEventListener('DOMContentLoaded', start);
})();
