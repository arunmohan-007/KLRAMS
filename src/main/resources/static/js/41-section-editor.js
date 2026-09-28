/* ============================================================================
   41-section-editor.js — Section Editor: split, merge and the lineage log

   Splitting or merging a road section rewrites the join key EVERY survey layer
   hangs off, and the chainage frame its rows are measured in. So this screen is
   built around one rule: nothing is written until the operator has read what
   would happen.

   Every action is a two-step. "Check" calls the /preview endpoint, which writes
   nothing and returns the resulting sections, the per-table row counts and the
   warnings. Only then does "Recalculate & Apply" appear, and it sends the same
   values to /apply. There is no path from typing a number to changing data that
   does not pass through a report the operator has seen.

   Three tabs:
     Split    pick a section, pick a chainage (or drop a pin on the map), fill
              in the two halves, check, apply.
     Merge    pick two or more sections, choose which one's value wins per
              attribute, check, apply.
     History  the lineage log, with Undo where it is still available.

   No inline handlers anywhere: the page's CSP has no 'unsafe-inline', so every
   control dispatches through js/00-actions.js via data-act (see CLAUDE.md).
   ========================================================================== */
(function () {
  'use strict';

  var API = '/api/roads/section';

  /* The chainage fields, by SYSTEM ATTRIBUTE NAME. The API speaks these, not the
     road shapefile's truncated column names — a survey return can rename the
     column, and this screen must not care. */
  var A_LABEL   = 'Section Label';
  var A_RD_FROM = 'Road Start Chainage';
  var A_RD_TO   = 'Road End Chainage';
  var A_SEC_FROM= 'Start Chainage';
  var A_SEC_TO  = 'End Chainage';
  var A_LEN     = 'Measured Length';

  /* Live availability of each typed label, keyed by field id. The check is the same one the
     preview enforces, so a field can never read "available" for a label apply would refuse. */
  var labelState = {};
  var labelTimer = {};
  /* The value each field is CURRENTLY asking about. A response is only applied if the field
     still wants that value — see checkLabel. */
  var labelWanted = {};

  var tab = 'split';
  var state = {
    split: { section: '', at: '', first: '', second: '', preview: null, busy: false,
             info: null, attrs: { first: {}, second: {} }, confirming: false, result: null },
    merge: { sections: [], result: '', preview: null, busy: false, attrs: { result: {} },
             confirming: false, result_of: null },
    history: { rows: null, busy: false, error: null },
    /* The derived layers a write invalidates, and how the rebuild is going. */
    rebuild: { busy: false, done: null, error: null, step: '', redrawSegments: false }
  };

  /* ---------- helpers ---------- */

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function num(v) {
    if (v == null || v === '') return null;
    var n = +v;
    return isNaN(n) ? null : n;
  }

  function fmt(v) {
    if (v == null || v === '') return '—';
    var n = +v;
    return isNaN(n) ? esc(v) : (Math.round(n * 1000) / 1000).toLocaleString();
  }

  /* Every response goes through this, not straight to r.json().

     An unauthenticated API call is answered with a 302 to the login page, and fetch FOLLOWS
     that redirect — so the reply is the login HTML with a perfectly successful status, and
     r.json() fails on it with "Unexpected token '<'". That message tells the operator nothing
     about what actually happened, which is that their session ended. */
  function readJson(r) {
    if (r.status === 403) throw new Error('You do not have permission to do this. Previewing is '
      + 'open to administrators; applying a change is restricted to super administrators.');
    if (r.status === 401) throw new Error('Your session has ended. Sign in again, then retry.');
    var ct = r.headers.get('content-type') || '';
    if (ct.indexOf('json') < 0) {
      if (r.redirected || ct.indexOf('html') >= 0)
        throw new Error('Your session has ended — the server answered with the sign-in page. '
          + 'Open KLRAMS in another tab, sign in, then retry. Nothing was changed.');
      throw new Error('The server did not answer with data (HTTP ' + r.status + '). '
        + 'Nothing was changed.');
    }
    return r.json();
  }

  /* A fetch that never reaches the server rejects with a bare TypeError whose message is
     "Failed to fetch" — true, and useless. The usual causes are worth naming. */
  function netError(e) {
    if (e && e.name === 'TypeError')
      return new Error('Could not reach the server. This screen needs the KLRAMS backend — it '
        + 'does not work against a static copy of the page. Check you are on the running '
        + 'application and still signed in. Nothing was changed.');
    return e;
  }

  function post(path, body) {
    return fetch(API + path, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    }).then(readJson).catch(function (e) { throw netError(e); });
  }

  function body() { return document.getElementById('seBody'); }

  /* Ask the server whether a label is free, debounced so a burst of keystrokes is one request.
     `excluding` is the sections this operation consumes — their labels are free to reuse, so
     keeping the parent's label on a half must not read as a clash with itself. */
  function checkLabel(field, value, excluding) {
    var v = (value || '').trim();
    clearTimeout(labelTimer[field]);
    labelWanted[field] = v;
    if (!v) { labelState[field] = null; paintLabel(field); return; }
    labelState[field] = { state: 'checking' };
    paintLabel(field);
    labelTimer[field] = setTimeout(function () {
      var q = '?label=' + encodeURIComponent(v)
            + (excluding || []).filter(Boolean).map(function (e) {
                return '&exclude=' + encodeURIComponent(e);
              }).join('');
      fetch(API + '/label-check' + q).then(readJson).then(function (r) {
        /* Apply only if the field still wants THIS value.

           Debouncing stops a burst of keystrokes becoming a burst of requests, but it cannot
           cancel a request already in flight, so two answers can be outstanding and arrive in
           either order. Comparing against the latest REQUESTED value is what makes that safe.
           Comparing against the last APPLIED value — which this did at first — gets it wrong
           twice over: an early answer lands because nothing has been applied yet, and the later,
           correct answer is then thrown away for disagreeing with it. Picking a real label from
           the autocomplete and editing it down left the field stuck on "already used". */
        if (labelWanted[field] !== v) return;
        labelState[field] = { state: r.available ? 'free' : 'taken', message: r.message,
                              noScope: (excluding || []).filter(Boolean).length === 0 };
        paintLabel(field);
      }).catch(function () {
        labelState[field] = null;
        paintLabel(field);
      });
    }, 350);
  }

  /* Written straight into the hint element rather than by re-rendering: a re-render would
     rebuild the input and take the caret with it, mid-typing. */
  function paintLabel(field) {
    var el = document.getElementById(field + 'Hint');
    if (!el) return;
    var st = labelState[field];
    if (!st) { el.className = 'se-hint'; el.textContent = ''; return; }
    if (st.state === 'checking') { el.className = 'se-hint'; el.textContent = 'Checking…'; return; }
    if (st.state === 'exists') { el.className = 'se-hint se-ok'; el.textContent = '✓ ' + st.message; return; }
    if (st.state === 'missing') { el.className = 'se-hint se-bad'; el.textContent = '✗ ' + st.message; return; }
    el.className = 'se-hint ' + (st.state === 'free' ? 'se-ok' : 'se-bad');
    /* A label can only be judged against the sections this operation CONSUMES, and until the
       operator has named one there are none. Reporting a bare "already used" then sends them
       looking for a clash that does not exist — the usual case is that the label they typed is
       the very section they are about to split. */
    var extra = (st.state === 'taken' && st.noScope)
      ? ' If this is the section you are splitting, enter it in "Section to split" first — a '
        + 'section being split or merged may keep its own label.'
      : '';
    el.textContent = (st.state === 'free' ? '✓ ' : '✗ ') + st.message + extra;
  }

  /* The "Section to split" field is the mirror image: here the label MUST already exist, so the
     same endpoint is read the other way round — in use means found. */
  function checkSectionExists(field, value) {
    var v = (value || '').trim();
    clearTimeout(labelTimer[field]);
    labelWanted[field] = v;
    if (!v) { labelState[field] = null; paintLabel(field); return; }
    labelState[field] = { state: 'checking' };
    paintLabel(field);
    labelTimer[field] = setTimeout(function () {
      fetch(API + '/label-check?label=' + encodeURIComponent(v))
        .then(readJson)
        .then(function (r) {
          if (labelWanted[field] !== v) return;
          var onNetwork = (r.used_by || []).some(function (u) { return u.table === 'roads'; });
          labelState[field] = onNetwork
            ? { state: 'exists', message: 'Found on the road network.' }
            : { state: 'missing', message: r.available
                ? 'No section with this label.'
                : 'Not on the road network — only leftover survey rows reference this label.' };
          paintLabel(field);
        }).catch(function () { labelState[field] = null; paintLabel(field); });
    }, 350);
  }

  function hint(field) { return '<span class="se-hint" id="' + field + 'Hint"></span>'; }

  /* Re-apply the hints after any render, since render() replaces the whole body. */
  function repaintLabels() {
    Object.keys(labelState).forEach(paintLabel);
  }

  /* Every section label on the network, for the pickers. Read from the road
     index the viewer already loads once per page rather than fetched again. */
  function sectionLabels() {
    var out = [];
    try {
      if (typeof ROADS !== 'undefined' && ROADS) out = Object.keys(ROADS);
    } catch (e) { /* index not loaded yet — the free-text field still works */ }
    return out.sort();
  }

  function labelDatalist(id) {
    var opts = sectionLabels().slice(0, 4000).map(function (l) {
      return '<option value="' + esc(l) + '"></option>';
    }).join('');
    return '<datalist id="' + id + '">' + opts + '</datalist>';
  }

  /* ---------- open / close ---------- */

  function open() {
    var s = document.getElementById('sectionEditor');
    if (!s) return;
    ['dashboard', 'reportHub', 'pciScreen', 'condScreen', 'regScreen'].forEach(function (id) {
      var e = document.getElementById(id);
      if (e) e.classList.remove('open');
    });
    s.classList.add('open');
    var fp = document.getElementById('fpanes');
    if (fp) fp.classList.add('hidden');
    document.querySelectorAll('#iconrail .railbtn').forEach(function (b) {
      b.classList.toggle('active', b.dataset.pane === 'sections');
    });
    screenTab(tab);
  }

  function close() {
    var s = document.getElementById('sectionEditor');
    if (s) s.classList.remove('open');
    if (typeof railSyncToPanes === 'function') railSyncToPanes();
  }

  function screenTab(k) {
    tab = k;
    ['split', 'merge', 'history'].forEach(function (t) {
      var b = document.getElementById('seTab_' + t);
      if (b) b.classList.toggle('on', t === k);
    });
    render();
  }

  /* ---------- rendering ---------- */

  function render() {
    var el = body();
    if (!el) return;
    if (tab === 'split') el.innerHTML = renderSplit();
    else if (tab === 'merge') el.innerHTML = renderMerge();
    else el.innerHTML = renderHistory();
    repaintLabels();
    paintChainage();
  }

  function warnBlock(warnings) {
    if (!warnings || !warnings.length) return '';
    return '<div class="se-warns">' + warnings.map(function (w) {
      return '<div class="se-warn se-' + esc(w.severity) + '">'
           + '<span class="se-warn-k">' + esc(w.kind) + '</span>'
           + '<span>' + esc(w.message) + '</span></div>';
    }).join('') + '</div>';
  }

  /* The four chainage fields of one resulting section, as an editable card.
     Start Chainage is shown but never editable: it is 0 on every section in the
     network by definition, and is derived from the length, not typed. */
  function chainageCard(title, plan, prefix, editable) {
    var ro = editable ? '' : ' readonly';
    return ''
      + '<div class="se-card">'
      + '  <div class="se-card-h">' + esc(title) + '</div>'
      + '  <label>' + esc(A_LABEL) + '<input type="text" id="' + prefix + 'Label" value="'
      + esc(plan ? plan[A_LABEL] : '') + '"' + ro + '></label>'
      + '  <label><span class="se-cap">' + esc(A_RD_FROM) + ' <span class="se-u">m</span></span>'
      + '    <input type="number" step="any" id="' + prefix + 'RdFrom" value="'
      + esc(plan ? plan[A_RD_FROM] : '') + '" readonly></label>'
      + '  <label><span class="se-cap">' + esc(A_RD_TO) + ' <span class="se-u">m</span></span>'
      + '    <input type="number" step="any" id="' + prefix + 'RdTo" value="'
      + esc(plan ? plan[A_RD_TO] : '') + '" readonly></label>'
      + '  <label><span class="se-cap">' + esc(A_SEC_FROM) + ' <span class="se-u">m</span></span>'
      + '    <input type="number" id="' + prefix + 'SecFrom" value="0" readonly title="'
      + 'Always 0 — a section&#39;s own chainage starts at its own beginning."></label>'
      + '  <label><span class="se-cap">' + esc(A_SEC_TO) + ' <span class="se-u">m</span></span>'
      + '    <input type="number" step="any" id="' + prefix + 'SecTo" value="'
      + esc(plan ? plan[A_SEC_TO] : '') + '" readonly></label>'
      + '  <label><span class="se-cap">' + esc(A_LEN) + ' <span class="se-u">m</span></span>'
      + '    <input type="number" step="any" id="' + prefix + 'Len" value="'
      + esc(plan ? plan[A_LEN] : '') + '" readonly></label>'
      + '</div>';
  }

  /* The attributes an operator may set on the resulting sections.

     Pre-filled from the section being changed, and every cell editable — a split or merge is
     usually the moment those values need correcting. The label and the four chainage columns
     are absent on purpose: they are computed by the operation, and offering them here would let
     a typed value contradict the arithmetic that moved the data. */
  function attrGrid(attributes, sources, targets, which) {
    if (!attributes || !attributes.length) return '';
    var head = '<tr><th>Attribute</th>'
      + sources.map(function (sc) { return '<th>' + esc(sc) + '</th>'; }).join('')
      + targets.map(function (t) { return '<th>' + esc(t.title) + '</th>'; }).join('') + '</tr>';

    var rows = attributes.map(function (a) {
      var cells = sources.map(function (sc) {
        var v = (a.values || {})[sc];
        return '<td class="se-was">' + (v == null || v === '' ? '-' : esc(v)) + '</td>';
      }).join('');
      cells += targets.map(function (t) {
        var store = which[t.key] || {};
        var v = store[a.column];
        if (v === undefined) {
          for (var i = 0; i < sources.length; i++) {
            var sv = (a.values || {})[sources[i]];
            if (sv != null && sv !== '') { v = sv; break; }
          }
          if (v === undefined) v = '';
        }
        return '<td><input type="text" class="se-attr" value="' + esc(v) + '" '
          + 'data-which="' + esc(t.key) + '" data-column="' + esc(a.column) + '" '
          + 'data-input="klValue" data-args="seSetAttr"></td>';
      }).join('');
      return '<tr><td class="se-attr-n">' + esc(a.attribute) + '</td>' + cells + '</tr>';
    }).join('');

    var pick = sources.length > 1
      ? '<div class="se-row">' + sources.map(function (sc) {
          return '<button class="se-ghost" data-act="seUseAll" data-args=' + KLARG(sc) + '>'
               + 'Use ' + esc(sc) + ' for all</button>';
        }).join('') + '</div>'
      : '';

    return '<div class="se-attrs"><div class="se-card-h">Attributes of the resulting section'
      + (targets.length > 1 ? 's' : '') + '</div>'
      + '<div class="se-sub">Pre-filled from the section' + (sources.length > 1 ? 's' : '')
      + ' being changed. Edit any value before applying.</div>'
      + pick
      + '<table class="se-table se-attr-table"><thead>' + head + '</thead><tbody>'
      + rows + '</tbody></table></div>';
  }

  /* data-args must be a JSON array in an HTML attribute; KLAct.args does the escaping. */
  function KLARG(v) {
    return (window.KLAct && KLAct.args) ? KLAct.args(v)
      : '\'' + JSON.stringify([v]).replace(/'/g, '&#39;') + '\'';
  }

  function tablesBlock(tables, keyed) {
    if (!tables || !tables.length) return '<div class="se-note">No survey rows reference this section.</div>';
    /* One column per section either way: the two halves a split produces, or each section a
       merge consumes. A single "by section" cell made it hard to see at a glance that every
       section was accounted for — which is the one thing this table exists to show. */
    var head = keyed
      ? '<tr><th>Layer</th><th>Rows</th>'
        + keyed.map(function (sc) { return '<th>' + esc(sc) + '</th>'; }).join('') + '</tr>'
      : '<tr><th>Layer</th><th>Rows</th><th>First half</th><th>Second half</th><th>Divided</th></tr>';
    var rows = tables.map(function (t) {
      if (keyed) {
        var by = t.by_section || {};
        var cells = keyed.map(function (sc) {
          var n = by[sc];
          // 0 is shown, never blank: "none here" and "not reported" must not look alike.
          return '<td' + (n ? '' : ' class="se-was"') + '>' + (n == null ? '—' : n) + '</td>';
        }).join('');
        return '<tr><td>' + esc(t.table) + '</td><td>' + t.rows + '</td>' + cells + '</tr>';
      }
      /* Both halves come from the preview. They used to be guessed here — "rows minus
         straddling" for the first and a literal dash for the second — which read as though
         nothing moved to the second half at all. */
      var toFirst = (t.to_first == null) ? '' : t.to_first;
      var toSecond = (t.to_second == null) ? '' : t.to_second;
      return '<tr><td>' + esc(t.table) + '</td><td>' + t.rows + '</td>'
        + '<td>' + toFirst + '</td>'
        + '<td>' + toSecond + '</td>'
        + '<td>' + (t.divided
            ? t.divided + ' <span class="se-sub">' + esc(t.straddle_handling || '') + '</span>'
            : (t.straddling
                ? t.straddling + ' <span class="se-sub">' + esc(t.straddle_handling || '') + '</span>'
                : '0'))
        + '</td></tr>';
    }).join('');
    var note = '';
    if (!keyed && tables.some(function (t) { return t.divided; }))
      note = '<div class="se-note">A divided row is counted in both halves — the split creates a '
           + 'row, so those two columns total more than the row count.</div>';
    return '<table class="se-table"><thead>' + head + '</thead><tbody>' + rows + '</tbody></table>' + note;
  }


  /* ---------- picking sections off the map ---------- */

  /* The editor is a full-screen overlay, so picking means stepping out of it: the screen closes,
     a banner explains what to click, and the map's own road-click path does the rest. Every road
     click in the viewer funnels through the global onPick(roadId, lngLat, ...), so wrapping that
     one function is enough — no second hit-test, no duplicate layer list to keep in step. */
  var pickMode = null;            // 'splitSection' | 'mergeSections' | null
  var _origOnPick = null;

  /* Absolute road chainage of this section's start.

     The viewer's road index carries the shapefile's own column names, so the spelling has to be
     recognised here. The list mirrors ROAD_COLUMN_ALIASES for "Road Start Chainage" in
     LayerAttributeCatalog — and deliberately does NOT include Start_Chai, which is the
     SECTION-LOCAL start (always 0) and would silently place every cut as if the section began at
     the road's origin. */
  function roadStartChainage(props) {
    var keys = ['Rd_Str_cha', 'Rd_Str_ch', 'Rd_Strt_ch', 'Road_Start_Chainage', 'RdStrCha', 'Rd_Start_c'];
    for (var i = 0; i < keys.length; i++) {
      for (var k in props) {
        if (k.toLowerCase() === keys[i].toLowerCase() && props[k] != null && props[k] !== '')
          return +props[k] || 0;
      }
    }
    return 0;
  }

  /* Section-local chainage of a click, by the same snap the NSV player uses, then shifted into
     the road's own frame — which is what the split endpoint takes. */
  function chainageAtClick(feature, lngLat) {
    try {
      var line = lineOf(feature);
      var len = parseFloat(feature.properties.len
              || feature.properties.Measrd_Len || feature.properties['Measured Length']) || 0;
      var geoKm = turf.length(line, { units: 'kilometers' });
      var snap = turf.nearestPointOnLine(line, [lngLat.lng, lngLat.lat], { units: 'kilometers' });
      var local = geoKm > 0 ? (snap.properties.location / geoKm) * len : 0;
      return Math.round((roadStartChainage(feature.properties) + local) * 10) / 10;
    } catch (e) { return null; }
  }

  /* The section's own chainage frame, fetched as soon as it is named.

     Without it the split field is ambiguous: "5800" is a road chainage on a section running
     4240..14640, which is 1560 m in — but read as a distance into the section it would be 5800 m
     in. Both readings are reasonable, so the editor shows both and accepts either. */
  function loadSectionInfo(label) {
    var l = (label || '').trim();
    if (!l) { state.split.info = null; paintChainage(); return; }
    fetch(API + '/info?label=' + encodeURIComponent(l)).then(readJson).then(function (r) {
      if (state.split.section !== l) return;          // a newer section superseded this
      state.split.info = (r && r.found) ? r : null;
      paintChainage();
    }).catch(function () { state.split.info = null; paintChainage(); });
  }

  function splitStart() {
    var i = state.split.info;
    return i ? (+i[A_RD_FROM] || 0) : 0;
  }

  /* Keeps the two chainage inputs and their explanation in step. Written into the DOM rather
     than re-rendered, so the caret stays where the operator put it. */
  function paintChainage() {
    var i = state.split.info;
    var into = document.getElementById('seSplitInto');
    var note = document.getElementById('seChainNote');
    var at = num(state.split.at);
    if (into && document.activeElement !== into)
      into.value = (at == null || !i) ? '' : String(Math.round((at - splitStart()) * 10) / 10);
    if (!note) return;
    if (!i) { note.className = 'se-hint'; note.textContent = ''; return; }
    var len = +i.length || 0;
    var local = at == null ? null : at - splitStart();
    var frame = 'This section runs road chainage ' + fmt(i[A_RD_FROM]) + '-' + fmt(i[A_RD_TO])
              + ' (' + fmt(len) + ' m long).';
    if (local == null) { note.className = 'se-hint'; note.textContent = frame; return; }
    if (local <= 0 || local >= len) {
      note.className = 'se-hint se-bad';
      note.textContent = '\u2717 ' + frame + ' A cut at road chainage ' + fmt(at)
        + ' falls outside it, so there is nothing to split.';
      return;
    }
    note.className = 'se-hint se-ok';
    note.textContent = '\u2713 ' + frame + ' Cutting at road chainage ' + fmt(at) + ' is '
      + fmt(local) + ' m into it - first half 0-' + fmt(local)
      + ' m, second half 0-' + fmt(len - local) + ' m.';
  }

  function banner() { return document.getElementById('sePickBar'); }

  function showBanner(html) {
    var el = banner();
    if (!el) {
      el = document.createElement('div');
      el.id = 'sePickBar';
      document.body.appendChild(el);
    }
    el.className = 'se-pickbar';
    el.innerHTML = html;
  }

  function hideBanner() {
    var el = banner();
    if (el) el.parentNode.removeChild(el);
  }

  function paintBanner() {
    if (pickMode === 'splitSection') {
      showBanner('<span class="se-pick-t">Click the road section to split.</span>'
        + '<span class="se-pick-s">Where you click also sets the split chainage.</span>'
        + '<button class="se-ghost" data-act="sePickCancel">Cancel</button>');
    } else if (pickMode === 'mergeSections') {
      var n = state.merge.sections.length;
      showBanner('<span class="se-pick-t">Click each section to merge.</span>'
        + '<span class="se-pick-s">' + (n ? esc(state.merge.sections.join('  +  ')) : 'None chosen yet')
        + '</span>'
        + '<button class="se-primary" data-act="sePickDone">Done' + (n ? ' (' + n + ')' : '') + '</button>'
        + '<button class="se-ghost" data-act="sePickCancel">Cancel</button>');
    }
  }

  function startPick(mode) {
    pickMode = mode;
    close();                                   // step out of the overlay so the map is clickable
    if (_origOnPick === null && typeof window.onPick === 'function') {
      _origOnPick = window.onPick;
      window.onPick = function (roadId, lngLat) {
        if (pickMode) { takePick(roadId, lngLat); return; }
        return _origOnPick.apply(this, arguments);
      };
    }
    document.addEventListener('keydown', escCancel);
    paintBanner();
  }

  function stopPick(reopen) {
    pickMode = null;
    document.removeEventListener('keydown', escCancel);
    hideBanner();
    if (reopen) open();
  }

  function escCancel(ev) { if (ev.key === 'Escape') stopPick(true); }

  function takePick(roadId, lngLat) {
    if (!roadId) return;
    if (pickMode === 'mergeSections') {
      if (state.merge.sections.indexOf(roadId) < 0) state.merge.sections.push(roadId);
      if (!state.merge.result) state.merge.result = state.merge.sections[0];
      state.merge.preview = null;
      paintBanner();
      return;
    }
    // Split: the section AND the chainage come from the one click.
    var feature = (typeof RoadsIndex !== 'undefined' && RoadsIndex.byRoad)
      ? RoadsIndex.byRoad(roadId) : null;
    function finish(f) {
      state.split.section = roadId;
      var ch = f ? chainageAtClick(f, lngLat) : null;
      if (ch != null) state.split.at = String(ch);
      if (!state.split.first) state.split.first = roadId;
      if (!state.split.second) state.split.second = roadId + '/B';
      tab = 'split';
      stopPick(true);
      checkSectionExists('seSplitSection', roadId);
      loadSectionInfo(roadId);
      recheckSplitLabels();
    }
    if (feature && feature.geometry) finish(feature);
    else if (typeof RoadsIndex !== 'undefined' && RoadsIndex.hydrateFeature)
      RoadsIndex.hydrateFeature(roadId).then(finish).catch(function () { finish(null); });
    else finish(null);
  }

  /* ---------- confirming a write, and reporting what it did ----------

     Both of these used to be native dialogs, and that was wrong twice over. A browser set to
     suppress further dialogs makes window.confirm return false, so Apply silently did nothing;
     and the success alert was the ONLY sign a write had happened, because the screen behind it
     re-rendered identically. Rendered into the page, neither can be suppressed and the outcome
     stays on screen to be read. */

  function confirmBlock(what, lines, warnings, act, busy) {
    var high = (warnings || []).filter(function (w) { return w.severity === 'high'; });
    return '<div class="se-confirm">'
      + '<div class="se-card-h">' + esc(what) + '</div>'
      + '<div class="se-sub">' + lines.map(esc).join('<br>') + '</div>'
      + (high.length
          ? '<div class="se-warns">' + high.map(function (w) {
              return '<div class="se-warn se-high"><span class="se-warn-k">' + esc(w.kind) + '</span>'
                   + '<span>' + esc(w.message) + '</span></div>';
            }).join('') + '</div>'
          : '')
      + '<div class="se-actions">'
      + '<button class="se-danger" data-act="' + esc(act) + '"' + (busy ? ' disabled' : '') + '>'
      + (busy ? 'Working\u2026' : 'Yes \u2014 write these changes') + '</button>'
      + '<button class="se-ghost" data-act="seCancelApply"' + (busy ? ' disabled' : '') + '>Cancel</button>'
      + (busy ? '<span class="se-note">Re-basing rows and re-placing geometry\u2026</span>' : '')
      + '</div></div>';
  }

  /* The rebuild control and its outcome. Rendered inside the result panel, because that is the
     moment the layers go stale and the only moment the operator is looking. */
  function rebuildBlock() {
    var rb = state.rebuild;
    if (rb.busy)
      return '<div class="se-actions"><button class="se-primary" disabled>Rebuilding…</button>'
           + '<span class="se-note">' + esc(rb.step || '') + '</span></div>';
    if (rb.done)
      return '<div class="se-done-h" style="font-size:13px">✓ Rebuilt</div>'
           + '<div class="se-sub">' + esc(rb.done) + '</div>';
    if (rb.error)
      return '<div class="se-refused">' + esc(rb.error) + '</div>'
           + '<div class="se-actions"><button class="se-primary" data-act="seRebuild">Try again</button></div>';
    return '<div class="se-actions"><button class="se-primary" data-act="seRebuild">Rebuild now</button>'
         + '<span class="se-note">Runs all three builds in order.</span></div>';
  }

  /* Sequential, not parallel: each build DROPs and re-creates its table, and the 2 km IRI bins
     are rolled up from the condition segments, so starting them together would race. */
  function rebuild() {
    var rb = state.rebuild;
    if (rb.busy) return;
    rb.busy = true; rb.done = null; rb.error = null; rb.step = 'Condition segments…'; render();

    /* Two steps, not three: POST /api/segments/build rebuilds the 2 km IRI bins itself, because
       they are rolled up from the same condition rows and must never go stale behind the
       segments. Calling /api/iri-2km/build as well would just rebuild them twice. */
    var steps = [
      { path: '/api/segments/build',     label: 'Condition segments' },
      { path: '/api/fwd-segments/build', label: 'FWD stretches' }
    ];
    var results = [];

    function run(i) {
      if (i >= steps.length) {
        rb.busy = false; rb.step = '';
        rb.done = results.join(' · ');
        /* The cut segments now exist under the new labels. Redraw them, but only if they were
           on screen when the change was made — loadSegments() pulls the whole network. */
        try {
          if (rb.redrawSegments && typeof loadSegments === 'function') loadSegments();
        } catch (e) { /* the per-road fetch still feeds the inspector card */ }
        render();
        return;
      }
      rb.step = steps[i].label + '…'; render();
      fetch(steps[i].path, { method: 'POST' }).then(readJson).then(function (r) {
        /* These endpoints report a failure in the BODY with HTTP 200, so a resolved fetch is
           not the same as a successful build. */
        if (r && r.status === 'error') throw new Error(r.message || 'build failed');
        var n = (r && r.segments != null) ? r.segments : null;
        results.push(steps[i].label + (n != null ? ': ' + n : ' done')
                   + (r && r.iri_2km != null ? ' · 2 km IRI bins: ' + r.iri_2km : ''));
        run(i + 1);
      }).catch(function (e) {
        rb.busy = false; rb.step = '';
        rb.error = steps[i].label + ' failed: ' + netError(e).message
                 + ' The change itself is saved; the remaining builds can be run from the Data Console.';
        render();
      });
    }
    run(0);
  }

  function resultBlock(r) {
    if (!r) return '';
    var moved = r.moved || {};
    var rows = Object.keys(moved).map(function (k) {
      var v = moved[k];
      var txt = (v && typeof v === 'object')
        ? Object.keys(v).map(function (kk) { return kk + ': ' + v[kk]; }).join(', ')
        : String(v);
      return '<tr><td>' + esc(k) + '</td><td>' + esc(txt) + '</td></tr>';
    }).join('');

    var replaced = (r.replaced || []).map(function (x) {
      return '<tr><td>' + esc(x.section || x.layer || '') + '</td><td>'
           + esc(x.status === 'failed' ? x.message : ('re-placed ' + (x.replaced || 0) + ' row(s)'))
           + '</td></tr>';
    }).join('');

    var steps = (r.rebuild_after || []).map(function (x) {
      return '<li><code>' + esc(x.endpoint) + '</code><br><span class="se-sub">'
           + esc(x.why) + '</span></li>';
    }).join('');

    return '<div class="se-done">'
      + '<div class="se-done-h">\u2713 Done'
      + (r.change_id != null ? ' \u2014 change #' + esc(r.change_id) : '') + '</div>'
      + '<div class="se-sub">' + esc(r.message || '') + '</div>'
      + (rows ? '<table class="se-table"><thead><tr><th>Layer</th><th>Rows moved</th></tr></thead>'
                + '<tbody>' + rows + '</tbody></table>' : '')
      + (replaced ? '<table class="se-table"><thead><tr><th>Section</th><th>Re-placement</th></tr>'
                + '</thead><tbody>' + replaced + '</tbody></table>' : '')
      + (steps ? '<div class="se-card-h" style="margin-top:14px">Rebuild the cut layers</div>'
                 + '<div class="se-sub">The condition segments, 2 km IRI bins and FWD stretches are '
                 + 'CUT from the centreline, so a split or merge leaves them describing a road that '
                 + 'no longer exists — the map shows no condition data for these sections until '
                 + 'they are rebuilt. This takes about a minute across the whole network, so it is a '
                 + 'deliberate click: run it now, or after a batch of edits.</div>'
                 + rebuildBlock()
                 + '<ul class="se-steps">' + steps + '</ul>' : '')
      + '<div class="se-actions">'
      + '<button class="se-ghost" data-act="seScreenTab" data-args=\'["history"]\'>Open History</button>'
      + '<button class="se-ghost" data-act="seStartOver">Start another change</button>'
      + '</div></div>';
  }

  /* ---------- Split ---------- */

  function renderSplit() {
    var s = state.split, p = s.preview;
    var h = ''
      + '<div class="se-form">'
      + '  <div class="se-row">'
      + '    <label class="se-f">Section to split'
      + '      <input type="text" id="seSplitSection" list="seLabels" value="' + esc(s.section)
      + '" placeholder="KPWD/MDR/501010103/17" data-input="klValue" data-args="seSetSplitSection">'
    + hint('seSplitSection') + '</label>'
      + '    <label class="se-f"><span class="se-cap">Split at road chainage <span class="se-u">m</span></span>'
      + '      <input type="number" step="any" id="seSplitAt" value="' + esc(s.at)
      + '" placeholder="2600" data-input="klValue" data-args="seSetSplitAt"></label>'
      + '    <label class="se-f"><span class="se-cap">&hellip;or distance into this section '
      + '<span class="se-u">m</span></span>'
      + '      <input type="number" step="any" id="seSplitInto" placeholder="1560" '
      + 'data-input="klValue" data-args="seSetSplitInto"></label>'
      + '    <button class="se-ghost" data-act="sePickSplit" title="Close this screen and click the section on the map">Pick on map</button>'
    + '    <button class="se-ghost" data-act="seLocate" title="Show this chainage on the map">Show on map</button>'
      + '  </div>'
      + '  <div class="se-row"><span class="se-hint" id="seChainNote"></span></div>'
      + '  <div class="se-row">'
      + '    <label class="se-f">First half — new ' + esc(A_LABEL)
      + '      <input type="text" id="seSplitFirst" value="' + esc(s.first) + '" data-input="klValue" data-args="seSetSplitFirst">'
    + hint('seSplitFirst') + '</label>'
      + '    <label class="se-f">Second half — new ' + esc(A_LABEL)
      + '      <input type="text" id="seSplitSecond" value="' + esc(s.second) + '" data-input="klValue" data-args="seSetSplitSecond">'
    + hint('seSplitSecond') + '</label>'
      + '  </div>'
      + '  <div class="se-actions">'
      + '    <button class="se-primary" data-act="seCheckSplit"' + (s.busy ? ' disabled' : '') + '>'
      + (s.busy ? 'Checking…' : 'Check') + '</button>'
      + '    <span class="se-note">Checking writes nothing. It reports what the split would do.</span>'
      + '  </div>'
      + labelDatalist('seLabels')
      + '</div>';

    if (s.result) return h + resultBlock(s.result);
    if (!p) return h;
    if (p.status !== 'ok') return h + '<div class="se-refused"><b>Cannot split.</b> ' + esc(p.message) + '</div>';

    var plan = p.plan || {};
    h += '<div class="se-result">'
      + warnBlock(p.warnings)
      + '<div class="se-cards">'
      + chainageCard('First half', plan.first, 'seF', false)
      + chainageCard('Second half', plan.second, 'seS', false)
      + '</div>'
      + '<div class="se-sub">Generated from the section being split. '
      + esc(A_SEC_FROM) + ' is always 0; to move the cut, change the chainage above and check again.</div>'
      + tablesBlock(p.tables, null)
      + attrGrid(p.attributes, [s.section],
                 [{ key: 'first', title: 'First half' }, { key: 'second', title: 'Second half' }],
                 s.attrs)
      + (s.confirming
          ? confirmBlock('Apply this split?',
              [s.section + '  \u2192  ' + s.first,
               s.section + '  \u2192  ' + s.second,
               'Every survey row on this section is re-based onto the half it falls in.'],
              p.warnings, 'seDoSplit', s.busy)
          : '<div class="se-apply">'
            + '  <button class="se-danger" data-act="seApplySplit">Recalculate &amp; Apply</button>'
            + '  <span class="se-note">Writes the new sections and re-bases every row above. '
            + 'A change can be undone from History until new data is imported onto these labels.</span>'
            + '</div>')
      + '</div>';
    return h;
  }

  function setSplit(k, v) { state.split[k] = v; }

  function checkSplit() {
    var s = state.split;
    s.busy = true; s.preview = null; render();
    post('/split/preview', {
      section: s.section, at_chainage: num(s.at), first: s.first, second: s.second
    }).then(function (r) {
      s.busy = false; s.preview = r; s.attrs = { first: {}, second: {} };
      s.confirming = false; s.result = null; render();
    }).catch(function (e) {
      s.busy = false; s.preview = { status: 'error', message: e.message }; render();
    });
  }

  function applySplit() {
    var s = state.split;
    if (!s.preview || s.preview.status !== 'ok') return;
    s.confirming = true;
    render();
  }

  function doSplit() {
    var s = state.split;
    if (!s.preview || s.preview.status !== 'ok' || s.busy) return;
    var high = (s.preview.warnings || []).filter(function (w) { return w.severity === 'high'; });
    s.busy = true; render();
    post('/split/apply', {
      section: s.section, at_chainage: num(s.at), first: s.first, second: s.second,
      confirm: true, accept_warnings: high.length > 0,
      first_attributes: s.attrs.first, second_attributes: s.attrs.second
    }).then(function (r) {
      s.busy = false; s.confirming = false;
      if (r.status === 'ok') { s.result = r; state.rebuild = { busy:false, done:null, error:null, step:'' }; afterWrite(r); }
      else { s.preview = r; }          // refused / error: show the reason where the preview was
      render();
    }).catch(function (e) {
      s.busy = false; s.confirming = false;
      s.preview = { status: 'error', message: e.message };
      render();
    });
  }

  /* Drop the Chainage Locator's pin on the proposed cut, so the operator sees where it lands
     before committing to it. Problems are reported in the chainage note rather than an alert —
     a browser set to suppress dialogs would otherwise swallow them. */
  function locate() {
    var s = state.split;
    var at = num(s.at);
    var note = document.getElementById('seChainNote');
    function say(msg) {
      if (note) { note.className = 'se-hint se-bad'; note.textContent = '✗ ' + msg; }
    }
    if (!s.section || at == null) { say('Enter a section and a chainage first.'); return; }
    var road = null;
    try { road = (ROADS[s.section] && (ROADS[s.section].properties || ROADS[s.section])) || null; }
    catch (e) { /* index not loaded */ }
    var name = road ? (road['Road_Name'] || road['Road Name']) : null;
    if (!name || typeof locateChainageOnMap !== 'function') {
      say('Could not place a pin for this road here — use the Chainage Locator tool on the map.');
      return;
    }
    close();
    locateChainageOnMap(name, at);
  }

  /* ---------- Merge ---------- */

  function renderMerge() {
    var s = state.merge, p = s.preview;
    var chips = s.sections.map(function (l, i) {
      return '<span class="se-chip">' + esc(l)
        + '<button data-act="seDropSection" data-args="' + i + '" title="Remove">&times;</button></span>';
    }).join('');

    var h = ''
      + '<div class="se-form">'
      + '  <div class="se-row">'
      + '    <label class="se-f">Add a section'
      + '      <input type="text" id="seMergeAdd" list="seLabels" placeholder="KPWD/MDR/501010103/18" '
      + 'data-enter="seAddSection"></label>'
      + '    <button class="se-ghost" data-act="seAddSection">Add</button>'
    + '    <button class="se-ghost" data-act="sePickMerge" title="Close this screen and click sections on the map">Pick on map</button>'
      + '  </div>'
      + '  <div class="se-chips">' + (chips || '<span class="se-note">No sections chosen yet. '
      + 'They may be added in any order — they are sorted by chainage before the offsets are worked out.</span>') + '</div>'
      + '  <div class="se-row">'
      + '    <label class="se-f">Merged section — ' + esc(A_LABEL)
      + '      <input type="text" id="seMergeResult" list="seLabels" value="' + esc(s.result)
      + '" placeholder="keep one of the labels above, or type a new one" data-input="klValue" data-args="seSetMergeResult">'
    + hint('seMergeResult') + '</label>'
      + '  </div>'
      + '  <div class="se-actions">'
      + '    <button class="se-primary" data-act="seCheckMerge"' + (s.busy ? ' disabled' : '') + '>'
      + (s.busy ? 'Checking…' : 'Check') + '</button>'
      + '    <span class="se-note">Checking writes nothing.</span>'
      + '  </div>'
      + labelDatalist('seLabels')
      + '</div>';

    if (s.result_of) return h + resultBlock(s.result_of);
    if (!p) return h;
    if (p.status !== 'ok') return h + '<div class="se-refused"><b>Cannot merge.</b> ' + esc(p.message) + '</div>';

    var plan = p.plan || {};
    var offs = plan.offsets || {};
    h += '<div class="se-result">'
      + warnBlock(p.warnings)
      + '<div class="se-cards">' + chainageCard('Merged section', plan.result, 'seM', false) + '</div>'
      + '<div class="se-sub">Each section&#39;s rows are shifted by the total length of everything before it:</div>'
      + '<table class="se-table"><thead><tr><th>Section</th><th>Shift applied to its rows</th></tr></thead><tbody>'
      + Object.keys(offs).map(function (k) {
          return '<tr><td>' + esc(k) + '</td><td>+' + fmt(offs[k]) + ' m</td></tr>';
        }).join('')
      + '</tbody></table>'
      + tablesBlock(p.tables, s.sections)
      + attrGrid(p.attributes, s.sections, [{ key: 'result', title: 'Merged section' }], s.attrs)
      + (s.confirming
          ? confirmBlock('Apply this merge?',
              s.sections.map(function (x) { return x + '  \u2192  ' + s.result; })
                .concat(['Every survey row on these sections is re-based onto the merged one.']),
              p.warnings, 'seDoMerge', s.busy)
          : '<div class="se-apply">'
            + '  <button class="se-danger" data-act="seApplyMerge">Recalculate &amp; Apply</button>'
            + '  <span class="se-note">Writes the merged section and re-bases every row above.</span>'
            + '</div>')
      + '</div>';
    return h;
  }

  function addSection() {
    var el = document.getElementById('seMergeAdd');
    var v = el && el.value ? el.value.trim() : '';
    if (!v) return;
    if (state.merge.sections.indexOf(v) < 0) state.merge.sections.push(v);
    if (!state.merge.result) state.merge.result = state.merge.sections[0];
    state.merge.preview = null;
    render();
    recheckMergeLabel();
  }

  function dropSection(i) {
    state.merge.sections.splice(+i, 1);
    state.merge.preview = null;
    render();
    recheckMergeLabel();
  }

  function checkMerge() {
    var s = state.merge;
    s.busy = true; s.preview = null; render();
    post('/merge/preview', { sections: s.sections, result: s.result }).then(function (r) {
      s.busy = false; s.preview = r; s.attrs = { result: {} };
      s.confirming = false; s.result_of = null; render();
    }).catch(function (e) {
      s.busy = false; s.preview = { status: 'error', message: e.message }; render();
    });
  }

  function applyMerge() {
    var s = state.merge;
    if (!s.preview || s.preview.status !== 'ok') return;
    s.confirming = true;
    render();
  }

  function doMerge() {
    var s = state.merge;
    if (!s.preview || s.preview.status !== 'ok' || s.busy) return;
    var high = (s.preview.warnings || []).filter(function (w) { return w.severity === 'high'; });
    s.busy = true; render();
    post('/merge/apply', {
      sections: s.sections, result: s.result, confirm: true, accept_warnings: high.length > 0,
      result_attributes: s.attrs.result
    }).then(function (r) {
      s.busy = false; s.confirming = false;
      if (r.status === 'ok') { s.result_of = r; state.rebuild = { busy:false, done:null, error:null, step:'' }; afterWrite(r); }
      else { s.preview = r; }
      render();
    }).catch(function (e) {
      s.busy = false; s.confirming = false;
      s.preview = { status: 'error', message: e.message };
      render();
    });
  }

  /* ---------- History ---------- */

  function renderHistory() {
    var s = state.history;
    if (s.busy) return '<div class="dash-loading">Loading…</div>';
    if (!s.rows) { loadHistory(); return '<div class="dash-loading">Loading…</div>'; }
    if (s.error) return '<div class="se-refused"><b>Could not load the history.</b> ' + esc(s.error) + '</div>';
    if (!s.rows.length) return '<div class="se-note">No section changes have been made yet.</div>';

    var rows = s.rows.map(function (r) {
      var from = (r.source_labels || []).join(', ');
      var to = (r.result_labels || []).join(', ');
      var when = r.performed_at ? String(r.performed_at).replace('T', ' ').slice(0, 19) : '';
      var act;
      if (r.undone_at) act = '<span class="se-sub">undone</span>';
      else if (r.undoable) act = '<button class="se-ghost" data-act="seUndo" data-args="' + r.id + '">Undo</button>';
      else act = '<span class="se-sub" title="' + esc(r.undo_blocked || '') + '">cannot undo</span>';
      return '<tr><td>' + r.id + '</td><td>' + esc(r.operation) + '</td>'
        + '<td>' + esc(from) + '</td><td>' + esc(to) + '</td>'
        + '<td>' + esc(r.username || '') + '</td><td>' + esc(when) + '</td><td>' + act + '</td></tr>';
    }).join('');

    return '<table class="se-table"><thead><tr><th>#</th><th>Operation</th><th>From</th>'
      + '<th>To</th><th>By</th><th>When</th><th></th></tr></thead><tbody>' + rows + '</tbody></table>'
      + '<div class="se-note">A change can be undone until new data is imported onto the labels it '
      + 'produced. After that the recorded state is no longer one the database can be returned to.</div>';
  }

  function loadHistory() {
    var s = state.history;
    s.busy = true;
    fetch(API + '/history?limit=200').then(readJson).then(function (rows) {
      s.busy = false; s.rows = rows || []; s.error = null; render();
    }).catch(function (e) {
      // Reported rather than shown as "no changes yet", which would be a different fact.
      s.busy = false; s.rows = []; s.error = e.message; render();
    });
  }

  function undo(id) {
    var s = state.history;
    s.busy = true; render();
    post('/undo', { change_id: +id, confirm: true }).then(function (r) {
      s.busy = false;
      s.rows = null;
      s.error = (r.status === 'ok') ? null : (r.message || 'Could not undo.');
      if (r.status === 'ok') afterWrite(r);
      render();
    }).catch(function (e) {
      s.busy = false; s.error = e.message; render();
    });
  }

  /* ---------- after any write ---------- */

  /* The outcome is rendered by resultBlock. This only clears what the change invalidated: the
     centreline moved, so every cached layer built from it is stale. The rebuild endpoints are
     named in the result rather than run here — they are minutes of work across the whole
     network, and only the operator knows whether to run them now or after a batch of edits. */
  function afterWrite(r) {
    state.history.rows = null;
    /* These used to call refreshRoads() and reloadRoadIndex() — neither of which exists. The
       calls sat in a try/catch, so they failed silently and the viewer went on showing the
       section that had just been split. Clear what actually caches the network instead:
       RoadsIndex holds the section list for the life of the page, ROADS holds hydrated
       geometry, and the vector tiles are cached by the browser. */
    try {
      if (typeof RoadsIndex !== 'undefined' && RoadsIndex.invalidate) {
        RoadsIndex.invalidate();
        RoadsIndex.ensure();
      }
      if (typeof ROADS !== 'undefined' && ROADS) {
        Object.keys(ROADS).forEach(function (k) { delete ROADS[k]; });
      }
      /* Re-point the road tile source at a fresh URL. MapLibre caches tiles per URL, so the
         same path would redraw the pre-split geometry however many times it is refreshed. */
      var src = (typeof map !== 'undefined' && map.getSource) ? map.getSource('roadnet') : null;
      if (src && src.setTiles && src.tiles && src.tiles.length) {
        var stamp = Date.now();
        src.setTiles(src.tiles.map(function (u) {
          var base = u.replace(/[?&]_v=\d+/, '');
          return base + (base.indexOf('?') < 0 ? '?' : '&') + '_v=' + stamp;
        }));
      }
    } catch (e) { /* a page reload still picks the change up, now that the server cache is cleared */ }
    /* Remember whether the condition layer was actually on screen, so the rebuild can redraw it
       instead of pulling the whole-network payload for someone who never had it loaded. */
    state.rebuild.redrawSegments = invalidateSectionCaches();
  }

  /* Every viewer cache that is keyed by SECTION LABEL. A split or merge retires labels and
     creates new ones, so each of these is answering for a section that no longer exists.

     The one that actually bit: the inspector's condition reader short-circuits on the
     whole-network snapshot —

         if (DATA && DATA.features) return Promise.resolve(segsByRoad[roadId] || []);

     A label created by a split is not in that pre-split snapshot, so the card returned an empty
     array WITHOUT asking the server and reported "No survey done at this chainage" on a section
     whose rows had migrated perfectly. Clearing DATA is what sends it back to /api/segments/one.

     Every write goes through here, not only the ones that need it, because a cache that is
     merely re-fetched costs a request while a cache that is wrongly kept reads as missing data. */
  function invalidateSectionCaches() {
    var segsWereLoaded = false;

    /* Condition — the inspector's Condition tab and the NSV coverage bar / HUD. */
    try {
      if (typeof DATA !== 'undefined' && DATA && DATA.features) segsWereLoaded = true;
      if (typeof DATA !== 'undefined') DATA = null;
      if (typeof segsByRoad !== 'undefined') segsByRoad = {};
      if (typeof _segRoadP !== 'undefined') _segRoadP = {};
    } catch (e) { /* not on a page that loads the map */ }

    /* PCI shown on the NSV HUD, cached per road label. */
    try { if (typeof _pciCache !== 'undefined') _pciCache = {}; } catch (e) {}

    /* FWD — both the inspector's FWD tab (FWD.byRoad) and the asset geojson promise. */
    try {
      if (window.FWD) { window.FWD.byRoad = {}; window.FWD.ready = false; }
      window._fwdGeoP = null;
      if (window.FWD && typeof window.FWD.load === 'function') window.FWD.load(true);
    } catch (e) {}

    /* Assets, and the Survey tab's counts built from them. */
    try {
      if (typeof ASSET_DATA !== 'undefined' && ASSET_DATA) {
        Object.keys(ASSET_DATA).forEach(function (k) { delete ASSET_DATA[k]; });
      }
      if (typeof _sumReady !== 'undefined') _sumReady = false;
      if (typeof _TRF_SUM !== 'undefined') _TRF_SUM = null;
    } catch (e) {}

    /* NSV video. CATALOG is keyed by label AND is appended to on load, so it has to be emptied
       before re-loading or every road would list its clips twice. Until it is reloaded the
       "Play footage" button is missing on the new sections. */
    try {
      if (typeof CATALOG !== 'undefined') CATALOG = {};
      if (typeof loadCatalog === 'function') loadCatalog();
    } catch (e) {}

    return segsWereLoaded;
  }

  /* ---------- exports for js/00-actions.js ---------- */

  window.openSectionEditor = open;
  window.closeSectionEditor = close;
  window.pickSectionEditor = function () {
    if (typeof pickModule === 'function') pickModule(open); else open();
  };
  window.seScreenTab = screenTab;
  /* Whether a label is free depends on which sections the operation is CONSUMING, so the answer
     changes when those change — not only when the label itself is typed. Re-checking from both
     sides is what lets the normal case work: keeping the parent's label on one half is allowed,
     but only once the editor knows which section is being split. Without this, typing the halves
     before the section left the hints reading "already used" against a stale, empty exclusion. */
  function recheckSplitLabels() {
    var s = state.split;
    if (s.first) checkLabel('seSplitFirst', s.first, [s.section]);
    if (s.second) checkLabel('seSplitSecond', s.second, [s.section]);
  }

  function recheckMergeLabel() {
    if (state.merge.result) checkLabel('seMergeResult', state.merge.result, state.merge.sections);
  }

  window.seSetSplitSection = function (v) {
    setSplit('section', v);
    state.split.info = null;
    checkSectionExists('seSplitSection', v);
    loadSectionInfo(v);
    recheckSplitLabels();
  };
  window.seSetSplitAt = function (v) { setSplit('at', v); paintChainage(); };
  /* The convenience field: a distance INTO the section, converted to the road chainage the API
     takes. Only one value is ever stored — the two inputs are two views of the same number. */
  window.seSetSplitInto = function (v) {
    var d = num(v);
    state.split.at = (d == null) ? '' : String(Math.round((splitStart() + d) * 10) / 10);
    var at = document.getElementById('seSplitAt');
    if (at) at.value = state.split.at;
    paintChainage();
  };
  window.seSetAttr = function (v) {
    var el = this;
    if (!el || !el.getAttribute) return;
    var which = el.getAttribute('data-which'), column = el.getAttribute('data-column');
    var store = (tab === 'merge' ? state.merge : state.split).attrs;
    if (!store[which]) store[which] = {};
    store[which][column] = v;
  };
  window.seUseAll = function (source) {
    var p = state.merge.preview;
    if (!p || !p.attributes) return;
    p.attributes.forEach(function (a) {
      var v = (a.values || {})[source];
      state.merge.attrs.result[a.column] = (v == null ? '' : v);
    });
    render();
  };
  window.seSetSplitFirst = function (v) {
    setSplit('first', v);
    checkLabel('seSplitFirst', v, [state.split.section]);
  };
  window.seSetSplitSecond = function (v) {
    setSplit('second', v);
    checkLabel('seSplitSecond', v, [state.split.section]);
  };
  window.seSetMergeResult = function (v) {
    state.merge.result = v;
    checkLabel('seMergeResult', v, state.merge.sections);
  };
  window.seCheckSplit = checkSplit;
  window.seApplySplit = applySplit;
  window.seLocate = locate;
  window.seAddSection = addSection;
  window.seDropSection = dropSection;
  window.seCheckMerge = checkMerge;
  window.seApplyMerge = applyMerge;
  window.seUndo = undo;
  window.seRebuild = rebuild;
  window.seDoSplit = doSplit;
  window.seDoMerge = doMerge;
  window.seCancelApply = function () {
    state.split.confirming = false;
    state.merge.confirming = false;
    render();
  };
  window.seStartOver = function () {
    if (tab === 'merge') {
      state.merge = { sections: [], result: '', preview: null, busy: false,
                      attrs: { result: {} }, confirming: false, result_of: null };
    } else {
      state.split = { section: '', at: '', first: '', second: '', preview: null, busy: false,
                      info: null, attrs: { first: {}, second: {} }, confirming: false, result: null };
    }
    labelState = {}; labelWanted = {};
    render();
  };
  window.sePickSplit = function () { startPick('splitSection'); };
  window.sePickMerge = function () { startPick('mergeSections'); };
  window.sePickCancel = function () { stopPick(true); };
  window.sePickDone = function () { stopPick(true); recheckMergeLabel(); };
})();
