/* map-boot — lifted out of an inline <script> in map.html so the page carries
   no inline script, which is what lets script-src drop 'unsafe-inline'. */
/* WebGL guard — MapLibre requires WebGL. On machines where hardware
   acceleration / WebGL is disabled, the map cannot initialise, which then
   cascades into "map is not defined" errors across every module. Detect that
   up front and send the user to a friendly instructions page instead of a
   broken blank screen. Runs before MapLibre is even loaded. */
(function(){
  function webglSupported(){
    try{
      var c=document.createElement('canvas');
      var gl=c.getContext('webgl2')||c.getContext('webgl')||c.getContext('experimental-webgl');
      return !!(window.WebGLRenderingContext && gl);
    }catch(e){ return false; }
  }
  if(!webglSupported()) location.replace('/map-lite.html');
})();
