/* ============================================================================
   40-module-menu.js — the rail's grouped "Modules" button

   The icon rail carried nine map tools, three full-screen modules and the
   Console link. On a laptop that list scrolled, and the icons at the end were
   not visible at all. Dashboard, Map Composer and Report Hub now share ONE rail
   slot: the button opens a flyout listing the three.

   Two deliberate choices:

   1. The three buttons still live inside #iconrail, still carry
      `class="railbtn"` and their own data-pane. Every module already lights its
      rail button with `querySelectorAll('#iconrail .railbtn')` (see
      11-dashboard-charts.js, 21-report-hub.js, 38b-composer-ui.js) — keeping
      them in that collection means none of those files had to change.
   2. The grouped button's own highlight is MIRRORED from those three by a
      MutationObserver rather than set by hand on open. Set by hand it would
      stick: closing a module hands the highlight back through
      railSyncToPanes(), which knows nothing about a grouped button.
   ========================================================================== */
(function () {
  'use strict';

  function menu() { return document.getElementById('moduleMenu'); }
  function btn() { return document.getElementById('modulesRailBtn'); }

  function isOpen() { var m = menu(); return !!m && !m.hidden; }

  /* Anchor the flyout to the rail button. It is position:fixed, so it is
     clipped by neither the rail's scroller nor the mobile tab bar — but that
     also means the placement is ours to compute. On mobile the rail sits at the
     foot of the screen, so the menu opens upward. */
  function place() {
    var m = menu(), b = btn();
    if (!m || !b) return;
    var r = b.getBoundingClientRect();
    var rail = document.getElementById('iconrail');
    var railR = rail ? rail.getBoundingClientRect() : r;
    var mh = m.offsetHeight, mw = m.offsetWidth;
    var gap = 8, pad = 10;

    /* Beside the rail when the rail is a column; above it when it is a bottom
       bar (mobile), where there is no room to the side. */
    var bottomBar = railR.width > railR.height;
    var left, top;
    if (bottomBar) {
      left = Math.min(Math.max(pad, r.left + r.width / 2 - mw / 2), window.innerWidth - mw - pad);
      top = railR.top - mh - gap;
    } else {
      left = railR.right + gap;
      top = r.top + r.height / 2 - mh / 2;
    }
    top = Math.min(Math.max(pad, top), window.innerHeight - mh - pad);
    m.style.left = Math.round(left) + 'px';
    m.style.top = Math.round(top) + 'px';
  }

  function open() {
    var m = menu(), b = btn();
    if (!m) return;
    m.hidden = false;
    m.classList.add('show');
    if (b) b.setAttribute('aria-expanded', 'true');
    place();                       /* needs the real height, so after unhide */
    document.addEventListener('mousedown', onOutside, true);
    document.addEventListener('keydown', onKey, true);
    window.addEventListener('resize', place);
  }

  function close() {
    var m = menu(), b = btn();
    if (!m || m.hidden) return;
    m.hidden = true;
    m.classList.remove('show');
    if (b) b.setAttribute('aria-expanded', 'false');
    document.removeEventListener('mousedown', onOutside, true);
    document.removeEventListener('keydown', onKey, true);
    window.removeEventListener('resize', place);
  }

  function onOutside(e) {
    var m = menu(), b = btn();
    if (!m) return;
    if (m.contains(e.target) || (b && b.contains(e.target))) return;
    close();
  }
  function onKey(e) { if (e.key === 'Escape') { close(); var b = btn(); if (b) b.focus(); } }

  window.toggleModuleMenu = function (e) {
    if (e) e.stopPropagation();
    if (isOpen()) close(); else open();
  };

  /* Every item closes the menu first, then runs its module — the modules take
     the whole screen, so a flyout left open would float over them. */
  window.pickModule = function (fn) {
    close();
    if (typeof fn === 'function') fn();
  };

  /* Mirror "one of my three is open" onto the grouped button. */
  function sync() {
    var m = menu(), b = btn();
    if (!m || !b) return;
    b.classList.toggle('active', !!m.querySelector('.railbtn.active'));
  }

  function boot() {
    var m = menu();
    if (!m) return;
    sync();
    new MutationObserver(sync).observe(m, {
      subtree: true, attributes: true, attributeFilter: ['class']
    });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
