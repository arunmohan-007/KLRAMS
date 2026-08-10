/* ============================================================
   KLRAMS viewer · 02c-segment-data.js
   The one way to ask a question about the condition segments.

   THIS MODULE CHANGES NOTHING TODAY. Every function below reads the
   same global `DATA` the callers read directly before, and returns the
   same answers. It exists to move the coupling, not the behaviour.

   Why
   ---
   `DATA` is doing two unrelated jobs, and every problem with the
   payload comes from that:

     RENDERING  — colours, lane offsets, filters. Needs only what is on
                  screen. A vector tile can serve this.
     ANALYSIS   — the PCI report, CSV export, "N of M segments match",
                  the network-scope card. Needs the WHOLE network,
                  always. A tile can NEVER serve this, because a tile
                  only ever carries the current viewport.

   Switching the map to vector tiles is a twenty-minute change. What
   makes it useless without this file is that eight modules still need
   the whole network, so the viewer would download tiles AND the full
   GeoJSON — strictly worse than today. Those eight have to be able to
   get their answers from somewhere other than a global array first.

   Every function here is therefore a QUESTION ("how much condition
   length is in scope?"), not an accessor ("give me the array"). That is
   the point: a question can later be answered by a SQL endpoint over
   the whole network, whereas `DATA.features` can only ever be answered
   by having already downloaded everything.

   Loaded from map.html after 02b-layer-registry.js. `DATA` is declared
   in 01-config.js and assigned by 07-data-loaders.js; everything here
   reads it at call time, never at load time.
   ============================================================ */
