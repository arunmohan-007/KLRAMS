/* ============================================================
   KLRAMS viewer · 01-config.js
   Constants, shapefile decode tables, road-detail fields, and shared application state.
   Loaded as an ordered classic script from map.html; all modules
   share one global scope, so load order is preserved exactly.
   ============================================================ */
const GOOD='#2ba66a',FAIR='#FFC400',POOR='#da4b43',NET='#8a99ad',NONE='#b9c2cc';
const PARAMS=[{key:'iri',label:'IRI',unit:' m/km',fair:2.55,poor:3.30},{key:'crack',label:'Crack',unit:' %',fair:5,poor:15},{key:'pothole',label:'Pothole',unit:'',fair:1,poor:3},{key:'rutting',label:'Rutting',unit:' mm',fair:5,poor:10},{key:'texture',label:'Texture',unit:'',fair:1,poor:3},{key:'patch_work',label:'Patch work',unit:' %',fair:5,poor:10},{key:'ravelling',label:'Ravelling',unit:' %',fair:5,poor:10}];
const PMAP={};PARAMS.forEach(p=>PMAP[p.key]=p);
// decode lookups for shapefile codes
const LK={
  Road_Class:{SH:'State Highway',MDR:'Major District Road',ODR:'Other District Road',NH:'National Highway'},
  Road_Type:{SLR:'Single Lane',ILR:'Intermediate',TLR:'Two Lane',WTL:'Wide Two Lane',FLR:'Four Lane'},
  Single_Du:{Single:'Single carriageway',Dual:'Dual carriageway'},
  Cons_Type:{FLX:'Flexible',RGD:'Rigid',CMP:'Composite',WBM:'WBM',GRV:'Gravel',ERT:'Earthen',PVB:'Paver Block'},
  Surface_Ty:{BT:'Bituminous',CC:'Cement Concrete',PVB:'Paver Block',WBM:'WBM',GRV:'Gravel',ERT:'Earthen'},
  Pavement_W:{'1':'≥3.75 & <5.5 m','2':'>5.5 & <7 m','3':'≥7 & <10.5 m','4':'≥10.5 & ≤12.5 m','5':'>12.5 m'},
  Current_Ow:{KMRL:'Kochi Metro Rail Ltd',KRFB:'Kerala Road Fund Board','KRFB-PMU':'KRFB — PMU',KSTP:'Kerala State Transport Project',RICK:'Road Infrastructure Company Kerala','PWD Section':'PWD Section','PWD Maintenance':'PWD Maintenance'}
};
// road detail fields: [column, label, decodeKey, unit]
const ROAD_FIELDS=[
  ['Road_Name','Road name'],['Road_Num','Road number'],['Road_Class','Class','Road_Class'],
  ['Road_Type','Lane type','Road_Type'],['Single_Du','Carriageway','Single_Du'],
  ['Rd_Str_Loc','Start location'],['Rd_End_Loc','End location'],
  ['Rd_Str_cha','Road start chainage',null,' m'],['Rd_End_cha','Road end chainage',null,' m'],
  ['Measrd_Len','Measured length',null,' m'],['Pavement_W','Pavement width','Pavement_W'],
  ['Cons_Type','Construction','Cons_Type'],['Surface_Ty','Surface','Surface_Ty'],
  ['Current_Ow','Current owner','Current_Ow'],
  ['PWD_Sec','PWD section'],['CRN','CRN'],['District','District']
];
function dec(group,val){const t=LK[group];const k=String(val).trim();return t&&t[k]?t[k]:val;}
let mode='all',filters=[],DATA=null,ROADS={},segsByRoad={},CATALOG={};
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

