/* ============================================================
   KLRAMS viewer · 05-road-network.js
   Road-network attribute metadata with colour-by and filter-by controls.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
// ---- road network: attribute metadata, colour-by, filter-by ----
const SKIP_ATTRS=new Set(['road','name','len','id']);
/* Kerala has 14 districts and the PWD_Sec attribute carries a similar
   handful of divisions — a 12-colour palette pushed most of them into the
   grey "other" bucket even though they're common values, not rare ones.
   Widened to 20 so district- and PWD-section-level colouring gives every
   value its own colour. */
const CAT_PALETTE=['#e0a33a','#3b6fa0','#2ba66a','#da4b43','#8a5cb8','#0fa3a3','#c2628e','#7a8b2f','#b06a2c','#5470c6','#9a6324','#46728e','#d97ec9','#3fae8f','#c9563c','#6f6fce','#4a90d9','#b8464f','#8c9c3f','#5c8a3a'];
const CAT_MAX=CAT_PALETTE.length;
/* Road_Num is an identifier, not a magnitude — a smooth gradient makes
   neighbouring numbers (45 vs 46) look almost identical. Hash each number
   into this wider, high-contrast palette instead, so adjacent road numbers
   land on unrelated colours. Order is deliberately non-monotonic (not just
   a hue ramp) to avoid any residual "nearby number -> nearby colour" drift. */
const ROAD_NUM_PALETTE=['#e0a33a','#3b6fa0','#da4b43','#2ba66a','#8a5cb8','#c2628e','#0fa3a3','#7a8b2f','#5470c6','#b06a2c','#46728e','#9a6324','#d97ec9','#3fae8f','#c9563c','#6f6fce'];
/* Some categorical attributes have far more distinct values than any hand-
   picked palette can cover (e.g. PWD_Sec repeats per district — 15 districts
   x a dozen-odd sections each is 100+ distinct labels). For those, hash each
   string to its own hue instead of falling back to a shared grey "other" —
   every value gets a colour, it's just generated rather than curated. */
function _strHash(s){let h=2166136261;for(let i=0;i<s.length;i++){h^=s.charCodeAt(i);h=Math.imul(h,16777619);}return h>>>0;}
function hashCatColor(s){
  const h=_strHash(String(s));
  /* golden-angle step keeps adjacent hashes from landing on similar hues */
  const hue=(h*137.508)%360;
  return 'hsl('+hue.toFixed(1)+',55%,50%)';
}
let ATTRS={}, netMode='all', netFilters=[];
let _netFitT=null;
function buildAttrMeta(gj){
  ATTRS={};
  const keys=new Set();
  gj.features.forEach(f=>Object.keys(f.properties||{}).forEach(k=>keys.add(k)));
  keys.forEach(k=>{
    if(SKIP_ATTRS.has(k))return;
    let numeric=true; const distinct=new Map(); let min=Infinity,max=-Infinity; let seen=0;
    for(const f of gj.features){
      const v=f.properties[k]; if(v==null||v==='')continue; seen++;
      const n=+v;
      if(isNaN(n)) numeric=false; else {min=Math.min(min,n);max=Math.max(max,n);}
      const s=String(v);
      if(distinct.has(s)) distinct.set(s,distinct.get(s)+1);
      else if(distinct.size<=400) distinct.set(s,1);
    }
    if(!seen)return;
    /* valuesByFreq drives colour assignment: the most COMMON values earn a
       distinct colour first, so a value that alphabetically sorts past the
       palette limit (but actually covers a big share of the network) no
       longer gets bucketed into the grey "other" catch-all. values (plain
       alphabetical) is kept for filter dropdowns/datalists, where sorting
       by name is what a user searching for one entry expects. */
    const valuesByFreq=[...distinct.keys()].sort((a,b)=>(distinct.get(b)-distinct.get(a))||a.localeCompare(b));
    ATTRS[k]={numeric:numeric&&min!==Infinity,min,max,values:[...distinct.keys()].sort(),valuesByFreq};
  });
  populateColorBySelect();
}
function populateColorBySelect(){
  const sel=document.getElementById('netColorBy');
  sel.innerHTML='<option value="__class__">Default (SH / MDR)</option>';
  Object.keys(ATTRS).sort().forEach(k=>{const o=document.createElement('option');o.value=k;o.textContent=k+(ATTRS[k].numeric?' (numeric)':'');sel.appendChild(o);});
}
/* Tile-mode twin of buildAttrMeta(gj): same ATTRS shape, but asked of
   /api/roads/attrs + /api/roads/attr-meta instead of scanned out of a
   downloaded FeatureCollection. Only worth doing because a MapLibre `match`
   expression has to have every category and its colour baked in before the
   first tile is even requested -- a paint expression can't compute "what
   values exist" from data it hasn't rendered yet, so this is the one
   question about the network a tile itself can never answer.
   /api/roads/attrs already excludes id/geom and never contains the
   road/name/len aliases (those aren't real columns), so no SKIP_ATTRS
   filtering is needed here the way buildAttrMeta needs it against raw
   feature properties. */
