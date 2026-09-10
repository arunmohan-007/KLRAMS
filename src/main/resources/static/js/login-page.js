/* Sign-in page (login.html).
   Lifted out of an inline <script> so the page carries no inline
   script, which is what lets script-src drop 'unsafe-inline'. */
(function(){
    var q = new URLSearchParams(location.search), err = document.getElementById('err');
    if (q.has('locked')) { err.textContent = 'Too many failed sign-in attempts from this connection. Please wait about 15 minutes and try again.'; err.classList.add('show'); }
    else if (q.has('error')) err.classList.add('show');
  })();
  document.getElementById('forgot').addEventListener('click', function(){ document.getElementById('resetNote').classList.toggle('show'); });
  function pad(n){return (n<10?'0':'')+n;}
  function tick(){
    // Kerala local time (IST, UTC+5:30) regardless of the viewer's device timezone
    var ist=new Date(Date.now()+(330+new Date().getTimezoneOffset())*60000);
    document.getElementById('clock').textContent=pad(ist.getHours())+':'+pad(ist.getMinutes())+':'+pad(ist.getSeconds());
  }
  tick(); setInterval(tick,1000);
  if (window.RoadLogin) RoadLogin.init('scene');
