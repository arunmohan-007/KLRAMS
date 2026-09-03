/* ============================================================
   KLRAMS viewer · 01-config.js
   Viewer-only application state and helpers. The data tables both map pages
   share live in 00-shared-config.js.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
/* Colours, PARAMS/PMAP, LK, ROAD_FIELDS and dec() moved to
   00-shared-config.js, which map.html loads immediately before this file —
   map-lite.html had its own drifted copy of all of them and now loads the same
   one. What stays here is viewer-only: it refers to the MapLibre map and to DOM
   the Lite map does not have. */

let mode='all',filters=[],DATA=null,ROADS={},segsByRoad={},CATALOG={};
/* Vector-tile mode, default ON. Opt out with ?tiles=0 (kept as an escape
   hatch — no redeploy needed to fall back to the old path if something looks
   wrong).
   OFF: the map downloads every condition segment as one GeoJSON before it is
   useful, and every module reads that array. ON: the condition layers render
   from /api/segments/tiles, roads from /api/roads/tiles, Avg IRI 2 km from
   /api/iri-2km/tiles, the whole-network questions (match count, segment
   count) are answered by /api/segments/* (and /api/iri-2km/match), and the
   full GeoJSON is fetched only when something genuinely needs per-segment
   rows — the PCI report, an export. */
const TILES_ON=!/[?&]tiles=0\b/.test(location.search);
let dir='fwd',cur=null,marker=null,carIcon=null,carLabel=null,carIri=null,seeking=false,lastChainage=0,follow=false,curCarLL=null;
const FOLLOW_ZOOM=16;
// keep the car in the visible band above the video dock: shift the map centre
// downward so the marker sits ~38% from the top of the *visible* map area.
function followTo(ll,dur){
  if(!ll)return;
  const dockH=document.getElementById('dock').classList.contains('open')?230:0;
  const c=map.getContainer();const h=c.clientHeight;
  const visTop=0,visBot=h-dockH;const targetY=visTop+(visBot-visTop)*0.42; // where we want the car
  const centerY=h/2;const offsetY=targetY-centerY; // px to shift; negative moves car up
  map.easeTo({center:ll,offset:[0,offsetY],duration:dur,zoom:Math.max(map.getZoom(),FOLLOW_ZOOM)});
}