function buildAttrMetaFromServer(){
  return fetch('/api/roads/attrs').then(r=>r.json()).then(list=>{
    ATTRS={};
    return Promise.all(list.map(a=>
      fetch('/api/roads/attr-meta?attr='+encodeURIComponent(a.attr))
        .then(r=>r.json()).then(meta=>{ATTRS[a.attr]=meta;})
    ));
  }).then(populateColorBySelect);
}
function netColorByExpr(attr){
  const m=ATTRS[attr];
  if(!m)return netColor();
  if(m.numeric){
    if(/road.?num/i.test(attr)){
      const n=ROAD_NUM_PALETTE.length;
      /* Knuth multiplicative hash, then double-mod to guard against a
         negative result (MapLibre's % follows JS semantics). */
      const idx=['%',['%',['*',['to-number',['coalesce',['get',attr],0]],2654435761],n],n];
      /* 'at' on a plain literal array yields an untyped value — MapLibre's
         style validator rejects that for a color-typed paint property and
         setPaintProperty then throws, silently leaving the OLD paint in
         place (the legend still updates fine since it's plain HTML, not a
         GL expression — that mismatch is exactly what made the legend text
         change while the map colours stayed the same). to-color fixes it. */
      return ['to-color',['at',idx,['literal',ROAD_NUM_PALETTE]]];
    }
    const lo=m.min,hi=m.max===m.min?m.min+1:m.max;
    return ['interpolate',['linear'],['to-number',['coalesce',['get',attr],lo]],lo,'#9ec97f',(lo+hi)/2,'#e4a13a',hi,'#c0392b'];
  }
  const e=['match',['to-string',['get',attr]]];
  const list=m.valuesByFreq||m.values;
  if(list.length<=CAT_MAX){
    list.forEach((v,i)=>{e.push(v,CAT_PALETTE[i%CAT_PALETTE.length]);});
  }else{
    /* too many distinct values for a curated palette — hash every one of
       them to its own colour so nothing collapses into a shared "other". */
    list.forEach(v=>{e.push(v,hashCatColor(v));});
  }
  e.push('#9aa7b5');
  return e;
}
function renderNetLegend(attr){
  const el=document.getElementById('netLegend'); el.innerHTML='';
  const m=ATTRS[attr];
  if(!m){el.innerHTML=
    '<div class="lg"><span class="bar" style="background:'+CLS.SH+'"></span><span class="lgt">SH</span></div>'+
    '<div class="lg"><span class="bar" style="background:'+CLS.MDR+'"></span><span class="lgt">MDR</span></div>';
    return;}
  if(m.numeric&&/road.?num/i.test(attr)){
    el.innerHTML=ROAD_NUM_PALETTE.map(c=>`<span class="bar" style="background:${c};display:inline-block;width:14px;height:10px;margin-right:2px;border-radius:2px"></span>`).join('')
      +`<div class="lg"><span class="lgt">Each road number gets its own distinct colour (hashed, not a gradient)</span></div>`;
    return;
  }
  if(m.numeric){el.innerHTML=`<div class="lg"><span class="bar" style="background:linear-gradient(90deg,#9ec97f,#e4a13a,#c0392b)"></span><span class="lgt">${m.min} → ${m.max}</span></div>`;return;}
  const ordered=m.valuesByFreq||m.values;
  if(ordered.length<=CAT_MAX){
    ordered.forEach((v,i)=>{const lbl=dec(attr,v);el.innerHTML+=`<div class="lg"><span class="bar" style="background:${CAT_PALETTE[i%CAT_PALETTE.length]}"></span><span class="lgt" title="${lbl}">${lbl}</span></div>`;});
  }else{
    /* too many distinct values to list one swatch per row — show a sample
       (most common first) plus a note, matching the road-number legend's
       "hashed, not grouped" framing instead of implying a shared "other". */
    const shown=ordered.slice(0,24);
    shown.forEach(v=>{const lbl=dec(attr,v);el.innerHTML+=`<div class="lg"><span class="bar" style="background:${hashCatColor(v)}"></span><span class="lgt" title="${lbl}">${lbl}</span></div>`;});
    const more=ordered.length-shown.length;
    if(more>0)el.innerHTML+=`<div class="lg"><span class="lgt">+${more} more value(s), each auto-coloured (hashed, not grouped as "other")</span></div>`;
  }
}
function setNetMode(m){netMode=m;document.getElementById('nAll').classList.toggle('on',m==='all');document.getElementById('nAny').classList.toggle('on',m==='any');applyNetFilter();}
function addNetFilter(){netFilters.push({attr:Object.keys(ATTRS).sort()[0]||'',op:'=',val:''});renderNetFilters();}
function clearNetFilters(){netFilters=[];renderNetFilters();applyNetFilter();}
/* Build 167 — multi-value conditions via a click-to-open picker, not typing.
   The value cell is a BUTTON (never a free-text field) showing the chosen
   values; clicking it opens a popup with its OWN search box, a scrollable
   checklist and All/Clear. Because the actual filter value is only ever set
   by ticking a checkbox, a typo in the search box just narrows the list —
   it can never corrupt the filter into matching nothing. */
function nfVals(f){return String(f.val==null?'':f.val).split(',').map(s=>s.trim()).filter(s=>s!=='');}
/* Does one metadata row satisfy one filter condition? Shared by the map
   filter and the cascading value picker so both answer the same question. */
function nfRowMatches(p,r){
  const m=ATTRS[r.attr]||{};const raw=p[r.attr];if(raw==null||raw==='')return false;
  if(m.numeric){
    const v=+raw;
    if(r.op==='=')return nfVals(r).map(Number).some(n=>!isNaN(n)&&v==n);
    const c=+r.val;switch(r.op){case'>':return v>c;case'>=':return v>=c;case'<':return v<c;case'<=':return v<=c;default:return v==c;}
  }
  const s=String(raw);
  if(r.op==='contains')return s.toLowerCase().includes(String(r.val).toLowerCase());
  return nfVals(r).indexOf(s)>=0;
}
/* Roads still in play after every OTHER completed condition (skip the row
   whose picker is open). That is what makes a second condition's value list
   collapse to e.g. only Section_La values that carry Road_Num=34 — the first
   condition already narrowed the pool. */
