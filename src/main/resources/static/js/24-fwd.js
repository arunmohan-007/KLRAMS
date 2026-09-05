/* ============================================================
   KLRAMS viewer · 24-fwd.js   (build 154)
   Dedicated FWD (Falling Weight Deflectometer) loader + lookup.

   The FWD survey is uploaded as the "fwd" asset (/api/assets/fwd/geojson) and
   already renders as the as-fwd map layer (06-assets.js). This module makes the
   SAME data available to the road inspector card and the NSV video HUD by
   chainage, independent of whether the map layer is toggled on.

   • Range data (from_ch .. to_ch), exactly like condition: at chainage 550 you
     read the 500-600 row, constant across the range, changing at the boundary.
   • D0..Dn are reported exactly as uploaded — MILLIMETRES — with no scaling.
   • Loads once on startup and caches; never re-fetches unless FWD.reload() is
     called, so values stay available for any call at any time.
   ============================================================ */
(function(){
  window.FWD = window.FWD || { byRoad:{}, ready:false, loading:false };

  function ck(k){return (typeof ckey==='function')?ckey(k):String(k).toLowerCase().replace(/[^a-z0-9]/g,'');}
  var RK=(typeof ROAD_KEYS!=='undefined')?ROAD_KEYS:['road','label','sectionla','section','sectionlabel','roadid','roadno','roadnumber','roadname','secid'];
  var FK=(typeof FROM_KEYS!=='undefined')?FROM_KEYS:['fromch','fromchainage','startch','startchainage','chainagefrom','chfrom','frch','fromm','startm','from','start'];
  var TK=(typeof TO_KEYS!=='undefined')?TO_KEYS:['toch','tochainage','endch','endchainage','chainageto','chto','tch','tom','endm','to','end'];
  function pp(p,cands){if(!p)return null;for(var k in p){if(cands.indexOf(ck(k))>=0){var v=p[k];if(v!=null&&v!=='')return v;}}return null;}
  /* Build 170 — FWD survey v2 adds Pavement Temp / Air Temp columns (°C).
     Matched loosely so 'Pavement Temp', 'Pav_Temp', 'Pavement Temperature',
     'Air Temp' etc. all resolve; kept as uploaded (no scaling). */
  function tempOf(p,re){for(var k in p){if(k.charAt(0)==='_')continue;if(re.test(ck(k))){var v=parseFloat(p[k]);if(!isNaN(v))return v;}}return null;}
  var PT_RE=/^pav(ement)?tem/, AT_RE=/^airtem/;

  /* index the FWD features by road, as chainage ranges carrying D0..Dn (mm) */
  function index(gj){
    var fs=(gj&&gj.features)||[];
    var by={};
    fs.forEach(function(f){
      var p=f.properties||{};var road=pp(p,RK);if(road==null||road==='')return;
      var from=+pp(p,FK), to=+pp(p,TK);
      var ds=[];
      Object.keys(p).forEach(function(k){if(k.charAt(0)==='_')return;var m=ck(k).match(/^d(\d+)$/);if(m){var v=p[k];if(v!=null&&v!=='')ds.push([+m[1],+v]);}});
      ds.sort(function(a,b){return a[0]-b[0];});
      var d0=null;for(var i=0;i<ds.length;i++){if(ds[i][0]===0){d0=ds[i][1];break;}}
      (by[road]=by[road]||[]).push({from:from,to:to,d0:d0,ds:ds,pt:tempOf(p,PT_RE),at:tempOf(p,AT_RE)});
    });
    FWD.byRoad=by; FWD.ready=true;
  }

  FWD.load=function(force){
    if(FWD.loading)return Promise.resolve();
    if(FWD.ready&&!force)return Promise.resolve();
    FWD.loading=true;
    /* Build 167 — reuse the shared download from 06-assets.js (fwdGeojsonFetch)
       instead of fetching /api/assets/fwd/geojson a second time per login. */
    var _p=(typeof fwdGeojsonFetch==='function')
      ?fwdGeojsonFetch(force)
      :fetch('/api/assets/fwd/geojson',{credentials:'same-origin'}).then(function(r){return r.ok?r.json():null;});
    return _p
      .then(function(gj){ if(gj&&gj.features&&gj.features.length)index(gj); FWD.loading=false; })
      .catch(function(){ FWD.loading=false; });
  };
  FWD.reload=function(){return FWD.load(true);};

  /* FWD record at a chainage: exact range first, else nearest range (so it still shows).
     Returns { d0, ds:[[n,mm],...], pt, at, from, to, exact } or null
     (pt/at = pavement/air temperature in °C, null on pre-v2 surveys). */
  FWD.at=function(road,ch){
    if(!FWD.ready){FWD.load();return null;}
    var arr=FWD.byRoad[road];
    if(!arr||!arr.length){
      /* fall back: try matching by the road's other identifiers via ROADS */
      if(typeof ROADS!=='undefined'&&ROADS[road]){var alt=pp(ROADS[road].properties||{},RK);if(alt!=null&&FWD.byRoad[alt])arr=FWD.byRoad[alt];}
      if(!arr||!arr.length)return null;
    }
    var best=null,bd=Infinity,exact=null;
    for(var i=0;i<arr.length;i++){var e=arr[i];
      if(!isNaN(e.from)&&!isNaN(e.to)){var lo=Math.min(e.from,e.to),hi=Math.max(e.from,e.to);
        if(ch>=lo&&ch<=hi){exact=e;break;}
        var d=Math.min(Math.abs(ch-lo),Math.abs(ch-hi));if(d<bd){bd=d;best=e;}
      } else if(!best){best=e;}
    }
    var r=exact||best;if(!r)return null;
    return {d0:r.d0, ds:r.ds, pt:r.pt, at:r.at, from:r.from, to:r.to, exact:!!exact};
  };

  /* Build 169 — no self-boot timer any more. It used to start the FWD download
     1.5s after page load, WHILE the ~6MB road network was still downloading, so
     the two competed for bandwidth on slow links. 15-main.js now drives the
     strict preload order (roads → condition → FWD → PCI) and calls FWD.load()
     at the right slot; FWD.at() still lazy-loads as a safety net. */
})();
