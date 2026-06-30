/* ============================================================
   KLRAMS viewer · 23-climate.js
   Climate module — Flood Susceptibility.
   Import a flood CSV (Section_La, Road_Name, Land_Forms,
   Village, LSG, LSG_Type, Taluk, Block, Flood_Length) and get a
   dashboard of road length (km) exposed to flooding, by road
   class (SH / MDR / ODR / NH) and by land-form, with charts.
   The same data also feeds the Report Hub (Flood tab).
   Reuses the km chart helpers (donutCard / rankedBars) and the
   palette from 11-dashboard-charts.js.
   ============================================================ */

var CLIMATE_ROWS=null;

function climateClass(sec){var p=String(sec||'').split('/');return (p[1]||'').toUpperCase();}
function climateClassFull(l){return (typeof CLASS_SHORT!=='undefined'&&CLASS_SHORT[l])||(typeof dec==='function'&&dec('Road_Class',l))||l||'\u2014';}

/* ---- CSV parsing ---- */
function climateParseCsv(text){
  text=String(text||'').replace(/\r/g,'');
  var lines=text.split('\n').filter(function(l){return l.length;});
  if(!lines.length)return {header:[],rows:[]};
  function split(line){var out=[],cur='',q=false;for(var i=0;i<line.length;i++){var c=line[i];if(q){if(c==='"'){if(line[i+1]==='"'){cur+='"';i++;}else q=false;}else cur+=c;}else{if(c==='"')q=true;else if(c===','){out.push(cur);cur='';}else cur+=c;}}out.push(cur);return out;}
  var header=split(lines[0]).map(function(h){return h.trim();});
  var rows=[];for(var i=1;i<lines.length;i++)rows.push(split(lines[i]));
  return {header:header,rows:rows};
}
function climateBuildRows(text){
  var p=climateParseCsv(text),out=[];
  p.rows.forEach(function(cells){
    var o={};p.header.forEach(function(h,i){o[h]=(cells[i]!=null?String(cells[i]).trim():'');});
    var sec=o.Section_La||o.Section_Label||o.section||'';
    if(!sec&&!o.Road_Name)return;
    out.push({sec:sec,name:o.Road_Name||'',form:o.Land_Forms||'',village:o.Village||'',lsg:o.LSG||'',lsgType:o.LSG_Type||'',taluk:o.Taluk||'',block:o.Block||'',km:parseFloat(o.Flood_Length)||0,cls:climateClass(sec),props:o});
  });
  return out;
}

/* ---- grouping ---- */
function climateGroup(rows,keyFn){var m={};rows.forEach(function(r){var k=keyFn(r);if(k==null||k==='')k='\u2014';m[k]=(m[k]||0)+(+r.km||0);});return Object.keys(m).map(function(k){return {label:k,km:m[k]};}).sort(function(a,b){return b.km-a.km;});}

/* ---- class x susceptibility (km) matrix ---- */
function climateMatrix(rows,byClass,byForm){
  var classes=byClass.map(function(c){return c.label;}),forms=byForm.map(function(f){return f.label;});
  if(!classes.length||!forms.length)return '';
  var cell={};rows.forEach(function(r){var c=r.cls||'\u2014',fm=r.form||'\u2014';cell[c+'||'+fm]=(cell[c+'||'+fm]||0)+(+r.km||0);});
  var head='<tr><th>Class \\ Susceptibility</th>'+forms.map(function(fm){return '<th class="n">'+escH(fm)+'</th>';}).join('')+'<th class="n">Total km</th></tr>';
  var body='';
  byClass.forEach(function(cr){var c=cr.label;body+='<tr><td>'+escH(climateClassFull(c))+'</td>'+forms.map(function(fm){var v=cell[c+'||'+fm]||0;return '<td class="n">'+(v?fmtKm(v):'<span class="z">\u00b7</span>')+'</td>';}).join('')+'<td class="n"><b>'+fmtKm(cr.km)+'</b></td></tr>';});
  body+='<tr class="amx-tot"><td><b>Total</b></td>'+forms.map(function(fm){var v=(byForm.find(function(x){return x.label===fm;})||{}).km||0;return '<td class="n"><b>'+fmtKm(v)+'</b></td>';}).join('')+'<td class="n"><b>'+fmtKm(rows.reduce(function(s,r){return s+(+r.km||0);},0))+'</b></td></tr>';
  return '<div class="dcard"><div class="dcard-head"><h3>Road class &times; flood susceptibility</h3><span class="totchip">km</span></div><div class="sub">Road length (km) in each susceptibility type, by road class</div><div class="amx-wrap"><table class="amx"><thead>'+head+'</thead><tbody>'+body+'</tbody></table></div></div>';
}

