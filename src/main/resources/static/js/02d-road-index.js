/* ============================================================
   KLRAMS viewer · 02d-road-index.js
   The one way to ask a network-wide question about roads, and the one
   way to hydrate a single road's full geometry on demand.

   Why two separate things
   ------------------------
   ROADS (01-config.js) is a feature-with-geometry per road, and three
   consumers need to scan EVERY road — search, the network attribute
   filter, the asset register table — but none of them read a
   coordinate. Measured: the full road GeoJSON is 4.0 MB; the same data
   with geometry left out is 92 KB, a 43x difference, because
   coordinate arrays are the entire cost of this payload and every
   other property is a few bytes. So in tile mode ROADS itself stays
   SPARSE — populated only for roads someone has actually clicked or
   selected — while this lightweight index is cheap enough to just
   always load, the way the whole network used to load unconditionally.

   RoadsIndex.all() / .byRoad() answer "what roads exist and what are
   their attributes" from that cheap index. RoadsIndex.hydrateFeature()
   answers "give me this ONE road's actual geometry", fetching it only
   when something is about to read coordinates — a popup, a click, a
   chosen search result's fit-bounds.
   ============================================================ */
var RoadsIndex = (function () {
  'use strict';

  var rows = null;      // array, straight from /api/roads/index
  var byRoad = null;    // Map<road, row>, for O(1) lookup
  var inflight = null;

  function index() {
    if (rows) return Promise.resolve(rows);
    if (inflight) return inflight;
    inflight = fetch('/api/roads/index').then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.json();
    }).then(function (arr) {
      rows = arr || [];
      byRoad = new Map();
      rows.forEach(function (row) { if (row && row.road != null) byRoad.set(String(row.road), row); });
      return rows;
    }).finally(function () { inflight = null; });
    return inflight;
  }

  /** Load the index if it is not already here. */
  function ensure() { return index(); }

  /** Every road's metadata (no geometry). Empty until ensure()/all() has
   *  actually resolved once — callers that need it synchronously should
   *  await ensure() first, the same contract Segs.all() has. */
  function all() { return rows || []; }

  /** One road's metadata row, or null. */
  function byRoadId(road) {
    return (byRoad && byRoad.get(String(road))) || null;
  }

  /**
   * Make sure ROADS[road] holds a full feature with geometry, fetching it
   * if not. This is the fallback onPick, search's choose(), and anything
   * else that needs coordinates should go through instead of assuming
   * ROADS[road] is already there — in tile mode it usually is not, until
   * exactly this has been called once for that road.
   */
  function hydrateFeature(road) {
    if (typeof ROADS !== 'undefined' && ROADS[road]) return Promise.resolve(ROADS[road]);
    return fetch('/api/roads/one/geojson?section=' + encodeURIComponent(road))
      .then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.json(); })
      .then(function (gj) {
        var f = gj && gj.features && gj.features[0];
        if (f && typeof ROADS !== 'undefined') ROADS[road] = f;
        return f || null;
      })
      .catch(function () { return null; });
  }

  return { ensure: ensure, all: all, byRoad: byRoadId, hydrateFeature: hydrateFeature };
})();
