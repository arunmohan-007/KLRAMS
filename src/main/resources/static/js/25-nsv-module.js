/* ============================================================
   KLRAMS viewer · 25-nsv-module.js
   NSV Survey Videos — a standalone module (#nsvScreen) listing every
   road that has survey footage, with search + filters and a
   "View Video" action that opens the EXISTING full-featured player
   (live-track HUD, coverage, direction, speed, fullscreen).

   It only *reuses* the current NSV machinery (playSurveyFromPopup,
   CATALOG, ROADS, roadGapRanges) — it does not change any existing
   NSV option or behaviour.
   ============================================================ */
(function(){
  var nsvSearch='', nsvDir='', nsvGapsOnly=false, NSV_ROWS=null;

  function _prop(p,keys){for(var i=0;i<keys.length;i++){var k=keys[i];if(p&&p[k]!=null&&p[k]!=='')return String(p[k]);}return '';}
  function _num(v){if(v==null||v==='')return null;var n=Number(String(v).replace(/,/g,''));return isNaN(n)?null:n;}
  function nsvRoadLen(f){
    var p=f.properties||{};
    var len=_num(p.len);
    if(len==null){var a=_num(p.Rd_Str_cha),b=_num(p.Rd_End_cha);if(a!=null&&b!=null)len=Math.abs(b-a);}
    if((len==null||len<=0)&&typeof turf!=='undefined'){try{var line=(typeof lineOf==='function')?lineOf(f):null;if(line)len=turf.length(line,{units:'kilometers'})*1000;}catch(e){}}
    return len||0;
  }
  /* total surveyed-gap length (metres) for the road, from condition coverage */
  function nsvGapLen(roadId,len){
    if(typeof roadGapRanges!=='function'||!(len>0))return null;
    try{var g=roadGapRanges(roadId,len);return g.reduce(function(s,r){return s+(r[1]-r[0]);},0);}catch(e){return null;}
  }
  function buildNsvRows(){
    var rows=[];
    if(typeof CATALOG==='undefined'||typeof ROADS==='undefined')return rows;
    Object.keys(CATALOG).forEach(function(roadId){
      var entry=CATALOG[roadId];if(!(entry&&entry.file))return;
      var f=ROADS[roadId];if(!f)return;                       /* only roads present in the network */
      var p=f.properties||{};
      var len=nsvRoadLen(f);
      rows.push({
        sec:roadId,
        name:_prop(p,['Road_Name','name','ROAD_NAME','RoadName']),
        num:_prop(p,['Road_Num','Road_No','RoadNumber','road_num']),
        len:len,
        gap:nsvGapLen(roadId,len),
        dir:(entry.direction&&/rev|back/i.test(entry.direction))?'Reverse':'Forward'
      });
    });
    rows.sort(function(a,b){return String(a.name||a.sec).localeCompare(String(b.name||b.sec),undefined,{numeric:true});});
    return rows;
  }
  function nsvFiltered(){
    var rows=NSV_ROWS||[];
    var q=(nsvSearch||'').toLowerCase().trim();
    if(q)rows=rows.filter(function(r){return (r.name+' '+r.num+' '+r.sec).toLowerCase().indexOf(q)>=0;});
    if(nsvDir)rows=rows.filter(function(r){return r.dir===nsvDir;});
    if(nsvGapsOnly)rows=rows.filter(function(r){return r.gap!=null&&r.gap>1;});
    return rows;
  }
  var _m=function(n){return (n==null)?'–':Math.round(n).toLocaleString()+' m';};

  function renderNsv(){
    var body=document.getElementById('nsvBody');if(!body)return;
    var rows=nsvFiltered();
    var toolbar='<div class="reg-bar">'
      +'<input id="nsvSearch" class="reg-search" placeholder="Search by Road Name, Road Number or Section Label…" value="'+escH(nsvSearch)+'" oninput="nsvSetSearch(this.value)">'
      +'<select class="reg-sel" onchange="nsvSetDir(this.value)"><option value="">All directions</option><option value="Forward"'+(nsvDir==='Forward'?' selected':'')+'>Forward</option><option value="Reverse"'+(nsvDir==='Reverse'?' selected':'')+'>Reverse</option></select>'
      +'<label class="reg-sel" style="display:inline-flex;align-items:center;gap:7px;cursor:pointer"><input type="checkbox" '+(nsvGapsOnly?'checked':'')+' onchange="nsvSetGaps(this.checked)" style="margin:0"> Gaps only</label>'
      +'<span class="reg-count">'+rows.length+' road'+(rows.length===1?'':'s')+' with footage</span>'
      +'<span class="reg-exp"><button class="btn ghost" onclick="nsvExportExcel()">Excel</button><button class="btn ghost" onclick="nsvPrint()">PDF</button></span>'
      +'</div>';
    var head='<tr><th class="n">Sl</th><th>Road Name</th><th class="m">Section Label</th><th class="n">Length of Road</th><th class="n">Gap Length</th><th>Direction</th><th>View Video</th></tr>';
    var tb='';
    rows.forEach(function(r,i){
      var sec=String(r.sec).replace(/'/g,"\\'");
      tb+='<tr>'
        +'<td class="n">'+(i+1)+'</td>'
        +'<td>'+(r.name?escH(r.name):'–')+(r.num?' <span style="color:#8a93a3">('+escH(r.num)+')</span>':'')+'</td>'
        +'<td class="m">'+escH(r.sec)+'</td>'
        +'<td class="n">'+_m(r.len)+'</td>'
        +'<td class="n">'+(r.gap==null?'–':(r.gap<=1?'<span style="color:#2ba66a">None</span>':'<b style="color:#d9822b">'+_m(r.gap)+'</b>'))+'</td>'
        +'<td>'+escH(r.dir)+'</td>'
        +'<td><button class="nsv-viewbtn" onclick="nsvViewVideo(\''+sec+'\')">&#9658;&nbsp;View Video</button></td>'
        +'</tr>';
    });
    body.innerHTML=toolbar+'<div class="reg-tablewrap"><table class="reg-table"><thead>'+head+'</thead><tbody>'
      +(tb||'<tr><td colspan="7" style="text-align:center;color:#8a93a3;padding:18px">No survey footage matches.</td></tr>')+'</tbody></table></div>';
    var si=document.getElementById('nsvSearch');if(si&&nsvSearch){si.focus();si.setSelectionRange(si.value.length,si.value.length);}
  }
  window.nsvSetSearch=function(v){nsvSearch=v;renderNsv();};
  window.nsvSetDir=function(v){nsvDir=v;renderNsv();};
  window.nsvSetGaps=function(v){nsvGapsOnly=!!v;renderNsv();};

  window.openNsvScreen=function(){
    var s=document.getElementById('nsvScreen');if(!s)return;
    ['dashboard','pciScreen','condScreen','regScreen','reportHub','climate'].forEach(function(id){var e=document.getElementById(id);if(e)e.classList.remove('open');});
    s.classList.add('open');
    document.getElementById('nsvBody').innerHTML='<div class="dash-loading">Loading survey footage catalogue…</div>';
    var needRoads=(typeof ROADS==='undefined'||!ROADS||!Object.keys(ROADS).length);
    var needCat=(typeof CATALOG==='undefined'||!Object.keys(CATALOG||{}).length);
    var needSegs=(typeof DATA==='undefined'||!DATA||!DATA.features);   /* gap length needs condition segments */
    Promise.resolve()
      .then(function(){return (needRoads&&typeof loadRoads==='function')?loadRoads(true):null;})
      .then(function(){return (needCat&&typeof loadCatalog==='function')?loadCatalog():null;})
      .then(function(){return (needSegs&&typeof loadSegments==='function')?loadSegments():null;})
      .then(function(){NSV_ROWS=buildNsvRows();renderNsv();})
      .catch(function(e){var b=document.getElementById('nsvBody');if(b)b.innerHTML='<div class="dash-loading">Could not load: '+escH((e&&e.message)||e)+'</div>';});
  };
  window.closeNsvScreen=function(){var s=document.getElementById('nsvScreen');if(s)s.classList.remove('open');};

  /* Open the road in the existing full player. Closes this screen, centres the
     map on the road, then hands off to the very same code path as the popup's
     "Play footage" button — so HUD, coverage, direction, speed and fullscreen
     all work identically. */
  window.nsvViewVideo=function(roadId){
    closeNsvScreen();
    if(typeof closeLauncher==='function')closeLauncher();
    try{
      var f=ROADS[roadId];
      if(f&&f.geometry&&typeof maplibregl!=='undefined'&&typeof map!=='undefined'){
        var b=new maplibregl.LngLatBounds();
        (function ex(a){if(typeof a[0]==='number')b.extend(a);else a.forEach(ex);})(f.geometry.coordinates);
        if(!b.isEmpty())map.fitBounds(b,{padding:80,duration:600});
      }
    }catch(e){}
    if(typeof playSurveyFromPopup==='function')setTimeout(function(){playSurveyFromPopup(roadId,0);},240);
  };

  /* ---- export (respects the active filter/search) ---- */
  function nsvMatrix(){
    var rows=nsvFiltered();
    var header=['Sl','Road Name','Road Number','Section Label','Length of Road (m)','Gap Length (m)','Direction'];
    var data=rows.map(function(r,i){return [i+1,r.name||'',r.num||'',r.sec,r.len?Math.round(r.len):'',r.gap==null?'':Math.round(r.gap),r.dir];});
    return {header:header,data:data};
  }
  window.nsvExportExcel=function(){
    var m=nsvMatrix();var esc=function(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');};
    var html='<table border="1"><thead><tr>'+m.header.map(function(h){return '<th>'+esc(h)+'</th>';}).join('')+'</tr></thead><tbody>';
    m.data.forEach(function(row){html+='<tr>'+row.map(function(c){return '<td>'+esc(c)+'</td>';}).join('')+'</tr>';});
    html+='</tbody></table>';
    var blob=new Blob(['<html xmlns:o="urn:schemas-microsoft-com:office:office" xmlns:x="urn:schemas-microsoft-com:office:excel"><head><meta charset="utf-8"></head><body>'+html+'</body></html>'],{type:'application/vnd.ms-excel'});
    var a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='nsv-survey-videos.xls';document.body.appendChild(a);a.click();setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();},600);
  };
  window.nsvPrint=function(){
    var m=nsvMatrix();var esc=function(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');};
    var dt=new Date().toLocaleString('en-IN',{dateStyle:'medium',timeStyle:'short'});
    var isN=function(i){return i===0||i===4||i===5;};
    var rowsHtml='';m.data.forEach(function(row){rowsHtml+='<tr>'+row.map(function(c,i){return '<td'+(isN(i)?' class="n"':'')+'>'+esc(c===''?'-':c)+'</td>';}).join('')+'</tr>';});
    var w=window.open('','_blank');if(!w)return;
    w.document.write('<html><head><title>NSV Survey Videos</title><style>body{font-family:Arial,Helvetica,sans-serif;margin:16px;color:#16202e}h1{font-size:15px;margin:0 0 2px}.sub{color:#5a6b82;font-size:11px;margin-bottom:11px}table{border-collapse:collapse;width:100%;font-size:9.5px}th,td{border:1px solid #c2cad6;padding:3px 5px;text-align:left}th{background:#0e2038;color:#fff}td.n,th.n{text-align:right}tr:nth-child(even){background:#f3f6fa}@page{size:A4 landscape;margin:9mm}</style></head><body><h1>NSV Survey Videos &mdash; KLRAMS</h1><div class="sub">Kerala PWD &middot; RMMS Cell, KHRI &middot; '+esc(dt)+' &middot; '+m.data.length+' roads</div><table><thead><tr>'+m.header.map(function(h,i){return '<th'+(isN(i)?' class="n"':'')+'>'+esc(h)+'</th>';}).join('')+'</tr></thead><tbody>'+rowsHtml+'</tbody></table><scr'+'ipt>setTimeout(function(){window.print();},300);</scr'+'ipt></body></html>');
    w.document.close();
  };
})();