function nfMatchMeta(exceptIdx){
  const meta=netMetaRows();
  const rows=netFilters.filter((f,i)=>i!==exceptIdx&&f.attr&&f.val!=='');
  if(!rows.length)return meta;
  return meta.filter(p=>{
    const t=rows.map(r=>nfRowMatches(p,r));
    return netMode==='all'?t.every(Boolean):t.some(Boolean);
  });
}
/* Distinct values of `attr` among the roads that survive the other conditions.
   Sorted the same way the unscoped ATTRS.values list is (alpha / numeric). */
function nfAttrValues(attr,exceptIdx){
  const set={};
  nfMatchMeta(exceptIdx).forEach(p=>{
    const v=p[attr];if(v==null||v==='')return;
    set[String(v)]=1;
  });
  return Object.keys(set).sort((a,b)=>a.localeCompare(b,undefined,{numeric:true}));
}
/* ATTRS entry for a row, but with .values replaced by the cascaded list so
   the picker / datalist / All button only offer in-scope choices. */
function nfAttrMetaForRow(i,f){
  const m=ATTRS[f.attr]||{numeric:false,values:[]};
  return {numeric:!!m.numeric,min:m.min,max:m.max,values:nfAttrValues(f.attr,i)};
}
let _nfPopRow=-1,_nfPopQuery='';
function nfCloseValPop(){const p=document.getElementById('nfValPop');if(p)p.remove();_nfPopRow=-1;_nfPopQuery='';}
function nfRefreshRowButton(i,f,m){
  const rowEl=document.querySelectorAll('#netFilters .frow')[i];if(!rowEl)return;
  const btn=rowEl.querySelector('.valbtn');if(!btn)return;
  const sel=nfVals(f);
  btn.querySelector('.vb-txt').textContent=sel.length?sel.join(', '):(m.numeric?'Number(s)…':'Select value(s)…');
  btn.classList.toggle('has',sel.length>0);
  btn.title=sel.length?sel.join(', '):'Click to choose value(s)';
}
function nfPopList(f,m){
  const p=document.getElementById('nfValPop');if(!p)return;
  const esc=v=>String(v==null?'':v).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
  const known=m.values.map(String);
  const sel=nfVals(f).filter(v=>known.indexOf(v)>=0);
  const q=_nfPopQuery.trim().toLowerCase();
  const items=known.filter(v=>!q||v.toLowerCase().indexOf(q)>=0);
  p.querySelector('#nvpCnt').textContent=sel.length+' selected · '+known.length+' value'+(known.length===1?'':'s');
  p.querySelector('#nvpList').innerHTML=items.length
    ?items.map(v=>'<label class="nvp-it"><span>'+esc(v)+'</span><input type="checkbox" value="'+esc(v)+'"'+(sel.indexOf(v)>=0?' checked':'')+'></label>').join('')
    :'<div class="nvp-empty">'+(known.length?'No values match “'+esc(_nfPopQuery)+'”.':'No values left under the other conditions.')+'</div>';
  p.querySelectorAll('#nvpList input').forEach(cb=>{cb.onchange=()=>{
    let vals=nfVals(f).filter(v=>known.indexOf(v)>=0);
    if(cb.checked){if(vals.indexOf(cb.value)<0)vals.push(cb.value);}else{vals=vals.filter(v=>v!==cb.value);}
    f.val=vals.join(', ');
    applyNetFilter();nfRefreshRowButton(_nfPopRow,f,m);nfPopList(f,m);
  };});
}
function nfOpenValPop(i,f,m,anchor){
  /* Re-resolve cascaded values at open time so a just-applied prior condition
     is reflected even if the row was rendered before that value was chosen. */
  m=nfAttrMetaForRow(i,f);
  nfCloseValPop();_nfPopRow=i;_nfPopQuery='';
  const p=document.createElement('div');p.className='nvp';p.id='nfValPop';
  p.innerHTML='<div class="nvp-top"><input type="text" class="nvp-q" placeholder="Search values…" autocomplete="off">'
    +'<button type="button" class="nvp-all">All</button><button type="button" class="nvp-clear">Clear</button></div>'
    +'<div class="nvp-list" id="nvpList"></div><div class="nvp-cnt" id="nvpCnt"></div>';
  document.body.appendChild(p);
  const r=anchor.getBoundingClientRect(),W=Math.max(240,Math.min(360,window.innerWidth-24));
  p.style.width=W+'px';
  p.style.left=Math.max(8,Math.min(r.left,window.innerWidth-W-8))+'px';
  p.style.top=(r.bottom+5)+'px';
  p.style.maxHeight=Math.max(160,Math.min(320,window.innerHeight-r.bottom-16))+'px';
  p.querySelector('.nvp-clear').onclick=()=>{f.val='';applyNetFilter();nfRefreshRowButton(i,f,m);nfPopList(f,m);};
  p.querySelector('.nvp-all').onclick=()=>{f.val=m.values.map(String).join(', ');applyNetFilter();nfRefreshRowButton(i,f,m);nfPopList(f,m);};
  const q=p.querySelector('.nvp-q');
  q.oninput=e=>{_nfPopQuery=e.target.value;nfPopList(f,m);};
  nfPopList(f,m);
  setTimeout(()=>{try{q.focus();}catch(e){}},0);
}
document.addEventListener('mousedown',function(e){
  if(_nfPopRow>=0&&!e.target.closest('#nfValPop')&&!e.target.closest('.valbtn'))nfCloseValPop();
},true);
document.addEventListener('scroll',function(e){
  const p=document.getElementById('nfValPop');
  if(p&&!(e.target&&p.contains&&e.target.nodeType===1&&p.contains(e.target)))nfCloseValPop();
},true);
window.addEventListener('resize',nfCloseValPop);
function renderNetFilters(){
  nfCloseValPop();
  const box=document.getElementById('netFilters');box.innerHTML='';
  netFilters.forEach((f,i)=>{
    const m=nfAttrMetaForRow(i,f);
    const row=document.createElement('div');row.className='frow';
    const vq=String(f.val==null?'':f.val).replace(/"/g,'&quot;');
    const as=Object.keys(ATTRS).sort().map(k=>`<option ${k===f.attr?'selected':''}>${k}</option>`).join('');
    const ops=m.numeric?['>','>=','=','<=','<']:['=','contains'];
    const os=ops.map(o=>`<option ${o===f.op?'selected':''}>${o}</option>`).join('');
    const listId='dl'+i;
    let valCell,isBtn=false;
    if(f.op==='='&&(m.values.length||(ATTRS[f.attr]&&ATTRS[f.attr].values&&ATTRS[f.attr].values.length))){
      /* click-to-open picker — values are cascaded (nfAttrMetaForRow), so a
         second condition only offers choices still present under the first */
      isBtn=true;
      const sel=nfVals(f).filter(v=>m.values.map(String).indexOf(v)>=0);
      const txt=sel.length?sel.join(', '):(m.numeric?'Number(s)…':'Select value(s)…');
      valCell=`<button type="button" class="valbtn${sel.length?' has':''}" title="${sel.length?sel.join(', ').replace(/"/g,'&quot;'):'Click to choose value(s)'}"><span class="vb-txt">${txt.replace(/</g,'&lt;')}</span><i class="vb-arr">&#9662;</i></button>`;
    }else if(m.numeric&&f.op==='='){
      valCell=`<input type="text" value="${vq}" placeholder="2, 8" title="One or more numbers, separated by commas">`;
    }else if(m.numeric){
      valCell=`<input type="number" step="0.1" value="${vq}">`;
    }else{
      const dl=`<datalist id="${listId}">${m.values.map(v=>`<option value="${String(v).replace(/"/g,'&quot;')}">`).join('')}</datalist>`;
      valCell=`<input type="text" list="${listId}" value="${vq}">${dl}`;
    }
    row.innerHTML=`<select>${as}</select><select>${os}</select>${valCell}<span class="x">&times;</span>`;
    const sels=row.querySelectorAll('select');
    sels[0].onchange=e=>{f.attr=e.target.value;f.op='=';f.val='';renderNetFilters();applyNetFilter();};
    sels[1].onchange=e=>{f.op=e.target.value;f.val='';renderNetFilters();applyNetFilter();};
    if(isBtn){
      const btn=row.querySelector('.valbtn');
      btn.onclick=()=>{if(_nfPopRow===i){nfCloseValPop();}else{nfOpenValPop(i,f,m,btn);}};
    }else{
      const iv=row.querySelector('input');
      iv.oninput=e=>{f.val=e.target.value;applyNetFilter();};
    }
    row.querySelector('.x').onclick=()=>{netFilters.splice(i,1);renderNetFilters();applyNetFilter();};
    box.appendChild(row);
  });
}
function netFilterExpr(){
  const rows=netFilters.filter(f=>f.attr&&f.val!=='');
  if(!rows.length)return null;
  const parts=rows.map(f=>{
    const m=ATTRS[f.attr]||{};
    if(m.numeric){
      if(f.op==='='){const nums=nfVals(f).map(Number).filter(n=>!isNaN(n));return ['in',['to-number',['coalesce',['get',f.attr],-999999]],['literal',nums]];}
      const map_={'>':'>','>=':'>=','<=':'<=','<':'<'};
      return [map_[f.op]||'==',['to-number',['coalesce',['get',f.attr],-999999]],+f.val];
    }
    if(f.op==='contains')return ['in',f.val,['to-string',['coalesce',['get',f.attr],'']]];
    return ['in',['to-string',['coalesce',['get',f.attr],'']],['literal',nfVals(f)]];
  });
  return [netMode==='all'?'all':'any',...parts];
}
/* Property bags for the network attribute filter. Tile mode keeps these in
   RoadsIndex (no geometry); GeoJSON mode never loads the index and already
   has every road in ROADS. Prefer the index when it has rows, otherwise
   fall back to ROADS — scanning an empty index produced "0 of 0" summaries
   and an empty NET_SCOPE Set that filtered roadnet-hit to nothing, so clicks
   stopped opening the road popup while a filter was active. */
