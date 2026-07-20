/* ============================================================
   KLRAMS viewer · 29-export.js   (build 171)
   Per-layer data export — Shapefile (zip), GeoJSON, KML, KMZ, CSV.

   Every layer row in the Layers panel gets an export button that
   opens a compact format menu. Exports honour EXACTLY what the map
   is showing: the Road-Network scope (NET_SCOPE), the condition
   attribute filters, the FWD D0 range, the PCI range and the
   Traffic min-ADT filter are all applied; with no filter active the
   full dataset is exported. The Road-Condition menu also offers a
   per-parameter export (IRI, Crack, Pothole, …) with the segment
   worst/avg and per-lane values for just that parameter.

   Everything runs client-side on the GeoJSON already loaded for the
   map (ROADS / DATA / ASSET_DATA / TRAFFIC_STN) — no backend calls
   beyond the normal layer loaders, so it works even on the slow
   office links. The file includes a minimal ZIP (store), Shapefile
   (.shp/.shx/.dbf/.prj/.cpg) and KML writer — no new libraries.
   ============================================================ */
(function(){
'use strict';
var ENC=new TextEncoder();

/* ================= tiny ZIP writer (store method) ================= */
var CRC_T=(function(){var t=new Int32Array(256);for(var n=0;n<256;n++){var c=n;for(var k=0;k<8;k++)c=(c&1)?(0xEDB88320^(c>>>1)):(c>>>1);t[n]=c;}return t;})();
function crc32(u8){var c=-1;for(var i=0;i<u8.length;i++)c=CRC_T[(c^u8[i])&0xFF]^(c>>>8);return (c^-1)>>>0;}
function zipStore(entries){
  var parts=[],cd=[],off=0,now=new Date();
  var dT=((now.getHours()<<11)|(now.getMinutes()<<5)|(now.getSeconds()>>1))&0xFFFF;
  var dD=(((now.getFullYear()-1980)<<9)|((now.getMonth()+1)<<5)|now.getDate())&0xFFFF;
  entries.forEach(function(e){
    var name=ENC.encode(e.name),data=e.data,crc=crc32(data);
    var lh=new DataView(new ArrayBuffer(30));
    lh.setUint32(0,0x04034b50,true);lh.setUint16(4,20,true);lh.setUint16(6,0x0800,true);
    lh.setUint16(10,dT,true);lh.setUint16(12,dD,true);
    lh.setUint32(14,crc,true);lh.setUint32(18,data.length,true);lh.setUint32(22,data.length,true);
    lh.setUint16(26,name.length,true);
    parts.push(new Uint8Array(lh.buffer),name,data);
    var ch=new DataView(new ArrayBuffer(46));
    ch.setUint32(0,0x02014b50,true);ch.setUint16(4,20,true);ch.setUint16(6,20,true);ch.setUint16(8,0x0800,true);
    ch.setUint16(12,dT,true);ch.setUint16(14,dD,true);
    ch.setUint32(16,crc,true);ch.setUint32(20,data.length,true);ch.setUint32(24,data.length,true);
    ch.setUint16(28,name.length,true);ch.setUint32(42,off,true);
    cd.push(new Uint8Array(ch.buffer),name);
    off+=30+name.length+data.length;
  });
  var cdLen=0;cd.forEach(function(a){cdLen+=a.length;});
  var eo=new DataView(new ArrayBuffer(22));
  eo.setUint32(0,0x06054b50,true);eo.setUint16(8,entries.length,true);eo.setUint16(10,entries.length,true);
  eo.setUint32(12,cdLen,true);eo.setUint32(16,off,true);
  var out=new Uint8Array(off+cdLen+22),pos=0;
  parts.concat(cd,[new Uint8Array(eo.buffer)]).forEach(function(a){out.set(a,pos);pos+=a.length;});
  return out;
}

/* ================= DBF writer (attribute table) ================= */
function dbfBuild(rows){
  var cols=[],seen={};
  rows.forEach(function(r){Object.keys(r).forEach(function(k){if(!seen[k]){seen[k]=1;cols.push(k);}});});
  if(!cols.length){cols=['ID'];rows=rows.map(function(_,i){return {ID:String(i+1)};});}
  var fields=cols.map(function(k){
    var maxW=1,numeric=true,dec=0,maxInt=1;
    rows.forEach(function(r){
      var v=r[k];if(v==null||v==='')return;
      var s=String(v),b=ENC.encode(s).length;if(b>maxW)maxW=b;
      if(numeric){var m=s.match(/^(-?\d{1,15})(?:\.(\d{1,10}))?$/);if(m){if(m[1].length>maxInt)maxInt=m[1].length;if(m[2]&&m[2].length>dec)dec=m[2].length;}else numeric=false;}
    });
    var nw=maxInt+(dec?dec+1:0);
    if(numeric&&nw<=18)return {key:k,type:'N',w:nw,d:dec};
    return {key:k,type:'C',w:Math.min(254,maxW),d:0};
  });
  var used={};
  fields.forEach(function(f){
    var n=f.key.toUpperCase().replace(/[^A-Z0-9]+/g,'_').replace(/^_+|_+$/g,'').slice(0,10);
    if(!n||/^\d/.test(n))n=('F'+n).slice(0,10);
    var base=n,i=2;
    while(used[n]){var sfx='_'+(i++);n=base.slice(0,10-sfx.length)+sfx;}
    used[n]=1;f.name=n;
  });
  var hdr=32+32*fields.length+1;
  var rec=1;fields.forEach(function(f){rec+=f.w;});
  var buf=new Uint8Array(hdr+rec*rows.length+1),dv=new DataView(buf.buffer),d=new Date();
  buf[0]=0x03;buf[1]=d.getFullYear()-1900;buf[2]=d.getMonth()+1;buf[3]=d.getDate();
  dv.setUint32(4,rows.length,true);dv.setUint16(8,hdr,true);dv.setUint16(10,rec,true);
  fields.forEach(function(f,i){
    var o=32+32*i;
    for(var j=0;j<f.name.length;j++)buf[o+j]=f.name.charCodeAt(j);
    buf[o+11]=f.type.charCodeAt(0);buf[o+16]=f.w;buf[o+17]=f.d;
  });
  buf[32+32*fields.length]=0x0D;
  var pos=hdr;
  rows.forEach(function(r){
    buf[pos]=0x20;var fo=pos+1;
    fields.forEach(function(f){
      var v=r[f.key],s=(v==null)?'':String(v);
      for(var j=0;j<f.w;j++)buf[fo+j]=0x20;
      if(f.type==='N'){
        if(s!==''){var n=Number(s);s=isNaN(n)?'':(f.d>0?n.toFixed(f.d):String(n));}
        if(s.length>f.w)s=s.slice(0,f.w);
        for(var j2=0;j2<s.length;j2++)buf[fo+f.w-s.length+j2]=s.charCodeAt(j2);
      }else{
        var by=ENC.encode(s);if(by.length>f.w)by=by.slice(0,f.w);
        buf.set(by,fo);
      }
      fo+=f.w;
    });
    pos+=rec;
  });
  buf[buf.length-1]=0x1A;
  return buf;
}

/* ================= SHP / SHX writers (WGS84) ================= */
var PRJ_WGS84='GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]';
function shpHeader(dv,words,type,bb){
  dv.setInt32(0,9994,false);dv.setInt32(24,words,false);
  dv.setInt32(28,1000,true);dv.setInt32(32,type,true);
  dv.setFloat64(36,bb[0],true);dv.setFloat64(44,bb[1],true);dv.setFloat64(52,bb[2],true);dv.setFloat64(60,bb[3],true);
}
function bboxOf(coords){var bb=[Infinity,Infinity,-Infinity,-Infinity];coords.forEach(function(p){if(p[0]<bb[0])bb[0]=p[0];if(p[1]<bb[1])bb[1]=p[1];if(p[0]>bb[2])bb[2]=p[0];if(p[1]>bb[3])bb[3]=p[1];});return isFinite(bb[0])?bb:[0,0,0,0];}
function shpPointsBuild(pts){
  var n=pts.length,bb=bboxOf(pts);
  var shp=new Uint8Array(100+n*28),dv=new DataView(shp.buffer);
  var shx=new Uint8Array(100+n*8),xv=new DataView(shx.buffer);
  shpHeader(dv,shp.length/2,1,bb);shpHeader(xv,shx.length/2,1,bb);
  var off=100;
  pts.forEach(function(p,i){
    dv.setInt32(off,i+1,false);dv.setInt32(off+4,10,false);
    dv.setInt32(off+8,1,true);dv.setFloat64(off+12,p[0],true);dv.setFloat64(off+20,p[1],true);
    xv.setInt32(100+i*8,off/2,false);xv.setInt32(100+i*8+4,10,false);
    off+=28;
  });
  return {shp:shp,shx:shx};
}
function shpLinesBuild(items){ /* items: array of "parts" arrays; parts = [[x,y],...][] */
  var metas=items.map(function(parts){
    var np=parts.length,npts=0,all=[];
    parts.forEach(function(p){npts+=p.length;all.push.apply(all,p);});
    return {parts:parts,np:np,npts:npts,bb:bboxOf(all),clen:44+4*np+16*npts};
  });
  var recBytes=0;metas.forEach(function(m){recBytes+=8+m.clen;});
  var all2=[];items.forEach(function(parts){parts.forEach(function(p){all2.push.apply(all2,p);});});
  var bb=bboxOf(all2);
  var shp=new Uint8Array(100+recBytes),dv=new DataView(shp.buffer);
  var shx=new Uint8Array(100+metas.length*8),xv=new DataView(shx.buffer);
  shpHeader(dv,shp.length/2,3,bb);shpHeader(xv,shx.length/2,3,bb);
  var off=100;
  metas.forEach(function(m,i){
    dv.setInt32(off,i+1,false);dv.setInt32(off+4,m.clen/2,false);
    var o=off+8;
    dv.setInt32(o,3,true);
    dv.setFloat64(o+4,m.bb[0],true);dv.setFloat64(o+12,m.bb[1],true);dv.setFloat64(o+20,m.bb[2],true);dv.setFloat64(o+28,m.bb[3],true);
    dv.setInt32(o+36,m.np,true);dv.setInt32(o+40,m.npts,true);
    var pi=o+44,pn=0;
    m.parts.forEach(function(p){dv.setInt32(pi,pn,true);pi+=4;pn+=p.length;});
    m.parts.forEach(function(p){p.forEach(function(c){dv.setFloat64(pi,c[0],true);dv.setFloat64(pi+8,c[1],true);pi+=16;});});
    xv.setInt32(100+i*8,off/2,false);xv.setInt32(100+i*8+4,m.clen/2,false);
    off+=8+m.clen;
  });
  return {shp:shp,shx:shx};
}

/* ================= geometry helpers ================= */
function toXY(c){return [+c[0],+c[1]];}
function pointList(g){
  if(!g)return null;
  if(g.type==='Point')return [toXY(g.coordinates)];
  if(g.type==='MultiPoint')return g.coordinates.map(toXY);
  return null;
}
function lineParts(g){
  if(!g)return null;
  if(g.type==='LineString')return [g.coordinates.map(toXY)];
  if(g.type==='MultiLineString')return g.coordinates.map(function(a){return a.map(toXY);});
  return null;
}
function wktOf(g){
  var pl=pointList(g);
  if(pl)return pl.length===1?('POINT ('+pl[0][0]+' '+pl[0][1]+')')
    :('MULTIPOINT ('+pl.map(function(p){return '('+p[0]+' '+p[1]+')';}).join(', ')+')');
  var lp=lineParts(g);
  if(lp)return lp.length===1?('LINESTRING ('+lp[0].map(function(p){return p[0]+' '+p[1];}).join(', ')+')')
    :('MULTILINESTRING ('+lp.map(function(part){return '('+part.map(function(p){return p[0]+' '+p[1];}).join(', ')+')';}).join(', ')+')');
  return '';
}

/* ================= format builders ================= */
function shapefileZip(base,feats,rowFor){
  var pts=[],ptRows=[],lns=[],lnRows=[];
  feats.forEach(function(f){
    var g=f&&f.geometry;if(!g)return;
    var row=rowFor(f);
    var pl=pointList(g);
    if(pl){pl.forEach(function(p){pts.push(p);ptRows.push(row);});return;}
    var lp=lineParts(g);
    if(lp&&lp.length)  {lns.push(lp);lnRows.push(row);}
  });
  var entries=[];
  function addSet(nm,built,rows){
    entries.push({name:nm+'.shp',data:built.shp},{name:nm+'.shx',data:built.shx},
      {name:nm+'.dbf',data:dbfBuild(rows)},{name:nm+'.prj',data:ENC.encode(PRJ_WGS84)},
      {name:nm+'.cpg',data:ENC.encode('UTF-8')});
  }
  if(pts.length&&lns.length){addSet(base+'_points',shpPointsBuild(pts),ptRows);addSet(base+'_lines',shpLinesBuild(lns),lnRows);}
  else if(pts.length)addSet(base,shpPointsBuild(pts),ptRows);
  else if(lns.length)addSet(base,shpLinesBuild(lns),lnRows);
  else return null;
  return zipStore(entries);
}
function xmlEsc(s){return String(s==null?'':s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');}
function kmlColor(hex){hex=String(hex||'#3388ff').replace('#','');if(hex.length===3)hex=hex.split('').map(function(c){return c+c;}).join('');return 'ff'+hex.slice(4,6)+hex.slice(2,4)+hex.slice(0,2);}
function kmlGeom(g){
  var cd=function(c){return c[0]+','+c[1]+',0';};
  var pl=pointList(g);
  if(pl){var ps=pl.map(function(p){return '<Point><coordinates>'+cd(p)+'</coordinates></Point>';});
    return ps.length===1?ps[0]:('<MultiGeometry>'+ps.join('')+'</MultiGeometry>');}
  var lp=lineParts(g);
  if(lp){var ls=lp.map(function(part){return '<LineString><tessellate>1</tessellate><coordinates>'+part.map(cd).join(' ')+'</coordinates></LineString>';});
    return ls.length===1?ls[0]:('<MultiGeometry>'+ls.join('')+'</MultiGeometry>');}
  return '';
}
function kmlBuild(title,hex,feats,rowFor,nameFor){
  var col=kmlColor(hex);
  var out=['<?xml version="1.0" encoding="UTF-8"?>',
    '<kml xmlns="http://www.opengis.net/kml/2.2"><Document>',
    '<name>'+xmlEsc(title)+'</name>',
    '<Style id="s"><LineStyle><color>'+col+'</color><width>3</width></LineStyle><IconStyle><color>'+col+'</color><scale>0.9</scale></IconStyle></Style>'];
  feats.forEach(function(f){
    var g=f&&f.geometry;if(!g)return;
    var geo=kmlGeom(g);if(!geo)return;
    var row=rowFor(f),ed='';
    Object.keys(row).forEach(function(k){var v=row[k];if(v==null||v==='')return;ed+='<Data name="'+xmlEsc(k)+'"><value>'+xmlEsc(v)+'</value></Data>';});
    out.push('<Placemark><name>'+xmlEsc(nameFor(f))+'</name><styleUrl>#s</styleUrl>'+(ed?'<ExtendedData>'+ed+'</ExtendedData>':'')+geo+'</Placemark>');
  });
  out.push('</Document></kml>');
  return out.join('\n');
}
function csvBuild(feats,rowFor){
  var cols=[],seen={},rows=[];
  feats.forEach(function(f){
    var g=f&&f.geometry,r=Object.assign({},rowFor(f));
    var pl=g?pointList(g):null;
    if(pl&&pl.length===1){r.Latitude=pl[0][1];r.Longitude=pl[0][0];}
    r.WKT_geometry=g?wktOf(g):'';
    Object.keys(r).forEach(function(k){if(!seen[k]){seen[k]=1;cols.push(k);}});
    rows.push(r);
  });
  var esc=function(v){var s=String(v==null?'':v);return /[",\n\r]/.test(s)?('"'+s.replace(/"/g,'""')+'"'):s;};
  return '﻿'+cols.map(esc).join(',')+'\r\n'+rows.map(function(r){return cols.map(function(c){return esc(r[c]);}).join(',');}).join('\r\n');
}

/* ================= property cleaning / per-layer rows ================= */
var EXP_LANES=['CC','CL1','CL2','CR1','CR2'];
function cleanProps(p){
  var o={};
  Object.keys(p||{}).forEach(function(k){
    if(k==='lane_vals')return;                      /* redundant JSON blob — lane values are flattened below */
    if(k==='__d0'){o.D0_microns=p[k];return;}
    if(k.charAt(0)==='_')return;                    /* internal (__sec, __dscale, __adt…) */
    if(/^L_(CC|CL1|CL2|CR1|CR2)$/.test(k))return;   /* internal lane-presence flags */
    var v=p[k];if(v==null||v==='')return;
    o[k]=v;
  });
  return o;
}
function condParamRow(p,param){
  var o={Road:p.road,From_ch_m:p.from_ch,To_ch_m:p.to_ch};
  if(p.lane_count!=null)o.Lanes=p.lane_count;
  if(p.xsp_list!=null&&p.xsp_list!=='')o.Lane_list=p.xsp_list;
  if(p[param]!=null&&p[param]!=='')o[param+'_worst']=p[param];
  if(p['avg_'+param]!=null&&p['avg_'+param]!=='')o[param+'_avg']=p['avg_'+param];
  EXP_LANES.forEach(function(L){var v=p[L+'_'+param];if(v!=null&&v!=='')o[L+'_'+param]=v;});
  return o;
}
function pciRow(p,prop){
  var o={Road:p.road,From_ch_m:p.from_ch,To_ch_m:p.to_ch};
  if(p.xsp_list!=null&&p.xsp_list!=='')o.Lane_list=p.xsp_list;
  if(+p.pci_avg>=0)o.PCI_composite=p.pci_avg;
  if(+p.pci_worst>=0)o.PCI_worst_lane=p.pci_worst;
  var v=+p[prop];
  if(v>=0&&typeof pciBand==='function')o.Rating=pciBand(v).label;
  return o;
}
function trafficRow(p){
  var o={Station:p.name||'',Road:p.road||'',Section:p.section||'',Chainage_m:(p.ch!=null?p.ch:''),Lane:p.xsp||''};
  var c=null;
  try{c=(typeof TRAFFIC_COUNTS!=='undefined')?TRAFFIC_COUNTS[p.name]:null;}catch(e){}
  if(c){
    var days=c.days||1;
    o.Survey_days=days;
    if(c.total!=null){o.Total_vehicles=c.total;o.ADT_veh_per_day=Math.round((c.total||0)/days);}
    if(c.dateMin)o.Date_from=c.dateMin;
    if(c.dateMax)o.Date_to=c.dateMax;
    if(c.byDir)Object.keys(c.byDir).sort().forEach(function(d){o['Dir_'+d]=c.byDir[d];});
    if(c.byClass)Object.keys(c.byClass).sort().forEach(function(k){o['Cls_'+k]=c.byClass[k];});
  }
  return o;
}
function featName(f,fallback){
  var p=(f&&f.properties)||{};
  var v=p.Road_Name||p.name||p.road||p.Section_La;
  if(v==null&&typeof pickProp==='function'&&typeof ROAD_KEYS!=='undefined')v=pickProp(p,ROAD_KEYS);
  return (v==null||v==='')?fallback:String(v);
}
function inScope(val){return !window.NET_SCOPE||window.NET_SCOPE.has(String(val!=null?val:''));}

/* ================= layer registry ================= */
function assetDef(t){try{return ASSETS.filter(function(a){return a.type===t;})[0];}catch(e){return null;}}
function ensureAssetData(t){
  if(typeof ASSET_DATA!=='undefined'&&ASSET_DATA[t])return Promise.resolve();
  var d=assetDef(t);
  return (d&&typeof loadAsset==='function')?loadAsset(d):Promise.resolve();
}
function assetEntry(type,label,color,toggle){
  return {label:label,color:color,toggle:toggle,
    ensure:function(){return ensureAssetData(type);},
    collect:function(){
      var gj=(typeof ASSET_DATA!=='undefined')?ASSET_DATA[type]:null;
      var all=(gj&&gj.features)||[];
      var fs=window.NET_SCOPE?all.filter(function(f){return inScope((f.properties||{}).__sec);}):all;
      return {feats:fs,total:all.length,filtered:!!window.NET_SCOPE};
    }};
}
function pciEntry(prop,label,toggle,color){
  return {label:label,color:color,toggle:toggle,
    ensure:function(){
      if(typeof DATA!=='undefined'&&DATA&&DATA.features&&DATA.features.length&&DATA.features[0].properties.pci_avg!==undefined)return Promise.resolve();
      var p=(typeof DATA!=='undefined'&&DATA&&DATA.features)?Promise.resolve():loadSegments();
      return p.then(function(){
        if(typeof generatePCI==='function'&&typeof DATA!=='undefined'&&DATA&&DATA.features&&DATA.features.length&&DATA.features[0].properties.pci_avg===undefined)generatePCI(true);
      });
    },
    collect:function(){
      var all=(typeof DATA!=='undefined'&&DATA&&DATA.features)||[];
      var mn=parseFloat((document.getElementById('pciMin')||{}).value),mx=parseFloat((document.getElementById('pciMax')||{}).value);
      var fs=all.filter(function(f){
        var p=f.properties||{},v=+p[prop];
        if(!(v>=0))return false;
        if(!isNaN(mn)&&v<mn)return false;
        if(!isNaN(mx)&&v>mx)return false;
        return inScope(p.road);
      });
      return {feats:fs,total:all.length,filtered:(!isNaN(mn)||!isNaN(mx)||!!window.NET_SCOPE),
        rowFor:function(f){return pciRow(f.properties||{},prop);}};
    }};
}
var EXP={
  roads:{label:'Road network',color:'#8a4d1f',toggle:'showRoads',
    ensure:function(){return (typeof loadRoads==='function')?loadRoads(true):Promise.resolve();},
    collect:function(){
      var all=Object.keys(ROADS||{}).map(function(k){return ROADS[k];});
      var fs=window.NET_SCOPE?all.filter(function(f){return inScope((f.properties||{}).road);}):all;
      return {feats:fs,total:all.length,filtered:!!window.NET_SCOPE};
    }},
  cond:{label:'Road condition data',color:'#2ba66a',toggle:'showCond',hasParam:true,
    ensure:function(){return (typeof DATA!=='undefined'&&DATA&&DATA.features)?Promise.resolve():loadSegments();},
    collect:function(param){
      var all=(typeof DATA!=='undefined'&&DATA&&DATA.features)||[];
      var m=(typeof matchingFeatures==='function')?matchingFeatures():null;
      var fs=m||all,condF=!!m;
      if(window.NET_SCOPE)fs=fs.filter(function(f){return inScope((f.properties||{}).road);});
      var rowFor=null,suffix='';
      if(param&&param!=='all'){
        suffix=param;
        fs=fs.filter(function(f){
          var p=f.properties||{};
          if(p[param]!=null&&p[param]!=='')return true;
          for(var i=0;i<EXP_LANES.length;i++){var v=p[EXP_LANES[i]+'_'+param];if(v!=null&&v!=='')return true;}
          return false;
        });
        rowFor=function(f){return condParamRow(f.properties||{},param);};
      }
      return {feats:fs,total:all.length,filtered:condF||!!window.NET_SCOPE,rowFor:rowFor,suffix:suffix};
    }},
  fwd:{label:'FWD deflection',color:'#7b1fa2',toggle:'showFwd',
    ensure:function(){return ensureAssetData('fwd');},
    collect:function(){
      var gj=(typeof ASSET_DATA!=='undefined')?ASSET_DATA.fwd:null;
      var all=(gj&&gj.features)||[];
      var mn=parseFloat((document.getElementById('fwdMin')||{}).value),mx=parseFloat((document.getElementById('fwdMax')||{}).value);
      var fs=all.filter(function(f){
        var p=f.properties||{};
        if(!isNaN(mn)&&!(+p.__d0>=mn))return false;
        if(!isNaN(mx)&&!(+p.__d0<=mx))return false;
        return inScope(p.__sec);
      });
      return {feats:fs,total:all.length,filtered:(!isNaN(mn)||!isNaN(mx)||!!window.NET_SCOPE)};
    }},
  bridge:assetEntry('bridge','Bridges','#8a5cb8','showBridge'),
  culvert:assetEntry('culvert','Culverts','#e07b2a','showCulvert'),
  furnl:assetEntry('furniture_line','Furniture (line)','#0fa3a3','showFurnL'),
  furnp:assetEntry('furniture_point','Furniture (point)','#3b6fa0','showFurnP'),
  pciavg:pciEntry('pci_avg','Composite PCI','showPciAvg','#157f3c'),
  pciworst:pciEntry('pci_worst','Worst-Lane PCI','showPciWorst','#e8590c'),
  traffic:{label:'Traffic stations',color:'#1565c0',toggle:'showTraffic',
    ensure:function(){
      return new Promise(function(res){
        if(typeof TRAFFIC_LOADED!=='undefined'&&TRAFFIC_LOADED)return res();
        if(typeof loadTraffic==='function')loadTraffic(res);else res();
        setTimeout(res,10000); /* fallback: loadTraffic doesn't call back when the store is empty */
      });
    },
    collect:function(){
      if(typeof trafficComputeAdt==='function')trafficComputeAdt();
      var all=(typeof TRAFFIC_STN!=='undefined'&&TRAFFIC_STN.features)||[];
      var mn=parseFloat((document.getElementById('trfMin')||{}).value);
      var fs=all.filter(function(f){
        var p=f.properties||{};
        if(!isNaN(mn)&&!(+p.__adt>=0&&+p.__adt>=mn))return false;
        return inScope(p.section);
      });
      return {feats:fs,total:all.length,filtered:(!isNaN(mn)||!!window.NET_SCOPE),
        rowFor:function(f){return trafficRow(f.properties||{});},
        nameFor:function(f){return (f.properties||{}).name||'Traffic station';}};
    }},
  soil:assetEntry('subgrade','Sub-grade soil','#8a4d1f','showSoil'),
  core:assetEntry('bituminous_core','Bituminous core','#2b2b2b','showCore'),
  crust:assetEntry('pavement_crust','Pavement crust','#b8860b','showCrust')
};

/* ================= download + dispatch ================= */
function saveBlob(name,blob){
  var a=document.createElement('a');
  a.href=URL.createObjectURL(blob);a.download=name;
  document.body.appendChild(a);a.click();
  setTimeout(function(){URL.revokeObjectURL(a.href);a.remove();},1500);
}
function slug(s){return String(s).replace(/[^A-Za-z0-9]+/g,'_').replace(/^_+|_+$/g,'');}
function dateTag(){var d=new Date();var p=function(n){return (n<10?'0':'')+n;};return d.getFullYear()+p(d.getMonth()+1)+p(d.getDate());}
function doExport(key,fmt,param){
  var E=EXP[key],res=E.collect(param);
  var feats=res.feats;
  if(!feats.length)return 0;
  var rowFor=res.rowFor||function(f){return cleanProps(f.properties);};
  var nameFor=res.nameFor||function(f){return featName(f,E.label);};
  var base='KLRAMS_'+slug(E.label)+(res.suffix?('_'+slug(res.suffix)):'')+(res.filtered?'_filtered':'')+'_'+dateTag();
  if(fmt==='geojson'){
    var gj={type:'FeatureCollection',name:E.label,features:feats.map(function(f){return {type:'Feature',geometry:f.geometry||null,properties:rowFor(f)};})};
    saveBlob(base+'.geojson',new Blob([JSON.stringify(gj)],{type:'application/geo+json'}));
  }else if(fmt==='kml'){
    saveBlob(base+'.kml',new Blob([kmlBuild(E.label,E.color,feats,rowFor,nameFor)],{type:'application/vnd.google-earth.kml+xml'}));
  }else if(fmt==='kmz'){
    var kml=ENC.encode(kmlBuild(E.label,E.color,feats,rowFor,nameFor));
    saveBlob(base+'.kmz',new Blob([zipStore([{name:'doc.kml',data:kml}])],{type:'application/vnd.google-earth.kmz'}));
  }else if(fmt==='csv'){
    saveBlob(base+'.csv',new Blob([csvBuild(feats,rowFor)],{type:'text/csv;charset=utf-8'}));
  }else if(fmt==='shp'){
    var z=shapefileZip(slug(E.label)+(res.suffix?('_'+slug(res.suffix)):''),feats,rowFor);
    if(!z)return 0;
    saveBlob(base+'_SHP.zip',new Blob([z],{type:'application/zip'}));
  }
  return feats.length;
}

/* ================= export menu UI ================= */
var FORMATS=[
  {id:'shp',name:'Shapefile',desc:'ArcGIS · QGIS',
   icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2 2 7l10 5 10-5-10-5z"/><path d="M2 12l10 5 10-5"/><path d="M2 17l10 5 10-5"/></svg>'},
  {id:'geojson',name:'GeoJSON',desc:'Web GIS',
   icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M8 4c-2 0-3 1-3 3v2c0 1.5-1 2.5-2 3 1 .5 2 1.5 2 3v2c0 2 1 3 3 3"/><path d="M16 4c2 0 3 1 3 3v2c0 1.5 1 2.5 2 3-1 .5-2 1.5-2 3v2c0 2-1 3-3 3"/></svg>'},
  {id:'kml',name:'KML',desc:'Google Earth',
   icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c2.5 2.5 4 5.5 4 9s-1.5 6.5-4 9c-2.5-2.5-4-5.5-4-9s1.5-6.5 4-9z"/></svg>'},
  {id:'kmz',name:'KMZ',desc:'Earth · zipped',
   icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="13" r="8"/><path d="M4 13h16M12 5c2.2 2.2 3.5 5 3.5 8s-1.3 5.8-3.5 8c-2.2-2.2-3.5-5-3.5-8s1.3-5.8 3.5-8z"/><path d="M9 2h6"/></svg>'},
  {id:'csv',name:'CSV',desc:'Excel table',
   icon:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="4" width="18" height="16" rx="2"/><path d="M3 10h18M3 15h18M9 4v16M15 4v16"/></svg>'}
];
var _menu=null,_menuKey=null;
function closeExpMenu(){if(_menu){_menu.remove();_menu=null;_menuKey=null;}}
function fmtCount(n){try{return n.toLocaleString('en-IN');}catch(e){return String(n);}}
function menuCollect(){
  var E=EXP[_menuKey];if(!E)return null;
  var sel=_menu?_menu.querySelector('#kexpParam'):null;
  return E.collect(sel?sel.value:undefined);
}
function refreshExpMenu(){
  if(!_menu)return;
  var sub=_menu.querySelector('#kexpSub');
  var res=null;
  try{res=menuCollect();}catch(e){}
  if(!res){sub.textContent='Could not read the layer data.';return;}
  var n=res.feats.length;
  if(!n){
    sub.innerHTML='<span class="kexp-warn">No features to export'+(res.total?' — the current filter matches nothing':' — no data uploaded yet')+'</span>';
  }else{
    sub.textContent=fmtCount(n)+' feature'+(n===1?'':'s')+(res.filtered?(' · filtered view of '+fmtCount(res.total)):' · full dataset');
  }
  _menu.querySelectorAll('.kexp-f').forEach(function(t){t.classList.toggle('off',!n);});
}
function openExpMenu(key,anchor){
  closeExpMenu();
  var E=EXP[key];if(!E)return;
  _menuKey=key;
  var m=document.createElement('div');
  m.id='klExpMenu';m.className='kexp';
  var paramRow='';
  if(E.hasParam&&typeof PARAMS!=='undefined'){
    paramRow='<div class="kexp-param"><span class="kexp-pl">Item</span><select id="kexpParam">'
      +'<option value="all">All parameters</option>'
      +PARAMS.map(function(p){return '<option value="'+p.key+'">'+p.label+'</option>';}).join('')
      +'</select></div>';
  }
  m.innerHTML=
    '<div class="kexp-hd">'
      +'<span class="kexp-dot" style="--c:'+E.color+'"></span>'
      +'<div class="kexp-tt"><b>'+E.label+'</b><span class="kexp-sub" id="kexpSub">Preparing data&hellip;</span></div>'
      +'<button type="button" class="kexp-x" title="Close">&times;</button>'
    +'</div>'
    +paramRow
    +'<div class="kexp-grid">'
      +FORMATS.map(function(f){return '<button type="button" class="kexp-f off f-'+f.id+'" data-fmt="'+f.id+'"><span class="kexp-fi">'+f.icon+'</span><span class="kexp-fn">'+f.name+'</span><span class="kexp-fd">'+f.desc+'</span></button>';}).join('')
    +'</div>'
    +'<div class="kexp-note"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round"><path d="M20 6 9 17l-5-5"/></svg><span>Exports exactly what the map shows &mdash; active filters are applied.</span></div>';
  document.body.appendChild(m);
  _menu=m;
  /* position: to the right of the layer row, clamped inside the viewport */
  var r=anchor.getBoundingClientRect(),W=272;
  m.style.left=Math.max(8,Math.min(r.right+14,window.innerWidth-W-8))+'px';
  m.style.top='0px';
  var H=m.getBoundingClientRect().height;
  m.style.top=Math.max(8,Math.min(r.top-14,window.innerHeight-H-8))+'px';
  m.querySelector('.kexp-x').onclick=closeExpMenu;
  var sel=m.querySelector('#kexpParam');
  if(sel)sel.onchange=refreshExpMenu;
  m.querySelectorAll('.kexp-f').forEach(function(tile){
    tile.onclick=function(){
      if(tile.classList.contains('off')||tile.classList.contains('busy'))return;
      tile.classList.add('busy');
      var fmt=tile.getAttribute('data-fmt');
      var param=sel?sel.value:undefined;
      setTimeout(function(){ /* let the spinner paint before the (possibly heavy) build */
        var n=0,err=null;
        try{n=doExport(key,fmt,param);}catch(e){err=e;}
        tile.classList.remove('busy');
        if(err){console.error('Export failed:',err);var s=_menu&&_menu.querySelector('#kexpSub');if(s)s.innerHTML='<span class="kexp-warn">Export failed &mdash; see the browser console.</span>';return;}
        if(!n)return;
        tile.classList.add('done');
        setTimeout(function(){tile.classList.remove('done');},1500);
      },50);
    };
  });
  E.ensure().then(function(){refreshExpMenu();}).catch(function(){refreshExpMenu();});
}
document.addEventListener('mousedown',function(e){
  if(_menu&&!e.target.closest('#klExpMenu')&&!e.target.closest('.lexp'))closeExpMenu();
},true);
window.addEventListener('resize',closeExpMenu);

/* ================= inject an export button on every layer row ================= */
(function(){
  var dl='<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round"><path d="M12 3v10"/><path d="M8 9l4 4 4-4"/><path d="M4 17v2a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-2"/></svg>';
  Object.keys(EXP).forEach(function(key){
    var E=EXP[key];
    var t=document.getElementById(E.toggle);if(!t)return;
    var row=t.closest('.switch');if(!row)return;
    var b=document.createElement('button');
    b.type='button';b.className='lexp';
    b.title='Export '+E.label+' — Shapefile / GeoJSON / KML / KMZ / CSV';
    b.innerHTML=dl;
    b.addEventListener('click',function(e){
      e.preventDefault();e.stopPropagation();
      if(_menuKey===key)closeExpMenu();else openExpMenu(key,b);
    });
    row.insertBefore(b,t);
  });
})();
})();
