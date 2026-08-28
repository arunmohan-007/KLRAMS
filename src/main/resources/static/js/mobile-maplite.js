/* ============================================================
   KLRAMS · mobile-maplite.js
   Turns the WebGL-free viewer's side panel into a draggable bottom
   sheet on phones, with a floating toggle. Desktop is untouched.
   Pairs with css/mobile-maplite.css.
   ============================================================ */
(function(){
  var mq = window.matchMedia('(max-width:820px)');
  function phone(){ return mq.matches; }
  function vh(){ return (window.visualViewport && window.visualViewport.height) || window.innerHeight; }

  function ready(fn){
    if (document.readyState !== 'loading') fn();
    else document.addEventListener('DOMContentLoaded', fn);
  }

  ready(function(){
    var panel = document.querySelector('.panel');
    if (!panel) return;

    var fab = document.createElement('button');
    fab.id = 'kllFab';
    fab.type = 'button';
    fab.setAttribute('aria-label', 'Layers');
    fab.title = 'Layers';
    fab.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
      'stroke-linecap="round" stroke-linejoin="round"><path d="M12 3l9 5-9 5-9-5 9-5z"/>' +
      '<path d="M3 13l9 5 9-5"/></svg>';

    var backdrop = document.createElement('div');
    backdrop.id = 'kllBackdrop';

    document.body.appendChild(backdrop);
    document.body.appendChild(fab);

    /* Two snap points: a peek that still shows most of the map, and near-full
       for working through the whole layer list. */
    function snaps(){
      var h = vh() - 100;
      return [Math.round(h * 0.42), Math.round(h * 0.92)];
    }
    function setH(px){
      document.documentElement.style.setProperty('--kl-lite-h', px + 'px');
    }
    function open(){
      setH(snaps()[0]);
      panel.classList.add('kl-open');
      document.body.classList.add('kll-open');
    }
    function close(){
      panel.classList.remove('kl-open');
      document.body.classList.remove('kll-open');
    }
    fab.addEventListener('click', open);
    backdrop.addEventListener('click', close);
    document.addEventListener('keydown', function(e){
      if (e.key === 'Escape' && panel.classList.contains('kl-open')) close();
    });

    /* ---- drag ---- */
    var startY = 0, startH = 0, dragging = false, moved = false, pid = null;

    panel.addEventListener('pointerdown', function(e){
      if (!phone() || !panel.classList.contains('kl-open')) return;
      /* only the top strip grabs, otherwise the layer list cannot scroll */
      if (e.clientY - panel.getBoundingClientRect().top > 30 && panel.scrollTop > 0) return;
      dragging = true; moved = false; pid = e.pointerId;
      startY = e.clientY;
      startH = panel.getBoundingClientRect().height;
    });

    panel.addEventListener('pointermove', function(e){
      if (!dragging || e.pointerId !== pid) return;
      var dy = startY - e.clientY;
      if (!moved){
        if (Math.abs(dy) < 5) return;
        moved = true;
        panel.classList.add('kl-dragging');
        try { panel.setPointerCapture(pid); } catch (err) {}
      }
      setH(Math.max(70, Math.min(vh() - 70, startH + dy)));
      e.preventDefault();
    }, {passive:false});

    function end(e){
      if (!dragging || (e && e.pointerId !== pid)) return;
      dragging = false;
      panel.classList.remove('kl-dragging');
      try { panel.releasePointerCapture(pid); } catch (err) {}
      if (!moved) return;
      var h = panel.getBoundingClientRect().height, s = snaps();
      if (h < s[0] * 0.6){ close(); return; }
      setH(Math.abs(s[0] - h) <= Math.abs(s[1] - h) ? s[0] : s[1]);
    }
    panel.addEventListener('pointerup', end);
    panel.addEventListener('pointercancel', end);

    /* the sheet starts closed so the map gets the whole screen on first paint */
    if (phone()) close();
    /* only animate from the second frame on — see the .kl-anim note in the CSS */
    requestAnimationFrame(function(){
      requestAnimationFrame(function(){ panel.classList.add('kl-anim'); });
    });
    mq.addEventListener('change', function(){ if (!phone()) close(); });
  });
})();