function netMetaRows(){
  if(typeof RoadsIndex!=='undefined'&&RoadsIndex.all().length)return RoadsIndex.all();
  return Object.keys(ROADS||{}).map(k=>{
    const f=ROADS[k],p=Object.assign({},(f&&f.properties)||{});
    if(p.road==null)p.road=(p.Section_La!=null?p.Section_La:k);
    return p;
  });
}
function applyNetFilter(){
  const ex=netFilterExpr();
  if(map.getLayer('roadnet'))map.setFilter('roadnet',ex);if(map.getLayer('roadnet-casing'))map.setFilter('roadnet-casing',ex);
  const rows=netFilters.filter(f=>f.attr&&f.val!=='');
  let info='',list=null;
  /* Attribute filtering never reads geometry -- every predicate is against a
     plain property (District, Road_Class, chainage, ...). Prefer RoadsIndex
     (metadata only); fall back to ROADS in GeoJSON mode. Wrapped back into
     {properties:...} so renderNetScopeCard and everything downstream keeps
     the shape it already expects. Prefer a hydrated ROADS feature when we
     have one so fitFeaturesBounds can still zoom to the match. */
  if(rows.length){
    const meta=netMetaRows();
    list=meta.filter(p=>{
      const t=rows.map(r=>nfRowMatches(p,r));
      return netMode==='all'?t.every(Boolean):t.some(Boolean);
    }).map(p=>{
      const road=p.road!=null?String(p.road):'';
      const full=(typeof ROADS!=='undefined'&&ROADS[road])?ROADS[road]:null;
      return full||{properties:p};
    });
    info=list.length+' of '+meta.length+' roads match';
  }
  document.getElementById('netMatchInfo').textContent=info;
  /* Build 163 — scope every road-linked layer to the filtered roads.
     list must be checked for .length: an empty array is truthy in JS, and
     `new Set([])` would scope every layer (including roadnet-hit) to nothing
     — summary tiles all read 0 and road clicks stop firing. */
  window.NET_SCOPE=rows.length?(list&&list.length?new Set(list.map(f=>String((f.properties||{}).road))):new Set()):null;
  if(typeof applyNetScope==='function')applyNetScope();
  renderNetScopeCard(list,rows);
  if(_netFitT)clearTimeout(_netFitT);
  if(list&&list.length){const fl=list;_netFitT=setTimeout(()=>fitFeaturesBounds(fl),550);}
}

