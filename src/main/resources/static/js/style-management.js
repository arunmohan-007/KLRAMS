/*
 * style-management.js — the Style & Label Management screen (/style.html).
 *
 * Renders /api/layer-styles/manage (every stylable layer, its attributes and
 * its current style) as a rail-and-stage studio: pick a layer on the left,
 * shape colour / line / point / fill / label on the right, watch the change in
 * a live SVG preview, then Save. Nothing reaches the map until Save is
 * pressed — the viewer's own module (34-layer-style.js) reads the same style
 * document this screen writes, so what you see in the preview is the same
 * expression logic the map paints with, not a second approximation of it.
 *
 * Condition and PCI are not offered here at all: the server refuses a style
 * for either (LayerStyleService.EXCLUDED) because both are already coloured
 * from their own screen in the viewer, by parameter and threshold rather than
 * by a saved style.
 */
(function () {
  'use strict';

  /* ------------------------------------------------------------------
     State
     ------------------------------------------------------------------ */

  var LAYERS = [];         // every stylable layer, as returned
  var TEMPLATES = [];
  var SEL = null;          // the selected layer object
  var DRAFT = null;        // the style being edited (always a full, clean document)
  var DIRTY = false;
  var TAB = 'color';
  var PREVIEW_MODE = 'dark';
  var VALUES_CACHE = {};   // "layerKey::attribute" -> [values]
  var TPL_PICK = null;     // template chosen in the gallery, awaiting Apply

  var PALETTE = ['#2bb8a3', '#3887be', '#e55e5e', '#f7c948', '#8a5cb8',
                  '#1a9850', '#e07b2a', '#4264fb', '#c4682a', '#0fa3a3'];

  /* ------------------------------------------------------------------
     Small helpers
     ------------------------------------------------------------------ */

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
  function $(sel, root) { return (root || document).querySelector(sel); }
  function $all(sel, root) { return Array.prototype.slice.call((root || document).querySelectorAll(sel)); }
  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function num(v, d) { var n = Number(v); return isNaN(n) ? d : n; }
  function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

  function msg(text, ok, el) {
    var box = el || document.getElementById('msg');
    box.className = 'msg ' + (ok ? 'ok' : 'err');
    box.textContent = text;
    if (ok) setTimeout(function () { box.className = 'msg'; }, 5000);
  }

  function api(url, body, method) {
    return fetch(url, {
      method: method || (body ? 'PUT' : 'GET'),
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      credentials: 'same-origin',
      body: body ? JSON.stringify(body) : undefined
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (d) {
        if (!r.ok) throw new Error((d && d.error) || 'Request failed');
        return d;
      });
    });
  }

  /* ------------------------------------------------------------------
     Default style — the exact shape LayerStyleService.clean(null) fills
     in server-side. Kept in sync deliberately: a fresh layer edited here
     must look identical to one the server has already normalised, or
     the first Save would visibly change something nobody touched.
     ------------------------------------------------------------------ */
  /**
   * Layers that already draw a name label of their own, and the field each
   * one shows.
   *
   * These need labels ON by default, and the reason is the same trap the
   * boundary outline fell into. The district boundary prints district names
   * today; if the editor opened with labels off, saving any style at all —
   * a colour tweak, nothing to do with text — would blank them, because a
   * saved style is applied whole. Starting from the built-in state means
   * saving preserves the map, and turning the names off stays a deliberate
   * act rather than a side effect.
   *
   * The constituency entry also quietly repairs something: the viewer's
   * built-in label coalesces over NAME_KEYS, which lists AC_NAME but not the
   * lower-case ac_name this data actually carries — so those names render
   * blank today. Naming the real field here makes them appear.
   */
  var BUILTIN_LABEL = {
    boundary_district: 'DISTRICT',
    boundary_constituency: 'ac_name'
  };

  /**
   * A fresh style document.
   *
   * `layer` is optional and only used to honour BUILTIN_LABEL — everything
   * else is geometry-independent, because the editor shows the sections that
   * apply and the server fills in the rest.
   */
  function defaultStyle(layer) {
    var d = baseDefaultStyle();
    var field = layer && BUILTIN_LABEL[layer.key];
    // Only if the layer really carries that attribute. A boundary uploaded
    // from a differently-spelled shapefile should fall back to no label
    // rather than to a field nothing holds.
    if (field && (layer.attributes || []).some(function (a) { return a.key === field; })) {
      d.label.on = true;
      d.label.attribute = field;
    }
    return d;
  }

  function baseDefaultStyle() {
    return {
      version: 1,
      color: {
        mode: 'SINGLE', value: '#2bb8a3', fallback: '#9aa0a6', attribute: null,
        categories: [], ranges: [],
        gradient: { min: 0, max: 100, stops: [{ at: 0, color: '#2166ac' }, { at: 1, color: '#b2182b' }] }
      },
      line: {
        width: 3, opacity: 1, dash: 'SOLID', cap: 'round', join: 'round', blur: 0, zoomScale: true,
        outline: { on: false, color: '#0b1322', width: 1.2 }
      },
      point: {
        mode: 'CIRCLE', radius: 6, opacity: 1, blur: 0, icon: 'circle', iconSize: 1, iconRotate: 0,
        allowOverlap: false, zoomScale: true, stroke: { color: '#ffffff', width: 1.6 }
      },
      fill: { opacity: 0.32, pattern: 'NONE',
              outline: { on: true, color: '#ffffff', width: 1.2, dash: 'SOLID' } },
      label: {
        on: false, attribute: null, size: 12, color: '#ffffff', opacity: 1, font: 'REGULAR',
        transform: 'none', placement: 'AUTO', anchor: 'center', offsetX: 0, offsetY: 0.9, rotate: 0,
        letterSpacing: 0, maxWidth: 10, minZoom: 0, maxZoom: 24, allowOverlap: false,
        prefix: '', suffix: '', decimals: null,
        halo: { color: '#0b1322', width: 1.4, blur: 0 }
      },
      minZoom: 0, maxZoom: 24
    };
  }

  /** Deep-merge b onto a, one level per section — mirrors the server's merge(). */
  function merge(a, b) {
    var out = clone(a);
    Object.keys(b || {}).forEach(function (k) {
      var v = b[k];
      if (v && typeof v === 'object' && !Array.isArray(v) && out[k] && typeof out[k] === 'object') {
        out[k] = Object.assign({}, out[k], v);
      } else {
        out[k] = v;
      }
    });
    return out;
  }

  /* ------------------------------------------------------------------
     Load
     ------------------------------------------------------------------ */

  function load() {
    return api('/api/layer-styles/manage').then(function (d) {
      LAYERS = d.layers || [];
      TEMPLATES = d.templates || [];
      renderList();
      if (SEL) {
        var still = LAYERS.filter(function (l) { return l.key === SEL.key; })[0];
        if (still) { SEL = still; return; }
      }
      /* Arriving from a layer's "Style" button on Layer Management: open that
         layer straight away rather than making someone find it again in the
         list they just came from. Only honoured on the first load, so a later
         refresh does not yank the selection back. */
      var wanted = new URLSearchParams(location.search).get('layer');
      if (wanted && LAYERS.some(function (l) { return l.key === wanted; })) {
        selectLayer(wanted);
        return;
      }
      renderEmptyStage();
    }).catch(function (e) { msg('Could not load the style registry: ' + e.message); });
  }

  /* ------------------------------------------------------------------
     Left rail
     ------------------------------------------------------------------ */

  function renderList() {
    var host = document.getElementById('layerList');
    var q = (document.getElementById('q').value || '').trim().toLowerCase();
    var byFolder = {};
    var order = [];
    LAYERS.forEach(function (l) {
      if (q && l.name.toLowerCase().indexOf(q) < 0 && l.folder.toLowerCase().indexOf(q) < 0) return;
      if (!byFolder[l.folder]) { byFolder[l.folder] = []; order.push(l.folder); }
      byFolder[l.folder].push(l);
    });
    if (!order.length) { host.innerHTML = '<div class="empty" style="padding:20px 10px;color:var(--muted-2);font-size:12.5px">No layers match.</div>'; return; }
    host.innerHTML = order.map(function (folder) {
      return '<div class="grp-t">' + esc(folder) + '</div>' +
        byFolder[folder].map(rowHtml).join('');
    }).join('');
    $all('.lrow', host).forEach(function (el) {
      el.addEventListener('click', function () { selectLayer(el.dataset.key); });
    });
  }

  function rowHtml(l) {
    var on = SEL && SEL.key === l.key;
    var sw = swatchSvg(l.style, l.geometryType, 26);
    var meta = geomLabel(l.geometryType) + (l.temporary ? ' · temp' : '');
    return '<div class="lrow' + (on ? ' on' : '') + '" data-key="' + esc(l.key) + '">' +
      '<span class="sw">' + sw + '</span>' +
      '<span class="lt"><div class="ln">' + esc(l.name) + '</div><div class="lm">' + esc(meta) + '</div></span>' +
      '<span class="dot' + (l.style ? '' : ' off') + '" title="' + (l.style ? 'Styled' : 'Built-in look') + '"></span>' +
      '</div>';
  }

  function geomLabel(g) {
    return { POINT: 'Point', LINESTRING: 'Line', MULTILINESTRING: 'Multi-line', POLYGON: 'Polygon' }[g] || g;
  }

  /* ------------------------------------------------------------------
     Selecting a layer
     ------------------------------------------------------------------ */

  function selectLayer(key) {
    var l = LAYERS.filter(function (x) { return x.key === key; })[0];
    if (!l) return;
    if (DIRTY && !confirm('Discard unsaved changes to "' + (SEL ? SEL.name : '') + '"?')) return;
    SEL = l;
    DRAFT = l.style ? merge(defaultStyle(l), l.style) : defaultStyle(l);
    DIRTY = false;
    TAB = 'color';
    renderList();
    renderStage();
  }

  function renderEmptyStage() {
    document.getElementById('stage').innerHTML =
      '<div class="card"><div class="empty-stage">' +
      '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M12 2 2 7l10 5 10-5-10-5Z"/><path d="m2 17 10 5 10-5M2 12l10 5 10-5"/></svg>' +
      '<div class="es-t">Choose a layer to style</div>' +
      '<div class="es-d">Pick anything from the list on the left. Layers with a filled dot already ' +
      'have a saved style; an open dot means the map still draws its built-in look.</div>' +
      '</div></div>';
  }

  /* ==================================================================
     THE STAGE
     ================================================================== */

  function renderStage() {
    var l = SEL, s = DRAFT;
    var geom = l.geometryType;
    var isLine = geom === 'LINESTRING' || geom === 'MULTILINESTRING';
    var isPoint = geom === 'POINT';
    var isPoly = geom === 'POLYGON';

    var badges = [];
    badges.push(l.style ? '<span class="badge styled">Styled</span>' : '<span class="badge plain">Built-in look</span>');
    if (l.hidden) badges.push('<span class="badge warn">Hidden from map</span>');
    if (l.frozen) badges.push('<span class="badge warn">Frozen</span>');

    var host = document.getElementById('stage');
    host.innerHTML =
      '<div class="card">' +
        '<div class="shead">' +
          '<div><h2>' + esc(l.name) + ' ' + badges.join(' ') + '</h2>' +
          '<div class="sm-meta">' + esc(l.folder) + ' · ' + geomLabel(geom) +
            (l.updatedAt ? ' · saved ' + esc(String(l.updatedAt).replace('T', ' ').slice(0, 16)) : '') + '</div></div>' +
          '<div class="sacts">' +
            '<button class="btn ghost sm" id="btnTemplates">Templates…</button>' +
            '<button class="btn ghost sm" id="btnSaveTpl">Save as template</button>' +
            (l.style ? '<button class="btn ghost sm" id="btnReset">Reset to built-in</button>' : '') +
          '</div>' +
        '</div>' +

        '<div class="preview ' + (PREVIEW_MODE === 'light' ? 'light' : '') + '" id="preview"></div>' +
        '<div class="pv-bar">' +
          '<span class="pv-l">Live preview — updates as you edit</span>' +
          '<span class="pv-modes">' +
            '<button data-m="dark" class="' + (PREVIEW_MODE === 'dark' ? 'on' : '') + '">Dark map</button>' +
            '<button data-m="light" class="' + (PREVIEW_MODE === 'light' ? 'on' : '') + '">Light map</button>' +
          '</span>' +
        '</div>' +

        '<div class="tabs">' +
          tabBtn('color', 'Colour') +
          (isLine ? tabBtn('line', 'Line') : '') +
          (isPoint ? tabBtn('point', 'Symbol') : '') +
          (isPoly ? tabBtn('fill', 'Fill') : '') +
          tabBtn('label', 'Label' + (s.label.on ? ' <span class="tn">on</span>' : '')) +
          tabBtn('advanced', 'Advanced') +
        '</div>' +

        '<div class="pane' + (TAB === 'color' ? ' on' : '') + '" data-p="color" id="paneColor"></div>' +
        (isLine ? '<div class="pane' + (TAB === 'line' ? ' on' : '') + '" data-p="line" id="paneLine"></div>' : '') +
        (isPoint ? '<div class="pane' + (TAB === 'point' ? ' on' : '') + '" data-p="point" id="panePoint"></div>' : '') +
        (isPoly ? '<div class="pane' + (TAB === 'fill' ? ' on' : '') + '" data-p="fill" id="paneFill"></div>' : '') +
        '<div class="pane' + (TAB === 'label' ? ' on' : '') + '" data-p="label" id="paneLabel"></div>' +
        '<div class="pane' + (TAB === 'advanced' ? ' on' : '') + '" data-p="advanced" id="paneAdvanced"></div>' +
      '</div>' +

      '<div class="bar">' +
        '<div class="bl" id="dirtyMsg">' + (DIRTY ? 'Unsaved changes' : 'No changes since last save') + '</div>' +
        '<div class="br">' +
          '<button class="btn ghost" id="btnDiscard"' + (DIRTY ? '' : ' disabled') + '>Discard changes</button>' +
          '<button class="btn" id="btnSave"' + (DIRTY ? '' : ' disabled') + '>Save style</button>' +
        '</div>' +
      '</div>';

    $all('.tab', host).forEach(function (t) {
      t.addEventListener('click', function () { TAB = t.dataset.t; renderStage(); });
    });
    $('#btnTemplates').addEventListener('click', openTemplates);
    $('#btnSaveTpl').addEventListener('click', openSave);
    var rb = $('#btnReset'); if (rb) rb.addEventListener('click', resetLayer);
    $('#btnDiscard').addEventListener('click', function () { DRAFT = l.style ? merge(defaultStyle(l), l.style) : defaultStyle(l); DIRTY = false; renderStage(); });
    $('#btnSave').addEventListener('click', saveLayer);
    $all('.pv-modes button', host).forEach(function (b) {
      b.addEventListener('click', function () {
        PREVIEW_MODE = b.dataset.m;
        $('#preview').className = 'preview ' + (PREVIEW_MODE === 'light' ? 'light' : '');
        $all('.pv-modes button', host).forEach(function (x) { x.classList.toggle('on', x === b); });
      });
    });

    renderColorPane();
    if (isLine) renderLinePane();
    if (isPoint) renderPointPane();
    if (isPoly) renderFillPane();
    renderLabelPane();
    renderAdvancedPane();
    renderPreview();
  }

  function tabBtn(key, label) {
    return '<button class="tab' + (TAB === key ? ' on' : '') + '" data-t="' + key + '">' + label + '</button>';
  }

  function touch() { DIRTY = true; var b = $('#btnSave'), d = $('#btnDiscard'), m = $('#dirtyMsg');
    if (b) b.disabled = false; if (d) d.disabled = false; if (m) m.textContent = 'Unsaved changes'; }

  /* ------------------------------------------------------------------
     Reusable controls
     ------------------------------------------------------------------ */

  function segmented(id, options, value) {
    return '<div class="seg" id="' + id + '">' + options.map(function (o) {
      return '<button data-v="' + o.v + '" class="' + (o.v === value ? 'on' : '') + '"' +
        (o.dis ? ' disabled' : '') + '>' + o.t + '</button>';
    }).join('') + '</div>';
  }
  function wireSegmented(id, onPick) {
    $all('#' + id + ' button').forEach(function (b) {
      if (b.disabled) return;
      b.addEventListener('click', function () {
        $all('#' + id + ' button').forEach(function (x) { x.classList.toggle('on', x === b); });
        onPick(b.dataset.v);
      });
    });
  }

  function slider(id, lo, hi, step, value, suffix) {
    var pct = ((value - lo) / (hi - lo)) * 100;
    // The unit is carried on the element rather than closed over, because
    // wireSlider() rewrites this span on every drag and would otherwise have
    // no way to put the unit back — the value would silently lose its "px".
    return '<div class="slid"><input type="range" id="' + id + '" min="' + lo + '" max="' + hi +
      '" step="' + step + '" value="' + value + '" style="--pct:' + pct + '%">' +
      '<span class="val" id="' + id + 'v" data-suf="' + (suffix || '') + '">' +
      round(value) + (suffix || '') + '</span></div>';
  }
  function wireSlider(id, onInput) {
    var el = document.getElementById(id); if (!el) return;
    el.addEventListener('input', function () {
      var v = Number(el.value);
      var pct = ((v - Number(el.min)) / (Number(el.max) - Number(el.min))) * 100;
      el.style.setProperty('--pct', pct + '%');
      var vEl = document.getElementById(id + 'v'); if (vEl) vEl.textContent = round(v) + (vEl.dataset.suf || '');
      onInput(v);
    });
  }
  function round(v) { return Math.round(v * 100) / 100; }

  function colorWell(id, value) {
    return '<div class="cwell"><input type="color" id="' + id + '" value="' + value + '">' +
      '<input type="text" id="' + id + 't" value="' + value + '" maxlength="7"></div>';
  }
  function wireColorWell(id, onChange) {
    var c = document.getElementById(id), t = document.getElementById(id + 't');
    if (!c) return;
    c.addEventListener('input', function () { t.value = c.value; onChange(c.value); });
    t.addEventListener('change', function () {
      var v = t.value.trim();
      if (/^#[0-9a-fA-F]{6}$/.test(v)) { c.value = v; onChange(v); } else { t.value = c.value; }
    });
  }

  function swatchRow(onPick) {
    return '<div class="swatches">' + PALETTE.map(function (c) {
      return '<i style="background:' + c + '" data-c="' + c + '" title="' + c + '"></i>';
    }).join('') + '</div>';
  }
  function wireSwatchRow(host, onPick) {
    $all('.swatches i', host).forEach(function (i) {
      i.addEventListener('click', function () { onPick(i.dataset.c); });
    });
  }

  function toggle(id, on, title, desc) {
    return '<label class="sw-row"><span><span class="swt">' + esc(title) + '</span>' +
      (desc ? '<span class="swd">' + esc(desc) + '</span>' : '') + '</span>' +
      '<span class="toggle"><input type="checkbox" id="' + id + '"' + (on ? ' checked' : '') + '><span></span></span></label>';
  }
  function wireToggle(id, onChange) {
    var el = document.getElementById(id); if (!el) return;
    el.addEventListener('change', function () { onChange(el.checked); });
  }

  /* ==================================================================
     COLOUR PANE
     ================================================================== */

  function styleAttrs() { return (SEL && SEL.attributes) || []; }
  function numericAttrs() { return styleAttrs().filter(function (a) { return a.numeric; }); }

  function renderColorPane() {
    var pane = document.getElementById('paneColor');
    var c = DRAFT.color;
    var geom = SEL.geometryType;
    var modeLabel = geom === 'POLYGON' ? 'fill' : (geom === 'POINT' ? 'symbol' : 'line');

    pane.innerHTML =
      '<div class="sect">' +
        '<div class="sect-t">How is the ' + modeLabel + ' coloured? ' +
          '<span class="sd">— single colour, one per class, banded by range, or a graduated ramp</span></div>' +
        segmented('colMode', [
          { v: 'SINGLE', t: 'Single' }, { v: 'CATEGORY', t: 'By class' },
          { v: 'RANGE', t: 'By range' }, { v: 'GRADIENT', t: 'Gradient' }
        ], c.mode) +
      '</div>' +

      '<div class="sect" id="colBody"></div>';

    wireSegmented('colMode', function (v) { DRAFT.color.mode = v; touch(); renderColorPane(); renderPreview(); refreshRailSwatch(); });
    renderColorBody();
  }

  function attrSelect(id, list, value, placeholder) {
    return '<select id="' + id + '"><option value="">' + esc(placeholder || 'Choose an attribute…') + '</option>' +
      list.map(function (a) {
        return '<option value="' + esc(a.key) + '"' + (a.key === value ? ' selected' : '') + '>' + esc(a.name) + '</option>';
      }).join('') + '</select>';
  }

  function renderColorBody() {
    var body = document.getElementById('colBody');
    var c = DRAFT.color;

    if (c.mode === 'SINGLE') {
      body.innerHTML =
        '<div class="fgrid"><div class="fld"><label>Colour</label>' + colorWell('colSingle', c.value) +
        swatchRow() + '</div></div>';
      wireColorWell('colSingle', function (v) { DRAFT.color.value = v; touch(); renderPreview(); refreshRailSwatch(); });
      wireSwatchRow(body, function (v) { DRAFT.color.value = v; document.getElementById('colSingle').value = v; document.getElementById('colSinglet').value = v; touch(); renderPreview(); refreshRailSwatch(); });
      return;
    }

    if (c.mode === 'CATEGORY') {
      body.innerHTML =
        '<div class="fgrid"><div class="fld"><label>Colour by attribute</label>' +
          attrSelect('catAttr', styleAttrs(), c.attribute) +
          '<div class="hint">One colour per distinct value — a road class, a surface type, a soil code.</div></div>' +
        '<div class="fld"><label>Fallback colour</label>' + colorWell('catFallback', c.fallback) +
          '<div class="hint">Used for a feature whose value matches nothing below.</div></div></div>' +
        '<div class="sect"><div class="sect-t">Classes<span class="sd">— add one row per value, or load them from the data</span></div>' +
        '<div class="rows" id="catRows"></div>' +
        '<div class="rowacts">' +
          '<button class="btn ghost sm" id="catAdd">+ Add class</button>' +
          '<button class="btn ghost sm" id="catAuto"' + (c.attribute ? '' : ' disabled') + '>Fill from data</button>' +
        '</div></div>';
      wireColorWell('catFallback', function (v) { DRAFT.color.fallback = v; touch(); renderPreview(); });
      document.getElementById('catAttr').addEventListener('change', function (e) {
        DRAFT.color.attribute = e.target.value || null; touch(); renderColorBody(); renderPreview(); refreshRailSwatch();
      });
      document.getElementById('catAdd').addEventListener('click', function () {
        DRAFT.color.categories.push({ value: '', color: PALETTE[DRAFT.color.categories.length % PALETTE.length], label: '' });
        touch(); renderCatRows();
      });
      var autoBtn = document.getElementById('catAuto');
      if (c.attribute) autoBtn.addEventListener('click', function () { autoFillCategories(); });
      renderCatRows();
      return;
    }

    if (c.mode === 'RANGE') {
      body.innerHTML =
        '<div class="fgrid"><div class="fld"><label>Colour by attribute</label>' +
          attrSelect('rngAttr', numericAttrs(), c.attribute, 'Choose a numeric attribute…') +
          '<div class="hint">Only numeric attributes can be banded.</div></div>' +
        '<div class="fld"><label>Fallback colour</label>' + colorWell('rngFallback', c.fallback) +
          '<div class="hint">Used when the value is missing.</div></div></div>' +
        '<div class="sect"><div class="sect-t">Bands<span class="sd">— each starts at "from" and runs until the next band begins</span></div>' +
        '<div class="rows" id="rngRows"></div>' +
        '<div class="rowacts"><button class="btn ghost sm" id="rngAdd">+ Add band</button></div></div>';
      wireColorWell('rngFallback', function (v) { DRAFT.color.fallback = v; touch(); renderPreview(); });
      document.getElementById('rngAttr').addEventListener('change', function (e) {
        DRAFT.color.attribute = e.target.value || null; touch(); renderPreview(); refreshRailSwatch();
      });
      document.getElementById('rngAdd').addEventListener('click', function () {
        var rs = DRAFT.color.ranges;
        var last = rs[rs.length - 1];
        rs.push({ from: last ? num(last.from, 0) + 10 : 0, to: null, color: PALETTE[rs.length % PALETTE.length], label: '' });
        touch(); renderRngRows();
      });
      renderRngRows();
      return;
    }

    /* GRADIENT */
    body.innerHTML =
      '<div class="fgrid"><div class="fld"><label>Colour by attribute</label>' +
        attrSelect('gradAttr', numericAttrs(), c.attribute, 'Choose a numeric attribute…') + '</div>' +
      '<div class="fld"><label>Minimum value</label><input type="text" id="gradMin" value="' + c.gradient.min + '"></div>' +
      '<div class="fld"><label>Maximum value</label><input type="text" id="gradMax" value="' + c.gradient.max + '"></div>' +
      '<div class="fld"><label>Fallback colour</label>' + colorWell('gradFallback', c.fallback) + '</div></div>' +
      '<div class="sect"><div class="sect-t">Ramp<span class="sd">— stops from minimum to maximum</span></div>' +
      '<div class="ramp" id="rampBar"></div>' +
      '<div class="rows" id="gradRows"></div>' +
      '<div class="rowacts">' +
        '<button class="btn ghost sm" id="gradAdd">+ Add stop</button>' +
        '<div class="seg" id="gradPreset" style="margin-left:auto">' +
          '<button data-p="viridis">Viridis</button><button data-p="magma">Magma</button>' +
          '<button data-p="traffic">Good–Poor</button><button data-p="blues">Blues</button>' +
          '<button data-p="diverging">Diverging</button>' +
        '</div>' +
      '</div></div>';
    document.getElementById('gradAttr').addEventListener('change', function (e) {
      DRAFT.color.attribute = e.target.value || null; touch(); renderPreview(); refreshRailSwatch();
    });
    document.getElementById('gradMin').addEventListener('change', function (e) { DRAFT.color.gradient.min = num(e.target.value, 0); touch(); renderPreview(); });
    document.getElementById('gradMax').addEventListener('change', function (e) { DRAFT.color.gradient.max = num(e.target.value, 100); touch(); renderPreview(); });
    wireColorWell('gradFallback', function (v) { DRAFT.color.fallback = v; touch(); renderPreview(); });
    $all('#gradPreset button').forEach(function (b) {
      b.addEventListener('click', function () { applyRampPreset(b.dataset.p); });
    });
    renderGradRows();
  }

  var RAMP_PRESETS = {
    viridis:  ['#440154', '#3b528b', '#21918c', '#5ec962', '#fde725'],
    magma:    ['#000004', '#51127c', '#b73779', '#fc8961', '#fcfdbf'],
    traffic:  ['#1a9850', '#91cf60', '#fee08b', '#fc8d59', '#d73027'],
    blues:    ['#eff3ff', '#bdd7e7', '#6baed6', '#3182bd', '#08519c'],
    diverging:['#2166ac', '#92c5de', '#f7f7f7', '#f4a582', '#b2182b']
  };
  function applyRampPreset(name) {
    var colors = RAMP_PRESETS[name];
    DRAFT.color.gradient.stops = colors.map(function (c, i) {
      return { at: colors.length === 1 ? 0 : i / (colors.length - 1), color: c };
    });
    touch(); renderGradRows(); renderPreview(); refreshRailSwatch();
  }

  function renderCatRows() {
    var host = document.getElementById('catRows');
    var cats = DRAFT.color.categories;
    host.innerHTML = cats.map(function (cat, i) {
      return '<div class="rrow cat" data-i="' + i + '">' +
        '<input type="color" class="cv" value="' + cat.color + '">' +
        '<input type="text" class="vv" placeholder="Value as stored in the data" value="' + esc(cat.value) + '">' +
        '<span class="x xrow">&times;</span></div>';
    }).join('') || '<div class="hint">No classes yet — add one, or fill from the data once an attribute is chosen.</div>';
    $all('.rrow.cat', host).forEach(function (row) {
      var i = Number(row.dataset.i);
      row.querySelector('.cv').addEventListener('input', function (e) { cats[i].color = e.target.value; touch(); renderRampAndSwatch(); });
      row.querySelector('.vv').addEventListener('change', function (e) { cats[i].value = e.target.value; touch(); refreshRailSwatch(); });
      row.querySelector('.xrow').addEventListener('click', function () { cats.splice(i, 1); touch(); renderCatRows(); renderPreview(); });
    });
  }

  function renderRngRows() {
    var host = document.getElementById('rngRows');
    var rs = DRAFT.color.ranges;
    host.innerHTML = rs.map(function (r, i) {
      return '<div class="rrow rng" data-i="' + i + '">' +
        '<input type="color" class="cv" value="' + r.color + '">' +
        '<input type="text" class="fv" placeholder="From" value="' + (r.from == null ? '' : r.from) + '">' +
        '<input type="text" class="tv" placeholder="To (optional)" value="' + (r.to == null ? '' : r.to) + '">' +
        '<span class="x xrow">&times;</span></div>';
    }).join('') || '<div class="hint">No bands yet.</div>';
    $all('.rrow.rng', host).forEach(function (row) {
      var i = Number(row.dataset.i);
      row.querySelector('.cv').addEventListener('input', function (e) { rs[i].color = e.target.value; touch(); renderPreview(); refreshRailSwatch(); });
      row.querySelector('.fv').addEventListener('change', function (e) { rs[i].from = num(e.target.value, 0); touch(); renderPreview(); });
      row.querySelector('.tv').addEventListener('change', function (e) { rs[i].to = e.target.value === '' ? null : num(e.target.value, null); touch(); renderPreview(); });
      row.querySelector('.xrow').addEventListener('click', function () { rs.splice(i, 1); touch(); renderRngRows(); renderPreview(); });
    });
  }

  function renderGradRows() {
    var host = document.getElementById('gradRows');
    var stops = DRAFT.color.gradient.stops;
    host.innerHTML = stops.map(function (st, i) {
      return '<div class="rrow stp" data-i="' + i + '">' +
        '<input type="color" class="cv" value="' + st.color + '">' +
        '<input type="range" min="0" max="1" step="0.01" class="av" value="' + st.at + '">' +
        '<span class="x xrow">&times;</span></div>';
    }).join('');
    $all('.rrow.stp', host).forEach(function (row) {
      var i = Number(row.dataset.i);
      row.querySelector('.cv').addEventListener('input', function (e) { stops[i].color = e.target.value; touch(); renderRampAndSwatch(); });
      row.querySelector('.av').addEventListener('input', function (e) { stops[i].at = Number(e.target.value); touch(); renderRampAndSwatch(); });
      row.querySelector('.xrow').addEventListener('click', function () {
        if (stops.length <= 2) return;
        stops.splice(i, 1); touch(); renderGradRows(); renderPreview(); refreshRailSwatch();
      });
    });
    document.getElementById('gradAdd').onclick = function () {
      stops.push({ at: 0.5, color: '#9aa0a6' }); touch(); renderGradRows(); renderPreview(); refreshRailSwatch();
    };
    renderRampBar();
  }

  function renderRampAndSwatch() { renderRampBar(); renderPreview(); refreshRailSwatch(); }

  function renderRampBar() {
    var bar = document.getElementById('rampBar'); if (!bar) return;
    var stops = DRAFT.color.gradient.stops.slice().sort(function (a, b) { return a.at - b.at; });
    var css = stops.map(function (s) { return s.color + ' ' + Math.round(s.at * 100) + '%'; }).join(', ');
    bar.style.background = 'linear-gradient(90deg,' + css + ')';
  }

  /** Fetch the distinct values of the chosen attribute and build one row per value. */
  function autoFillCategories() {
    var attr = DRAFT.color.attribute;
    if (!attr) return;
    var cacheKey = SEL.key + '::' + attr;
    var p = VALUES_CACHE[cacheKey]
      ? Promise.resolve(VALUES_CACHE[cacheKey])
      : api('/api/layer-styles/' + encodeURIComponent(SEL.key) + '/values?attribute=' + encodeURIComponent(attr))
          .then(function (d) { VALUES_CACHE[cacheKey] = d.values || []; return VALUES_CACHE[cacheKey]; });
    var btn = document.getElementById('catAuto');
    btn.disabled = true; btn.textContent = 'Loading…';
    p.then(function (values) {
      if (!values.length) { msg('No values found for that attribute yet.', false); return; }
      DRAFT.color.categories = values.slice(0, 40).map(function (v, i) {
        return { value: v, color: PALETTE[i % PALETTE.length], label: '' };
      });
      touch(); renderCatRows(); renderPreview(); refreshRailSwatch();
      msg(values.length + ' value' + (values.length === 1 ? '' : 's') + ' loaded.', true);
    }).catch(function (e) { msg('Could not load values: ' + e.message); })
      .then(function () { btn.disabled = false; btn.textContent = 'Fill from data'; });
  }

  /* ==================================================================
     LINE PANE
     ================================================================== */

  var DASH_LIST = [
    ['SOLID', null], ['DASH', [4, 3]], ['DOT', [1, 2.6]],
    ['DASH_DOT', [5, 2, 1, 2]], ['LONG_DASH', [7, 3]], ['RAIL', [1.4, 1.6]]
  ];

  function renderLinePane() {
    var pane = document.getElementById('paneLine');
    var l = DRAFT.line;
    pane.innerHTML =
      '<div class="fgrid">' +
        '<div class="fld"><label>Weight</label>' + slider('lnWidth', 0.5, 16, 0.5, l.width, ' px') + '</div>' +
        '<div class="fld"><label>Opacity</label>' + slider('lnOpacity', 0, 1, 0.05, l.opacity) + '</div>' +
        '<div class="fld"><label>Blur</label>' + slider('lnBlur', 0, 10, 0.5, l.blur) + '</div>' +
      '</div>' +
      '<div class="sect"><div class="sect-t">Line pattern</div>' +
        '<div class="dashes" id="dashPick">' + DASH_LIST.map(function (d) {
          return '<div class="dsh' + (l.dash === d[0] ? ' on' : '') + '" data-d="' + d[0] + '">' +
            dashSvg(d[1]) + '<div class="dn">' + d[0].replace('_', ' ') + '</div></div>';
        }).join('') + '</div></div>' +
      '<div class="fgrid sect">' +
        '<div class="fld"><label>Cap</label>' + segmented('lnCap', [{ v: 'butt', t: 'Butt' }, { v: 'round', t: 'Round' }, { v: 'square', t: 'Square' }], l.cap) + '</div>' +
        '<div class="fld"><label>Join</label>' + segmented('lnJoin', [{ v: 'bevel', t: 'Bevel' }, { v: 'round', t: 'Round' }, { v: 'miter', t: 'Miter' }], l.join) + '</div>' +
      '</div>' +
      '<div class="sect">' + toggle('lnZoom', l.zoomScale, 'Scale with zoom', 'Thicker as you zoom in. Turn off for a fixed pixel width at every zoom.') + '</div>' +
      '<div class="sect"><div class="sect-t">Outline / casing<span class="sd">— a contrasting edge behind the line</span></div>' +
        toggle('lnOutlineOn', l.outline.on, 'Draw an outline') +
        '<div class="fgrid" style="margin-top:12px" id="lnOutlineBody"></div></div>';

    wireSlider('lnWidth', function (v) { DRAFT.line.width = v; renderPreview(); refreshRailSwatch(); });
    wireSlider('lnOpacity', function (v) { DRAFT.line.opacity = v; renderPreview(); });
    wireSlider('lnBlur', function (v) { DRAFT.line.blur = v; renderPreview(); });
    $all('#dashPick .dsh').forEach(function (el) {
      el.addEventListener('click', function () {
        DRAFT.line.dash = el.dataset.d; touch();
        $all('#dashPick .dsh').forEach(function (x) { x.classList.toggle('on', x === el); });
        renderPreview();
      });
    });
    wireSegmented('lnCap', function (v) { DRAFT.line.cap = v; touch(); renderPreview(); });
    wireSegmented('lnJoin', function (v) { DRAFT.line.join = v; touch(); });
    wireToggle('lnZoom', function (v) { DRAFT.line.zoomScale = v; touch(); });
    wireToggle('lnOutlineOn', function (v) { DRAFT.line.outline.on = v; touch(); renderLineOutlineBody(); renderPreview(); });
    ['lnWidth', 'lnOpacity', 'lnBlur'].forEach(function (id) { document.getElementById(id).addEventListener('input', touch); });
    renderLineOutlineBody();
  }

  function renderLineOutlineBody() {
    var body = document.getElementById('lnOutlineBody'); if (!body) return;
    var o = DRAFT.line.outline;
    body.innerHTML =
      '<div class="fld"><label>Outline colour</label>' + colorWell('lnOutColor', o.color) + '</div>' +
      '<div class="fld"><label>Outline width</label>' + slider('lnOutWidth', 0, 8, 0.2, o.width, ' px') + '</div>';
    wireColorWell('lnOutColor', function (v) { DRAFT.line.outline.color = v; touch(); renderPreview(); });
    wireSlider('lnOutWidth', function (v) { DRAFT.line.outline.width = v; renderPreview(); });
    document.getElementById('lnOutWidth').addEventListener('input', touch);
  }

  /**
   * The SVG stroke-dasharray for a dash name, or '' for a solid line.
   *
   * Reads DASH_LIST rather than holding a second copy of the patterns, so
   * the swatch in the picker and the stroke in the preview cannot drift.
   */
  function dashAttr(name) {
    for (var i = 0; i < DASH_LIST.length; i++) {
      if (DASH_LIST[i][0] === name && DASH_LIST[i][1]) {
        return ' stroke-dasharray="' + DASH_LIST[i][1].join(',') + '"';
      }
    }
    return '';
  }

  function dashSvg(pattern) {
    var dash = pattern ? ' stroke-dasharray="' + pattern.map(function (n) { return n * 1.6; }).join(',') + '"' : '';
    return '<svg viewBox="0 0 100 12" preserveAspectRatio="none"><line x1="2" y1="6" x2="98" y2="6" ' +
      'stroke="currentColor" stroke-width="3.5" stroke-linecap="round"' + dash + ' style="color:#8fa3c8"/></svg>';
  }

  /* ==================================================================
     POINT / SYMBOL PANE
     ================================================================== */

  function renderPointPane() {
    var pane = document.getElementById('panePoint');
    var p = DRAFT.point;
    pane.innerHTML =
      '<div class="sect"><div class="sect-t">Marker type</div>' +
        segmented('ptMode', [{ v: 'CIRCLE', t: 'Plain dot' }, { v: 'ICON', t: 'Symbol' }], p.mode) + '</div>' +
      '<div class="sect" id="ptBody"></div>' +
      '<div class="fgrid sect">' +
        '<div class="fld"><label>Opacity</label>' + slider('ptOpacity', 0, 1, 0.05, p.opacity) + '</div>' +
        '<div class="fld"><label>Outline colour</label>' + colorWell('ptStroke', p.stroke.color) + '</div>' +
        '<div class="fld"><label>Outline width</label>' + slider('ptStrokeW', 0, 6, 0.2, p.stroke.width, ' px') + '</div>' +
      '</div>' +
      '<div class="sect">' + toggle('ptZoom', p.zoomScale, 'Scale with zoom', 'Larger as you zoom in.') + '</div>' +
      '<div class="sect">' + toggle('ptOverlap', p.allowOverlap, 'Allow overlap', 'Draw every marker even when they crowd each other. Off lets the busiest ones hide to keep the map readable.') + '</div>';

    wireSegmented('ptMode', function (v) { DRAFT.point.mode = v; touch(); renderPointPane(); renderPreview(); refreshRailSwatch(); });
    wireSlider('ptOpacity', function (v) { DRAFT.point.opacity = v; renderPreview(); });
    wireColorWell('ptStroke', function (v) { DRAFT.point.stroke.color = v; touch(); renderPreview(); });
    wireSlider('ptStrokeW', function (v) { DRAFT.point.stroke.width = v; renderPreview(); });
    wireToggle('ptZoom', function (v) { DRAFT.point.zoomScale = v; touch(); });
    wireToggle('ptOverlap', function (v) { DRAFT.point.allowOverlap = v; touch(); });
    ['ptOpacity', 'ptStrokeW'].forEach(function (id) { document.getElementById(id).addEventListener('input', touch); });
    renderPointBody();
  }

  function renderPointBody() {
    var body = document.getElementById('ptBody');
    var p = DRAFT.point;
    if (p.mode === 'CIRCLE') {
      body.innerHTML = '<div class="fgrid"><div class="fld"><label>Radius</label>' + slider('ptRadius', 1, 24, 0.5, p.radius, ' px') + '</div></div>';
      wireSlider('ptRadius', function (v) { DRAFT.point.radius = v; renderPreview(); refreshRailSwatch(); });
      document.getElementById('ptRadius').addEventListener('input', touch);
      return;
    }
    body.innerHTML =
      '<div class="fgrid"><div class="fld"><label>Icon size</label>' + slider('ptIconSize', 0.4, 3, 0.1, p.iconSize, 'x') + '</div>' +
      '<div class="fld"><label>Rotation</label>' + slider('ptRotate', -180, 180, 5, p.iconRotate, '&deg;') + '</div></div>' +
      '<div class="fld sect"><label>Symbol</label><div class="icons" id="iconPick">' +
        KLSymbols.names().map(function (name) {
          return '<div class="ico' + (p.icon === name ? ' on' : '') + '" data-i="' + name + '" title="' + name + '">' +
            '<svg viewBox="0 0 32 32">' + KLSymbols.group(name, currentRepColor(), '#ffffff', 1.4) + '</svg></div>';
        }).join('') + '</div></div>';
    wireSlider('ptIconSize', function (v) { DRAFT.point.iconSize = v; renderPreview(); refreshRailSwatch(); });
    wireSlider('ptRotate', function (v) { DRAFT.point.iconRotate = v; renderPreview(); });
    document.getElementById('ptIconSize').addEventListener('input', touch);
    document.getElementById('ptRotate').addEventListener('input', touch);
    $all('#iconPick .ico').forEach(function (el) {
      el.addEventListener('click', function () {
        DRAFT.point.icon = el.dataset.i; touch();
        $all('#iconPick .ico').forEach(function (x) { x.classList.toggle('on', x === el); });
        renderPreview(); refreshRailSwatch();
      });
    });
  }

  /** The colour the icon picker draws its glyphs in — the single colour,
   *  or the first category/range/gradient colour when data-driven. */
  function currentRepColor() {
    var c = DRAFT.color;
    if (c.mode === 'SINGLE') return c.value;
    if (c.mode === 'CATEGORY') return (c.categories[0] && c.categories[0].color) || c.fallback;
    if (c.mode === 'RANGE') return (c.ranges[0] && c.ranges[0].color) || c.fallback;
    return (c.gradient.stops[0] && c.gradient.stops[0].color) || c.fallback;
  }

  /* ==================================================================
     FILL PANE
     ================================================================== */

  var PATTERN_LIST = [['NONE', 'Solid'], ['HATCH', 'Hatch'], ['CROSS', 'Cross-hatch'], ['DOTS', 'Dots']];

  function renderFillPane() {
    var pane = document.getElementById('paneFill');
    var f = DRAFT.fill;
    var singleOnly = DRAFT.color.mode !== 'SINGLE';
    pane.innerHTML =
      '<div class="fgrid">' +
        '<div class="fld"><label>Fill opacity</label>' + slider('flOpacity', 0, 1, 0.02, f.opacity) + '</div>' +
      '</div>' +
      '<div class="sect"><div class="sect-t">Fill pattern' +
        (singleOnly ? '<span class="sd">— only available with a single colour</span>' : '') + '</div>' +
        '<div class="dashes" id="patPick">' + PATTERN_LIST.map(function (p) {
          return '<div class="dsh' + (f.pattern === p[0] ? ' on' : '') + (singleOnly && p[0] !== 'NONE' ? ' dis' : '') +
            '" data-p="' + p[0] + '" style="' + (singleOnly && p[0] !== 'NONE' ? 'opacity:.35;pointer-events:none' : '') + '">' +
            patternSvg(p[0]) + '<div class="dn">' + p[1] + '</div></div>';
        }).join('') + '</div></div>' +
      '<div class="sect"><div class="sect-t">Outline</div>' +
        toggle('flOutlineOn', f.outline.on, 'Draw an outline') +
        '<div class="fgrid" style="margin-top:12px" id="flOutlineBody"></div></div>';

    wireSlider('flOpacity', function (v) { DRAFT.fill.opacity = v; renderPreview(); });
    document.getElementById('flOpacity').addEventListener('input', touch);
    $all('#patPick .dsh:not(.dis)').forEach(function (el) {
      el.addEventListener('click', function () {
        DRAFT.fill.pattern = el.dataset.p; touch();
        $all('#patPick .dsh').forEach(function (x) { x.classList.toggle('on', x === el); });
        renderPreview();
      });
    });
    wireToggle('flOutlineOn', function (v) { DRAFT.fill.outline.on = v; touch(); renderFillOutlineBody(); renderPreview(); });
    renderFillOutlineBody();
  }

  function renderFillOutlineBody() {
    var body = document.getElementById('flOutlineBody'); if (!body) return;
    var o = DRAFT.fill.outline;
    /* Nothing at all when the outline is off. Leaving a colour and a width
       on screen under a switch that says "no outline" invites exactly the
       question of which one is really in charge. */
    if (!o.on) { body.innerHTML = ''; return; }
    body.innerHTML =
      '<div class="fld"><label>Outline colour</label>' + colorWell('flOutColor', o.color) + '</div>' +
      /* From 0, not 0.5. "Set the width to nothing" has to be reachable —
         a floor of half a pixel means the thinnest a border can get is
         still a border. */
      '<div class="fld"><label>Outline width</label>' + slider('flOutWidth', 0, 6, 0.2, o.width, ' px') + '</div>' +
      '<div class="fld span2"><label>Outline style</label>' +
        '<div class="dashes" id="flDashPick">' + DASH_LIST.map(function (d) {
          return '<div class="dsh' + (o.dash === d[0] ? ' on' : '') + '" data-d="' + d[0] + '">' +
            dashSvg(d[1]) + '<div class="dn">' + d[0].replace('_', ' ') + '</div></div>';
        }).join('') + '</div></div>';

    wireColorWell('flOutColor', function (v) { DRAFT.fill.outline.color = v; touch(); renderPreview(); });
    wireSlider('flOutWidth', function (v) { DRAFT.fill.outline.width = v; renderPreview(); });
    document.getElementById('flOutWidth').addEventListener('input', touch);
    $all('#flDashPick .dsh').forEach(function (el) {
      el.addEventListener('click', function () {
        DRAFT.fill.outline.dash = el.dataset.d; touch();
        $all('#flDashPick .dsh').forEach(function (x) { x.classList.toggle('on', x === el); });
        renderPreview();
      });
    });
  }

  function patternSvg(p) {
    if (p === 'DOTS') return '<svg viewBox="0 0 40 12"><circle cx="6" cy="6" r="1.6" fill="#8fa3c8"/><circle cx="18" cy="6" r="1.6" fill="#8fa3c8"/><circle cx="30" cy="6" r="1.6" fill="#8fa3c8"/></svg>';
    if (p === 'CROSS') return '<svg viewBox="0 0 40 12"><path d="M2 2l8 8M10 2 2 10M16 2l8 8M24 2l-8 8M30 2l8 8M38 2l-8 8" stroke="#8fa3c8" stroke-width="1.3"/></svg>';
    if (p === 'HATCH') return '<svg viewBox="0 0 40 12"><path d="M-2 12 10 0M6 12 18 0M14 12 26 0M22 12 34 0M30 12 42 0" stroke="#8fa3c8" stroke-width="1.3"/></svg>';
    return '<svg viewBox="0 0 40 12"><rect x="2" y="3" width="36" height="6" fill="#8fa3c8" fill-opacity=".55" rx="2"/></svg>';
  }

  /* ==================================================================
     LABEL PANE
     ================================================================== */

  function renderLabelPane() {
    var pane = document.getElementById('paneLabel');
    var lb = DRAFT.label;
    pane.innerHTML =
      toggle('lbOn', lb.on, 'Label this layer', 'Print an attribute\'s value next to each feature.') +
      '<div class="sect" id="lbBody" style="' + (lb.on ? '' : 'opacity:.4;pointer-events:none') + '"></div>';
    wireToggle('lbOn', function (v) {
      DRAFT.label.on = v; touch();
      document.getElementById('lbBody').style.cssText = v ? '' : 'opacity:.4;pointer-events:none';
      renderStage();
    });
    renderLabelBody();
  }

  var FONT_LIST = [['REGULAR', 'Regular'], ['SEMIBOLD', 'Semibold'], ['BOLD', 'Bold']];
  var ANCHOR_LIST = ['center', 'top', 'bottom', 'left', 'right', 'top-left', 'top-right', 'bottom-left', 'bottom-right'];

  function renderLabelBody() {
    var host = document.getElementById('lbBody');
    var lb = DRAFT.label;
    host.innerHTML =
      '<div class="fgrid">' +
        '<div class="fld"><label>Label with</label>' + attrSelect('lbAttr', styleAttrs(), lb.attribute) + '</div>' +
        '<div class="fld"><label>Decimal places</label><select id="lbDecimals">' +
          '<option value="">As stored</option>' +
          [0, 1, 2, 3].map(function (d) { return '<option value="' + d + '"' + (lb.decimals === d ? ' selected' : '') + '>' + d + '</option>'; }).join('') +
        '</select></div>' +
        '<div class="fld"><label>Prefix</label><input type="text" id="lbPrefix" value="' + esc(lb.prefix) + '" maxlength="24"></div>' +
        '<div class="fld"><label>Suffix</label><input type="text" id="lbSuffix" value="' + esc(lb.suffix) + '" maxlength="24" placeholder="e.g.  m or  %"></div>' +
      '</div>' +
      '<div class="fgrid sect">' +
        '<div class="fld"><label>Text size</label>' + slider('lbSize', 8, 32, 1, lb.size, ' px') + '</div>' +
        '<div class="fld"><label>Text colour</label>' + colorWell('lbColor', lb.color) + '</div>' +
        '<div class="fld"><label>Opacity</label>' + slider('lbOpacity', 0, 1, 0.05, lb.opacity) + '</div>' +
        '<div class="fld"><label>Weight</label>' + segmented('lbFont', FONT_LIST.map(function (f) { return { v: f[0], t: f[1] }; }), lb.font) + '</div>' +
        '<div class="fld"><label>Text case</label>' + segmented('lbCase', [{ v: 'none', t: 'Aa' }, { v: 'uppercase', t: 'AA' }, { v: 'lowercase', t: 'aa' }], lb.transform) + '</div>' +
        '<div class="fld"><label>Position</label><select id="lbAnchor">' +
          ANCHOR_LIST.map(function (a) { return '<option value="' + a + '"' + (lb.anchor === a ? ' selected' : '') + '>' + a.replace('-', ' ') + '</option>'; }).join('') +
        '</select></div>' +
      '</div>' +
      '<div class="fgrid sect">' +
        '<div class="fld"><label>Halo colour</label>' + colorWell('lbHaloColor', lb.halo.color) + '</div>' +
        '<div class="fld"><label>Halo width</label>' + slider('lbHaloWidth', 0, 4, 0.2, lb.halo.width, ' px') + '</div>' +
        '<div class="fld"><label>Min zoom</label>' + slider('lbMinZoom', 0, 20, 1, lb.minZoom) + '</div>' +
        '<div class="fld"><label>Max zoom</label>' + slider('lbMaxZoom', 4, 24, 1, lb.maxZoom) + '</div>' +
      '</div>' +

      /* Placement only has more than one sensible answer on a line: a point
         label sits at its point and an area label at its centre, so offering
         "along the line" for either would be offering something that cannot
         happen. */
      (isLineGeom() ?
        '<div class="sect"><div class="sect-t">Placement<span class="sd">— where the text sits relative to the feature</span></div>' +
        segmented('lbPlacement', [
          { v: 'AUTO', t: 'Auto' }, { v: 'LINE', t: 'Along the line' },
          { v: 'LINE_CENTER', t: 'At the centre' }, { v: 'POINT', t: 'At a point' }
        ], lb.placement) + '</div>' : '') +

      '<div class="fgrid sect">' +
        '<div class="fld"><label>Offset across</label>' + slider('lbOffX', -4, 4, 0.1, lb.offsetX, ' em') + '</div>' +
        '<div class="fld"><label>Offset down</label>' + slider('lbOffY', -4, 4, 0.1, lb.offsetY, ' em') + '</div>' +
        '<div class="fld"><label>Rotation</label>' + slider('lbRotate', -180, 180, 5, lb.rotate, '&deg;') + '</div>' +
        '<div class="fld"><label>Letter spacing</label>' + slider('lbTrack', -0.05, 0.5, 0.01, lb.letterSpacing, ' em') + '</div>' +
        '<div class="fld"><label>Wrap width</label>' + slider('lbMaxWidth', 2, 30, 1, lb.maxWidth, ' ems') + '</div>' +
      '</div>' +
      '<div class="sect">' + toggle('lbAllowOverlap', lb.allowOverlap, 'Allow overlap', 'Print every label even where they collide.') + '</div>';

    document.getElementById('lbAttr').addEventListener('change', function (e) { DRAFT.label.attribute = e.target.value || null; touch(); renderStage(); });
    document.getElementById('lbDecimals').addEventListener('change', function (e) { DRAFT.label.decimals = e.target.value === '' ? null : Number(e.target.value); touch(); renderPreview(); });
    document.getElementById('lbPrefix').addEventListener('input', function (e) { DRAFT.label.prefix = e.target.value; touch(); renderPreview(); });
    document.getElementById('lbSuffix').addEventListener('input', function (e) { DRAFT.label.suffix = e.target.value; touch(); renderPreview(); });
    wireSlider('lbSize', function (v) { DRAFT.label.size = v; renderPreview(); });
    wireColorWell('lbColor', function (v) { DRAFT.label.color = v; touch(); renderPreview(); });
    wireSlider('lbOpacity', function (v) { DRAFT.label.opacity = v; renderPreview(); });
    wireSegmented('lbFont', function (v) { DRAFT.label.font = v; touch(); renderPreview(); });
    wireSegmented('lbCase', function (v) { DRAFT.label.transform = v; touch(); renderPreview(); });
    document.getElementById('lbAnchor').addEventListener('change', function (e) { DRAFT.label.anchor = e.target.value; touch(); renderPreview(); });
    wireColorWell('lbHaloColor', function (v) { DRAFT.label.halo.color = v; touch(); renderPreview(); });
    wireSlider('lbHaloWidth', function (v) { DRAFT.label.halo.width = v; renderPreview(); });
    wireSlider('lbMinZoom', function (v) { DRAFT.label.minZoom = v; });
    wireSlider('lbMaxZoom', function (v) { DRAFT.label.maxZoom = v; });
    wireToggle('lbAllowOverlap', function (v) { DRAFT.label.allowOverlap = v; touch(); });
    if (isLineGeom()) wireSegmented('lbPlacement', function (v) { DRAFT.label.placement = v; touch(); });
    wireSlider('lbOffX', function (v) { DRAFT.label.offsetX = v; renderPreview(); });
    wireSlider('lbOffY', function (v) { DRAFT.label.offsetY = v; renderPreview(); });
    wireSlider('lbRotate', function (v) { DRAFT.label.rotate = v; renderPreview(); });
    wireSlider('lbTrack', function (v) { DRAFT.label.letterSpacing = v; renderPreview(); });
    wireSlider('lbMaxWidth', function (v) { DRAFT.label.maxWidth = v; });
    ['lbSize', 'lbOpacity', 'lbHaloWidth', 'lbMinZoom', 'lbMaxZoom',
     'lbOffX', 'lbOffY', 'lbRotate', 'lbTrack', 'lbMaxWidth'].forEach(function (id) {
      var el = document.getElementById(id);
      if (el) el.addEventListener('input', touch);
    });
  }

  function isLineGeom() {
    return SEL && (SEL.geometryType === 'LINESTRING' || SEL.geometryType === 'MULTILINESTRING');
  }

  /* ==================================================================
     ADVANCED PANE
     ================================================================== */

  function renderAdvancedPane() {
    var pane = document.getElementById('paneAdvanced');
    pane.innerHTML =
      '<div class="sect"><div class="sect-t">Zoom visibility<span class="sd">— hide the whole layer outside this range</span></div>' +
      '<div class="fgrid">' +
        '<div class="fld"><label>Minimum zoom</label>' + slider('advMin', 0, 20, 1, DRAFT.minZoom) + '</div>' +
        '<div class="fld"><label>Maximum zoom</label>' + slider('advMax', 4, 24, 1, DRAFT.maxZoom) + '</div>' +
      '</div></div>';
    wireSlider('advMin', function (v) { DRAFT.minZoom = v; });
    wireSlider('advMax', function (v) { DRAFT.maxZoom = v; });
    document.getElementById('advMin').addEventListener('input', touch);
    document.getElementById('advMax').addEventListener('input', touch);
  }

  /* ------------------------------------------------------------------
     Rail swatch refresh — cheap enough to call after every colour edit
     without a full list re-render.
     ------------------------------------------------------------------ */
  function refreshRailSwatch() {
    if (!SEL) return;
    var row = document.querySelector('.lrow[data-key="' + SEL.key.replace(/"/g, '') + '"] .sw');
    if (row) row.innerHTML = swatchSvg(DRAFT, SEL.geometryType, 26);
  }

  /* ==================================================================
     PREVIEW RENDERING (shared by rail swatches, the stage and templates)
     ================================================================== */

  /** A small representative colour, for a swatch or a template thumb. */
  function repColor(s) {
    var c = (s && s.color) || {};
    if (c.mode === 'SINGLE' || !c.mode) return c.value || '#3887be';
    if (c.mode === 'CATEGORY') return (c.categories && c.categories[0] && c.categories[0].color) || c.fallback || '#9aa0a6';
    if (c.mode === 'RANGE') return (c.ranges && c.ranges[0] && c.ranges[0].color) || c.fallback || '#9aa0a6';
    var stops = c.gradient && c.gradient.stops;
    return (stops && stops[Math.floor(stops.length / 2)] && stops[Math.floor(stops.length / 2)].color) || '#9aa0a6';
  }

  function swatchSvg(style, geom, size) {
    var s = style || defaultStyle();
    if (geom === 'POLYGON') {
      var f = s.fill || {};
      return '<svg viewBox="0 0 32 32" width="' + size + '" height="' + size + '">' +
        '<rect x="4" y="4" width="24" height="24" rx="5" fill="' + repColor(s) + '" fill-opacity="' + (f.opacity == null ? 0.35 : f.opacity) + '" ' +
        'stroke="' + ((f.outline && f.outline.on) ? f.outline.color : 'none') + '" stroke-width="1.6"/></svg>';
    }
    if (geom === 'POINT') {
      var p = s.point || {};
      if (p.mode === 'ICON') {
        return '<svg viewBox="0 0 32 32" width="' + size + '" height="' + size + '">' +
          KLSymbols.group(p.icon || 'circle', repColor(s), (p.stroke && p.stroke.color) || '#fff', (p.stroke && p.stroke.width) || 0) + '</svg>';
      }
      return '<svg viewBox="0 0 32 32" width="' + size + '" height="' + size + '">' +
        '<circle cx="16" cy="16" r="10" fill="' + repColor(s) + '" stroke="' + ((p.stroke && p.stroke.color) || '#fff') + '" ' +
        'stroke-width="' + ((p.stroke && p.stroke.width) || 0) + '"/></svg>';
    }
    var l = s.line || {};
    return '<svg viewBox="0 0 32 32" width="' + size + '" height="' + size + '">' +
      '<path d="M4 24 12 10 20 20 28 8" fill="none" stroke="' + repColor(s) + '" stroke-width="' +
      clamp((l.width || 3) * 0.9, 2, 6) + '" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  }

  /**
   * The full stage preview.
   *
   * Purely illustrative — not a map render — but built from the exact
   * same style document the viewer paints from, so a category list, a
   * ramp or a dash pattern set here shows the same colours and rhythm
   * the map will use.
   */
  function renderPreview() {
    var host = document.getElementById('preview');
    if (!host || !SEL) return;
    var geom = SEL.geometryType;
    var s = DRAFT;
    var html;
    if (geom === 'POLYGON') html = previewPolygon(s);
    else if (geom === 'POINT') html = previewPoint(s);
    else html = previewLine(s);
    host.innerHTML = html;
  }

  function sampleColors(s, n) {
    var c = s.color;
    var out = [];
    if (c.mode === 'SINGLE' || !c.attribute) { for (var i = 0; i < n; i++) out.push(c.value); return out; }
    if (c.mode === 'CATEGORY') {
      var cats = c.categories.length ? c.categories : [{ color: c.fallback }];
      for (var j = 0; j < n; j++) out.push(cats[j % cats.length].color);
      return out;
    }
    if (c.mode === 'RANGE') {
      var rs = c.ranges.length ? c.ranges : [{ color: c.fallback }];
      for (var k = 0; k < n; k++) out.push(rs[k % rs.length].color);
      return out;
    }
    var stops = c.gradient.stops.slice().sort(function (a, b) { return a.at - b.at; });
    for (var m = 0; m < n; m++) {
      var t = n === 1 ? 0.5 : m / (n - 1);
      out.push(stopColorAt(stops, t));
    }
    return out;
  }

  function stopColorAt(stops, t) {
    if (!stops.length) return '#9aa0a6';
    if (t <= stops[0].at) return stops[0].color;
    for (var i = 1; i < stops.length; i++) {
      if (t <= stops[i].at) return stops[i].color; // nearest, for a light preview
    }
    return stops[stops.length - 1].color;
  }

  function previewLine(s) {
    var l = s.line, lb = s.label;
    var colors = sampleColors(s, 3);
    var dashMap = { SOLID: null, DASH: [8, 6], DOT: [1.5, 5], DASH_DOT: [10, 4, 1.5, 4], LONG_DASH: [14, 6], RAIL: [3, 3.4] };
    var dash = dashMap[l.dash];
    var w = clamp(l.width * 2.1, 2, 30);
    var paths = [
      'M20 130 100 55 190 100 270 40 340 90',
      'M20 100 100 150 190 60 270 130 340 60',
      'M20 60 100 100 190 40 270 90 340 130'
    ];
    var n = colors.length === 1 ? 1 : 3;
    var body = '';
    for (var i = 0; i < n; i++) {
      var col = colors[i % colors.length];
      var out = l.outline.on ? '<path d="' + paths[i] + '" fill="none" stroke="' + l.outline.color + '" ' +
        'stroke-width="' + (w + l.outline.width * 4) + '" stroke-linecap="' + l.cap + '" stroke-linejoin="' + l.join + '"/>' : '';
      var dashAttr = dash ? ' stroke-dasharray="' + dash.map(function (d) { return d * (w / 6); }).join(',') + '"' : '';
      body += out + '<path d="' + paths[i] + '" fill="none" stroke="' + col + '" stroke-width="' + w +
        '" stroke-opacity="' + l.opacity + '" stroke-linecap="' + l.cap + '" stroke-linejoin="' + l.join + '"' + dashAttr + '/>';
      if (lb.on) {
        var pt = samplePoint(paths[i]);
        body += labelSvg(lb, pt.x, pt.y, sampleLabelText(i));
      }
    }
    return '<svg viewBox="0 0 360 168">' + body + '</svg>';
  }

  function previewPoint(s) {
    var p = s.point, lb = s.label;
    var colors = sampleColors(s, 5);
    var pos = [[54, 90], [120, 46], [186, 112], [252, 58], [312, 96]];
    var body = '';
    colors.forEach(function (col, i) {
      var xy = pos[i % pos.length];
      if (p.mode === 'ICON') {
        var size = 20 + p.iconSize * 14;
        body += '<g transform="translate(' + (xy[0] - size / 2) + ',' + (xy[1] - size / 2) + ') rotate(' + p.iconRotate + ' ' + size / 2 + ' ' + size / 2 + ')">' +
          '<svg width="' + size + '" height="' + size + '" viewBox="0 0 32 32">' +
          KLSymbols.group(p.icon, col, p.stroke.color, p.stroke.width) + '</svg></g>';
      } else {
        var r = 6 + p.radius * 1.3;
        body += '<circle cx="' + xy[0] + '" cy="' + xy[1] + '" r="' + r + '" fill="' + col + '" fill-opacity="' + p.opacity +
          '" stroke="' + p.stroke.color + '" stroke-width="' + p.stroke.width + '"/>';
      }
      // Baseline just clear of the marker; the style's own offset moves it
      // from there, so the sliders read the same way they will on the map.
      if (lb.on) body += labelSvg(lb, xy[0], xy[1] + (p.mode === 'ICON' ? 6 : 5), sampleLabelText(i));
    });
    return '<svg viewBox="0 0 360 168">' + body + '</svg>';
  }

  function previewPolygon(s) {
    var f = s.fill, lb = s.label;
    var colors = sampleColors(s, 3);
    var shapes = [
      'M40 40 160 30 175 110 60 130Z',
      'M180 55 300 30 330 110 210 130Z',
      'M90 130 260 130 250 155 100 155Z'
    ];
    var n = colors.length === 1 ? 1 : 3;
    var body = '';
    var patId = 'pat' + Math.random().toString(36).slice(2, 8);
    var pattern = '';
    if (f.pattern !== 'NONE' && colors.length) {
      pattern = patternDef(patId, f.pattern, colors[0]);
    }
    for (var i = 0; i < n; i++) {
      var fillRef = (f.pattern !== 'NONE') ? 'url(#' + patId + ')' : colors[i % colors.length];
      body += '<path d="' + shapes[i] + '" fill="' + (f.pattern !== 'NONE' ? colors[i % colors.length] : fillRef) +
        '" fill-opacity="' + f.opacity + '"/>';
      if (f.pattern !== 'NONE') body += '<path d="' + shapes[i] + '" fill="url(#' + patId + ')"/>';
      // Width zero is the same as "no outline" here, exactly as it is on
      // the map — the preview has to agree with what saving will do.
      if (f.outline.on && f.outline.width > 0) {
        body += '<path d="' + shapes[i] + '" fill="none" stroke="' + f.outline.color +
          '" stroke-width="' + f.outline.width + '"' + dashAttr(f.outline.dash) + '/>';
      }
      if (lb.on) { var c = centroidOf(shapes[i]); body += labelSvg(lb, c.x, c.y, sampleLabelText(i)); }
    }
    return '<svg viewBox="0 0 360 168">' + (pattern ? '<defs>' + pattern + '</defs>' : '') + body + '</svg>';
  }

  function patternDef(id, kind, color) {
    if (kind === 'DOTS') return '<pattern id="' + id + '" width="10" height="10" patternUnits="userSpaceOnUse">' +
      '<circle cx="2.5" cy="2.5" r="1.3" fill="' + color + '"/></pattern>';
    if (kind === 'CROSS') return '<pattern id="' + id + '" width="9" height="9" patternUnits="userSpaceOnUse">' +
      '<path d="M0 0 9 9M9 0 0 9" stroke="' + color + '" stroke-width="1"/></pattern>';
    return '<pattern id="' + id + '" width="8" height="8" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">' +
      '<line x1="0" y1="0" x2="0" y2="8" stroke="' + color + '" stroke-width="2.4"/></pattern>';
  }

  /** The rough midpoint of a preview path, for planting a sample label near it. */
  function samplePoint(d) {
    var coords = d.replace('M', '').trim().split(/\s+/).map(Number);
    var midI = Math.floor(coords.length / 4) * 2;
    return { x: coords[midI] || coords[0], y: (coords[midI + 1] || coords[1]) - 14 };
  }

  function centroidOf(d) {
    var coords = d.replace('M', '').replace('Z', '').trim().split(/\s+/).map(Number);
    var sx = 0, sy = 0, n = 0;
    for (var i = 0; i < coords.length; i += 2) { sx += coords[i]; sy += coords[i + 1]; n++; }
    return { x: sx / n, y: sy / n };
  }

  function sampleLabelText(i) {
    var lb = DRAFT.label;
    var samples = ['128', 'SH-42', '3.6'];
    var base = samples[i % samples.length];
    if (lb.decimals != null && !isNaN(Number(base))) base = Number(base).toFixed(lb.decimals);
    return base;
  }

  function labelSvg(lb, x, y, text) {
    var full = (lb.prefix || '') + text + (lb.suffix || '');
    var cased = lb.transform === 'uppercase' ? full.toUpperCase() : (lb.transform === 'lowercase' ? full.toLowerCase() : full);
    var weight = lb.font === 'BOLD' ? '700' : (lb.font === 'SEMIBOLD' ? '600' : '400');
    // MapLibre's text-offset is in ems, so the preview converts with the
    // label's own size rather than treating the number as pixels — otherwise
    // the offset sliders would move the text by a different amount here than
    // they do on the map.
    var px = x + (lb.offsetX || 0) * lb.size;
    var py = y + (lb.offsetY || 0) * lb.size;
    var ls = ((lb.letterSpacing || 0) * lb.size) + 'px';
    var rot = lb.rotate ? ' transform="rotate(' + lb.rotate + ' ' + px + ' ' + py + ')"' : '';
    return '<text x="' + px + '" y="' + py + '" font-family="Inter,sans-serif" font-weight="' + weight +
      '" font-size="' + lb.size + '" letter-spacing="' + ls + '" text-anchor="middle"' + rot + ' ' +
      'fill="' + lb.color + '" fill-opacity="' + lb.opacity + '" ' +
      'stroke="' + lb.halo.color + '" stroke-width="' + (lb.halo.width * 2.2) + '" paint-order="stroke" ' +
      'stroke-linejoin="round">' + esc(cased) + '</text>';
  }

  /* ==================================================================
     SAVE / RESET
     ================================================================== */

  function saveLayer() {
    var btn = document.getElementById('btnSave');
    btn.disabled = true;
    api('/api/layer-styles/' + encodeURIComponent(SEL.key), { style: DRAFT })
      .then(function (d) {
        DIRTY = false;
        msg('Style saved for "' + SEL.name + '".', true);
        return load();
      })
      .then(function () { if (SEL) { DRAFT = SEL.style ? merge(defaultStyle(SEL), SEL.style) : defaultStyle(SEL); renderStage(); } })
      .catch(function (e) { msg('Could not save: ' + e.message); btn.disabled = false; });
  }

  function resetLayer() {
    if (!confirm('Put "' + SEL.name + '" back to its built-in look? This discards its saved style.')) return;
    api('/api/layer-styles/' + encodeURIComponent(SEL.key), null, 'DELETE')
      .then(function () { msg('"' + SEL.name + '" reset to its built-in look.', true); return load(); })
      .then(function () { if (SEL) { DRAFT = SEL.style ? merge(defaultStyle(SEL), SEL.style) : defaultStyle(SEL); DIRTY = false; renderStage(); } })
      .catch(function (e) { msg('Could not reset: ' + e.message); });
  }

  /* ==================================================================
     TEMPLATES
     ================================================================== */

  function openSave() {
    document.getElementById('tplName').value = '';
    document.getElementById('tplDesc').value = '';
    document.getElementById('tplScope').value = scopeOf(SEL.geometryType);
    document.getElementById('saveVeil').classList.add('on');
  }
  function closeSave() { document.getElementById('saveVeil').classList.remove('on'); }
  function scopeOf(g) { return g === 'POLYGON' ? 'POLYGON' : (g === 'POINT' ? 'POINT' : 'LINE'); }

  function doSaveTemplate() {
    var name = document.getElementById('tplName').value.trim();
    if (!name) { msg('Give the template a name.'); return; }
    var btn = document.getElementById('tplSave');
    btn.disabled = true;
    api('/api/layer-styles/templates', {
      name: name, description: document.getElementById('tplDesc').value.trim(),
      scope: document.getElementById('tplScope').value, style: DRAFT
    }, 'POST').then(function () {
      msg('Template "' + name + '" saved.', true);
      closeSave();
      return load();
    }).catch(function (e) { msg('Could not save template: ' + e.message); }).then(function () { btn.disabled = false; });
  }

  function openTemplates() {
    TPL_PICK = null;
    renderTemplateGallery();
    document.getElementById('tplVeil').classList.add('on');
  }
  function closeTemplates() { document.getElementById('tplVeil').classList.remove('on'); }

  function renderTemplateGallery() {
    var scope = scopeOf(SEL.geometryType);
    var relevant = TEMPLATES.filter(function (t) { return t.scope === 'ANY' || t.scope === scope; });
    var built = relevant.filter(function (t) { return t.builtIn; });
    var mine = relevant.filter(function (t) { return !t.builtIn; });
    var body = document.getElementById('tplBody');
    body.innerHTML =
      (built.length ? '<div class="tplsec">Built-in presets</div><div class="tplgrid">' + built.map(tplCard).join('') + '</div>' : '') +
      '<div class="tplsec">Your templates</div>' +
      (mine.length ? '<div class="tplgrid">' + mine.map(tplCard).join('') + '</div>'
                   : '<div class="hint" style="padding:6px 2px 14px">None yet — style a layer the way you like it, then "Save as template".</div>') +
      '<div class="sect"><div class="sect-t">Apply to</div><div class="applyto" id="applyTo"></div></div>';
    $all('.tpl', body).forEach(function (el) {
      el.addEventListener('click', function () {
        TPL_PICK = el.dataset.k;
        $all('.tpl', body).forEach(function (x) { x.classList.toggle('on', x === el); });
        renderApplyTo();
      });
    });
    document.getElementById('tplDel').style.visibility = 'hidden';
    renderApplyTo();
  }

  function tplCard(t) {
    var thumb = swatchSvg(t.style, t.scope === 'POLYGON' ? 'POLYGON' : (t.scope === 'POINT' ? 'POINT' : 'LINESTRING'), 66);
    return '<div class="tpl' + (TPL_PICK === t.key ? ' on' : '') + '" data-k="' + esc(t.key) + '">' +
      '<div class="th" style="display:flex;align-items:center;justify-content:center">' + thumb + '</div>' +
      '<div class="tb"><div class="tt">' + esc(t.name) + (t.builtIn ? '' : '<span class="tg">yours</span>') + '</div>' +
      (t.description ? '<div class="td">' + esc(t.description) + '</div>' : '') + '</div></div>';
  }

  function renderApplyTo() {
    var host = document.getElementById('applyTo');
    var folderLayers = LAYERS.filter(function (l) { return l.folder === SEL.folder; }).map(function (l) { return l.key; });
    host.innerHTML =
      '<div class="pick">' +
      '<label class="chk"><input type="radio" name="applyScope" value="this" checked> Just "' + esc(SEL.name) + '"</label>' +
      '<label class="chk"><input type="radio" name="applyScope" value="folder"> Every layer in ' + esc(SEL.folder) + ' (' + folderLayers.length + ')</label>' +
      '<label class="chk"><input type="radio" name="applyScope" value="all"> Every stylable layer (' + LAYERS.length + ')</label>' +
      '</div>';
    var tpl = TEMPLATES.filter(function (t) { return t.key === TPL_PICK; })[0];
    document.getElementById('tplDel').style.visibility = (tpl && !tpl.builtIn) ? 'visible' : 'hidden';
    document.getElementById('tplDel').onclick = function () {
      if (!tpl || tpl.builtIn) return;
      if (!confirm('Delete the template "' + tpl.name + '"?')) return;
      api('/api/layer-styles/templates/' + encodeURIComponent(tpl.key), null, 'DELETE')
        .then(function () { TPL_PICK = null; return load(); })
        .then(renderTemplateGallery)
        .catch(function (e) { msg('Could not delete: ' + e.message); });
    };
  }

  function doApplyTemplate() {
    if (!TPL_PICK) { msg('Pick a template first.', false, document.getElementById('tplMsg')); return; }
    var scope = $('input[name=applyScope]:checked').value;
    var keys = scope === 'this' ? [SEL.key]
             : scope === 'folder' ? LAYERS.filter(function (l) { return l.folder === SEL.folder; }).map(function (l) { return l.key; })
             : LAYERS.map(function (l) { return l.key; });
    var btn = document.getElementById('tplApply');
    btn.disabled = true;
    api('/api/layer-styles/apply', { templateKey: TPL_PICK, layerKeys: keys }, 'POST')
      .then(function (d) {
        msg('Applied "' + d.template + '" to ' + d.applied.length + ' layer' + (d.applied.length === 1 ? '' : 's') + '.', true);
        closeTemplates();
        return load();
      })
      .then(function () { if (SEL) { var still = LAYERS.filter(function (l) { return l.key === SEL.key; })[0]; if (still) { SEL = still; DRAFT = merge(defaultStyle(still), SEL.style || {}); DIRTY = false; renderStage(); } } })
      .catch(function (e) { msg('Could not apply: ' + e.message); })
      .then(function () { btn.disabled = false; });
  }

  /* ------------------------------------------------------------------
     Wiring
     ------------------------------------------------------------------ */

  document.getElementById('q').addEventListener('input', renderList);
  document.getElementById('tplSave').addEventListener('click', doSaveTemplate);
  document.getElementById('tplApply').addEventListener('click', doApplyTemplate);

  window.SM = { closeTemplates: closeTemplates, closeSave: closeSave };

  document.addEventListener('DOMContentLoaded', load);
})();
