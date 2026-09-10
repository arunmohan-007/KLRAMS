/* map-panels — lifted out of an inline <script> in map.html so the page carries
   no inline script, which is what lets script-src drop 'unsafe-inline'. */
/* Build 90 — bulletproof, self-contained dock guard (lives in map.html so it
     works regardless of which JS files are deployed). The survey-video dock is
     shown ONLY when: it is open AND a real video frame has loaded AND
     "Video on click" is on. An empty or leftover dock can never appear. */
  (function(){
    var dock=document.getElementById('dock'), vid=document.getElementById('video');
    if(!dock) return;
    function vclickOn(){return !!(((document.getElementById('videoMode')||{}).checked)||((document.getElementById('videoMode2')||{}).checked));}
    /* readyState>=1 (HAVE_METADATA): mobile browsers defer decoding a frame
       until a play gesture, so gating on >=2 kept the dock hidden on phones. */
    function hasRealVideo(){return !!(vid&&(vid.currentSrc||vid.getAttribute('src'))&&vid.readyState>=1);}
    function refresh(){var show=dock.classList.contains('open')&&hasRealVideo()&&vclickOn();dock.classList.toggle('klshow',show);}
    try{new MutationObserver(refresh).observe(dock,{attributes:true,attributeFilter:['class']});}catch(e){}
    if(vid)['loadeddata','canplay','playing','seeked','emptied','error','loadstart','pause'].forEach(function(ev){vid.addEventListener(ev,refresh);});
    ['videoMode','videoMode2'].forEach(function(id){var el=document.getElementById(id);if(el)el.addEventListener('change',refresh);});
    dock.classList.remove('open','loaded','klshow');   /* always start fully closed */
    setTimeout(refresh,0);
  })();