/* ============================================================
   Saved Road Network filters.
   The whole filter state is just {mode, rows:[{attr,op,val}]}, so a
   named filter is that object round-tripped through
   /api/saved-filters (kind="network"). Own filters plus any an admin
   has shared come back from one GET; only your own can be deleted.
   ============================================================ */
let NET_SAVED=[];
function _nsInfo(msg,bad){
  const el=document.getElementById('netSavedInfo');if(!el)return;
  el.textContent=msg||'';
  /* klrams-dark.css sets .statusline{color:...!important}, so a plain inline
     colour is ignored in the dark theme — only an inline !important wins. */
  if(bad)el.style.setProperty('color','#e07b7b','important');
  else el.style.removeProperty('color');
}
/* Current panel state, in the shape the server stores. Blank rows are
   dropped so an unfinished "+ Add condition" row never gets saved. */
function netFilterState(){
  return {mode:netMode,rows:netFilters.filter(f=>f.attr&&f.val!=='').map(f=>({attr:f.attr,op:f.op,val:f.val}))};
}
function renderNetSavedList(){
  const sel=document.getElementById('netSavedSel');if(!sel)return;
  const keep=sel.value;
  sel.innerHTML='';
  const ph=document.createElement('option');
  ph.value='';ph.textContent=NET_SAVED.length?'— Select a saved filter —':'— No saved filters —';
  sel.appendChild(ph);
  NET_SAVED.forEach(s=>{
    const o=document.createElement('option');
    o.value=String(s.id);
    /* textContent, not innerHTML: names are user-typed and shared filters
       carry another user's name, so neither may be parsed as markup. */
    o.textContent=s.name+(s.mine?'':' (shared by '+s.owner+')');
    sel.appendChild(o);
  });
  if(keep&&NET_SAVED.some(s=>String(s.id)===keep))sel.value=keep;
  onNetSavedPick();
}
function onNetSavedPick(){
  const sel=document.getElementById('netSavedSel'),del=document.getElementById('netSavedDel');
  if(!sel||!del)return;
  const s=NET_SAVED.find(x=>String(x.id)===sel.value);
  /* Shared filters are load-only for everyone but the user who saved them. */
  del.disabled=!(s&&s.mine);
  del.title=s?(s.mine?'Delete “'+s.name+'”':'Only '+s.owner+' can delete this shared filter'):'Delete the selected filter';
}
function refreshNetSavedList(){
  return fetch('/api/saved-filters?kind=network',{credentials:'same-origin',headers:{'Accept':'application/json'}})
    .then(r=>r.ok?r.json():[])
    .then(list=>{NET_SAVED=Array.isArray(list)?list:[];renderNetSavedList();})
    .catch(()=>{NET_SAVED=[];renderNetSavedList();});
}
function saveNetFilter(){
  const nameEl=document.getElementById('netSaveName');if(!nameEl)return;
  const name=nameEl.value.trim();
  if(!name){_nsInfo('Give the filter a name first.',true);nameEl.focus();return;}
  const state=netFilterState();
  if(!state.rows.length){_nsInfo('Add at least one condition before saving.',true);return;}
  const shareEl=document.getElementById('netSaveShared');
  const existing=NET_SAVED.find(s=>s.mine&&s.name.toLowerCase()===name.toLowerCase());
  if(existing&&!confirm('You already have a filter named “'+existing.name+'”. Overwrite it?'))return;
  _nsInfo('Saving…');
  fetch('/api/saved-filters',{
    method:'POST',credentials:'same-origin',
    headers:{'Content-Type':'application/json'},
    body:JSON.stringify({kind:'network',name:name,shared:!!(shareEl&&shareEl.checked),payload:state})
  }).then(r=>r.json().then(j=>({ok:r.ok,j:j})))
    .then(res=>{
      if(!res.ok||!res.j.ok){_nsInfo(res.j.error||'Could not save the filter.',true);return;}
      nameEl.value='';
      return refreshNetSavedList().then(()=>{
        const saved=NET_SAVED.find(s=>s.mine&&s.name===res.j.name);
        if(saved)document.getElementById('netSavedSel').value=String(saved.id);
        onNetSavedPick();
        _nsInfo('Saved “'+res.j.name+'”'+(res.j.shared?' and shared with all users.':'.'));
      });
    })
    .catch(()=>_nsInfo('Could not reach the server.',true));
}
function loadNetSaved(){
  const sel=document.getElementById('netSavedSel');if(!sel)return;
  const s=NET_SAVED.find(x=>String(x.id)===sel.value);
  if(!s){_nsInfo('Pick a saved filter to load.',true);return;}
  const p=s.payload||{};
  const rows=Array.isArray(p.rows)?p.rows:[];
  if(!rows.length){_nsInfo('“'+s.name+'” has no conditions saved.',true);return;}
  /* A filter saved against an earlier upload can name a column the current
     road table no longer has. Load what still resolves and say what was
     dropped, rather than silently applying a filter that matches nothing. */
  const known=rows.filter(r=>ATTRS[r.attr]);
  const missing=rows.filter(r=>!ATTRS[r.attr]).map(r=>r.attr);
  netFilters=known.map(r=>({attr:r.attr,op:r.op||'=',val:r.val==null?'':String(r.val)}));
  netMode=p.mode==='any'?'any':'all';
  document.getElementById('nAll').classList.toggle('on',netMode==='all');
  document.getElementById('nAny').classList.toggle('on',netMode==='any');
  renderNetFilters();
  applyNetFilter();
  _nsInfo(missing.length
    ?('Loaded “'+s.name+'” — skipped '+missing.length+' condition(s) on missing attribute(s): '+missing.join(', '))
    :('Loaded “'+s.name+'”.'),missing.length>0);
}
function deleteNetSaved(){
  const sel=document.getElementById('netSavedSel');if(!sel)return;
  const s=NET_SAVED.find(x=>String(x.id)===sel.value);
  if(!s||!s.mine)return;
  if(!confirm('Delete the saved filter “'+s.name+'”?'+(s.shared?'\n\nIt is shared, so it will disappear for all users.':'')))return;
  fetch('/api/saved-filters/'+encodeURIComponent(s.id),{method:'DELETE',credentials:'same-origin'})
    .then(r=>r.json().then(j=>({ok:r.ok,j:j})))
    .then(res=>{
      if(!res.ok||!res.j.ok){_nsInfo(res.j.error||'Could not delete the filter.',true);return;}
      sel.value='';
      return refreshNetSavedList().then(()=>_nsInfo('Deleted “'+s.name+'”.'));
    })
    .catch(()=>_nsInfo('Could not reach the server.',true));
}
/* "Share with all users" is an ADMIN/SUPER_ADMIN control. map.html resolves
   /api/me once and announces the role on kl-role-ready (window.__klRole);
   this module loads before that script runs, so the listener always catches
   it — the __klRole check is only a guard for a re-ordered load. This is UX
   only: SavedFilterController re-checks the authority before honouring
   shared=true. */
