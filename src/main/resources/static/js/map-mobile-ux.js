/* map-mobile-ux — lifted out of an inline <script> in map.html so the page carries
   no inline script, which is what lets script-src drop 'unsafe-inline'. */
(function(){
  var bd=document.createElement('div');bd.id='fpaneBackdrop';
  (function add(){if(document.body)document.body.appendChild(bd);else setTimeout(add,60);})();
  function fp(){return document.getElementById('fpanes');}
  function clearActive(){document.querySelectorAll('#iconrail .railbtn').forEach(function(b){b.classList.remove('active');});}
  /* A full-screen module (Dashboard, Report Hub, PCI/Condition/Register/NSV)
     hides the panel column but keeps its own rail button lit, so "no panel
     showing" is not on its own a reason to drop the highlight. */
  function moduleOpen(){return !!document.querySelector('#dashboard.open,#reportHub.open,#pciScreen.open,#condScreen.open,#regScreen.open,#nsvScreen.open');}
  function sync(){var f=fp();var open=f&&!f.classList.contains('hidden');document.body.classList.toggle('panel-open',!!open);if(!open&&!moduleOpen())clearActive();}
  function close(){var f=fp();if(f)f.classList.add('hidden');clearActive();document.body.classList.remove('panel-open');}
  bd.addEventListener('click',close);window.klClosePanes=close;
  (function wrap(){
    if(typeof window.openPane!=='function'||typeof window.togglePanes!=='function')return setTimeout(wrap,140);
    if(window.__paneWrapped)return;window.__paneWrapped=true;
    var oOpen=window.openPane,oTog=window.togglePanes;
    window.openPane=function(id){
      var f=fp();var btn=document.querySelector('#iconrail .railbtn[data-pane="'+id+'"]');
      var visible=f&&!f.classList.contains('hidden');var active=btn&&btn.classList.contains('active');
      if(visible&&active){close();return;}        /* re-tap active tool -> collapse */
      oOpen(id);sync();
    };
    window.togglePanes=function(){oTog();sync();};  /* the < chevron now collapses/restores cleanly */
  })();
  function collapse(){var f=fp();if(f)f.classList.add('hidden');clearActive();document.body.classList.remove('panel-open');}
  if(document.readyState!=='loading')collapse();else document.addEventListener('DOMContentLoaded',collapse);
})();
