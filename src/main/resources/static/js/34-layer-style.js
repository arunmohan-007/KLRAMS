/* ============================================================
   KLRAMS viewer · 34-layer-style.js
   Paints the styles saved in Style & Label Management onto the map.

   WHAT THIS MODULE IS
   -------------------
   An OVERLAY, not a renderer. Every layer is still created by the
   module that owns it today — 07-data-loaders builds the road network,
   06-assets builds the structures, 33-user-layers builds the user
   layers. This file waits for a layer to appear and then, if somebody
   has saved a style for it, repaints it.

   That is the whole design, and it is why adding this module changed
   nothing about how the map looks on the day it shipped:

     no saved style  ->  the layer keeps the paint its own module gave
                         it, untouched, forever
     a saved style   ->  colour, width, symbol, outline and label come
                         from the style document instead

   So the built-in look never had to be transcribed into the database,
   where it would then be a second copy to keep in step with the code
   that actually draws it. "Reset to the built-in style" on the
   management screen deletes the row; the next map open draws exactly
   what it drew before anyone touched it.

   CONDITION AND PCI ARE NOT HERE
   ------------------------------
   Both are coloured from their own screen — by survey parameter and
   Good/Fair/Poor threshold for condition (03-condition-style-filter.js),
   by IRC:82-2023 score band for PCI (14-pci-engine.js). Those are
   analytical scales with a legend printed beside them, not presentation
   choices, so the server refuses to store a style for them and this
   file has no entry for them either.

   HOW A LAYER IS FOUND
   --------------------
   Layers are created lazily, long after this file loads and in an order
   that depends on which toggles the user clicks. Rather than try to
   guess when that has happened, map.addLayer is wrapped once (see
   `hook`) so anything added is offered to applyToLayer() the moment it
   exists — whether that is at boot, on a toggle, or on a rebuild after
   an import.
   ============================================================ */