function _nsApplyRole(role){
  const row=document.getElementById('netShareRow');if(!row)return;
  row.style.display=(role==='ADMIN'||role==='SUPER_ADMIN')?'':'none';
}
document.addEventListener('kl-role-ready',e=>_nsApplyRole(e.detail));
if(typeof window.__klRole!=='undefined')_nsApplyRole(window.__klRole);

if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',refreshNetSavedList);
else refreshNetSavedList();

/* ============================================================
   Build 163 — on-map filter summary card (#netScopeCard).
   Shows the active Road Network filter criteria, live counts of
   everything in scope (sections, length, condition segments,
   bridges, culverts, FWD, traffic…) and the owners of the matched
   roads. Lazy layers refresh the card as they load. Close hides it
   until the filter criteria change again.
   ============================================================ */
let _nscState=null,_nscClosedSig=null,_nscLoadKicked=false;
function closeNetScopeCard(){const el=document.getElementById('netScopeCard');if(el)el.classList.remove('show');_nscClosedSig=_nscState?_nscState.sig:null;}
function toggleNetScopeCard(){
  const el=document.getElementById('netScopeCard'),b=document.getElementById('nscMin');if(!el)return;
  const min=!el.classList.contains('min');
  el.classList.toggle('min',min);
  if(b){b.innerHTML=min?'&plus;':'&minus;';b.title=min?'Expand':'Minimize';}
}
/* The card counts every road-linked dataset, so any that were never toggled on
   are fetched in the background the first time a filter activates. Their layers
   stay hidden (visibility follows the checkboxes); each loader refreshes the
   card via updateNetScopeCard() when it finishes. */
