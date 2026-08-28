/* ============================================================
   KLRAMS viewer · 02e-active-layer.js
   "Active layer" — which layer answers a click on the map.

   The viewer draws a dozen clickable layers on top of one another:
   the road network, condition colouring, PCI, the 2 km IRI roll-up,
   every asset class, traffic stations, boundaries and whatever the
   user imported in Layer Management (including the temporary ones).
   Each of those modules registers its own popup with
   map.on('click', <layerId>, ...), and MapLibre fires EVERY handler
   whose layer sits under the cursor. Where two layers overlap — which
   on a road map is everywhere — you get whichever popup happens to
   win, or several at once.

   This module puts the choice in the user's hands. A chip next to the
   search box lists the clickable layers that are actually on the map
   right now; pick one and only that layer answers clicks. "Auto"
   restores the old behaviour (topmost / all handlers fire).

   HOW IT GATES, and why it is done this way
   -----------------------------------------
   map.on / map.off are wrapped here, ONCE, before any other module
   loads (map.html loads this right after the layer registry). Every
   layer-scoped click handler registered anywhere in the viewer — past,
   present, and any layer created at runtime, which is what the user
   and temporary layers are — passes through this wrapper and is
   silently skipped when its layer is not the active one. No module
   needs to know this exists, and nothing has to be re-registered when
   the selection changes.

   Only 'click' and 'mouseenter' are gated. 'mouseleave' is left alone
   so the pointer cursor is always cleared, and the map-wide handlers
   (the measure tool) are untouched — they are not layer-scoped.
   ============================================================ */
