/*
 * 00-calc-rules.js — the Calculation Rules the viewer computes with.
 *
 * The map viewer recomputes several things the server also computes: PCI (so a
 * user can nudge the weights and watch the map redraw), the pavement area a PCI
 * ranking weights by, and the network scope card's corrected length and station
 * count. Each of those used to carry its own copy of the numbers — PCI_W_DEFAULT
 * and PARAMS' fair/poor here, PVMT_W_M in the PCI report, an A/B regex in the
 * scope card — and a copy drifts.
 *
 * This fetches /api/calc-rules/client once and points all of them at the saved
 * values, so the browser and the server agree by construction. It loads FIRST
 * (hence 00-) but nothing waits on it: every consumer reads the numbers at
 * render time, not at load time, so the built-in IRC defaults stand in for the
 * fraction of a second before the answer lands, and a failed fetch simply leaves
 * them standing. Call CalcRules.ready() if you need the real ones.
 *
 * The GROUPS (carriageway, traffic station) are different in kind: there is no
 * sensible built-in default for "which sections are one stretch", so a consumer
 * that has not received them yet falls back to the old A/B guess — see
 * CalcRules.carriagewayKey() and CalcRules.stationKey().
 */
(function () {
  var CalcRules = window.CalcRules || (window.CalcRules = {});

  CalcRules.loaded = false;
  CalcRules.width = { bands: {}, default_m: 7, dual_factor: 0.5 };
  CalcRules.carriagewayGroups = null;   // null = not loaded yet, use the fallback
  CalcRules.stationGroups = null;
  CalcRules.stationGroupNames = null;   // group key ("g3") -> display name, e.g. "TVM_STN_021"

  var waiters = [];

  /** Resolves once the saved rules are in (or the fetch has given up). */
  CalcRules.ready = function () {
    if (CalcRules.loaded) return Promise.resolve(CalcRules);
    return new Promise(function (res) { waiters.push(res); });
  };

  /**
   * The key a road section counts under: its carriageway group when it has one,
   * otherwise itself. Sections sharing a key are ONE stretch and are counted at
   * the average of their lengths.
   *
   * Before the groups arrive (or if the fetch failed) this falls back to the old
   * rule — a trailing A/B on a section marked Dual — so the card is never wrong
   * by more than the moment it takes to load.
   */
  CalcRules.carriagewayKey = function (sectionLabel, singleDu) {
    var label = String(sectionLabel == null ? '' : sectionLabel);
    if (CalcRules.carriagewayGroups) {
      var g = CalcRules.carriagewayGroups[label];
      return g ? 'G:' + g : 'S:' + label;
    }
    var isDual = /^dual/i.test(String(singleDu || ''));
    return (isDual && /[AB]$/.test(label)) ? 'G:' + label.slice(0, -1) : 'S:' + label;
  };

  /** The same for a traffic station: its group's key, or its own name. */
  CalcRules.stationKey = function (name) {
    var n = String(name == null ? '' : name).trim();
    if (CalcRules.stationGroups) {
      var g = CalcRules.stationGroups[n];
      return g ? 'G:' + g : 'S:' + n;
    }
    return 'S:' + n.replace(/([0-9])[ABab]$/, '$1');
  };

  /**
   * Every station name sharing this one's group, itself included — e.g. both
   * carriageways of a dual-road count station (TVM_STN_021A / …B). Returns
   * just [name] when the station is not grouped, or the groups have not
   * loaded yet, so a caller can always sum over the returned list.
   */
  CalcRules.stationGroupMembers = function (name) {
    var n = String(name == null ? '' : name).trim();
    if (!CalcRules.stationGroups) return [n];
    var g = CalcRules.stationGroups[n];
    if (!g) return [n];
    var out = [];
    Object.keys(CalcRules.stationGroups).forEach(function (k) {
      if (CalcRules.stationGroups[k] === g) out.push(k);
    });
    return out.length ? out : [n];
  };

  /** Display name for a station: its group's name when grouped, otherwise itself. */
  CalcRules.stationLabel = function (name) {
    var n = String(name == null ? '' : name).trim();
    if (CalcRules.stationGroups) {
      var g = CalcRules.stationGroups[n];
      if (g) return (CalcRules.stationGroupNames && CalcRules.stationGroupNames[g]) || n.replace(/([0-9])[ABab]$/, '$1');
    }
    return n;
  };

  /**
   * Pavement width in metres for one road's properties — the band's metres, the
   * default when the band code is missing, halved for a dual carriageway because
   * the band describes the whole road while this is one of its two centrelines.
   */
  CalcRules.pavementWidthM = function (props) {
    var p = props || {};
    var code = p.Pavement_W;
    var w = CalcRules.width.bands[String(code == null ? '' : code).trim()];
    if (w == null || isNaN(w)) w = CalcRules.width.default_m;
    var dual = String(p.Single_Du == null ? '' : p.Single_Du).trim().toLowerCase() === 'dual';
    return dual ? w * CalcRules.width.dual_factor : w;
  };

  function applyPci(pci) {
    if (!pci) return;
    /* PCI_W and PCI_W_DEFAULT live in 14-pci-engine.js and PARAMS in
       01-config.js. All three are mutated in place, never reassigned: modules
       captured them by reference at load time and a reassignment would leave
       those holding the old object. */
    Object.keys(pci).forEach(function (key) {
      var v = pci[key];
      if (!v || v.length < 3) return;
      if (typeof PCI_W_DEFAULT !== 'undefined') PCI_W_DEFAULT[key] = +v[0];
      if (typeof PCI_W !== 'undefined') PCI_W[key] = +v[0];
      if (typeof PMAP !== 'undefined' && PMAP[key]) {
        PMAP[key].fair = +v[1];
        PMAP[key].poor = +v[2];
      }
    });
  }

  function done() {
    CalcRules.loaded = true;
    waiters.splice(0).forEach(function (res) { res(CalcRules); });
  }

  fetch('/api/calc-rules/client', { credentials: 'same-origin', headers: { Accept: 'application/json' } })
    .then(function (r) { return r.ok ? r.json() : null; })
    .then(function (d) {
      if (!d) { done(); return; }
      applyPci(d.pci);
      if (d.width) {
        CalcRules.width = {
          bands: d.width.bands || {},
          default_m: +d.width.default_m || 7,
          dual_factor: +d.width.dual_factor || 0.5
        };
      }
      CalcRules.carriagewayGroups = d.carriageway_groups || {};
      CalcRules.stationGroups = d.station_groups || {};
      CalcRules.stationGroupNames = d.station_group_names || {};
      done();
      /* The scope card may already be on screen, drawn with the A/B fallback.
         updateNetScopeCard() redraws it from its own kept state, and is a no-op
         when the card was never opened. */
      try {
        if (typeof updateNetScopeCard === 'function') updateNetScopeCard();
      } catch (e) { /* the card is not open — nothing to redraw */ }
    })
    .catch(function () { done(); });
})();
