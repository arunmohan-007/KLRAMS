/* ============================================================
   KLRAMS viewer · 39-heatmap.js
   Heat Map Analysis — a kernel-density surface over the two POINT datasets
   that actually have something worth interpolating: Sub-Grade Soil test
   points and Traffic Stations.

   Why this is an ANALYSIS, not a layer
   ------------------------------------
   Every other entry in the Layers panel draws stored geometry. This one
   derives a NEW surface from the points on each run, and the surface depends
   on three choices the user makes here (dataset, weighting value, kernel
   radius). Making it a permanent toggle would imply the surface is a fixed
   product of the data; it is not. So it lives behind "Run Heat Map Analysis",
   is rebuilt on every run, and is cleared explicitly.

   The weighting is the whole point
   --------------------------------
   Unweighted (Point density) a traffic heat map shows where the DEPARTMENT
   PUT ITS COUNTERS, not where the traffic is — 33 stations spread over Kerala
   cluster wherever the survey clustered. Weighting by ADT turns the same 33
   points into a picture of demand. Same for soil: density shows where the
   boreholes are, weighting by CBR shows where the subgrade is weak. Both
   modes are offered because the unweighted one is the honest answer to
   "where do we have data?", which is a real question during survey planning.

   Robust normalisation
   --------------------
   Weights are normalised to 0..1 across the SELECTED points using the 2nd and
   98th percentile, not min/max. One mis-keyed ADT of 900 000 against a real
   range of 2 000..40 000 would otherwise push every genuine hotspot to the
   bottom of the colour ramp and the map would read as uniformly cold.

   Boundary overlay
   ----------------
   The district boundary is drawn ON TOP of the surface as a reference frame —
   it does not clip the heat. A kernel density surface is continuous and
   deliberately spills past a point's own district; clipping it at the border
   would invent a discontinuity the data does not have. The overlay uses its
   own layers over the shared 'district' source, so it is independent of the
   District boundary switch in the Layers panel.

   Palette
   -------
   The ramp is the same green -> yellow -> red family the FWD D0 legend uses
   (FWD_D0_STOPS in 06-assets.js), so "red is bad / red is heavy" reads the
   same way across the application.

   Loaded as an ordered classic script from map.html; all modules share one
   global scope, so load order is preserved exactly.
   ============================================================ */

const HM_SRC   = 'hm-pts';
const HM_LAYER = 'hm-heat';
const HM_BND   = ['hm-bnd-casing','hm-bnd-line','hm-bnd-label'];

/* Density-mode sentinel. A real attribute key can never be this, because every
   discovered key comes from a CSV column name. */
const HM_DENSITY = '__density__';

const HM = {
  dataset : 'soil',      // 'soil' | 'traffic'
  attr    : HM_DENSITY,
  radius  : 26,          // kernel radius in px at z11
  intensity: 1,
  boundary: 'district',  // 'none' | 'district'
  ran     : false,
  last    : null,        // descriptor of the surface now on the map (see KLHeatmap)
  attrs   : {soil:null, traffic:null}   // discovered attribute lists, cached per dataset
};

const HM_RAMP = [
  [0.00,'rgba(26,152,80,0)'],
  [0.12,'rgba(26,152,80,0.55)'],
  [0.30,'#91cf60'],
  [0.48,'#fee08b'],
  [0.66,'#fdae61'],
  [0.83,'#f46d43'],
  [1.00,'#b2182b']
];

const HM_DATASETS = {
  soil   : {label:'Sub-Grade Soil',   assetType:'subgrade', scopeProp:'__sec',  dot:'#8a4d1f', unit:'test point'},
  traffic: {label:'Traffic Stations', assetType:null,       scopeProp:'section', dot:'#1565c0', unit:'station'}
};

/* Count-derived traffic measures. These are not CSV columns — they are computed
   from the stored count object (16-traffic.js), so they are declared rather than
   discovered. `pick` receives the normalised count object for one station. */
const HM_TRAFFIC_MEASURES = [
  {key:'__adt',   label:'ADT — average daily traffic', unit:'veh/day',
   pick:c => (c && c.total != null) ? (+c.total)/Math.max(1, +c.days||1) : null},
  {key:'__total', label:'Survey total volume',         unit:'veh',
   pick:c => (c && c.total != null) ? +c.total : null},
  {key:'__peak',  label:'Peak-hour volume',            unit:'veh/h',
   pick:c => {
     if(!c || !c.byHour || !c.byHour.length) return null;
     const d = Math.max(1, +c.days||1); let mx = 0;
     for(let i=0;i<24;i++){ const a=(+c.byHour[i]||0)/d; if(a>mx) mx=a; }
     return mx>0 ? mx : null;
   }}
];