var KLActive = (function () {
  'use strict';

  var AUTO = 'auto';
  var active = AUTO;          // group key, or AUTO
  var GROUPS = {};            // key -> {key,label,layers:[]}
  var ORDER = [];             // group keys, in the order they registered
  var BY_LAYER = {};          // render layer id -> group key
  var LABELS = {};            // group key -> label supplied by the owning module

  /* ------------------------------------------------------------------
     Naming a layer

     Most render layer ids are already described in 02b-layer-registry,
     so the label comes from there. The exceptions are ids the registry
     names for a different purpose (roadnet-hit is "the click target",
     which is not what a user picking a layer wants to read) and the
     user layers, which do not exist at build time and hand their name
     over through label() below.
     ------------------------------------------------------------------ */
  var OVERRIDES = [
    [/^roadnet-hit$/,     'roadnet',       'Road network'],
    [/^roadnet2$/,        'roadnet2',      'Merged road network'],
    [/^seg-/,             'condition',     'Road condition'],
    [/^district-/,        'district',      'District boundary'],
    [/^cons-/,            'constituency',  'Constituency boundary']
  ];

  function groupFor(layerId) {
    for (var i = 0; i < OVERRIDES.length; i++) {
      if (OVERRIDES[i][0].test(layerId)) {
        return { key: OVERRIDES[i][1], label: OVERRIDES[i][2] };
      }
    }
    var ul = /^ul-(\d+)-/.exec(layerId);
    if (ul) return { key: 'ul-' + ul[1], label: LABELS['ul-' + ul[1]] || ('Layer ' + ul[1]) };

    if (typeof KLLayers !== 'undefined' && KLLayers.specForLayer) {
      var spec = KLLayers.specForLayer(layerId);
      if (spec) return { key: spec.key, label: spec.label };
    }
    return { key: layerId, label: layerId };
  }

  /** Record that `layerId` has a popup, so it can be offered in the chip. */
  function register(layerId) {
    if (BY_LAYER[layerId]) return;
    var g = groupFor(layerId);
    BY_LAYER[layerId] = g.key;
    if (!GROUPS[g.key]) {
      GROUPS[g.key] = { key: g.key, label: g.label, layers: [] };
      ORDER.push(g.key);
    }
    if (LABELS[g.key]) GROUPS[g.key].label = LABELS[g.key];
    GROUPS[g.key].layers.push(layerId);
  }

  /**
   * Give a group a human name.
   *
   * For layers born at runtime: 33-user-layers calls this with the name
   * the user gave the layer, before its popup is bound. Safe to call
   * either side of registration.
   */
  function label(key, text) {
    LABELS[key] = text;
    if (GROUPS[key]) GROUPS[key].label = text;
    render();
  }

  /* ------------------------------------------------------------------
     The gate
     ------------------------------------------------------------------ */

  function isAuto() { return active === AUTO; }

  /** May a handler on this layer run for the click that just happened? */
  function allows(layerId) {
    if (active === AUTO) return true;
    return BY_LAYER[layerId] === active;
  }

  function wrap(map) {
    var GATED = { click: 1, mouseenter: 1 };
    var _on = map.on;
    var _off = map.off;

    map.on = function (type, layerId, fn) {
      if (GATED[type] && typeof layerId === 'string' && typeof fn === 'function') {
        register(layerId);
        var wrapped = function () {
          if (!allows(layerId)) return;
          return fn.apply(this, arguments);
        };
        /* Keyed by type+layer: the same function is sometimes bound to
           several layers (the asset modules do exactly that), so one
           slot per function would lose all but the last and make
           map.off silently miss. */
        fn.__klGate = fn.__klGate || {};
        fn.__klGate[type + '|' + layerId] = wrapped;
        render();
        return _on.call(map, type, layerId, wrapped);
      }
      return _on.apply(map, arguments);
    };

    map.off = function (type, layerId, fn) {
      if (typeof layerId === 'string' && fn && fn.__klGate) {
        var w = fn.__klGate[type + '|' + layerId];
        if (w) return _off.call(map, type, layerId, w);
      }
      return _off.apply(map, arguments);
    };
  }

  /* ------------------------------------------------------------------
     What is selectable right now

     A group is offered only when at least one of its layers is on the
     map AND visible. Listing a layer the user has switched off would
     let them arm a selection that swallows every click and shows
     nothing — the exact confusion this chip exists to remove.
     ------------------------------------------------------------------ */
  function visible(layerId) {
    try {
      if (!map.getLayer(layerId)) return false;
      return map.getLayoutProperty(layerId, 'visibility') !== 'none';
    } catch (e) { return false; }
  }

  function available() {
    return ORDER.map(function (k) { return GROUPS[k]; })
      .filter(function (g) { return g && g.layers.some(visible); });
  }

  function set(key) {
    active = key || AUTO;
    render();
    try {
      var c = map.getCanvas();
      if (c) c.style.cursor = '';
    } catch (e) { /* map not ready */ }
  }

  /* ------------------------------------------------------------------
     Chip + menu
     ------------------------------------------------------------------ */

  var elBtn, elLabel, elMenu, lastSig = '';

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function pad2(n) { return (n < 10 ? '0' : '') + n; }

  function render() {
    if (!elBtn) return;
    var list = available();

    /* Selection pointing at a layer that has since been switched off:
       fall back to Auto rather than leaving clicks going nowhere. */
    if (active !== AUTO && !list.some(function (g) { return g.key === active; })) {
      active = AUTO;
    }

    var sig = active + '|' + list.map(function (g) { return g.key + ':' + g.label; }).join(',');
    if (sig === lastSig) return;
    lastSig = sig;

    elLabel.textContent = (active === AUTO)
      ? 'Active Map Layers : ' + pad2(list.length)
      : (GROUPS[active] ? GROUPS[active].label : 'Active layer');
    elBtn.classList.toggle('is-picked', active !== AUTO);

    var h = '<div class="alb-head">Which layer answers a click</div>' +
      '<button type="button" class="alb-it' + (active === AUTO ? ' on' : '') +
        '" data-key="' + AUTO + '">' +
        '<span class="alb-nm">Auto</span>' +
        '<span class="alb-sub">every visible layer, as before</span>' +
      '</button>';

    if (list.length) {
      h += '<div class="alb-sep">Active layers</div>' +
        list.map(function (g) {
          return '<button type="button" class="alb-it' + (active === g.key ? ' on' : '') +
                 '" data-key="' + esc(g.key) + '">' +
                 '<span class="alb-nm">' + esc(g.label) + '</span></button>';
        }).join('');
    } else {
      h += '<div class="alb-empty">Switch a layer on in the Layers panel to pick it here.</div>';
    }
    elMenu.innerHTML = h;
  }

  var pending = 0;
  /** Coalesce the render storms 'data' would otherwise cause. */
  function schedule() {
    if (pending) return;
    pending = setTimeout(function () { pending = 0; render(); }, 300);
  }

  function open(on) {
    if (!elMenu) return;
    elMenu.classList.toggle('show', on);
    elBtn.setAttribute('aria-expanded', on ? 'true' : 'false');
  }

  function boot() {
    elBtn = document.getElementById('albBtn');
    elLabel = document.getElementById('albLabel');
    elMenu = document.getElementById('albMenu');
    if (!elBtn) return;

    elBtn.addEventListener('click', function (e) {
      e.stopPropagation();
      var willOpen = !elMenu.classList.contains('show');
      if (willOpen) { lastSig = ''; render(); }
      open(willOpen);
    });

    elMenu.addEventListener('click', function (e) {
      var it = e.target.closest && e.target.closest('.alb-it');
      if (!it) return;
      lastSig = '';
      set(it.getAttribute('data-key'));
      open(false);
    });

    document.addEventListener('click', function (e) {
      if (elMenu.classList.contains('show') && !e.target.closest('#activeLayerBox')) open(false);
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape') open(false);
    });

    /* Layers appear and disappear as toggles are ticked and data loads,
       so the chip has to re-read the map rather than be told.

       'data' is what actually correlates with the list changing — a
       source added, a tile in, a layer built. 'idle' alone is not
       enough: a map that is streaming tiles, or one whose tab is not
       compositing, can go a long time without ever being idle, and the
       chip would sit on a stale count. Both are debounced into one
       render, which is itself a no-op unless the list really moved. */
    if (typeof map !== 'undefined') {
      map.on('idle', schedule);
      map.on('data', schedule);
    }
    /* Ticking a layer on in the panel is the other half of it: the
       toggle fires before its layers exist, so this catches the state a
       beat later. */
    var pane = document.getElementById('pane-layers');
    if (pane) pane.addEventListener('change', function () { setTimeout(schedule, 400); });
    lastSig = '';
    render();
  }

  if (typeof map !== 'undefined') wrap(map);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  return {
    isAuto: isAuto,
    allows: allows,
    label: label,
    set: set,
    get: function () { return active; },
    available: available
  };
})();
