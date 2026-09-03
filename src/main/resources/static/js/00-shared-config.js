/* ============================================================
   KLRAMS · 00-shared-config.js
   The condition parameters, decode tables and road-detail fields that BOTH
   map pages need — the WebGL viewer (map.html) and the Canvas Lite map
   (map-lite.html).

   These lived in js/01-config.js and were copy-pasted into map-lite.html
   under a "mirrors js/01-config.js" comment. The copies drifted: patch work
   was labelled sqm in the viewer and % in Lite, and pothole and texture lost
   their units in Lite entirely — the same figure presented as two different
   quantities depending on which map you opened. One definition, loaded by
   both, is the only thing that keeps them honest.

   Viewer-only state (map handles, follow mode, tile flags) stays in
   01-config.js: it refers to MapLibre and to DOM the Lite map does not have.

   Loaded as a classic script before 01-config.js on map.html, and before the
   inline script on map-lite.html.
   ============================================================ */
var GOOD='#2ba66a',FAIR='#FFC400',POOR='#da4b43',NET='#8a99ad',NONE='#b9c2cc';

var PARAMS=[
  {key:'iri',        label:'IRI',        unit:' m/km',  fair:2.55, poor:3.30},
  {key:'crack',      label:'Crack',      unit:' %',     fair:5,    poor:15},
  {key:'pothole',    label:'Pothole',    unit:' nos/km',fair:1,    poor:3},
  {key:'rutting',    label:'Rutting',    unit:' mm',    fair:5,    poor:10},
  {key:'texture',    label:'Texture',    unit:' mm',    fair:1,    poor:3},
  {key:'patch_work', label:'Patch work', unit:' sqm',   fair:5,    poor:10},
  {key:'ravelling',  label:'Ravelling',  unit:' %',     fair:5,    poor:10}
];
var PMAP={};PARAMS.forEach(function(p){PMAP[p.key]=p;});

/* PARAMS is where every condition label and unit comes from — the inspection
   card, the summary card, the legend, the filters, the PCI report and the
   dashboards all read it through PMAP — so it is the one place worth pointing
   at Attribute Data. Each entry takes the name and unit the RMMS cell set for
   the matching `condition` column, and a rename there reaches all of them.

   The objects are mutated in place, never replaced: modules captured PMAP
   entries by reference at load time and a reassignment would leave them
   holding the old ones.

   The Good/Fair/Poor thresholds are deliberately NOT taken from the catalogue.
   They are the IRC:82-2023 bands — engineering, not naming — and Attribute Data
   has no business moving them. */
if(window.AttrCatalog)AttrCatalog.ready().then(function(){
  PARAMS.forEach(function(p){
    var m=AttrCatalog.meta('condition',p.key);
    if(!m)return;
    if(m.label)p.label=m.label;
    // The viewer prints unit straight after the number, so it carries the
    // separating space the catalogue's bare "m/km" does not.
    if(m.unit)p.unit=' '+m.unit;
  });
});

// decode lookups for shapefile codes
var LK={
  Road_Class:{SH:'State Highway',MDR:'Major District Road',ODR:'Other District Road',NH:'National Highway'},
  Road_Type:{SLR:'Single Lane',ILR:'Intermediate',TLR:'Two Lane',WTL:'Wide Two Lane',FLR:'Four Lane'},
  Single_Du:{Single:'Single carriageway',Dual:'Dual carriageway'},
  Cons_Type:{FLX:'Flexible',RGD:'Rigid',CMP:'Composite',WBM:'WBM',GRV:'Gravel',ERT:'Earthen',PVB:'Paver Block'},
  Surface_Ty:{BT:'Bituminous',CC:'Cement Concrete',PVB:'Paver Block',WBM:'WBM',GRV:'Gravel',ERT:'Earthen'},
  Pavement_W:{'1':'≥3.75 & <5.5 m','2':'>5.5 & <7 m','3':'≥7 & <10.5 m','4':'≥10.5 & ≤12.5 m','5':'>12.5 m'},
  Current_Ow:{KMRL:'Kochi Metro Rail Ltd',KRFB:'Kerala Road Fund Board','KRFB-PMU':'KRFB — PMU',KSTP:'Kerala State Transport Project',RICK:'Road Infrastructure Company Kerala','PWD Section':'PWD Section','PWD Maintenance':'PWD Maintenance'}
};

// road detail fields: [column, label, decodeKey, unit]
var ROAD_FIELDS=[
  ['Road_Name','Road name'],['Road_Num','Road number'],['Road_Class','Class','Road_Class'],
  ['Road_Type','Lane type','Road_Type'],['Single_Du','Carriageway','Single_Du'],
  ['Rd_Str_Loc','Start location'],['Rd_End_Loc','End location'],
  ['Rd_Str_cha','Road start chainage',null,' m'],['Rd_End_cha','Road end chainage',null,' m'],
  ['Measrd_Len','Measured length',null,' m'],['Pavement_W','Pavement width','Pavement_W'],
  ['Cons_Type','Construction','Cons_Type'],['Surface_Ty','Surface','Surface_Ty'],
  ['Current_Ow','Current owner','Current_Ow'],
  ['PWD_Sec','PWD section'],['CRN','CRN'],['District','District']
];

/* The road inspection card's labels come from Attribute Data too, on the same
   terms as PARAMS above: name and unit only, mutated in place.

   The unit is taken ONLY for a field with no decode key. A decoded field prints
   a band of text, not a measurement — Pavement_W renders "≥7 & <10.5 m" — and
   appending the column's unit to that would produce "≥7 & <10.5 m m". */
if(window.AttrCatalog)AttrCatalog.ready().then(function(){
  ROAD_FIELDS.forEach(function(f){
    var m=AttrCatalog.meta('roads',f[0]);
    if(!m)return;
    if(m.label)f[1]=m.label;
    if(m.unit&&!f[2])f[3]=' '+m.unit;
  });
});

/* Expand a coded road value.
   Asks the Lookup & Short Code module first — one place the RMMS cell can edit
   — and falls back to LK above for a code it does not cover and for the moment
   before the catalogue has loaded. LK stays as that floor rather than being
   deleted: it is the only decode available if the lookup tables are empty.

   On the Lite map AttrCatalog is not loaded at all, so the guard sends every
   call straight to LK — same answers, minus the RMMS cell's overrides. */
function dec(group,val){
  var k=String(val==null?'':val).trim();
  if(k==='')return val;
  if(window.AttrCatalog){
    var full=AttrCatalog.expand('roads',group,k);
    if(full!=null&&String(full)!==k)return full;
  }
  var t=LK[group];
  return t&&t[k]?t[k]:val;
}
