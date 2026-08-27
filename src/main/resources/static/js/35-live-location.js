/* ============================================================
   KLRAMS viewer · 35-live-location.js
   "My location" — a live GPS dot on the map for field use.

   Tap the crosshair button (top-right, under the zoom controls) and the
   browser asks for location permission once; after that the dot follows
   the device continuously (watchPosition), so while driving a road the
   dot moves along it and the data under it can be checked against what
   is on the ground.

   Built on MapLibre's GeolocateControl, so permission handling, the
   accuracy circle, heading and the follow/free-pan states come for free.
   The only additions here are KLRAMS colours for the dot and a readout
   chip with latitude, longitude, accuracy and speed.

   Requires a secure context (https:// or localhost) — browsers refuse
   geolocation over plain http. klrams.fist.social is https, so this
   works in the field; a local http test box will show the button
   disabled with an explanatory tooltip.
   ============================================================ */
(function () {
  'use strict';

  /* Styles live here rather than in app.css because the dot must look the
     same in day and night mode; keeping them together avoids a second set
     of !important overrides in klrams-dark.css. */
  var CSS = [
    '.maplibregl-user-location-dot,.maplibregl-user-location-dot::before{background-color:#00d4ff}',
    '.maplibregl-user-location-dot::after{border:2px solid #fff;box-shadow:0 0 0 2px rgba(0,212,255,.55),0 2px 8px rgba(0,0,0,.45)}',
    '.maplibregl-user-location-accuracy-circle{background-color:rgba(0,212,255,.18)}',
    '#llChip{position:absolute;left:50%;transform:translateX(-50%);bottom:34px;z-index:5;',
    'display:none;align-items:center;gap:10px;padding:6px 13px;border-radius:14px;',
    'background:rgba(9,20,32,.9);border:1px solid rgba(0,212,255,.5);color:#dff3ff;',
    'font-family:var(--mono,monospace);font-size:11.5px;letter-spacing:.2px;',
    'box-shadow:0 6px 20px rgba(0,0,0,.35);backdrop-filter:blur(4px);pointer-events:none;',
    'max-width:calc(100% - 16px);flex-wrap:wrap;justify-content:center;text-align:center}',
    '#llChip.on{display:flex}',
    '#llChip b{color:#00d4ff;font-weight:600}',
    '#llChip .llacc{color:#9fc4d8}',
    '@media(max-width:820px){#llChip{bottom:76px;font-size:11px;padding:5px 10px;gap:7px}}'
  ].join('');

  function injectCss() {
    var s = document.createElement('style');
    s.id = 'll-css';
    s.textContent = CSS;
    document.head.appendChild(s);
  }

  var errTimer = null;

  function chip() { return document.getElementById('llChip'); }

  function showChip(pos) {
    var c = chip();
    if (!c) return;
    clearTimeout(errTimer);
    var cd = pos.coords;
    var acc = isFinite(cd.accuracy) ? Math.round(cd.accuracy) + ' m' : '—';
    /* speed is m/s and null when the device cannot derive it (stationary,
       or a desktop using wifi positioning). */
    var spd = (cd.speed != null && isFinite(cd.speed) && cd.speed > 0.3)
      ? ' · <b>' + (cd.speed * 3.6).toFixed(0) + '</b> km/h' : '';
    c.innerHTML = '<span><b>' + cd.latitude.toFixed(5) + '</b>, <b>' +
      cd.longitude.toFixed(5) + '</b></span>' +
      '<span class="llacc">±' + acc + '</span>' + spd;
    c.classList.add('on');
  }

  function hideChip() {
    var c = chip();
    if (c) c.classList.remove('on');
  }

  function boot() {
    if (typeof map === 'undefined' || !map || typeof maplibregl === 'undefined') return;

    injectCss();

    var c = document.createElement('div');
    c.id = 'llChip';
    (map.getContainer() || document.body).appendChild(c);

    var geo = new maplibregl.GeolocateControl({
      positionOptions: { enableHighAccuracy: true, timeout: 10000, maximumAge: 2000 },
      trackUserLocation: true,     // keep watching and keep the dot moving
      showUserLocation: true,
      showAccuracyCircle: true,
      showUserHeading: true,
      fitBoundsOptions: { maxZoom: 17 }
    });
    map.addControl(geo, 'top-right');

    geo.on('geolocate', showChip);
    geo.on('trackuserlocationend', hideChip);
    geo.on('error', function (e) {
      /* code 1 = permission denied, 2 = position unavailable, 3 = timeout.
         There is no toast helper in the viewer, so the chip doubles as the
         error surface and clears itself after a few seconds. */
      var msg = (e && e.code === 1)
        ? 'Location blocked — allow location for this site in the browser'
        : 'No location fix — move to open sky and try again';
      var el = chip();
      if (!el) return;
      el.innerHTML = '<span class="llacc">' + msg + '</span>';
      el.classList.add('on');
      clearTimeout(errTimer);
      errTimer = setTimeout(hideChip, 6000);
    });

    /* The button's own label is left to MapLibre: it sets it asynchronously
       once the geolocation support check resolves ("Find my location", or
       "Location not available" over plain http), and overwrites anything
       set here beforehand. */

    window.klLiveLocation = geo;   // so other modules can trigger a fix
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