/* ---- dashboard ---- */
function climateDashHtml(rows){
  if(!rows||!rows.length)return '<div class="dash-loading">No flood data imported yet. Use \u201cImport flood CSV\u201d above to load your CSV.</div>';
  var totKm=rows.reduce(function(s,r){return s+(+r.km||0);},0);
  var byClass=climateGroup(rows,function(r){return r.cls;});
  var byForm=climateGroup(rows,function(r){return r.form;});
  var byTaluk=climateGroup(rows,function(r){return r.taluk;});
  var secs={};rows.forEach(function(r){if(r.sec)secs[r.sec]=1;});
  var topClass=byClass[0];
  var kpi='<div class="kpi-row">'+
    '<div class="kpi feature" style="--kc:#2a6cb0"><div class="ringmark"></div><div class="kcap">Flood-prone road length</div><div class="kv">'+fmtKm(totKm)+'<span class="u">km</span></div><div class="kl">Total across the network</div></div>'+
    '<div class="kpi" style="--kc:#15976a"><div class="kcap">Road sections affected</div><div class="kv">'+Object.keys(secs).length+'</div><div class="kl">Distinct sections</div></div>'+
    '<div class="kpi" style="--kc:#d4a02e"><div class="kcap">Susceptibility types</div><div class="kv">'+byForm.length+'</div><div class="kl">Land-form categories</div></div>'+
    '<div class="kpi" style="--kc:#c2603f"><div class="kcap">Most affected class</div><div class="kv" style="font-size:20px">'+(topClass?escH(topClass.label):'\u2014')+'</div><div class="kl">'+(topClass?fmtKm(topClass.km)+' km':'')+'</div></div>'+
    '</div>';
  var classDonut=donutCard('Flood length by road class','Road km in flood-prone areas by classification',byClass,{colorFn:dColor,full:climateClassFull,centerSmall:'km'});
  var formDonut=donutCard('Flood length by susceptibility','Road km by flood land-form type',byForm,{centerSmall:'km'});
  var comp='<div class="comp-row">'+classDonut+formDonut+'</div>';
  var classBars='<div class="dcard"><div class="dcard-head"><h3>Road class ranked</h3><span class="totchip">km</span></div><div class="sub">Flood-prone length per road class</div>'+rankedBars(byClass,{colorFn:dColor,full:climateClassFull})+'</div>';
  var talukBars='<div class="dcard"><div class="dcard-head"><h3>Taluk ranked</h3><span class="totchip">'+byTaluk.length+'</span></div><div class="sub">Flood-prone length per taluk</div>'+rankedBars(byTaluk,{})+'</div>';
  var ranks='<div class="comp-row">'+classBars+talukBars+'</div>';
  return kpi+comp+ranks+climateMatrix(rows,byClass,byForm);
}

/* ---- open / close / import ---- */
function openClimate(){
  var s=document.getElementById('climate'); if(!s)return;
  ['dashboard','pciScreen','condScreen','regScreen','reportHub'].forEach(function(id){var e=document.getElementById(id);if(e)e.classList.remove('open');});
  s.classList.add('open'); renderClimate();
}
function closeClimate(){var s=document.getElementById('climate');if(s)s.classList.remove('open');}
function renderClimate(){var body=document.getElementById('climateBody');if(body)body.innerHTML=climateDashHtml(CLIMATE_ROWS||[]);}
function importClimateCsv(){var fi=document.getElementById('climateFile');if(fi)fi.click();}
function climateSetStatus(t){var s=document.getElementById('climateStatus');if(s)s.textContent=t;}
function climateOnFile(input){
  var file=input.files&&input.files[0]; if(!file)return;
  climateSetStatus('Reading '+file.name+'\u2026');
  file.text().then(function(t){CLIMATE_ROWS=climateBuildRows(t);climateSetStatus(CLIMATE_ROWS.length+' rows imported');if(typeof rhCache!=='undefined')delete rhCache.flood;renderClimate();input.value='';})
    .catch(function(){climateSetStatus('Could not read file.');input.value='';});
}
(function(){function wire(){var fi=document.getElementById('climateFile');if(fi)fi.addEventListener('change',function(){climateOnFile(this);});}if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',wire);else wire();})();