var Segs = (function () {
  'use strict';

  /* The backing store, read lazily. Today this is the global DATA that
     07-data-loaders.js fills. When the analysis endpoints land, only
     the bodies below change — no caller does. */
  function fc() {
    return (typeof DATA !== 'undefined' && DATA && DATA.features) ? DATA : null;
  }

  function features() {
    var d = fc();
    return d ? (d.features || []) : [];
  }

  /* ---- availability -------------------------------------------------
     Callers wrote `!DATA || !DATA.features || !DATA.features.length`
     nine different times, and two of them got it subtly different.
     One spelling, one meaning. */

  /** Are the segments loaded AND non-empty? */
  function loaded() {
    return features().length > 0;
  }

  /** Load them if they are not already here. Resolves when usable. */
  function ensure() {
    if (loaded()) return Promise.resolve();
    if (typeof loadSegments !== 'function') return Promise.resolve();
    return Promise.resolve(loadSegments());
  }

  /** Has the PCI engine stamped its per-segment scores on yet?
   *  29-export.js asks this to decide whether to trigger generatePCI(). */
  function hasPci() {
    var f = features();
    return f.length > 0 && f[0].properties.pci_avg !== undefined;
  }

  /* ---- whole-network reads ------------------------------------------
     These are the ones a vector tile can never satisfy. Each is a
     candidate for a server-side aggregate endpoint. */

  /** Every segment feature. The bluntest question, and the one to
   *  design out first — the PCI report and CSV export use it only to
   *  fold the network down to a summary the server could compute. */
  function all() {
    return features();
  }

  /** How many segments exist network-wide. */
  function count() {
    return features().length;
  }

  /** The raw FeatureCollection, for the two callers that genuinely need
   *  the envelope rather than the array (the report hub's row builder,
   *  and the map source itself). */
  function collection() {
    return fc();
  }

  /* ---- scoped reads --------------------------------------------------
     Narrower questions that a tile still cannot answer, because the
     answer must consider segments outside the viewport. */

  /** Segments of one road, in upload order. Backed by the segsByRoad
   *  index 07-data-loaders.js builds. This is the ONE read here that a
   *  per-road endpoint could serve cheaply — it is what the NSV player
   *  and the condition popup use, and neither needs the network. */
  function forRoad(road) {
    return (typeof segsByRoad !== 'undefined' && segsByRoad[road]) ? segsByRoad[road] : [];
  }

  /** [minChainage, maxChainage] across one section, or [null,null]. */
  function chainExtent(section) {
    var mn = Infinity, mx = -Infinity, want = String(section);
    features().forEach(function (f) {
      var p = f.properties;
      if (String(p.road) !== want) return;
      var a = +p.from_ch, b = +p.to_ch;
      if (!isNaN(a)) mn = Math.min(mn, a);
      if (!isNaN(b)) mx = Math.max(mx, b);
    });
    return (mn !== Infinity) ? [Math.round(mn), Math.round(mx)] : [null, null];
  }

  /**
   * Lane-metres of condition data whose road is in the given scope set.
   *
   * Lifted verbatim from the network-scope card in 05-road-network.js:
   * length x lane_count, summed. Kept here rather than inlined because
   * it is exactly the shape a `SELECT sum(...) GROUP BY` replaces.
   */
  function scopedLaneMetres(scope) {
    var total = 0;
    features().forEach(function (f) {
      var p = (f && f.properties) || {};
      if (!scope.has(String(p.road != null ? p.road : ''))) return;
      var lanes = Math.max(1, (+p.lane_count || 0) || 1);
      total += Math.max(0, (+p.to_ch || 0) - (+p.from_ch || 0)) * lanes;
    });
    return total;
  }

  /**
   * Segments matching the Road-Condition attribute filters.
   *
   * `rows` are {param, op, val} as the filter UI builds them; `mode` is
   * 'all' or 'any'. Returns null when there is nothing to filter by,
   * which is how callers tell "no filter" from "filter matched nothing"
   * — a distinction the old inline version also made and which is easy
   * to lose when this becomes a query.
   */
  function matching(rows, mode) {
    if (!fc() || !rows || !rows.length) return null;
    return features().filter(function (ft) {
      var p = ft.properties;
      var hits = rows.map(function (f) {
        var raw = p[f.param];
        if (raw == null) return false;
        var v = +raw;
        switch (f.op) {
          case '>':  return v > +f.val;
          case '>=': return v >= +f.val;
          case '<':  return v < +f.val;
          case '<=': return v <= +f.val;
          case '=':  return v == +f.val;
        }
        return false;
      });
      return (mode === 'all') ? hits.every(Boolean) : hits.some(Boolean);
    });
  }

  /* ---- server-answered questions --------------------------------------
     In tile mode the segments array is not downloaded, so these are asked
     of /api/segments/* instead. Async by necessity: the answer is over the
     network. The GeoJSON path keeps its synchronous answers, so callers
     that cannot await still work exactly as before. */

  /** {count, total, filtered, bbox} for the condition filters, from SQL. */
  function matchStats(rows, mode) {
    var qs = '/api/segments/match?mode=' + encodeURIComponent(mode || 'all');
    (rows || []).forEach(function (r) {
      var op = { '>': 'gt', '>=': 'gte', '<': 'lt', '<=': 'lte', '=': 'eq' }[r.op];
      if (op) qs += '&f=' + encodeURIComponent(r.param + ':' + op + ':' + r.val);
    });
    return fetch(qs).then(function (res) {
      if (!res.ok) throw new Error('HTTP ' + res.status);
      return res.json();
    });
  }

  /** Network segment count, from SQL. */
  function countRemote() {
    return fetch('/api/segments/stats')
      .then(function (r) { return r.ok ? r.json() : { count: 0 }; })
      .then(function (j) { return j.count || 0; });
  }

  return {
    matchStats: matchStats,
    countRemote: countRemote,
    loaded: loaded,
    ensure: ensure,
    hasPci: hasPci,
    all: all,
    count: count,
    collection: collection,
    forRoad: forRoad,
    chainExtent: chainExtent,
    scopedLaneMetres: scopedLaneMetres,
    matching: matching
  };
})();
