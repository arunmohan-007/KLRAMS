/* ============================================================
   KLRAMS viewer · 48-map-context-menu.js
   Right-click (or long-press on a phone) anywhere on the map to get
   the latitude / longitude of that exact spot, with one click to copy
   it as "lat, long" or as a Google Maps link.
   ============================================================ */
(function () {
  'use strict';
  if (typeof map === 'undefined' || !map.on) return;

  var menu = null;
  var current = null;

  function css() {
    var s = document.createElement('style');
    s.textContent =
      '.kl-ctx{position:absolute;z-index:9999;min-width:190px;background:#1f2933;color:#fff;' +
      'border-radius:8px;box-shadow:0 6px 20px rgba(0,0,0,.35);font:13px/1.3 system-ui,sans-serif;' +
      'padding:4px;user-select:none}' +
      '.kl-ctx-h{padding:6px 10px;font-size:12px;opacity:.75;border-bottom:1px solid rgba(255,255,255,.15);' +
      'margin-bottom:4px;font-variant-numeric:tabular-nums}' +
      '.kl-ctx button{display:block;width:100%;text-align:left;background:none;border:0;color:inherit;' +
      'padding:7px 10px;border-radius:5px;font:inherit;cursor:pointer}' +
      '.kl-ctx button:hover{background:rgba(255,255,255,.14)}';
    document.head.appendChild(s);
  }

  function fmt(ll) { return ll.lat.toFixed(6) + ', ' + ll.lng.toFixed(6); }

  function dms(v, pos, neg) {
    var a = Math.abs(v), d = Math.floor(a), m = Math.floor((a - d) * 60);
    var s = ((a - d) * 60 - m) * 60;
    return d + '°' + m + '′' + s.toFixed(2) + '″' + (v >= 0 ? pos : neg);
  }

  function copy(text) {
    function fallback() {
      var t = document.createElement('textarea');
      t.value = text;
      t.style.cssText = 'position:fixed;left:-9999px';
      document.body.appendChild(t);
      t.select();
      try { document.execCommand('copy'); } catch (e) { /* nothing more to try */ }
      document.body.removeChild(t);
    }
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text).catch(fallback);
    } else {
      fallback();
    }
  }

  function close() {
    if (menu) { menu.remove(); menu = null; }
  }

  function flash(btn, msg) {
    btn.textContent = msg;
    setTimeout(close, 700);
  }

  function open(e) {
    close();
    if (!menu && !document.getElementById('kl-ctx-style')) css();
    current = e.lngLat;
    menu = document.createElement('div');
    menu.className = 'kl-ctx';
    menu.innerHTML =
      '<div class="kl-ctx-h"></div>' +
      '<button type="button" data-k="ll">Copy Lat, Long</button>' +
      '<button type="button" data-k="xy">Copy Long, Lat (x, y)</button>' +
      '<button type="button" data-k="dms">Copy Degrees-Minutes-Seconds</button>' +
      '<button type="button" data-k="gm">Copy Google Maps link</button>';
    menu.firstChild.textContent = 'WGS 84 (EPSG:4326)  ' + fmt(current);
    menu.addEventListener('click', function (ev) {
      var b = ev.target.closest('button');
      if (!b) return;
      var k = b.dataset.k;
      if (k === 'll') copy(fmt(current));
      else if (k === 'xy') copy(current.lng.toFixed(6) + ', ' + current.lat.toFixed(6));
      else if (k === 'dms') copy(dms(current.lat, 'N', 'S') + ' ' + dms(current.lng, 'E', 'W'));
      else copy('https://www.google.com/maps?q=' + current.lat.toFixed(6) + ',' + current.lng.toFixed(6));
      flash(b, 'Copied ✓');
    });
    menu.addEventListener('contextmenu', function (ev) { ev.preventDefault(); });

    var host = map.getContainer();
    host.appendChild(menu);
    var w = host.clientWidth, h = host.clientHeight;
    var x = Math.min(e.point.x, w - menu.offsetWidth - 6);
    var y = Math.min(e.point.y, h - menu.offsetHeight - 6);
    menu.style.left = Math.max(4, x) + 'px';
    menu.style.top = Math.max(4, y) + 'px';
  }

  map.on('contextmenu', function (e) {
    if (e.originalEvent && e.originalEvent.preventDefault) e.originalEvent.preventDefault();
    open(e);
  });
  map.on('movestart', close);
  map.on('click', close);
  document.addEventListener('keydown', function (ev) { if (ev.key === 'Escape') close(); });
})();
