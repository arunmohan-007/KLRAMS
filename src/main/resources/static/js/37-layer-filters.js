/* ============================================================
   KLRAMS viewer · 37-layer-filters.js
   Attribute filters for the three layer families the Filter panel
   never covered: the Administrative boundary folder (district and
   constituency), the user layers created in Layer Management, and the
   temporary layers someone drops in to look at once.

   Why they were missing
   ---------------------
   Every other section of the Filter panel is markup in map.html with a
   hand-written module behind it, and each one knows its layer's schema
   at build time. These three do not have one: a boundary's columns are
   whatever shapefile the RMMS cell last uploaded, and a user layer does
   not exist until somebody creates it. So this file builds its sections
   from the data instead — attribute list, operators and value lists all
   discovered — and renders them into #fsecExtra.

   These filters are INDEPENDENT of the Road Network filter. NET_SCOPE
   deliberately does not reach these layers (scopePropFor returns null
   for every id here), because none of them is road-linked: a district
   polygon and an imported survey layer have no section label to scope
   by. Filtering them is therefore its own question, asked here.

   Two ways to apply one filter
   ----------------------------
   A boundary is a GeoJSON source, so its features carry real properties
   and the conditions compile straight to a MapLibre filter expression.
   A user layer in tile mode does not: UserLayerTileService ships the
   whole attribute bag as ONE `attrs` JSON string, because MVT properties
   must be flat scalars — and no expression can read inside a string. So
   the match is made here against /api/layer-data/{id}/attrs (the bags
   without geometry, the user-layer twin of /api/roads/index) and the map
   layer is filtered by the matching row ids, which the tile does carry
   flat. ?tiles=0 puts the same layer on a GeoJSON source, where the
   expression path works, and that is what it uses.
   ============================================================ */
