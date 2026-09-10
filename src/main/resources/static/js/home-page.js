/* Internal staff portal (home.html).
   Lifted out of an inline <script> so the page carries no inline script,
   which is what lets script-src drop 'unsafe-inline'. */
(function(){
    function pad(n){return (n<10?'0':'')+n;}
    function tick(){
      var d=new Date(),h=d.getHours(),ap=h>=12?'PM':'AM',h12=h%12||12;
      document.getElementById('clock').innerHTML=pad(h12)+':'+pad(d.getMinutes())+'<small> '+ap+'</small>';
      document.getElementById('dateln').textContent=d.toLocaleDateString('en-IN',{weekday:'long',day:'numeric',month:'long',year:'numeric'});
      var g=h<12?'Good morning':(h<17?'Good afternoon':'Good evening');
      document.getElementById('greet').textContent=g+' · signed in';
    }
    tick();setInterval(tick,15000);
  })();

  // role badge + card gating (role-gate.js does the hiding + force-change redirect)
  window.RoleGate = window.RoleGate || {};
  window.RoleGate.onReady = function(me){
    var r = (me && me.role) || 'USER';
    var label = r === 'SUPER_ADMIN' ? 'Super Admin' : r === 'ADMIN' ? 'Admin' : 'View only';
    var el = document.getElementById('roleBadge');
    if(el) el.textContent = label;
  };