function ensureScopeDatasets(){
  if(_nscLoadKicked)return;_nscLoadKicked=true;
  const wanted={bridge:1,culvert:1,fwd:1,subgrade:1,bituminous_core:1}; /* only the datasets the card shows */
  /* loadAssetData, not loadAsset: the card counts the WHOLE network, which a tile can
     never answer, and it must not depend on the layer having been built. */
  try{if(typeof ASSETS!=='undefined'&&typeof loadAssetData==='function')ASSETS.forEach(a=>{if(wanted[a.type]&&(typeof ASSET_DATA==='undefined'||!ASSET_DATA[a.type]))loadAssetData(a);});}catch(e){}
  try{if(!Segs.collection())Segs.ensure();}catch(e){}
  try{if(typeof TRAFFIC_LOADED!=='undefined'&&!TRAFFIC_LOADED&&typeof loadTraffic==='function')loadTraffic();}catch(e){}
}
function _nscCountIn(feats,prop){let n=0;(feats||[]).forEach(f=>{const p=(f&&f.properties)||{};if(window.NET_SCOPE.has(String(p[prop]!=null?p[prop]:'')))n++;});return n;}
function renderNetScopeCard(list,rows){
  const el=document.getElementById('netScopeCard');if(!el)return;
  if(!window.NET_SCOPE||!rows||!rows.length){el.classList.remove('show');_nscState=null;return;}
  const sig=JSON.stringify(rows.map(r=>[r.attr,r.op,r.val]))+'|'+netMode;
  _nscState={list:list||[],rows:rows,sig:sig};
  if(_nscClosedSig===sig)return;               /* user closed this exact filter's card */
  _nscClosedSig=null;
  /* criteria chips */
  const xe=t=>String(t==null?'':t).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  document.getElementById('nscCrit').innerHTML=rows.map(r=>'<span class="nc"><i>'+xe(r.attr)+' '+xe(r.op)+'</i><span>'+xe(r.val)+'</span></span>').join('')
    +(rows.length>1?('<span class="nc" style="color:#9fb2cd;border-color:rgba(120,160,215,.3);background:rgba(120,160,215,.08)"><i>match</i> '+(netMode==='all'?'ALL':'ANY')+'</span>'):'');
  /* stat tiles — only datasets that are actually loaded */
  const L=list||[];
  /* Network length = sum of the roads' MEASURED length attribute (Measrd_Len
     or similar); falls back to len when no measured-length column exists.
     Dual-carriageway sections are stored as two rows (Section_La with a
     trailing A/B, Single_Du='Dual') that both carry the full length of the
     same physical stretch — summing them raw double-counts it. Group by the
     base label (A/B stripped for dual rows) and average each dual pair,
     matching the `corr` CTE in DashboardController.java. */
  const num=v=>{const n=parseFloat(String(v==null?'':v).replace(/,/g,''));return isNaN(n)?0:n;};
  const corrGroups=new Map();
  L.forEach(f=>{
    const p=f.properties||{};
    const mk=Object.keys(p).find(k=>/meas/i.test(k)&&/len/i.test(k));
    const len=num(mk!=null?p[mk]:p.len)||num(p.len);
    const isDual=/^dual/i.test(String(p.Single_Du||''));
    const label=String(p.Section_La||'');
    const baseLabel=(isDual&&/[AB]$/.test(label))?label.slice(0,-1):(label||('__f'+corrGroups.size));
    const key=(isDual?'D:':'S:')+baseLabel;
    if(!corrGroups.has(key))corrGroups.set(key,[]);
    corrGroups.get(key).push(len);
  });
  let lenM=0,lenRawM=0;
  corrGroups.forEach(lens=>{lenM+=lens.reduce((a,b)=>a+b,0)/lens.length;lenRawM+=lens.reduce((a,b)=>a+b,0);});
  const tiles=[['#19b277',L.length,'Road section'+(L.length===1?'':'s')],['#3b86e6',(lenM/1000).toFixed(1)+' km','Length'],
    /* Raw sum of every section label's Measrd_Len, carriageway A/B counted
       separately (no dual-pair averaging) — the "pakka"/as-recorded total. */
    ['#3b86e6',(lenRawM/1000).toFixed(1)+' km','Road Length (Carriageway considered)']];
  /* Chainage extent + start/end location are shown ONLY when the filter is a
     single condition on Road Name or Road Number with a single value. Any
     additional condition (or a multi-value comma list) hides them. */
  if(rows.length===1&&/road.?(name|num)/i.test(rows[0].attr)&&nfVals(rows[0]).length===1){
    const strKeys=['Rd_Str_cha','Start_Chaina','start_chainage','Road_Start_Chainage','Rd_Str_Cha','Start_Chainage','Str_Chainage'];
    const endKeys=['Rd_End_cha','End_Chaina','end_chainage','Road_End_Chainage','Rd_End_Cha','End_Chainage'];
    const strLocKeys=['Rd_Str_Loc','Start_Loc','Start_Location','Strt_Loc','start_location','Str_Loc','StartLoc'];
    const endLocKeys=['Rd_End_Loc','End_Loc','End_Location','end_location','End_Locn','EndLoc'];
    /* first-present value for a set of alias keys */
    const pick=(p,keys)=>{for(let i=0;i<keys.length;i++){const v=p[keys[i]];if(v!=null&&String(v).trim()!=='')return v;}return null;};
    /* Find the chainage extent across the matched sections. Track WHICH
       feature holds the min start-chainage (road origin) and the max
       end-chainage (road terminus) so we can read their locations.
       Start chainage defaults to 0 when not stored on the section. */
    let minStart=Infinity,maxEnd=0,minStartF=null,maxEndF=null;
    L.forEach(f=>{
      const p=f.properties||{};
      const sv=pick(p,strKeys); const s=sv!=null?num(sv):0;
      const ev=pick(p,endKeys); const e=ev!=null?num(ev):0;
      if(s<minStart){minStart=s;minStartF=f;}
      if(e>maxEnd){maxEnd=e;maxEndF=f;}
    });
    if(!isFinite(minStart))minStart=0;
    /* chainage tiles */
    tiles.push(['#e6c878',Math.round(minStart).toLocaleString()+' m','Road start chainage']);
    if(maxEnd>0)tiles.push(['#e6c878',Math.round(maxEnd).toLocaleString()+' m','Road end chainage']);
    /* location tiles — start location comes from the section at the
       minimum chainage (the road origin); end location from the section
       with the maximum end chainage (the road terminus). Shown only when
       the location column actually carries a value for that section. */
    const startLoc=minStartF?pick(minStartF.properties||{},strLocKeys):null;
    const endLoc=maxEndF?pick(maxEndF.properties||{},endLocKeys):null;
    const locLbl=v=>String(v==null?'':v).trim();
    if(locLbl(startLoc))tiles.push(['#3ad29a',xe(locLbl(startLoc)),'Start location',true]);
    if(locLbl(endLoc))tiles.push(['#3ad29a',xe(locLbl(endLoc)),'End location',true]);
  }
  /* Condition data available = lane-km of in-scope condition segments.
     condition_segments GROUPs BY (section_label, from_ch, to_ch) — every
     lane surveyed at that chainage (xsp: CC/CL1/CL2/CR1/CR2) is collapsed
     into ONE row, with lane_count carrying how many lanes were merged.
     So (to_ch - from_ch) alone is road/corridor km, not lane km — each
     segment's length must be multiplied by its lane_count to recover the
     true lane-wise total (a CR1+CR2 stretch counts twice, etc). Unlike the
     corridor "Length" tile, this is NOT averaged across a dual carriageway's
     A/B pair — each carriageway is surveyed separately and both legitimately
     contribute their own lane-km here. */
  if(Segs.collection()){
    const condM=Segs.scopedLaneMetres(window.NET_SCOPE);
    tiles.push(['#2ba66a',(condM/1000).toFixed(1),'Condition data available (lane km)']);
  }
  const AD=(typeof ASSET_DATA!=='undefined')?ASSET_DATA:{};
  [['bridge','#8a5cb8','Bridges'],['culvert','#e07b2a','Culverts'],['fwd','#7b1fa2','FWD points'],['subgrade','#8a4d1f','Soil tests'],['bituminous_core','#5c6470','Bituminous core test']]
    .forEach(t=>{const gj=AD[t[0]];if(gj&&gj.features)tiles.push([t[1],_nscCountIn(gj.features,'__sec'),t[2]]);});
  /* A dual carriageway's A/B pair (TVM_STN_021A / TVM_STN_021B) is ONE
     physical station — count distinct base names (trailing A/B after the
     station number stripped), matching SurveyDashboardController. */
  if(typeof TRAFFIC_STN!=='undefined'&&TRAFFIC_STN.features&&TRAFFIC_STN.features.length){
    const stnSet=new Set();
    TRAFFIC_STN.features.forEach(f=>{const p=(f&&f.properties)||{};if(!window.NET_SCOPE.has(String(p.section!=null?p.section:'')))return;const nm=String(p.name||'').trim();stnSet.add(nm?nm.replace(/([0-9])[ABab]$/,'$1'):('__anon'+stnSet.size));});
    tiles.push(['#1565c0',stnSet.size,'Traffic stations']);
  }
  document.getElementById('nscStats').innerHTML=tiles.map(t=>'<span class="nsc-stat'+(t[3]?' txt':'')+'" style="--sc:'+t[0]+'"><span class="n">'+t[1]+'</span><span class="l">'+t[2]+'</span></span>').join('');
  /* owners of the matched roads — prefer the "Current owner" attribute
     (Current_Ow / Current_Owner…) over any other owner-ish column */
  const attrKeys=Object.keys(ATTRS);
  const propKeys=L.length?Object.keys(L[0].properties||{}):[];
  const findKey=re=>attrKeys.find(k=>re.test(k))||propKeys.find(k=>re.test(k));
  const ownKey=findKey(/current[ _]?ow/i)||findKey(/owner/i);
  let ownHtml='';
  if(ownKey){
    const set={};L.forEach(f=>{const v=f.properties[ownKey];if(v!=null&&v!=='')set[String(v).trim()]=1;});
    const dv=v=>(typeof decodeVal==='function')?decodeVal('Owner',v):v;
    const owners=Object.keys(set).map(dv).sort();
    if(owners.length)ownHtml='<span class="ol">Current owner</span>'+owners.slice(0,6).map(o=>'<span class="oc">'+xe(o)+'</span>').join('')+(owners.length>6?('<span class="oc">+'+(owners.length-6)+' more</span>'):'');
  }
  const ownEl=document.getElementById('nscOwn');ownEl.innerHTML=ownHtml;ownEl.style.display=ownHtml?'':'none';
  el.classList.add('show');
  ensureScopeDatasets();
}
/* lazy datasets call this as they finish loading, so counts stay live */
function updateNetScopeCard(){if(_nscState&&window.NET_SCOPE)renderNetScopeCard(_nscState.list,_nscState.rows);}