var KLStyle = (function () {
  'use strict';

  var STYLES = {};        // layerKey -> style document
  var LOADED = false;
  var USER_KEYS = {};     // 'ul-<id>' -> layerKey, registered by 33-user-layers
  var applying = false;   // re-entry guard for the wrapped map methods

  /* ------------------------------------------------------------------
     Which map layers belong to which registry layer

     `role` says what part of the picture a render layer draws, because
     one style document feeds several of them:

       line / fill / circle / symbol — the feature itself
       outline  — a casing layer that already exists (the white halo
                  under the road network, the district border's casing)
       icon     — a symbol layer drawn alongside a line, e.g. the bridge
                  glyph at the centre of a bridge span
       label    — a text layer that already exists (the district and
                  constituency name layers)
       auto     — decide from the layer's actual MapLibre type at
                  runtime. Assets need this: `as-culvert` is a symbol
                  layer when its icon loaded and a circle layer when the
                  data turned out to be a stretch.
     ------------------------------------------------------------------ */
  var TARGETS = {
    'roadnet':          { key: 'roads', role: 'line', casing: 'roadnet-casing' },
    'roadnet-casing':   { key: 'roads', role: 'outline' },

    'roadnet2':         { key: 'full_road_network', role: 'line', casing: 'roadnet2-casing' },
    'roadnet2-casing':  { key: 'full_road_network', role: 'outline' },

    /* The boundaries are polygons whose border is drawn by a line layer of
       its own, and that border IS what anyone means by the polygon's
       outline — so it is painted from `fill.outline`, the section the Fill
       tab actually edits, and NOT from `line`.

       Getting this wrong is not a cosmetic detail. `line` is not shown for
       a polygon layer at all, so a border painted from it would sit there
       at the default 3 px in the fill colour with no control anywhere in
       the screen able to change or remove it. */
    'district-fill':    { key: 'boundary_district', role: 'fill' },
    'district-line':    { key: 'boundary_district', role: 'edge' },
    'district-casing':  { key: 'boundary_district', role: 'edge-casing' },
    'district-label':   { key: 'boundary_district', role: 'label' },

    'cons-fill':        { key: 'boundary_constituency', role: 'fill' },
    'cons-line':        { key: 'boundary_constituency', role: 'edge' },
    'cons-label':       { key: 'boundary_constituency', role: 'label' },

    'trafficstn-lyr':   { key: 'traffic_stations', role: 'auto' }
  };

  /* Assets: one registry key per as-* family, three render layers each.
     Written as a loop rather than 24 literal entries so the family shape
     stays visible — and so adding an asset type is one line. */
  [['bridge', 'as-bridge'], ['culvert', 'as-culvert'],
   ['furniture_line', 'as-furnl'], ['furniture_point', 'as-furnp'],
   ['subgrade', 'as-soil'], ['bituminous_core', 'as-core'],
   ['pavement_crust', 'as-crust'], ['fwd', 'as-fwd']].forEach(function (a) {
    TARGETS[a[1]] = { key: a[0], role: 'auto' };
    TARGETS[a[1] + '-pt'] = { key: a[0], role: 'auto' };
    TARGETS[a[1] + '-icon'] = { key: a[0], role: 'icon' };
  });

  /* Layers this module must never touch. roadnet-hit is the invisible
     click target that keeps Video-on-click working with the network
     switched off — painting it would make it visible. The selection
     highlights are drawn by code in response to a click and are not
     part of any layer's presentation. */
  var SKIP = {
    'roadnet-hit': 1, 'roadnet2-sel': 1,
    'sel-road-glow': 1, 'sel-road-line': 1
  };

  /** Suffixes of the companion layers this module creates itself. */
  var OUTLINE = '__klout', SYM = '__klsym', DOT = '__kldot', LABEL = '__kllabel';

  function isOurs(id) {
    return id.indexOf(OUTLINE) > 0 || id.indexOf(SYM) > 0
        || id.indexOf(DOT) > 0 || id.indexOf(LABEL) > 0;
  }

  /**
   * The style target a render layer belongs to, or null.
   *
   * User layers are resolved by source prefix rather than by a table
   * entry: their ids are `ul-<id>-fill|line|pt` and the registry key is
   * a slug nobody can predict here, so 33-user-layers registers the
   * pairing as it builds each one.
   */
  function targetFor(id) {
    if (SKIP[id] || isOurs(id)) return null;
    if (TARGETS[id]) return TARGETS[id];
    var m = /^(ul-\d+)-(fill|line|pt)$/.exec(id);
    if (m && USER_KEYS[m[1]]) {
      return { key: USER_KEYS[m[1]], role: 'auto' };
    }
    return null;
  }

  /* ------------------------------------------------------------------
     Colour expressions
     ------------------------------------------------------------------ */

  /**
   * The styled attribute's value, whichever kind of source carries it.
   *
   * Two shapes have to answer the same question. A GeoJSON source and a
   * column-backed tile have the attribute under its own name. The
   * layers whose attributes live in a jsonb bag — assets and user
   * layers — cannot: an MVT property is a flat scalar, so their tiles
   * ship the bag as one JSON string, and a MapLibre expression cannot
   * look inside a string. For those, the server lifts the one styled
   * attribute out under the fixed name `__style` (see
   * LayerStyleService.tileKeys).
   *
   * Reading `__style` first and the real key second means one
   * expression works on both without this file having to know which
   * kind of source it is looking at.
   */
  function raw(key) {
    return ['coalesce', ['get', '__style'], ['get', key], ''];
  }

  function rawLabel(key) {
    return ['coalesce', ['get', '__label'], ['get', key], ''];
  }

  /**
   * The layer's declared geometry, as the registry records it.
   *
   * Attached to each style by the server (LayerStyleService.allStyles) and
   * normalised here: MULTILINESTRING styles exactly like LINESTRING, and an
   * older document saved before this field existed answers null, which every
   * caller treats as "no restriction" so it keeps behaving as it did.
   */
  function geomOf(s) {
    var g = s && s.geometry;
    if (!g) return null;
    return (g === 'MULTILINESTRING') ? 'LINESTRING' : g;
  }

  /**
   * Does this style's geometry match `want`?
   *
   * An unknown geometry answers YES to everything, deliberately. A style
   * saved before the geometry field existed carries none, and the safe
   * reading of "I do not know what this is" is to paint it exactly as this
   * module did before the field was introduced rather than to paint
   * nothing at all.
   */
  function isGeom(geom, want) {
    return geom == null || geom === want;
  }

  /**
   * Is this the render layer that carries the family's real geometry?
   *
   * The one that should hold the label. An unknown geometry falls back to
   * "anything that is not a circle", which is what this module did before
   * the geometry field existed — a line or fill layer is the sensible
   * default carrier and a stray point layer is the one worth excluding.
   */
  function isPrimaryFor(type, geom) {
    if (geom === 'POINT') return type === 'circle' || type === 'symbol';
    if (geom === 'POLYGON') return type === 'fill';
    if (geom === 'LINESTRING') return type === 'line';
    return type !== 'circle';
  }

  /** True when the styled attribute is missing or blank on a feature. */
  function blank(key) {
    return ['==', ['to-string', raw(key)], ''];
  }

  /**
   * Turn a style's colour section into a MapLibre colour expression.
   *
   * Returns a plain colour string for SINGLE, so the common case costs
   * nothing at paint time.
   */
  function colorExpr(s) {
    var c = s.color || {};
    var key = c.attribute;
    if (c.mode === 'SINGLE' || !key) return c.value || '#3887be';
    var fallback = c.fallback || '#9aa0a6';

    if (c.mode === 'CATEGORY') {
      var pairs = [];
      var seen = {};
      (c.categories || []).forEach(function (cat) {
        var v = String(cat.value == null ? '' : cat.value);
        // A `match` cannot list the same label twice, and an empty value
        // is what `blank` already answers for — either would make the
        // whole expression invalid and silently drop the layer's colour.
        if (!v || seen[v]) return;
        seen[v] = 1;
        pairs.push(v, cat.color);
      });
      if (!pairs.length) return fallback;
      return ['match', ['to-string', raw(key)]].concat(pairs).concat([fallback]);
    }

    if (c.mode === 'RANGE') {
      var bands = (c.ranges || []).filter(function (r) { return r && r.color; })
        .slice().sort(function (a, b) { return num(a.from) - num(b.from); });
      if (!bands.length) return fallback;
      var step = ['step', ['to-number', raw(key), 0], bands[0].color];
      var last = -Infinity;
      for (var i = 1; i < bands.length; i++) {
        var at = num(bands[i].from);
        // `step` needs strictly ascending breaks; two bands starting at
        // the same number is a half-finished edit, not a reason to drop
        // the style, so the duplicate is skipped.
        if (!(at > last)) continue;
        last = at;
        step.push(at, bands[i].color);
      }
      return ['case', blank(key), fallback, step];
    }

    /* GRADIENT — stops are 0-1 fractions of the declared min..max, so
       one ramp works on an IRI in m/km and a deflection in microns. */
    var g = c.gradient || {};
    var lo = num(g.min), hi = num(g.max);
    if (!(hi > lo)) return fallback;
    var stops = (g.stops || []).slice().sort(function (a, b) { return num(a.at) - num(b.at); });
    if (stops.length < 2) return fallback;
    var interp = ['interpolate', ['linear'], ['to-number', raw(key), lo]];
    var prev = -Infinity;
    stops.forEach(function (st) {
      var at = lo + clamp(num(st.at), 0, 1) * (hi - lo);
      if (!(at > prev)) at = prev + 1e-6;   // keep the stops strictly ascending
      prev = at;
      interp.push(at, st.color);
    });
    return ['case', blank(key), fallback, interp];
  }

  /**
   * Every colour a style can produce, for the icon images that have to
   * be generated up front.
   *
   * A symbol layer switches image by expression, and an image has to
   * exist before it can be named — so a category style with six colours
   * needs six images loaded before its `icon-image` is set.
   */
  function paletteOf(s) {
    var c = s.color || {};
    var out = [c.value || '#3887be'];
    if (c.mode === 'CATEGORY') (c.categories || []).forEach(function (x) { out.push(x.color); });
    if (c.mode === 'RANGE') (c.ranges || []).forEach(function (x) { out.push(x.color); });
    if (c.mode === 'GRADIENT') ((c.gradient || {}).stops || []).forEach(function (x) { out.push(x.color); });
    out.push(c.fallback || '#9aa0a6');
    return out.filter(function (x, i, a) { return x && a.indexOf(x) === i; });
  }

  /* ------------------------------------------------------------------
     Widths and sizes
     ------------------------------------------------------------------ */

  /**
   * A width that grows with zoom, unless the style asks for a fixed one.
   *
   * Zoom scaling is the default because a pixel width chosen so the
   * whole state reads well is a hairline at street level — the built-in
   * layers all interpolate for exactly that reason. Turning it off is
   * for an overlay that genuinely wants "this many pixels, always".
   */
  function widthExpr(w, scale) {
    if (!scale) return w;
    return ['interpolate', ['linear'], ['zoom'],
      8, round2(w * 0.65), 12, round2(w), 16, round2(w * 1.7)];
  }

  function radiusExpr(r, scale) {
    if (!scale) return r;
    return ['interpolate', ['linear'], ['zoom'],
      8, round2(r * 0.6), 12, round2(r), 16, round2(r * 1.5)];
  }

  /* Dash patterns, in line-width multiples — which is what
     line-dasharray means, so a dashed line keeps its rhythm as the
     width changes rather than turning into a dotted one. */
  var DASHES = {
    SOLID: null,
    DASH: [2, 2],
    DOT: [0.4, 2],
    DASH_DOT: [4, 1.5, 0.5, 1.5],
    LONG_DASH: [6, 2.5],
    RAIL: [1, 1.2]
  };

  /* ------------------------------------------------------------------
     Symbols

     Icons are generated here rather than shipped as files so a symbol
     can be ANY colour the style asks for: MapLibre can only recolour an
     image at draw time if it is an SDF, and an SDF gives up the two-tone
     fill-plus-outline that makes a small glyph readable over a busy
     basemap. Drawing the SVG with the colour already in it keeps both.
     ------------------------------------------------------------------ */

  var IMAGES = {};   // image name -> Promise, so one shape/colour loads once

  function imageName(shape, fill, stroke, width) {
    return 'kls_' + shape + '_' + String(fill).replace('#', '')
         + '_' + String(stroke).replace('#', '') + '_' + Math.round(width * 10);
  }

  /**
   * Register one generated symbol with the map, once.
   *
   * Resolves even when the image fails to decode: a symbol that will not
   * load must leave the rest of the style working rather than leaving
   * the whole layer waiting on a promise that never settles.
   */
  function ensureImage(shape, fill, stroke, width) {
    var name = imageName(shape, fill, stroke, width);
    if (IMAGES[name]) return IMAGES[name];
    var svg = KLSymbols.svg(shape, fill, stroke, width, 32);
    IMAGES[name] = new Promise(function (resolve) {
      try {
        if (map.hasImage(name)) return resolve(name);
      } catch (e) { /* map not ready */ }
      var img = new Image(64, 64);
      img.onload = function () {
        try { if (!map.hasImage(name)) map.addImage(name, img, { pixelRatio: 2 }); } catch (e) { }
        resolve(name);
      };
      img.onerror = function () { resolve(name); };
      img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
    });
    return IMAGES[name];
  }

  /** Load every image a style could ask for, then answer with the icon-image expression. */
  function iconExpr(s) {
    var p = s.point || {};
    var shape = p.icon || 'circle';
    var sw = (p.stroke && p.stroke.width) || 0;
    var sc = (p.stroke && p.stroke.color) || '#ffffff';
    var colors = paletteOf(s);
    return Promise.all(colors.map(function (col) {
      return ensureImage(shape, col, sc, sw);
    })).then(function () {
      var c = s.color || {};
      var img = function (col) { return imageName(shape, col, sc, sw); };
      if (c.mode === 'SINGLE' || !c.attribute) return img(c.value || '#3887be');

      if (c.mode === 'CATEGORY') {
        var pairs = [], seen = {};
        (c.categories || []).forEach(function (cat) {
          var v = String(cat.value == null ? '' : cat.value);
          if (!v || seen[v]) return;
          seen[v] = 1;
          pairs.push(v, img(cat.color));
        });
        if (!pairs.length) return img(c.fallback);
        return ['match', ['to-string', raw(c.attribute)]].concat(pairs).concat([img(c.fallback)]);
      }

      if (c.mode === 'RANGE') {
        var bands = (c.ranges || []).filter(function (r) { return r && r.color; })
          .slice().sort(function (a, b) { return num(a.from) - num(b.from); });
        if (!bands.length) return img(c.fallback);
        var step = ['step', ['to-number', raw(c.attribute), 0], img(bands[0].color)];
        var last = -Infinity;
        for (var i = 1; i < bands.length; i++) {
          var at = num(bands[i].from);
          if (!(at > last)) continue;
          last = at;
          step.push(at, img(bands[i].color));
        }
        return ['case', blank(c.attribute), img(c.fallback), step];
      }

      /* A gradient cannot interpolate between two images, so the ramp is
         served as its own stops — the same colours, in bands rather than
         a continuous blend. Any other reading of "gradient on a symbol"
         would be inventing colours the legend does not show. */
      var g = c.gradient || {};
      var lo = num(g.min), hi = num(g.max);
      var stops = (g.stops || []).slice().sort(function (a, b) { return num(a.at) - num(b.at); });
      if (!(hi > lo) || stops.length < 2) return img(c.fallback);
      var qs = ['step', ['to-number', raw(c.attribute), lo], img(stops[0].color)];
      var prevAt = -Infinity;
      for (var j = 1; j < stops.length; j++) {
        var v = lo + clamp(num(stops[j].at), 0, 1) * (hi - lo);
        if (!(v > prevAt)) continue;
        prevAt = v;
        qs.push(v, img(stops[j].color));
      }
      return ['case', blank(c.attribute), img(c.fallback), qs];
    });
  }

  /* ------------------------------------------------------------------
     Labels
     ------------------------------------------------------------------ */

  /* The only font stacks the viewer's glyph server actually serves. A
     text-font naming a stack it does not have renders nothing at all,
     so this list is checked rather than assumed. */
  var FONTS = {
    REGULAR:  ['Noto Sans Regular'],
    BOLD:     ['Noto Sans Bold'],
    SEMIBOLD: ['Open Sans Semibold']
  };

  /**
   * The text of one label.
   *
   * Empty in, empty out: a feature missing the labelled attribute gets
   * no text rather than a stray prefix and suffix with nothing between
   * them — and an empty text-field also releases the collision box, so
   * unlabelled features stop crowding out labelled ones.
   */
  function labelField(lb) {
    var key = lb.attribute;
    var value = rawLabel(key);
    var body;
    if (lb.decimals == null) {
      body = ['to-string', value];
    } else {
      // MapLibre has no toFixed, so rounding is done in the expression:
      // multiply, round, divide. Whole numbers therefore print without
      // trailing zeros, which is the right answer for a map label.
      var p = Math.pow(10, lb.decimals);
      body = ['to-string', ['/', ['round', ['*', ['to-number', value, 0], p]], p]];
    }
    var text = ['concat', lb.prefix || '', body, lb.suffix || ''];
    return ['case', ['==', ['to-string', value], ''], '', text];
  }

  function labelPlacement(lb, baseType) {
    if (lb.placement === 'LINE') return 'line';
    if (lb.placement === 'LINE_CENTER') return 'line-center';
    if (lb.placement === 'POINT') return 'point';
    // AUTO: follow the geometry. A line label that runs along the line
    // is the readable default; a point label sits at the point.
    return baseType === 'line' ? 'line' : 'point';
  }

  function labelLayout(lb, baseType) {
    return {
      'text-field': labelField(lb),
      'text-font': FONTS[lb.font] || FONTS.REGULAR,
      'text-size': lb.size,
      'text-transform': lb.transform || 'none',
      'text-letter-spacing': lb.letterSpacing || 0,
      'text-max-width': lb.maxWidth || 10,
      'text-offset': [lb.offsetX || 0, lb.offsetY || 0],
      'text-anchor': lb.anchor || 'center',
      'text-rotate': lb.rotate || 0,
      'text-allow-overlap': !!lb.allowOverlap,
      'text-ignore-placement': false,
      'symbol-placement': labelPlacement(lb, baseType)
    };
  }

  function labelPaint(lb) {
    return {
      'text-color': lb.color,
      'text-opacity': lb.opacity == null ? 1 : lb.opacity,
      'text-halo-color': (lb.halo && lb.halo.color) || '#0b1322',
      'text-halo-width': (lb.halo && lb.halo.width) || 0,
      'text-halo-blur': (lb.halo && lb.halo.blur) || 0
    };
  }

  /* ------------------------------------------------------------------
     Applying a style to one render layer
     ------------------------------------------------------------------ */

  function applyToLayer(id) {
    var t = targetFor(id);
    if (!t) return;
    var s = STYLES[t.key];
    if (!s) return;
    var lyr;
    try { lyr = map.getLayer(id); } catch (e) { return; }
    if (!lyr) return;

    applying = true;
    try {
      var type = lyr.type;
      if (t.role === 'outline') { paintCasing(id, s, type); return; }
      if (t.role === 'edge') { paintEdge(id, s, type); return; }
      if (t.role === 'edge-casing') { paintEdgeCasing(id, s, type); return; }
      if (t.role === 'label') { paintExistingLabel(id, s, type); return; }

      /* A decorative glyph on a layer that is not a point layer is left
         exactly as its own module drew it.

         Bridges and line furniture carry a distinctive symbol at the
         centre of the span; FWD carries its D0 reading as TEXT with no
         icon at all. All three are line layers, so the editor shows them
         no Symbol tab — painting them from `point` would swap a bridge
         for a plain dot, and stamp a dot next to every deflection
         reading, with nothing on screen able to put either back. */
      if (t.role === 'icon' && !isGeom(geomOf(s), 'POINT')) return;

      /* Which section a layer reads follows the layer's DECLARED geometry,
         not the MapLibre type of whichever render layer we happen to be
         looking at. A user layer is built with a fill, a line and a circle
         layer whatever its geometry is — 33-user-layers does that on
         purpose, because a point layer whose CSV half-failed still has
         points and a shapefile can mix parts — so the type alone would
         send a polygon's stray line off to read `line`, a section the
         editor does not show for a polygon. That is the same fault as the
         boundary border: paint driven by a control that is not on screen. */
      var geom = geomOf(s);
      if (type === 'line') {
        if (geom === 'POLYGON') paintEdge(id, s, type);   // a polygon's line IS its border
        else paintLine(id, s, t);
      } else if (type === 'circle') {
        if (isGeom(geom, 'POINT')) paintCircle(id, s, t);
      } else if (type === 'fill') {
        paintFill(id, s, t);
      } else if (type === 'symbol') {
        if (isGeom(geom, 'POINT')) paintSymbol(id, s, t);
      }

      zoomWindow(id, s);
      /* The label rides on its own layer so it can sit above every fill
         and line on the map, whatever depth the feature is drawn at.

         Attached to the ONE render layer that carries the layer's real
         geometry, never to all of them. A family routinely has three —
         a user layer always builds a fill, a line and a circle — and
         labelling each would stack three symbol layers on one source,
         every one of them competing for the same collision slots. */
      if (t.role !== 'icon' && isPrimaryFor(type, geom)) ensureLabel(id, s, type);
    } catch (e) {
      // A style that will not apply must never take the layer with it —
      // the map is more useful in the wrong colour than not at all.
      if (window.console) console.warn('KLStyle: could not style ' + id, e);
    } finally {
      applying = false;
    }
  }

  function paintLine(id, s, t) {
    var l = s.line || {};
    set(id, 'line-color', colorExpr(s));
    set(id, 'line-width', widthExpr(l.width, l.zoomScale));
    set(id, 'line-opacity', l.opacity == null ? 1 : l.opacity);
    set(id, 'line-blur', l.blur || 0);
    setLayout(id, 'line-cap', l.cap || 'round');
    setLayout(id, 'line-join', l.join || 'round');
    set(id, 'line-dasharray', DASHES[l.dash] || undefined);

    /* The outline. A layer that already has a casing of its own (the
       road network's white halo, the district border) uses it — that
       one is registered with role 'outline' and is painted separately.
       Everything else gets a companion line drawn underneath. */
    if (t.casing && map.getLayer(t.casing)) return;
    var out = l.outline || {};
    if (out.on && out.width > 0) ensureOutline(id, s);
    else removeLayer(id + OUTLINE);
  }

  /** Paint a casing layer that the owning module already created. */
  function paintCasing(id, s, type) {
    var l = s.line || {};
    var out = l.outline || {};
    if (type !== 'line') return;
    set(id, 'line-color', out.color || '#0b1322');
    set(id, 'line-width', widthExpr(l.width + 2 * (out.width || 0), l.zoomScale));
    // Width zero would still draw a hairline, so an outline that is off
    // is turned off by opacity — and turning it back on needs no rebuild.
    set(id, 'line-opacity', out.on ? 1 : 0);
    setLayout(id, 'line-cap', l.cap || 'round');
    setLayout(id, 'line-join', l.join || 'round');
  }

  /**
   * The border of a polygon that is drawn by its own line layer.
   *
   * Driven entirely by `fill.outline`, so the Fill tab's outline switch is
   * the whole truth about whether a boundary has a border.
   *
   * Turned off by opacity AND width together, deliberately. Width alone is
   * not enough: MapLibre still rasterises a line of width 0 as a hairline
   * on some devices, which is exactly the "I set the width to nothing and
   * the outline is still there" case this has to answer for. Opacity alone
   * would leave the line reserving its space. Setting both is the only
   * combination that reliably means gone.
   */
  function paintEdge(id, s, type) {
    if (type !== 'line') return;
    var out = (s.fill || {}).outline || {};
    var on = !!out.on && num(out.width) > 0;
    set(id, 'line-color', out.color || '#ffffff');
    set(id, 'line-width', on ? out.width : 0);
    set(id, 'line-opacity', on ? 1 : 0);
    set(id, 'line-dasharray', DASHES[out.dash] || undefined);
  }

  /**
   * The pale halo some boundaries draw beneath their border.
   *
   * Follows the border's own on/off — a halo around a border that is not
   * there reads as a mysterious second outline, which is the other half of
   * the same complaint.
   */
  function paintEdgeCasing(id, s, type) {
    if (type !== 'line') return;
    var out = (s.fill || {}).outline || {};
    var on = !!out.on && num(out.width) > 0;
    set(id, 'line-width', on ? num(out.width) + 2 : 0);
    set(id, 'line-opacity', on ? 0.7 : 0);
  }

  /** A companion casing for a line layer that has none of its own. */
  function ensureOutline(baseId, s) {
    var id = baseId + OUTLINE;
    var l = s.line || {}, out = l.outline || {};
    if (!map.getLayer(id)) {
      var base = map.getLayer(baseId);
      var spec = {
        id: id, type: 'line', source: base.source,
        layout: {
          'line-cap': l.cap || 'round', 'line-join': l.join || 'round',
          visibility: visibilityOf(baseId)
        },
        paint: {}
      };
      if (base.sourceLayer) spec['source-layer'] = base.sourceLayer;
      var f = filterOf(baseId);
      if (f) spec.filter = f;
      // Added BEFORE the base layer, which is what puts it underneath —
      // an outline drawn on top would hide the line it is outlining.
      map.addLayer(spec, baseId);
    }
    set(id, 'line-color', out.color || '#0b1322');
    set(id, 'line-width', widthExpr(l.width + 2 * (out.width || 0), l.zoomScale));
    set(id, 'line-opacity', l.opacity == null ? 1 : l.opacity);
    set(id, 'line-dasharray', DASHES[l.dash] || undefined);
  }

  function paintCircle(id, s, t) {
    var p = s.point || {};
    if (p.mode === 'ICON') {
      // Hidden by opacity rather than visibility: the owning module's
      // toggle drives visibility, and taking it over here would leave
      // the layer stuck off the next time the user ticks the box.
      set(id, 'circle-opacity', 0);
      set(id, 'circle-stroke-opacity', 0);
      ensureSymbol(id, s);
      return;
    }
    removeLayer(id + SYM);
    set(id, 'circle-color', colorExpr(s));
    set(id, 'circle-radius', radiusExpr(p.radius, p.zoomScale));
    set(id, 'circle-opacity', p.opacity == null ? 1 : p.opacity);
    set(id, 'circle-blur', p.blur || 0);
    set(id, 'circle-stroke-color', (p.stroke && p.stroke.color) || '#ffffff');
    set(id, 'circle-stroke-width', (p.stroke && p.stroke.width) || 0);
    set(id, 'circle-stroke-opacity', p.opacity == null ? 1 : p.opacity);
  }

  function paintFill(id, s) {
    var f = s.fill || {};
    set(id, 'fill-color', colorExpr(s));
    set(id, 'fill-opacity', f.opacity == null ? 0.3 : f.opacity);
    var out = f.outline || {};
    var on = !!out.on && num(out.width) > 0;
    /* fill-outline-color is always exactly one pixel, so anything heavier
       than a hairline has to be a real line layer. Cleared to the fill
       colour rather than to undefined when the outline is off: MapLibre
       falls BACK to fill-color when this is unset, which still draws a
       1 px edge — visibly an outline to anyone who just switched one off. */
    set(id, 'fill-outline-color', on ? out.color : colorExpr(s));
    if (on && out.width > 1.2) ensureFillEdge(id, s);
    else removeLayer(id + OUTLINE);
  }

  function ensureFillEdge(baseId, s) {
    var id = baseId + OUTLINE;
    var out = (s.fill || {}).outline || {};
    if (!map.getLayer(id)) {
      var base = map.getLayer(baseId);
      var spec = {
        id: id, type: 'line', source: base.source,
        layout: { 'line-cap': 'round', 'line-join': 'round', visibility: visibilityOf(baseId) },
        paint: {}
      };
      if (base.sourceLayer) spec['source-layer'] = base.sourceLayer;
      var f = filterOf(baseId);
      if (f) spec.filter = f;
      map.addLayer(spec);
    }
    set(id, 'line-color', out.color);
    set(id, 'line-width', out.width);
  }

  function paintSymbol(id, s, t) {
    var p = s.point || {};
    if (p.mode === 'CIRCLE' && t.role !== 'icon') {
      set(id, 'icon-opacity', 0);
      ensureDot(id, s);
      return;
    }
    removeLayer(id + DOT);
    setLayout(id, 'icon-size', iconSizeExpr(p));
    setLayout(id, 'icon-rotate', p.iconRotate || 0);
    setLayout(id, 'icon-allow-overlap', !!p.allowOverlap);
    set(id, 'icon-opacity', p.opacity == null ? 1 : p.opacity);
    iconExpr(s).then(function (expr) {
      try { setLayout(id, 'icon-image', expr); } catch (e) { /* layer went away */ }
    });
  }

  function iconSizeExpr(p) {
    var sz = p.iconSize == null ? 1 : p.iconSize;
    if (!p.zoomScale) return sz;
    return ['interpolate', ['linear'], ['zoom'],
      8, round2(sz * 0.55), 12, round2(sz * 0.85), 16, round2(sz)];
  }

  /** A generated symbol layer for a point family drawn as circles. */
  function ensureSymbol(baseId, s) {
    var id = baseId + SYM;
    if (!map.getLayer(id)) {
      var base = map.getLayer(baseId);
      var spec = {
        id: id, type: 'symbol', source: base.source,
        layout: { visibility: visibilityOf(baseId) }, paint: {}
      };
      if (base.sourceLayer) spec['source-layer'] = base.sourceLayer;
      var f = filterOf(baseId);
      if (f) spec.filter = f;
      map.addLayer(spec);
    }
    paintSymbol(id, s, { role: 'icon' });
  }

  /** A generated circle layer for a point family drawn as symbols. */
  function ensureDot(baseId, s) {
    var id = baseId + DOT;
    if (!map.getLayer(id)) {
      var base = map.getLayer(baseId);
      var spec = {
        id: id, type: 'circle', source: base.source,
        layout: { visibility: visibilityOf(baseId) }, paint: {}
      };
      if (base.sourceLayer) spec['source-layer'] = base.sourceLayer;
      var f = filterOf(baseId);
      if (f) spec.filter = f;
      map.addLayer(spec, baseId);
    }
    paintCircle(id, s, { role: 'dot' });
  }

  /** Re-style a name layer the owning module already built. */
  function paintExistingLabel(id, s, type) {
    if (type !== 'symbol') return;
    var lb = s.label || {};
    if (!lb.on) {
      // Emptied rather than hidden, for the same reason a circle is
      // faded rather than hidden: visibility belongs to the toggle.
      setLayout(id, 'text-field', '');
      return;
    }
    var layout = labelLayout(lb, 'line');
    Object.keys(layout).forEach(function (k) { setLayout(id, k, layout[k]); });
    var paint = labelPaint(lb);
    Object.keys(paint).forEach(function (k) { set(id, k, paint[k]); });
    zoomWindow(id, { minZoom: lb.minZoom, maxZoom: lb.maxZoom });
  }

  /** Build, update or remove the companion label layer of a feature layer. */
  function ensureLabel(baseId, s, baseType) {
    var id = baseId + LABEL;
    var lb = s.label || {};
    if (!lb.on || !lb.attribute) { removeLayer(id); return; }

    if (!map.getLayer(id)) {
      var base = map.getLayer(baseId);
      var spec = {
        id: id, type: 'symbol', source: base.source,
        layout: { visibility: visibilityOf(baseId) }, paint: {}
      };
      if (base.sourceLayer) spec['source-layer'] = base.sourceLayer;
      var f = filterOf(baseId);
      if (f) spec.filter = f;
      // Slotted at label depth, so text reads on top of the map rather
      // than disappearing under whichever layer loads next.
      map.addLayer(spec, labelBefore());
    }
    var layout = labelLayout(lb, baseType);
    Object.keys(layout).forEach(function (k) { setLayout(id, k, layout[k]); });
    var paint = labelPaint(lb);
    Object.keys(paint).forEach(function (k) { set(id, k, paint[k]); });
    zoomWindow(id, { minZoom: lb.minZoom, maxZoom: lb.maxZoom });
  }

  function labelBefore() {
    try {
      if (window.KLLayers && KLLayers.beforeId) return KLLayers.beforeId(KLLayers.Z.BOUNDARY_LABEL - 1);
    } catch (e) { /* registry not loaded */ }
    return undefined;
  }

  function zoomWindow(id, s) {
    var lo = s.minZoom == null ? 0 : s.minZoom;
    var hi = s.maxZoom == null ? 24 : s.maxZoom;
    if (lo <= 0 && hi >= 24) return;
    try { map.setLayerZoomRange(id, lo, Math.max(lo + 0.1, hi)); } catch (e) { /* ignore */ }
  }

  /* ------------------------------------------------------------------
     Companions follow their base layer

     A generated outline, symbol, dot or label is not in any module's
     idea of the map, so nothing else will show it, hide it or clean it
     up. These three wrappers are what keep them in step.
     ------------------------------------------------------------------ */

  function companionsOf(baseId) {
    return [baseId + OUTLINE, baseId + SYM, baseId + DOT, baseId + LABEL];
  }

  function visibilityOf(id) {
    try {
      return map.getLayoutProperty(id, 'visibility') === 'none' ? 'none' : 'visible';
    } catch (e) {
      return 'visible';
    }
  }

  function filterOf(id) {
    try { return map.getFilter(id); } catch (e) { return null; }
  }

  function removeLayer(id) {
    try { if (map.getLayer(id)) map.removeLayer(id); } catch (e) { /* already gone */ }
  }

  function set(id, prop, value) {
    try { map.setPaintProperty(id, prop, value); } catch (e) { /* not on this layer type */ }
  }

  function setLayout(id, prop, value) {
    try { map.setLayoutProperty(id, prop, value); } catch (e) { /* not on this layer type */ }
  }

  /* ------------------------------------------------------------------
     The hooks
     ------------------------------------------------------------------ */

  /**
   * Wrap the four map methods that decide what this module has to react
   * to.
   *
   * addLayer          — a layer appeared; style it if it is one of ours
   * setLayoutProperty — visibility changed; carry it to the companions
   * setFilter         — a filter changed; carry it to the companions
   * removeLayer       — a layer went; take its companions with it
   *
   * Wrapping is what makes this module work without editing the ten
   * files that create layers. They keep calling map.addLayer exactly as
   * they always have; the style arrives on the way past.
   *
   * setPaintProperty is deliberately NOT wrapped, and that is a
   * decision rather than an omission. The viewer's own "colour by
   * attribute" selector on the road network, and the light/dark theme
   * switch, both repaint a layer after it is built — and they are
   * meant to. A saved style is the layer's STANDING look, applied when
   * it is created and on every reload; those controls are a temporary
   * view someone has asked for on the spot, and the one they asked for
   * last should win until they change it or reload the map. Blocking
   * them would make a styled layer stop responding to a control that
   * is still on screen, which reads as a broken selector rather than
   * as a style being protected.
   */
  function hook() {
    if (typeof map === 'undefined' || map.__klStyleHooked) return;
    map.__klStyleHooked = true;

    var addLayer = map.addLayer.bind(map);
    map.addLayer = function (spec, before) {
      var r = addLayer(spec, before);
      if (!applying && spec && spec.id && LOADED) {
        // Deferred by a tick: the caller usually goes on to set the
        // layer's filter and visibility immediately after adding it,
        // and a companion built before that would copy neither.
        var id = spec.id;
        setTimeout(function () { applyToLayer(id); }, 0);
      }
      return r;
    };

    var setLayout_ = map.setLayoutProperty.bind(map);
    map.setLayoutProperty = function (id, prop, value, opts) {
      var r = setLayout_(id, prop, value, opts);
      if (!applying && prop === 'visibility') {
        applying = true;
        try {
          companionsOf(id).forEach(function (c) {
            if (map.getLayer(c)) setLayout_(c, 'visibility', value, opts);
          });
        } catch (e) { /* ignore */ } finally { applying = false; }
      }
      return r;
    };

    var setFilter_ = map.setFilter.bind(map);
    map.setFilter = function (id, filter, opts) {
      var r = setFilter_(id, filter, opts);
      if (!applying) {
        applying = true;
        try {
          companionsOf(id).forEach(function (c) {
            if (map.getLayer(c)) setFilter_(c, filter, opts);
          });
        } catch (e) { /* ignore */ } finally { applying = false; }
      }
      return r;
    };

    var removeLayer_ = map.removeLayer.bind(map);
    map.removeLayer = function (id) {
      if (!applying) {
        applying = true;
        try {
          companionsOf(id).forEach(function (c) {
            try { if (map.getLayer(c)) removeLayer_(c); } catch (e) { /* ignore */ }
          });
        } finally { applying = false; }
      }
      return removeLayer_(id);
    };
  }

  /* ------------------------------------------------------------------
     Loading
     ------------------------------------------------------------------ */

  function applyAll() {
    var layers;
    try { layers = (map.getStyle() || {}).layers || []; } catch (e) { return; }
    // A copy of the id list, because applying a style adds layers and
    // iterating the live array while it grows would revisit them.
    layers.map(function (l) { return l.id; }).forEach(applyToLayer);
  }

  function load() {
    return fetch('/api/layer-styles', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (d) {
        STYLES = (d && d.styles) || {};
        LOADED = true;
        applyAll();
      })
      .catch(function () {
        // The viewer is entirely usable on built-in paint, so a failure
        // here is not worth telling anyone about.
        LOADED = true;
      });
  }

  /**
   * Pair a user layer's map source with the registry key its style is
   * saved under.
   *
   * Called by 33-user-layers.js as it builds each one: user layer ids
   * are allocated at creation time and their registry keys are slugs of
   * whatever the layer was named, so neither is knowable here.
   */
  function registerUserLayer(sourceId, layerKey) {
    if (!sourceId || !layerKey) return;
    USER_KEYS[sourceId] = layerKey;
    if (LOADED) {
      ['-fill', '-line', '-pt'].forEach(function (sfx) { applyToLayer(sourceId + sfx); });
    }
  }

  /** Re-read every style and repaint. For the styling screen's preview. */
  function reload() {
    return load();
  }

  function styleFor(key) { return STYLES[key] || null; }

  /* Helpers small enough that a shared util would cost more than it saves. */
  function num(v) { var n = Number(v); return isNaN(n) ? 0 : n; }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }
  function round2(v) { return Math.round(v * 100) / 100; }

  function boot() {
    hook();
    load();
  }

  /**
   * Start as soon as the map OBJECT exists — deliberately not on its
   * 'load' event.
   *
   * Nothing here needs the basemap: hook() only rebinds map methods,
   * load() is a fetch, and applyAll() reads the style's layer list,
   * all of which are available the moment the Map is constructed.
   * Waiting for 'load' would mean a slow or unreachable basemap delays
   * every layer's colour — and if the tiles never arrive at all, 'load'
   * never fires and no saved style is ever applied. Hooking now also
   * guarantees the wrapper is in place before the first module gets
   * round to adding a layer.
   */
  if (typeof map !== 'undefined') boot();
  else document.addEventListener('DOMContentLoaded', boot);

  return {
    reload: reload,
    styleFor: styleFor,
    registerUserLayer: registerUserLayer,
    apply: applyToLayer,
    applyAll: applyAll
  };
})();