(function () {
  'use strict';

  /* Every section on screen, in render order. */
  var TARGETS = [];
  /* Section key -> {rows:[{attr,op,val}], mode:'all'|'any'} — kept outside the
     target so a re-render (a new user layer appears) does not drop a filter
     somebody has already set. */
  var STATE = {};
  /* Section key -> promise of its attribute bags, fetched at most once. */
  var BAGS = {};
  /* Section key -> those bags once the promise has actually resolved. Export
     asks the same question this panel does, but from a synchronous collect(),
     so it needs an answer that is already in hand rather than a promise. */
  var READY = {};

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function state(key) {
    if (!STATE[key]) STATE[key] = { rows: [], mode: 'all' };
    return STATE[key];
  }

  /* ------------------------------------------------------------------
     Attribute metadata, discovered from the rows
     ------------------------------------------------------------------ */

  /**
   * Column list for a set of property bags: which columns exist, whether each
   * is numeric, and the distinct values it holds.
   *
   * Numeric means EVERY non-empty value parses as a number — one stray "N/A"
   * makes the column text, which is the right answer: offering ">" on a column
   * that cannot be compared is worse than offering "contains" on one that can.
   */
  function metaFrom(bags) {
    var out = {};
    bags.forEach(function (b) {
      Object.keys(b.p || {}).forEach(function (k) {
        var v = b.p[k];
        if (v == null || String(v).trim() === '') return;
        var m = out[k] || (out[k] = { numeric: true, set: {} });
        if (isNaN(+v)) m.numeric = false;
        /* A column with thousands of distinct values is a free-text note, not
           something anyone picks from a list. Stop collecting past a sane cap
           and the row falls back to a typed value. */
        if (Object.keys(m.set).length < 600) m.set[String(v)] = 1;
      });
    });
    Object.keys(out).forEach(function (k) {
      var m = out[k];
      m.values = Object.keys(m.set).sort(function (a, b) {
        return a.localeCompare(b, undefined, { numeric: true });
      });
      delete m.set;
    });
    return out;
  }

  /* ------------------------------------------------------------------
     Conditions
     ------------------------------------------------------------------ */

  function vals(f) {
    return String(f.val == null ? '' : f.val).split(',')
      .map(function (s) { return s.trim(); })
      .filter(function (s) { return s !== ''; });
  }

  /** Does one property bag satisfy one condition? */
  function rowMatches(p, r, meta) {
    var m = (meta && meta[r.attr]) || {};
    var raw = p[r.attr];
    if (raw == null || String(raw) === '') return false;
    if (m.numeric) {
      var v = +raw;
      if (r.op === '=') {
        return vals(r).map(Number).some(function (n) { return !isNaN(n) && v === n; });
      }
      var c = +r.val;
      if (isNaN(c)) return false;
      switch (r.op) {
        case '>': return v > c;
        case '>=': return v >= c;
        case '<': return v < c;
        case '<=': return v <= c;
        default: return v === c;
      }
    }
    var s = String(raw);
    if (r.op === 'contains') return s.toLowerCase().indexOf(String(r.val).toLowerCase()) >= 0;
    return vals(r).indexOf(s) >= 0;
  }

  /** The conditions worth evaluating — a half-filled "+ Add condition" row is not one. */
  function liveRows(st) {
    return st.rows.filter(function (f) { return f.attr && f.val !== ''; });
  }

  function bagMatches(p, rows, mode, meta) {
    var t = rows.map(function (r) { return rowMatches(p, r, meta); });
    return mode === 'all' ? t.every(Boolean) : t.some(Boolean);
  }

  /**
   * The conditions as one MapLibre filter expression.
   *
   * Only usable where the features carry their attributes as real properties —
   * a GeoJSON source. Returns null when there is nothing to filter by, which is
   * how setFilter is told to show everything again.
   */
  function filterExpr(rows, mode, meta) {
    if (!rows.length) return null;
    var parts = rows.map(function (f) {
      var m = (meta && meta[f.attr]) || {};
      if (m.numeric) {
        if (f.op === '=') {
          var nums = vals(f).map(Number).filter(function (n) { return !isNaN(n); });
          return ['in', ['to-number', ['coalesce', ['get', f.attr], -999999]], ['literal', nums]];
        }
        return [f.op === '=' ? '==' : f.op,
                ['to-number', ['coalesce', ['get', f.attr], -999999]], +f.val];
      }
      if (f.op === 'contains') {
        return ['in', f.val, ['to-string', ['coalesce', ['get', f.attr], '']]];
      }
      return ['in', ['to-string', ['coalesce', ['get', f.attr], '']], ['literal', vals(f)]];
    });
    return [mode === 'all' ? 'all' : 'any'].concat(parts);
  }

  /* ------------------------------------------------------------------
     The sections
     ------------------------------------------------------------------ */

  /**
   * A boundary section. Its data is the FeatureCollection 04-geo-helpers
   * already loaded (window.BOUNDARY_DATA), so nothing is downloaded twice, and
   * ensureBoundary() is asked to load it if the layer has never been switched
   * on in this session.
   */
  function boundaryTarget(type, name, layerIds, toggle) {
    return {
      key: 'b_' + type,
      name: name,
      toggle: toggle,
      lockName: name,
      layers: layerIds,
      /* Always the expression path: a boundary is drawn from a GeoJSON source
         in both render modes, so its properties are always readable. */
      byExpression: true,
      bags: function () {
        var take = function () {
          var gj = (window.BOUNDARY_DATA || {})[type];
          return (((gj || {}).features) || []).map(function (f, i) {
            return { id: i, p: f.properties || {} };
          });
        };
        if (take().length) return Promise.resolve(take());
        if (typeof ensureBoundary !== 'function') return Promise.resolve([]);
        return ensureBoundary(type).then(take, function () { return []; });
      }
    };
  }

  function userTarget(l) {
    return {
      key: 'u_' + l.id,
      name: l.name + (l.temporary ? ' · temporary' : ''),
      toggle: 'showUL' + l.id,
      lockName: l.name,
      layers: ['ul-' + l.id + '-fill', 'ul-' + l.id + '-line', 'ul-' + l.id + '-pt'],
      /* Tile mode cannot read inside the tile's `attrs` string, so it filters by
         matching row id instead; ?tiles=0 draws the same layer from GeoJSON,
         where the properties are flat and the expression works. */
      byExpression: !(typeof TILES_ON !== 'undefined' && TILES_ON),
      bags: function () {
        return fetch('/api/layer-data/' + l.id + '/attrs', { credentials: 'same-origin' })
          .then(function (r) { return r.ok ? r.json() : []; })
          .then(function (list) {
            if (typeof list === 'string') list = JSON.parse(list);
            return (list || []).map(function (r) { return { id: r.id, p: r.a || {} }; });
          })
          .catch(function () { return []; });
      }
    };
  }

  /**
   * The bags for one section, fetched at most once per section.
   *
   * An EMPTY answer is deliberately not cached. "No rows" is almost never this
   * layer's schema — it is the boundary document not having arrived yet, or a
   * layer whose data is still being imported — and caching it would leave the
   * section permanently insisting the layer has nothing to filter on, long
   * after the data turned up. Re-asking costs one request, and only when
   * somebody opens the section again.
   */
  function bagsOf(t) {
    if (!BAGS[t.key]) {
      BAGS[t.key] = t.bags().then(function (bags) {
        bags = bags || [];
        if (bags.length) READY[t.key] = bags;
        else delete BAGS[t.key];
        return bags;
      }, function () { delete BAGS[t.key]; return []; });
    }
    /* The column list is derived for the target the CALLER holds, on every
       call, rather than once inside the fetch. A section is rebuilt whenever
       the layer list changes, which makes a new target object under the same
       key — and the second one would otherwise be handed the first one's
       cached promise and never have its own meta filled in, so it rendered
       "this layer has no columns" over a layer whose columns were right there.
       An empty answer never clears a list already worked out: two loads of the
       same boundary can be in flight at once and the one that started before
       the document arrived resolves with nothing. */
    return BAGS[t.key].then(function (bags) {
      if (bags.length || !t.meta) t.meta = metaFrom(bags);
      return bags;
    });
  }

  /* ------------------------------------------------------------------
     Applying
     ------------------------------------------------------------------ */

  /* Layer id -> the filter it was built with, captured the first time we touch
     it. A user layer's three paint layers are told apart ONLY by their filter
     ("draw the polygons", "draw the lines", "draw the points"), so replacing it
     outright does not narrow the layer — it makes every polygon draw a second
     and third time as a line and a circle. The condition is AND-ed onto that
     base instead, the same thing NET_SCOPE does with _scopeBase. */
  var BASE = {};

  function setFilterOn(t, expr) {
    t.layers.forEach(function (id) {
      try {
        if (!map.getLayer(id)) return;      // not built until the layer is on
        if (!(id in BASE)) {
          var f = map.getFilter(id);
          BASE[id] = (f == null) ? null : f;
        }
        var base = BASE[id];
        map.setFilter(id, expr == null ? base : (base == null ? expr : ['all', base, expr]));
      } catch (e) { /* a layer that went away under us */ }
    });
  }

  function info(t, text) {
    var el = document.getElementById('lf-info-' + t.key);
    if (el) el.textContent = text || '';
  }

  function apply(t) {
    var st = state(t.key);
    var rows = liveRows(st);
    var seq = ASEQ[t.key] = (ASEQ[t.key] || 0) + 1;
    if (!rows.length) { setFilterOn(t, null); info(t, ''); return; }
    bagsOf(t).then(function (bags) {
      if (ASEQ[t.key] !== seq) return;       // the conditions moved on
      var hits = bags.filter(function (b) { return bagMatches(b.p, rows, st.mode, t.meta); });
      info(t, hits.length.toLocaleString() + ' of ' + bags.length.toLocaleString() + ' features match');
      if (t.byExpression) {
        setFilterOn(t, filterExpr(rows, st.mode, t.meta));
      } else {
        setFilterOn(t, ['in', ['get', 'id'],
          ['literal', hits.map(function (b) { return b.id; })]]);
      }
    });
  }

  /* ------------------------------------------------------------------
     Cascading value lists
     ------------------------------------------------------------------ */

  /**
   * The values of `attr` still reachable under this section's OTHER conditions
   * (the row whose picker is open is skipped). Same rule the Road Network
   * filter uses, so a second condition only offers choices the first left
   * standing rather than a list that silently matches nothing.
   */
  function valuesFor(t, bags, attr, exceptIdx) {
    var st = state(t.key);
    var others = st.rows.filter(function (f, i) { return i !== exceptIdx && f.attr && f.val !== ''; });
    var set = {};
    bags.forEach(function (b) {
      if (others.length && !bagMatches(b.p, others, st.mode, t.meta)) return;
      var v = b.p[attr];
      if (v == null || String(v).trim() === '') return;
      set[String(v)] = 1;
    });
    return Object.keys(set).sort(function (a, b) {
      return a.localeCompare(b, undefined, { numeric: true });
    });
  }

  /* ------------------------------------------------------------------
     Value picker — the same click-to-choose popup the Road Network
     filter uses (.nvp in app.css), so a typo can narrow the list but
     can never become the filter value.
     ------------------------------------------------------------------ */

  var POP = { key: null, idx: -1, query: '' };

  function closePop() {
    var p = document.getElementById('lfValPop');
    if (p) p.remove();
    POP = { key: null, idx: -1, query: '' };
  }

  function popList(t, f, values) {
    var p = document.getElementById('lfValPop');
    if (!p) return;
    var sel = vals(f).filter(function (v) { return values.indexOf(v) >= 0; });
    var q = POP.query.trim().toLowerCase();
    var items = values.filter(function (v) { return !q || v.toLowerCase().indexOf(q) >= 0; });
    p.querySelector('.nvp-cnt').textContent =
      sel.length + ' selected · ' + values.length + ' value' + (values.length === 1 ? '' : 's');
    p.querySelector('.nvp-list').innerHTML = items.length
      ? items.map(function (v) {
          return '<label class="nvp-it"><span>' + esc(v) + '</span>' +
                 '<input type="checkbox" value="' + esc(v) + '"' +
                 (sel.indexOf(v) >= 0 ? ' checked' : '') + '></label>';
        }).join('')
      : '<div class="nvp-empty">' + (values.length
          ? 'No values match “' + esc(POP.query) + '”.'
          : 'No values left under the other conditions.') + '</div>';
    p.querySelectorAll('.nvp-list input').forEach(function (cb) {
      cb.onchange = function () {
        var chosen = vals(f).filter(function (v) { return values.indexOf(v) >= 0; });
        if (cb.checked) { if (chosen.indexOf(cb.value) < 0) chosen.push(cb.value); }
        else { chosen = chosen.filter(function (v) { return v !== cb.value; }); }
        f.val = chosen.join(', ');
        refreshButton(t, POP.idx, f, values);
        apply(t);
        popList(t, f, values);
      };
    });
  }

  function refreshButton(t, i, f, values) {
    var row = document.querySelectorAll('#lf-rows-' + t.key + ' .frow')[i];
    if (!row) return;
    var btn = row.querySelector('.valbtn');
    if (!btn) return;
    var sel = vals(f);
    btn.querySelector('.vb-txt').textContent = sel.length ? sel.join(', ') : 'Select value(s)…';
    btn.classList.toggle('has', sel.length > 0);
    btn.title = sel.length ? sel.join(', ') : 'Click to choose value(s)';
  }

  function openPop(t, i, f, anchor) {
    bagsOf(t).then(function (bags) {
      var values = valuesFor(t, bags, f.attr, i);
      closePop();
      POP = { key: t.key, idx: i, query: '' };
      var p = document.createElement('div');
      p.className = 'nvp';
      p.id = 'lfValPop';
      p.innerHTML =
        '<div class="nvp-top"><input type="text" class="nvp-q" placeholder="Search values…" autocomplete="off">' +
        '<button type="button" class="nvp-all">All</button>' +
        '<button type="button" class="nvp-clear">Clear</button></div>' +
        '<div class="nvp-list"></div><div class="nvp-cnt"></div>';
      document.body.appendChild(p);
      var r = anchor.getBoundingClientRect();
      var W = Math.max(240, Math.min(360, window.innerWidth - 24));
      p.style.width = W + 'px';
      p.style.left = Math.max(8, Math.min(r.left, window.innerWidth - W - 8)) + 'px';
      p.style.top = (r.bottom + 5) + 'px';
      p.style.maxHeight = Math.max(160, Math.min(320, window.innerHeight - r.bottom - 16)) + 'px';
      p.querySelector('.nvp-clear').onclick = function () {
        f.val = ''; refreshButton(t, i, f, values); apply(t); popList(t, f, values);
      };
      p.querySelector('.nvp-all').onclick = function () {
        f.val = values.join(', '); refreshButton(t, i, f, values); apply(t); popList(t, f, values);
      };
      var q = p.querySelector('.nvp-q');
      q.oninput = function (e) { POP.query = e.target.value; popList(t, f, values); };
      popList(t, f, values);
      setTimeout(function () { try { q.focus(); } catch (e) {} }, 0);
    });
  }

  document.addEventListener('mousedown', function (e) {
    if (POP.idx >= 0 && !e.target.closest('#lfValPop') && !e.target.closest('.valbtn')) closePop();
  }, true);
  document.addEventListener('scroll', function (e) {
    var p = document.getElementById('lfValPop');
    if (p && !(e.target && e.target.nodeType === 1 && p.contains(e.target))) closePop();
  }, true);
  window.addEventListener('resize', closePop);

  /* ------------------------------------------------------------------
     Rendering one section's condition rows
     ------------------------------------------------------------------ */

  /* Section key -> how many renders/applies have been started for it. Both wait
     on a fetch, so two can be in flight at once (opening the section, then
     switching the layer on); without a sequence the SLOWER one wins whether or
     not it is the newer one. Only the latest is allowed to write. */
  var RSEQ = {}, ASEQ = {};

  function renderRows(t) {
    closePop();
    var box = document.getElementById('lf-rows-' + t.key);
    if (!box) return;
    var st = state(t.key);
    var seq = RSEQ[t.key] = (RSEQ[t.key] || 0) + 1;
    box.innerHTML = '';
    bagsOf(t).then(function (bags) {
      if (RSEQ[t.key] !== seq) return;       // a newer render already answered
      var meta = t.meta || {};
      var names = Object.keys(meta).sort();
      if (!names.length) {
        /* Two different answers, and saying the wrong one sends someone
           looking for a missing column when the layer simply has no data in
           it (or has not been switched on yet, which is what loads a
           boundary's document in the first place). */
        box.innerHTML = '<div class="note" style="margin:6px 0 0">' +
          (bags.length
            ? 'Every column of this layer is empty, so there is nothing to filter on.'
            : 'No data loaded for this layer yet — switch it on, or import into it, and reopen this section.') +
          '</div>';
        return;
      }
      box.innerHTML = '';
      st.rows.forEach(function (f, i) {
        if (!f.attr || !meta[f.attr]) { f.attr = names[0]; f.op = '='; f.val = ''; }
        var m = meta[f.attr];
        var row = document.createElement('div');
        row.className = 'frow';
        var attrSel = names.map(function (k) {
          return '<option value="' + esc(k) + '"' + (k === f.attr ? ' selected' : '') + '>' +
                 esc(label(t, k)) + '</option>';
        }).join('');
        var ops = m.numeric ? ['>', '>=', '=', '<=', '<'] : ['=', 'contains'];
        var opSel = ops.map(function (o) {
          return '<option' + (o === f.op ? ' selected' : '') + '>' + o + '</option>';
        }).join('');

        var isBtn = false, valCell;
        if (f.op === '=' && m.values.length) {
          isBtn = true;
          var sel = vals(f);
          valCell = '<button type="button" class="valbtn' + (sel.length ? ' has' : '') + '" title="' +
            (sel.length ? esc(sel.join(', ')) : 'Click to choose value(s)') + '">' +
            '<span class="vb-txt">' + esc(sel.length ? sel.join(', ') : 'Select value(s)…') + '</span>' +
            '<i class="vb-arr">&#9662;</i></button>';
        } else if (m.numeric) {
          valCell = '<input type="number" step="any" value="' + esc(f.val) + '">';
        } else {
          valCell = '<input type="text" value="' + esc(f.val) + '" placeholder="text…">';
        }

        row.innerHTML = '<select>' + attrSel + '</select><select>' + opSel + '</select>' +
                        valCell + '<span class="x" title="Remove">&times;</span>';
        var sels = row.querySelectorAll('select');
        sels[0].onchange = function (e) {
          f.attr = e.target.value; f.op = '='; f.val = ''; renderRows(t); apply(t);
        };
        sels[1].onchange = function (e) {
          f.op = e.target.value; f.val = ''; renderRows(t); apply(t);
        };
        if (isBtn) {
          var btn = row.querySelector('.valbtn');
          btn.onclick = function () {
            if (POP.key === t.key && POP.idx === i) closePop(); else openPop(t, i, f, btn);
          };
        } else {
          row.querySelector('input').oninput = function (e) { f.val = e.target.value; apply(t); };
        }
        row.querySelector('.x').onclick = function () {
          st.rows.splice(i, 1); renderRows(t); apply(t);
        };
        box.appendChild(row);
      });
    });
  }

  /** What to call a column on screen — Layer Management's name for it, if it has one. */
  function label(t, key) {
    if (!window.AttrCatalog || !t.layerKey) return key;
    return AttrCatalog.label(t.layerKey, key) || key;
  }

  /* ------------------------------------------------------------------
     Rendering the panel
     ------------------------------------------------------------------ */

  function sectionHtml(t) {
    return '<details class="flt-sec" id="lf-sec-' + t.key + '">' +
      '<summary>' + esc(t.name) + '</summary>' +
      '<div class="flt-lock">The <b>' + esc(t.lockName) + '</b> layer is off. ' +
      '<button class="btn ghost" data-lf-on="' + t.toggle + '">Turn on layer</button></div>' +
      '<div class="flt-body">' +
        '<div class="eyebrow">Filter by attribute</div>' +
        '<div id="lf-rows-' + t.key + '"></div>' +
        '<div class="row2">' +
          '<button class="btn ghost" data-lf-add="' + t.key + '">+ Add condition</button>' +
          '<button class="btn ghost" data-lf-clear="' + t.key + '">Clear</button>' +
        '</div>' +
        '<div class="row2"><span class="note" style="margin:0">Match</span>' +
          '<span class="segmented">' +
            '<button data-lf-mode="' + t.key + '|all">All</button>' +
            '<button data-lf-mode="' + t.key + '|any">Any</button>' +
          '</span></div>' +
        '<div class="statusline" id="lf-info-' + t.key + '"></div>' +
      '</div></details>';
  }

  function render() {
    var host = document.getElementById('fsecExtra');
    if (!host) return;
    closePop();
    host.innerHTML = TARGETS.map(sectionHtml).join('');

    TARGETS.forEach(function (t) {
      var st = state(t.key);
      var sec = document.getElementById('lf-sec-' + t.key);
      if (!sec) return;
      sec.querySelectorAll('[data-lf-mode]').forEach(function (b) {
        b.classList.toggle('on', b.getAttribute('data-lf-mode') === t.key + '|' + st.mode);
      });
      /* The rows are only built when the section is actually opened: each one
         costs a fetch of that layer's attribute bags, and a viewer with a dozen
         user layers should not pay for twelve of them to draw a closed
         <details>. Already-filtered sections are drawn straight away, so a
         re-render never blanks a filter that is on the map. */
      if (liveRows(st).length) { sec.open = true; renderRows(t); apply(t); }
      sec.addEventListener('toggle', function () { if (sec.open) renderRows(t); });
    });

    host.querySelectorAll('[data-lf-add]').forEach(function (b) {
      b.onclick = function () {
        var t = byKey(b.getAttribute('data-lf-add'));
        state(t.key).rows.push({ attr: '', op: '=', val: '' });
        renderRows(t);
      };
    });
    host.querySelectorAll('[data-lf-clear]').forEach(function (b) {
      b.onclick = function () {
        var t = byKey(b.getAttribute('data-lf-clear'));
        state(t.key).rows = [];
        renderRows(t); apply(t);
      };
    });
    host.querySelectorAll('[data-lf-mode]').forEach(function (b) {
      b.onclick = function () {
        var parts = b.getAttribute('data-lf-mode').split('|');
        var t = byKey(parts[0]);
        state(t.key).mode = parts[1];
        b.parentNode.querySelectorAll('button').forEach(function (x) {
          x.classList.toggle('on', x === b);
        });
        renderRows(t); apply(t);
      };
    });
    host.querySelectorAll('[data-lf-on]').forEach(function (b) {
      b.onclick = function () {
        if (typeof enableLayer === 'function') enableLayer(b.getAttribute('data-lf-on'));
      };
    });

    wireToggles();
    refreshLocks();
  }

  function byKey(k) {
    return TARGETS.filter(function (t) { return t.key === k; })[0];
  }

  /* A section is locked while its layer is off, the same rule every other
     section in the panel follows — and re-applied when the layer comes back on,
     because a user layer's MapLibre layers are only built at that moment and a
     filter set beforehand would otherwise land on nothing. */
  function refreshLocks() {
    TARGETS.forEach(function (t) {
      var sec = document.getElementById('lf-sec-' + t.key);
      var box = document.getElementById(t.toggle);
      if (sec) sec.classList.toggle('locked', !(box && box.checked));
    });
  }

  function wireToggles() {
    TARGETS.forEach(function (t0) {
      var box = document.getElementById(t0.toggle);
      if (!box || box.__lfWired) return;
      box.__lfWired = true;
      /* Resolved by KEY at click time, never captured. The listener is attached
         once per checkbox and outlives every re-render, so a captured target
         object would go stale the first time the layer list changed. */
      var key = t0.key;
      box.addEventListener('change', function () {
        refreshLocks();
        /* The layers are added asynchronously (a boundary is fetched, a user
           layer's source is built on first tick), so re-apply once they can
           exist rather than against a map that has not caught up. */
        if (!box.checked) return;
        setTimeout(function () {
          var t = byKey(key);
          if (!t) return;
          /* Switching a layer on is also what loads it, so a section opened
             beforehand was built from nothing. Re-read it now. */
          var sec = document.getElementById('lf-sec-' + key);
          if (sec && sec.open) renderRows(t);
          apply(t);
        }, 450);
      });
    });
  }

  /* refreshFilterLocks() is what the Filter rail button and every built-in
     section call; extending it keeps this panel's locks in step without
     18-filters.js needing to know these sections exist. */
  (function () {
    var base = window.refreshFilterLocks;
    window.refreshFilterLocks = function () {
      if (typeof base === 'function') base.apply(this, arguments);
      refreshLocks();
    };
  })();

  /* ------------------------------------------------------------------
     Boot
     ------------------------------------------------------------------ */

  function build() {
    var list = [];
    var d = boundaryTarget('district', 'District boundary',
      ['district-fill', 'district-casing', 'district-line', 'district-label'], 'showDist');
    d.layerKey = 'boundary_district';
    var c = boundaryTarget('constituency', 'Constituency boundary',
      ['cons-fill', 'cons-line', 'cons-label'], 'showCons');
    c.layerKey = 'boundary_constituency';
    list.push(d, c);

    var users = [];
    return fetch('/api/layer-data/viewer-layers', { credentials: 'same-origin' })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (res) {
        ((res && res.layers) || []).forEach(function (l) {
          var t = userTarget(l);
          t.layerKey = l.key;
          list.push(t);
          users.push(l);
        });
      })
      .catch(function () { /* the boundary sections still work without them */ })
      .then(function () {
        TARGETS = list;
        render();
        /* Export's layer registry is fixed at load time and these layers are
           not knowable then, so it is told about them here — from the same
           list, in the same order, so a layer's export swatch matches the
           colour 33-user-layers.js gave its switch. */
        if (window.KLExport) KLExport.syncUserLayers(users);
      });
  }

  /** Re-read the layer list — called after an import so a new layer gets a section. */
  function refresh() {
    BAGS = {};
    READY = {};
    /* A layer may have been discarded and re-created since the last render, in
       which case its remembered base filter belongs to a layer that no longer
       exists. Re-capture rather than AND onto a stale one. */
    BASE = {};
    return build();
  }

  /** 04-geo-helpers-boundaries.js calls this when a boundary document arrives. */
  function onBoundaryData(type) {
    var key = 'b_' + type;
    delete BAGS[key];
    delete READY[key];
    var t = byKey(key);
    if (!t) return;
    t.meta = null;
    /* Re-read the section whenever it is OPEN, not only when it already has
       conditions. Someone who opens "District boundary" before the layer has
       ever been switched on is looking at a section built from no document at
       all; the document arriving is exactly the moment that answer changes,
       and without this it would keep saying the layer has nothing to filter on. */
    var sec = document.getElementById('lf-sec-' + key);
    if (sec && sec.open) renderRows(t);
    if (liveRows(state(key)).length) apply(t);
  }

  /* ------------------------------------------------------------------
     What export asks
     ------------------------------------------------------------------ */

  /**
   * Load one section's attribute bags. 29-export.js awaits this in its
   * ensure() phase so that matchIds() below can then answer synchronously,
   * which is the only shape its collect() contract allows.
   */
  function ensureBags(key) {
    var t = byKey(key);
    return t ? bagsOf(t).then(function () {}) : Promise.resolve();
  }

  /**
   * The ids this section's conditions match, or null for "no filter is set" —
   * which export reads as "write the whole layer".
   *
   * An id means whatever that section's bags mean by it: a user layer's row id
   * (the same id its tile and its GeoJSON both carry), and for a boundary the
   * INDEX of the feature in its FeatureCollection, since a boundary is one
   * stored document with no per-feature key. Export filters the very same
   * array those indexes were taken from.
   *
   * Also null when the bags have not loaded, so a caller that skipped
   * ensureBags() exports everything rather than silently exporting nothing.
   */
  function matchIds(key) {
    var t = byKey(key);
    if (!t) return null;
    var st = state(key);
    var rows = liveRows(st);
    if (!rows.length) return null;
    var bags = READY[key];
    if (!bags) return null;
    var out = new Set();
    bags.forEach(function (b) {
      if (bagMatches(b.p, rows, st.mode, t.meta)) out.add(b.id);
    });
    return out;
  }

  window.KLLayerFilters = {
    refresh: refresh, onBoundaryData: onBoundaryData,
    ensureBags: ensureBags, matchIds: matchIds
  };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', build);
  else build();
})();