/* Columns that parse as numbers but mean nothing as a heat weight: coordinates,
   identifiers and the chainage that only says how far along a road the sample
   sits. Offering them would make the dropdown look thorough and the map wrong. */
const HM_SKIP_KEYS = new Set([
  'lat','latitude','lng','lon','long','longitude','x','y','id','fid','objectid',
  'slno','sino','srno','sno','serialno','no','chainage','ch','chainagem',
  'fromch','toch','fromchainage','tochainage','startch','endch','year'
]);

function hmEl(id){ return document.getElementById(id); }
function hmEsc(s){ return String(s==null?'':s).replace(/[&<>"]/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c])); }
function hmKey(k){ return String(k).toLowerCase().replace(/[^a-z0-9]/g,''); }
function hmNum(v){
  if(v==null || v==='') return NaN;
  const n = parseFloat(String(v).replace(/,/g,'').trim());
  return isFinite(n) ? n : NaN;
}
function hmFmt(v){
  if(v==null || !isFinite(v)) return '—';
  const a = Math.abs(v);
  const d = a>=100 ? 0 : a>=10 ? 1 : 2;
  try{ return Number(v.toFixed(d)).toLocaleString('en-IN'); }catch(e){ return v.toFixed(d); }
}
function hmSetStatus(html, tone){
  const el = hmEl('hmStatus');
  if(el){ el.innerHTML = html || ''; el.className = 'hm-status' + (tone ? ' '+tone : ''); }
}

/* ---------- attribute discovery ---------- */

/* Friendly labels and units for the soil columns, lifted straight from the
   popup schema so the dropdown says "CBR (soaked) · %" and not "CBR". Keyed on
   the normalised column name, because the CSV header casing has drifted
   between imports. */
function hmSchemaLabels(assetType){
  const out = {};
  try{
    const sch = (typeof ASSET_UNITS_SCHEMA !== 'undefined') && ASSET_UNITS_SCHEMA[assetType];
    if(!sch) return out;
    (sch.groups||[]).forEach(g => (g.rows||[]).forEach(r => {
      out[hmKey(r[0])] = {label:r[1], unit:r[2]||'', order:Object.keys(out).length};
    }));
  }catch(e){}
  return out;
}

/* A column qualifies as a heat weight when it is numeric nearly everywhere it
   is filled in, and filled in often enough to interpolate: at least 3 values
   and at least 80% of the non-blank cells parsing as numbers. A column that is
   80% blank still qualifies — sparse lab results are normal — but one that is
   mostly text (soil type, remarks) does not. */
function hmDiscoverAttrs(features){
  const stat = {};
  (features||[]).forEach(f => {
    const p = (f && f.properties) || {};
    for(const k in p){
      if(k.charAt(0)==='_') continue;
      const nk = hmKey(k);
      if(!nk || HM_SKIP_KEYS.has(nk)) continue;
      const v = p[k];
      if(v==null || v==='') continue;
      const s = (stat[k] || (stat[k] = {filled:0, num:0, min:Infinity, max:-Infinity}));
      s.filled++;
      const n = hmNum(v);
      if(!isNaN(n)){ s.num++; if(n<s.min)s.min=n; if(n>s.max)s.max=n; }
    }
  });
  const labels = hmSchemaLabels(HM_DATASETS[HM.dataset].assetType);
  const out = [];
  Object.keys(stat).forEach(k => {
    const s = stat[k];
    if(s.num < 3 || s.num/s.filled < 0.8) return;
    /* A column with one repeated value has no gradient to show. */
    if(s.min === s.max) return;
    const meta = labels[hmKey(k)];
    out.push({key:k, label:(meta?meta.label:k), unit:(meta?meta.unit:''), n:s.num,
              order:(meta ? meta.order : 900 + out.length)});
  });
  out.sort((a,b) => a.order - b.order || a.label.localeCompare(b.label));
  return out;
}

/* ---------- data ---------- */

/* Both datasets are already cached by their own module once the user has
   touched them; neither is fetched twice. Soil goes through loadAssetData()
   (the same call the Report Hub and the scope card use), traffic through
   loadTraffic(). */
function hmEnsureData(){
  if(HM.dataset === 'traffic'){
    /* Wait for the saved station groups before reading the stations. Without
       them CalcRules.stationKey() falls back to guessing at a trailing A/B,
       which is right for most names and wrong for any pair the Calculation
       Rules module groups by hand — and a heat map built on the guess would
       silently disagree with the Traffic dashboard. */
    const rules = (typeof CalcRules !== 'undefined' && CalcRules.ready)
      ? CalcRules.ready().catch(() => {}) : Promise.resolve();
    const stations = (typeof TRAFFIC_STN !== 'undefined' && TRAFFIC_STN.features && TRAFFIC_STN.features.length)
      ? Promise.resolve(TRAFFIC_STN.features)
      : (typeof loadTraffic !== 'function'
          ? Promise.resolve([])
          : new Promise(res => loadTraffic(() => res((typeof TRAFFIC_STN!=='undefined' && TRAFFIC_STN.features) || []))));
    return rules.then(() => stations);
  }
  const spec = (typeof ASSETS !== 'undefined') && ASSETS.find(a => a.type === 'subgrade');
  if(!spec || typeof loadAssetData !== 'function') return Promise.resolve([]);
  return loadAssetData(spec).then(gj => (gj && gj.features) || [], () => []);
}

/* Honour the Road Network filter, exactly as the Chainage Locator does: an
   analysis run while a filter is on must describe the roads on screen, not the
   whole state. NET_SCOPE is null when no filter is applied. */
function hmScoped(features){
  const scope = window.NET_SCOPE;
  if(!scope || !scope.size) return {rows:features, scoped:false, dropped:0};
  const prop = HM_DATASETS[HM.dataset].scopeProp;
  const rows = (features||[]).filter(f => {
    const p = (f && f.properties) || {};
    return scope.has(String(p[prop]!=null ? p[prop] : ''));
  });
  return {rows:rows, scoped:true, dropped:(features||[]).length - rows.length};
}

/* The traffic station-group correction (Calculation Rules · Traffic station
   groups), applied here exactly as the Traffic dashboard and the station popup
   apply it: TVM_STN_021A and TVM_STN_021B are the two carriageways of ONE
   physical count station, so they become ONE heat point.

   It matters in both modes, differently:
     · Density — ungrouped, every dual-carriageway station counts twice and the
       dual roads light up hotter purely because they were surveyed in two
       directions. That is an artefact of the storage format, not a real
       concentration of stations.
     · ADT / volume — ungrouped, each carriageway carries only its own
       direction's traffic, so a dual road reads as two medium stations instead
       of the one heavy one it is. Merging restores the physical station's real
       ADT, and reconciles the heat map with the dashboard's figure.

   Counts are merged over EVERY member of the group, including a member the
   Road Network filter excluded — a station's ADT is a property of the station,
   not of the filter. Presence is the opposite: the group appears only if at
   least one member survived the filter. That is the same split the network
   scope card makes (05-road-network.js), so the tile count and this map agree.

   The merged point sits at the mean of its in-scope members' positions. Both
   carriageways are placed from the same chainage on parallel sections, so they
   are metres apart and the mean is the physical station. */
function hmGroupTraffic(rows){
  const haveRules = (typeof CalcRules !== 'undefined' && CalcRules.stationKey);
  const groups = new Map();
  (rows||[]).forEach(f => {
    const p = (f && f.properties) || {};
    const nm = String(p.name==null ? '' : p.name).trim();
    /* An unnamed row cannot be grouped and must not be merged with the other
       unnamed rows, so it keys on itself. */
    const key = (nm && haveRules) ? CalcRules.stationKey(nm) : ('#' + groups.size);
    let g = groups.get(key);
    if(!g){ g = {names:[], rows:[], x:0, y:0, n:0}; groups.set(key, g); }
    if(nm && g.names.indexOf(nm) < 0) g.names.push(nm);
    g.rows.push(f);
    const c = f.geometry && f.geometry.coordinates;
    if(c){ g.x += c[0]; g.y += c[1]; g.n++; }
  });

  const out = [];
  let mergedAway = 0, partial = 0;
  groups.forEach(g => {
    if(!g.n) return;
    const first = g.rows[0], fp = (first.properties)||{};
    const nm = String(fp.name==null ? '' : fp.name).trim();
    /* Every name in the group, not just the ones that survived the filter. */
    let members = g.names;
    if(nm && haveRules && CalcRules.stationGroupMembers){
      const all = CalcRules.stationGroupMembers(nm);
      if(all && all.length) members = all;
    }
    let counts = null;
    try{
      const objs = members.map(m => (typeof trfCountObj === 'function')
        ? trfCountObj(TRAFFIC_COUNTS[m]) : (TRAFFIC_COUNTS||{})[m]);
      counts = (typeof trfMergeCounts === 'function')
        ? trfMergeCounts(objs) : (objs.filter(Boolean)[0] || null);
    }catch(e){}
    const label = (nm && haveRules && CalcRules.stationLabel) ? CalcRules.stationLabel(nm) : nm;
    mergedAway += (g.rows.length - 1);
    /* The group carries traffic from members that are not among the rows here —
       filtered out, or never placed on a road. The value is still right, but
       the readout has to say so. */
    if(members.length > g.rows.length) partial++;
    out.push({type:'Feature',
      geometry:{type:'Point', coordinates:[g.x/g.n, g.y/g.n]},
      properties:{name:label, section:fp.section||'', __counts:counts,
                  __rows:g.rows.length, __members:members.length}});
  });
  return {rows:out, mergedAway:mergedAway, partial:partial};
}

/* Read the chosen measure off one feature. Traffic reads the MERGED count
   object hmGroupTraffic() stamped on the point, never TRAFFIC_COUNTS directly —
   going back to the raw store here would undo the station-group correction.
   Soil's values come off the feature itself. */
function hmValueOf(f){
  if(HM.attr === HM_DENSITY) return 1;
  const p = (f && f.properties) || {};
  if(HM.dataset === 'traffic'){
    const m = HM_TRAFFIC_MEASURES.find(x => x.key === HM.attr);
    if(m){
      const v = m.pick(p.__counts);
      return (v==null || !isFinite(v)) ? NaN : v;
    }
  }
  return hmNum(p[HM.attr]);
}

function hmPercentile(sorted, q){
  if(!sorted.length) return NaN;
  const i = (sorted.length-1) * q, lo = Math.floor(i), hi = Math.ceil(i);
  return lo===hi ? sorted[lo] : sorted[lo] + (sorted[hi]-sorted[lo])*(i-lo);
}

/* ---------- map layers ---------- */

/* Keep the source points DRAWABLE above the surface: a heat map with the
   stations hidden underneath it cannot be checked against its own inputs.
   So the surface is inserted below the first point layer that exists, not on
   top of the style. */
function hmBeforeLayer(){
  const candidates = ['as-soil-icon','as-soil','trafficstn-lyr','as-culvert','as-core','as-crust','as-furnp'];
  for(const l of candidates) if(map.getLayer(l)) return l;
  return undefined;
}

function hmRampExpr(){
  const e = ['interpolate',['linear'],['heatmap-density']];
  HM_RAMP.forEach(s => { e.push(s[0]); e.push(s[1]); });
  return e;
}

function hmPaint(){
  const r = HM.radius, k = HM.intensity;
  return {
    /* Density mode must not go through the weight ramp at all — every point
       weighs 1, and remapping 1 through an interpolate whose domain is 0..1
       is just an identity with rounding. */
    'heatmap-weight': (HM.attr === HM_DENSITY)
      ? 1
      : ['interpolate',['linear'],['get','__w'], 0, 0.08, 1, 1],
    'heatmap-intensity': ['interpolate',['linear'],['zoom'], 6, k*0.6, 11, k*1.1, 16, k*2.0],
    'heatmap-radius':    ['interpolate',['linear'],['zoom'], 6, r*0.5,  11, r,     16, r*2.4],
    'heatmap-color':     hmRampExpr(),
    'heatmap-opacity':   ['interpolate',['linear'],['zoom'], 6, 0.85, 14, 0.72, 17, 0.55]
  };
}

/* MapLibre throws "Style is not done loading" from addSource/addLayer until the
   style has settled. Opening the panel and pressing Run within the first
   seconds of a cold map open is entirely normal, so wait for the style rather
   than failing on it.

   Resolves TRUE when the style is ready and FALSE if it never arrives. Both
   answers matter: pushing on after the wait expires would just re-raise the
   exact MapLibre exception this exists to avoid, and "Style is not done
   loading" tells the user nothing they can act on. The timeout is generous
   because the base map is known to crawl on the PWD office connection. */
function hmStyleReady(){
  if(map.isStyleLoaded && map.isStyleLoaded()) return Promise.resolve(true);
  return new Promise(res => {
    let done = false;
    const fin = ok => { if(done) return; done = true; map.off('styledata', tick); res(ok); };
    const tick = () => { if(map.isStyleLoaded && map.isStyleLoaded()) fin(true); };
    map.on('styledata', tick);
    setTimeout(() => fin(!!(map.isStyleLoaded && map.isStyleLoaded())), 15000);
    tick();
  });
}

function hmDraw(fc){
  if(map.getSource(HM_SRC)) map.getSource(HM_SRC).setData(fc);
  else map.addSource(HM_SRC, {type:'geojson', data:fc});
  if(!map.getLayer(HM_LAYER)){
    map.addLayer({id:HM_LAYER, type:'heatmap', source:HM_SRC, paint:hmPaint()}, hmBeforeLayer());
  }else{
    const p = hmPaint();
    Object.keys(p).forEach(k => map.setPaintProperty(HM_LAYER, k, p[k]));
  }
}

/* The overlay draws over the SHARED 'district' source but with its own layer
   ids, so turning the District boundary switch off in the Layers panel does
   not strip the frame off a running analysis. */
function hmBoundary(){
  hmClearBoundary();
  if(HM.boundary !== 'district') return Promise.resolve();
  const add = () => {
    if(!map.getSource('district')) return;
    if(!map.getLayer('hm-bnd-casing'))
      map.addLayer({id:'hm-bnd-casing', type:'line', source:'district',
        paint:{'line-color':'#ffffff','line-width':4.5,'line-opacity':0.75}});
    if(!map.getLayer('hm-bnd-line'))
      map.addLayer({id:'hm-bnd-line', type:'line', source:'district',
        paint:{'line-color':'#0e2038','line-width':2,'line-opacity':0.9}});
    try{
      if(!map.getLayer('hm-bnd-label'))
        map.addLayer({id:'hm-bnd-label', type:'symbol', source:'district',
          layout:{'text-field':nameExpr(),'text-size':12,'text-letter-spacing':0.08,'text-transform':'uppercase'},
          paint:{'text-color':'#0e2038','text-halo-color':'#ffffff','text-halo-width':2,'text-opacity':0.95}});
    }catch(e){}
  };
  if(map.getSource('district')){ add(); return Promise.resolve(); }
  if(typeof ensureBoundary !== 'function') return Promise.resolve();
  return ensureBoundary('district').then(add, () => {});
}

function hmClearBoundary(){
  HM_BND.forEach(l => { if(map.getLayer(l)) map.removeLayer(l); });
}

function hmClear(){
  hmClearBoundary();
  if(map.getLayer(HM_LAYER)) map.removeLayer(HM_LAYER);
  if(map.getSource(HM_SRC))  map.removeSource(HM_SRC);
  HM.ran = false;
  HM.last = null;
  const out = hmEl('hmOut'); if(out) out.style.display = 'none';
  const act = hmEl('hmActions'); if(act) act.style.display = 'none';
  hmSetStatus('');
}

/* ---------- the run ---------- */

let HM_BOUNDS = null;   // extent of the points the last run actually used

function hmRun(){
  const btn = hmEl('hmRun');
  if(btn){ btn.disabled = true; btn.classList.add('busy'); }
  hmSetStatus('Preparing ' + hmEsc(HM_DATASETS[HM.dataset].label.toLowerCase()) + '&hellip;');

  hmEnsureData().then(feats => {
    if(!feats.length){
      hmClear();
      hmSetStatus(HM.dataset==='traffic'
        ? 'No traffic stations are loaded. Import traffic data in the Data Console, then run again.'
        : 'No sub-grade soil points are loaded. Import the sub-grade soil survey in the Data Console, then run again.', 'bad');
      return;
    }

    const sc = hmScoped(feats);
    if(!sc.rows.length){
      hmClear();
      hmSetStatus('The Road Network filter leaves no ' + hmEsc(HM_DATASETS[HM.dataset].unit) + 's to analyse. Clear or widen the filter, then run again.', 'bad');
      return;
    }

    /* Apply the station-group correction AFTER scoping, so a group counts as
       present when any one of its carriageways is in the filter. */
    const grp = (HM.dataset === 'traffic') ? hmGroupTraffic(sc.rows) : {rows:sc.rows, mergedAway:0, partial:0};

    /* Report exclusions in the unit the map actually draws. After grouping,
       "2 rows dropped" can mean one physical station dropped and one merely
       losing a carriageway to the filter — reporting the raw row count would
       overstate what left the map. Re-group the unfiltered set to get the
       honest figure; at ~33 stations this costs nothing. */
    let dropped = sc.dropped;
    if(sc.scoped && HM.dataset === 'traffic')
      dropped = Math.max(0, hmGroupTraffic(feats).rows.length - grp.rows.length);

    /* Read every value first: the normalisation needs the whole distribution
       before a single point can be weighted. */
    const rows = [], vals = [];
    let noVal = 0;
    grp.rows.forEach(f => {
      const g = f && f.geometry;
      if(!g || g.type !== 'Point' || !g.coordinates) return;
      const v = hmValueOf(f);
      if(isNaN(v)){ noVal++; return; }
      rows.push({c:g.coordinates, v:v, p:(f.properties||{})});
      vals.push(v);
    });

    if(!rows.length){
      hmClear();
      hmSetStatus('No ' + hmEsc(HM_DATASETS[HM.dataset].unit) + ' carries a usable value for the selected measure.', 'bad');
      return;
    }

    const sorted = vals.slice().sort((a,b) => a-b);
    const p02 = hmPercentile(sorted, 0.02), p98 = hmPercentile(sorted, 0.98);
    const span = (p98 - p02) || 1;
    const stats = {
      n: rows.length, noVal: noVal,
      min: sorted[0], max: sorted[sorted.length-1],
      med: hmPercentile(sorted, 0.5),
      lo: p02, hi: p98,
      scoped: sc.scoped, dropped: dropped,
      mergedAway: grp.mergedAway, partial: grp.partial
    };

    let a=180,b=90,c=-180,d=-90;
    const fc = {type:'FeatureCollection', features: rows.map(r => {
      const w = Math.max(0, Math.min(1, (r.v - p02)/span));
      a=Math.min(a,r.c[0]); c=Math.max(c,r.c[0]); b=Math.min(b,r.c[1]); d=Math.max(d,r.c[1]);
      return {type:'Feature', geometry:{type:'Point', coordinates:r.c},
              properties:{__w:w, __v:r.v, name:r.p.name||'', section:r.p.section||r.p.__sec||''}};
    })};
    HM_BOUNDS = [[a,b],[c,d]];

    /* Fit only on the FIRST run of a surface. Changing the measure re-runs, and
       an engineer who has panned in to read one district must not be thrown
       back to the state extent every time they compare CBR against PI. "Zoom
       to extent" is there for when they do want it. */
    const first = !HM.ran;
    return hmStyleReady().then(ok => {
      if(!ok){
        hmSetStatus('The base map has not finished loading, so the heat map cannot be drawn on it yet. Wait for the map to appear, then run again.', 'bad');
        return;
      }
      hmDraw(fc);
      hmBoundary();
      HM.ran = true;
      /* Everything a second renderer needs to label this surface, recorded at
         the moment it is drawn. The Map Composer cannot derive the range from
         the layer: heatmap-color interpolates over heatmap-density, which is
         always 0..1, so reading the paint would legend a 6 240–17 760 veh/day
         ramp as "0 → 1". The real range only exists here. */
      const meta0 = hmAttrMeta();
      HM.last = {
        dataset: HM.dataset,
        datasetLabel: HM_DATASETS[HM.dataset].label,
        measureLabel: meta0.label,
        unit: meta0.unit || '',
        mode: (HM.attr === HM_DENSITY) ? 'density' : 'value',
        lo: stats.lo, hi: stats.hi, n: stats.n,
        unitNoun: HM_DATASETS[HM.dataset].unit,
        ramp: HM_RAMP.map(function (s) { return [s[0], s[1]]; })
      };
      hmRenderOut(stats);
      hmSetStatus('');
      if(first) hmZoom();
    });
  }).catch(err => {
    hmClear();
    hmSetStatus('Could not build the heat map: ' + hmEsc(err && err.message ? err.message : 'unexpected error') + '.', 'bad');
  }).then(() => {
    if(btn){ btn.disabled = false; btn.classList.remove('busy'); }
  });
}

function hmZoom(){
  if(!HM_BOUNDS) return;
  try{ map.fitBounds(HM_BOUNDS, {padding:70, maxZoom:12, duration:600}); }catch(e){}
}

/* ---------- readout ---------- */

function hmAttrMeta(){
  if(HM.attr === HM_DENSITY) return {label:'Point density', unit:''};
  if(HM.dataset === 'traffic'){
    const m = HM_TRAFFIC_MEASURES.find(x => x.key === HM.attr);
    if(m) return {label:m.label, unit:m.unit};
  }
  const list = HM.attrs[HM.dataset] || [];
  const a = list.find(x => x.key === HM.attr);
  return a ? {label:a.label, unit:a.unit} : {label:HM.attr, unit:''};
}

function hmRenderOut(s){
  const out = hmEl('hmOut'); if(!out) return;
  const meta = hmAttrMeta();
  const u = meta.unit ? (' <span class="hm-u">'+hmEsc(meta.unit)+'</span>') : '';
  const ds = HM_DATASETS[HM.dataset];
  const grad = 'linear-gradient(90deg,' + HM_RAMP.map(r => r[1]+' '+(r[0]*100).toFixed(0)+'%').join(',') + ')';

  let scale;
  if(HM.attr === HM_DENSITY){
    scale = '<div class="hm-scale"><span>Sparse</span><span>Clustered</span></div>' +
            '<div class="hm-legnote">Colour is the density of ' + hmEsc(ds.unit) + 's alone. It shows where the survey has coverage, not how severe the values are.</div>';
  }else{
    scale = '<div class="hm-scale"><span>' + hmFmt(s.lo) + '</span><span>' + hmFmt(s.hi) + '</span></div>' +
            '<div class="hm-legnote">Weighting is clipped to the 2nd–98th percentile (' + hmFmt(s.lo) + ' – ' + hmFmt(s.hi) +
            (meta.unit ? ' ' + hmEsc(meta.unit) : '') + '), so a single outlier cannot flatten the ramp.</div>';
  }

  const stats =
    '<div class="hm-stats">' +
      '<div class="hm-st"><span>' + hmEsc(ds.unit.charAt(0).toUpperCase()+ds.unit.slice(1)) + 's used</span><b>' + s.n.toLocaleString('en-IN') + '</b></div>' +
      (HM.attr === HM_DENSITY ? '' :
        '<div class="hm-st"><span>Minimum</span><b>' + hmFmt(s.min) + '</b></div>' +
        '<div class="hm-st"><span>Median</span><b>' + hmFmt(s.med) + '</b></div>' +
        '<div class="hm-st"><span>Maximum</span><b>' + hmFmt(s.max) + '</b></div>') +
    '</div>';

  const notes = [];
  if(s.mergedAway) notes.push('Station-group correction applied: <b>' + (s.n + s.noVal + s.mergedAway) +
    '</b> station rows were merged into <b>' + (s.n + s.noVal) + '</b> physical stations, so a dual carriageway counts once and carries both directions&rsquo; traffic.');
  if(s.noVal) notes.push('<b>' + s.noVal + '</b> ' + hmEsc(ds.unit) + (s.noVal===1?' had':'s had') + ' no value for this measure and ' + (s.noVal===1?'was':'were') + ' left out.');
  if(s.scoped) notes.push('The Road Network filter is on: <b>' + s.dropped + '</b> ' + hmEsc(ds.unit) + (s.dropped===1?'':'s') + ' outside it ' + (s.dropped===1?'was':'were') + ' excluded.');
  if(s.scoped && s.partial) notes.push('<b>' + s.partial + '</b> of the stations shown ' + (s.partial===1?'has a carriageway':'have a carriageway') +
    ' outside the filter. Its traffic is still included, because a station&rsquo;s ADT is a property of the station, not of the filter.');

  out.innerHTML =
    '<div class="hm-legt">' + hmEsc(ds.label) + ' &middot; ' + hmEsc(meta.label) + u + '</div>' +
    '<div class="hm-bar" style="background:' + grad + '"></div>' +
    scale + stats +
    (notes.length ? '<div class="hm-note">' + notes.join(' ') + '</div>' : '');
  out.style.display = 'block';
  const act = hmEl('hmActions'); if(act) act.style.display = 'flex';
}

/* ---------- panel wiring ---------- */

function hmSetDataset(ds){
  if(!HM_DATASETS[ds]) return;
  HM.dataset = ds;
  document.querySelectorAll('#hmSeg .hm-segbtn').forEach(b => b.classList.toggle('on', b.dataset.ds === ds));
  HM.attr = HM_DENSITY;
  hmBuildAttrs();
  if(HM.ran) hmClear();
}

/* The attribute list is discovered from the data, so it can only be built once
   the data is in hand. Until then the dropdown offers density (which needs no
   attribute) and says it is still looking. */
function hmBuildAttrs(){
  const sel = hmEl('hmAttr'); if(!sel) return;
  const cached = HM.attrs[HM.dataset];
  const fill = list => {
    const opts = ['<option value="'+HM_DENSITY+'">Point density (no weighting)</option>'];
    if(HM.dataset === 'traffic'){
      opts.push('<optgroup label="Traffic volume">');
      HM_TRAFFIC_MEASURES.forEach(m => opts.push('<option value="'+hmEsc(m.key)+'">'+hmEsc(m.label)+'</option>'));
      opts.push('</optgroup>');
    }
    if(list && list.length){
      opts.push('<optgroup label="'+(HM.dataset==='traffic'?'Station attributes':'Test result')+'">');
      list.forEach(a => opts.push('<option value="'+hmEsc(a.key)+'">'+hmEsc(a.label)+(a.unit?(' · '+hmEsc(a.unit)):'')+'</option>'));
      opts.push('</optgroup>');
    }
    sel.innerHTML = opts.join('');
    sel.value = HM.attr;
    if(sel.value !== HM.attr){ HM.attr = HM_DENSITY; sel.value = HM_DENSITY; }
    hmRenderAttrHint();
  };

  if(cached){ fill(cached); return; }
  fill(null);
  const hint = hmEl('hmAttrHint');
  if(hint) hint.textContent = 'Reading available measures…';
  const ds = HM.dataset;
  hmEnsureData().then(feats => {
    if(HM.dataset !== ds) return;                 // user switched while it loaded
    /* Traffic's usable measures are the count-derived ones, which are declared
       above; its station records carry only labels and coordinates, so there is
       nothing to discover there. */
    HM.attrs[ds] = (ds === 'traffic') ? [] : hmDiscoverAttrs(feats);
    fill(HM.attrs[ds]);
  }, () => { if(HM.dataset === ds) fill([]); });
}

function hmRenderAttrHint(){
  const hint = hmEl('hmAttrHint'); if(!hint) return;
  if(HM.attr === HM_DENSITY){
    hint.innerHTML = HM.dataset === 'traffic'
      ? 'Density alone maps where the <b>counters were placed</b>, not where the traffic is. Weight by ADT for demand. Grouped carriageways count as <b>one</b> station.'
      : 'Density alone maps where the <b>boreholes were taken</b>. Weight by CBR or PI to map subgrade quality.';
  }else{
    const m = hmAttrMeta();
    hint.innerHTML = 'Hot areas are where <b>' + hmEsc(m.label) + '</b> is high' + (m.unit ? ' (' + hmEsc(m.unit) + ')' : '') + '.';
  }
}

function hmOnAttr(v){ HM.attr = v; hmRenderAttrHint(); if(HM.ran) hmRun(); }
function hmOnBoundary(v){ HM.boundary = v; if(HM.ran) hmBoundary(); }

/* Radius and intensity retune the surface without re-reading the data — the
   weights are already in the source, only the paint changes. */
function hmOnTune(){
  const r = hmEl('hmRadius'), i = hmEl('hmIntensity');
  if(r){ HM.radius = +r.value; const o = hmEl('hmRadiusV'); if(o) o.textContent = HM.radius + ' px'; }
  if(i){ HM.intensity = (+i.value)/100; const o = hmEl('hmIntensityV'); if(o) o.textContent = HM.intensity.toFixed(2) + '×'; }
  if(HM.ran && map.getLayer(HM_LAYER)){
    const p = hmPaint();
    Object.keys(p).forEach(k => map.setPaintProperty(HM_LAYER, k, p[k]));
  }
}

/* Opened either from the rail or from the "Heat map" shortcut beside the two
   layers in the Layers panel, which preselects its own dataset. */
function openHeatmapPane(ds){
  if(typeof openPane === 'function') openPane('heatmap');
  if(ds && ds !== HM.dataset) hmSetDataset(ds);
  else hmBuildAttrs();
}

/* Nothing here may touch the data. hmBuildAttrs() downloads the sub-grade soil
   GeoJSON to discover its columns, and doing that at parse time would add a
   download to EVERY map open for a panel most sessions never open. The
   attribute list is therefore built on first open (openHeatmapPane), not now. */
(function initHeatmap(){
  const sel = hmEl('hmAttr');
  if(!sel) return;
  document.querySelectorAll('#hmSeg .hm-segbtn').forEach(b =>
    b.addEventListener('click', () => hmSetDataset(b.dataset.ds)));
  hmOnTune();
  sel.innerHTML = '<option value="'+HM_DENSITY+'">Point density (no weighting)</option>';
  hmRenderAttrHint();
})();

/* ============================================================
   Public descriptor — read by the Map Composer (38-map-composer.js).

   The Composer builds its sheet out of the LIVE style, so it can copy the
   heatmap layer itself without help. What it cannot do is label it: a
   heatmap's paint interpolates over `heatmap-density`, a normalised 0..1,
   so the range an engineer needs on the sheet ("6 240 – 17 760 veh/day")
   exists nowhere in the style. It is recorded on each run instead, and
   handed over here rather than letting another module reach into HM.

   Exposed as a plain object, not a class, to match KLLayers / KLUserLayers.
   ============================================================ */
window.KLHeatmap = {
  /** Is a surface on the map right now? Only then is it offerable. */
  active: function () {
    try { return !!(HM.ran && map.getLayer(HM_LAYER)); } catch (e) { return false; }
  },
  /** Render layer ids in draw order: the surface, then its boundary frame. */
  layerIds: function () {
    var out = [];
    try {
      if (map.getLayer(HM_LAYER)) out.push(HM_LAYER);
      HM_BND.forEach(function (l) { if (map.getLayer(l)) out.push(l); });
    } catch (e) {}
    return out;
  },
  /** What the surface shows, for the legend. Null when nothing has been run. */
  info: function () {
    return HM.last ? JSON.parse(JSON.stringify(HM.last)) : null;
  },
  /** One line naming the dataset and its measure, e.g. for a sheet title. */
  title: function () {
    var i = HM.last;
    if (!i) return 'Heat map';
    return i.datasetLabel + ' — ' + i.measureLabel + (i.unit ? ' (' + i.unit + ')' : '');
  }
};