/* ============================================================
   Build 163 — network scope.
   When the Road Network filter is active, every road-linked data
   layer (condition seg-*, PCI, FWD / soil / core / crust / bridges /
   culverts / furniture as-*, traffic stations) is limited to the
   matching roads. Implemented by wrapping map.setFilter/addLayer so
   the scope survives each module re-setting its own filters: the
   caller's filter is remembered as the "base" and the scope
   membership test is AND-ed on top of it.
   ============================================================ */
window.NET_SCOPE=null;
const _scopeBase={};
function scopePropFor(id){
  if(id.indexOf('seg-')===0)return 'road';
  if(id==='pci-avg'||id==='pci-worst')return 'road';
  if(id==='trafficstn-lyr')return 'section';
  if(id==='roadnet-hit')return 'road';
  if(id.indexOf('as-')===0)return '__sec';
  return null;
}
function scopeExpr(prop){return ['in',['to-string',['coalesce',['get',prop],'']],['literal',Array.from(window.NET_SCOPE)]];}
function scopeCombine(id,f){const p=scopePropFor(id);if(!p||!window.NET_SCOPE)return f;return (f==null)?scopeExpr(p):['all',f,scopeExpr(p)];}
(function(){
  const _sf=map.setFilter.bind(map),_al=map.addLayer.bind(map);
  map.setFilter=function(id,f){_scopeBase[id]=(f==null)?null:f;return _sf(id,scopeCombine(id,(f==null)?null:f));};
  map.addLayer=function(def,before){
    const r=_al(def,before);
    try{if(def&&def.id&&scopePropFor(def.id)){_scopeBase[def.id]=def.filter||null;if(window.NET_SCOPE)_sf(def.id,scopeCombine(def.id,_scopeBase[def.id]));}}catch(e){}
    return r;
  };
  window.applyNetScope=function(){
    try{
      (map.getStyle().layers||[]).forEach(function(L){
        if(!scopePropFor(L.id))return;
        if(!(L.id in _scopeBase)){const cur=map.getFilter(L.id);_scopeBase[L.id]=(cur==null)?null:cur;}
        _sf(L.id,scopeCombine(L.id,_scopeBase[L.id]));
      });
    }catch(e){}
  };
})();