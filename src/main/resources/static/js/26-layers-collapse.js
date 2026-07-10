/* ============================================================
   KLRAMS viewer · 26-layers-collapse.js
   Turns the Layers panel into a premium accordion — each section
   heading (.grp-title) becomes an interactive row with an icon chip
   and a chevron that shows/hides the group card (.grp) below it.
   The panel had grown long, so every group starts collapsed on each
   load/login — a click is the only thing that expands a section, and
   that open state is intentionally not remembered across reloads.
   Visual styling lives in css/klrams-dark.css and css/app.css.
   ============================================================ */
(function(){
  /* stroke icons (currentColor) chosen per section */
  var ICONS={
    road:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 20 8 4M20 20 16 4M12 5v2M12 11v2M12 17v2"/></svg>',
    pulse:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 12h4l3 8 4-16 3 8h4"/></svg>',
    boundary:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M9 4 4 6v14l5-2 6 2 5-2V4l-5 2-6-2z"/><path d="M9 4v14M15 6v14"/></svg>',
    bridge:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12h20M5 12v7M19 12v7M7 12a5 5 0 0 1 10 0"/></svg>',
    gauge:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M4 19a8 8 0 1 1 16 0"/><path d="M12 19l4-5"/></svg>',
    traffic:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="8" y="2" width="8" height="16" rx="4"/><circle cx="12" cy="6" r="1.2"/><circle cx="12" cy="10.5" r="1.2"/><circle cx="12" cy="15" r="1.2"/><path d="M12 18v4"/></svg>',
    layers:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2 2 7l10 5 10-5-10-5z"/><path d="M2 12l10 5 10-5"/><path d="M2 17l10 5 10-5"/></svg>'
  };

  function iconFor(label){
    var t=label.toLowerCase();
    if(t.indexOf('condition')>-1||t.indexOf('fwd')>-1) return ICONS.pulse;
    if(t.indexOf('administrative')>-1||t.indexOf('boundary')>-1) return ICONS.boundary;
    if(t.indexOf('structure')>-1||t.indexOf('furniture')>-1) return ICONS.bridge;
    if(t.indexOf('pci')>-1) return ICONS.gauge;
    if(t.indexOf('traffic')>-1) return ICONS.traffic;
    if(t.indexOf('soil')>-1||t.indexOf('core')>-1||t.indexOf('crust')>-1) return ICONS.layers;
    return ICONS.road;
  }

  function setState(title, body, collapsed){
    body.classList.toggle('grp-collapsed', collapsed);
    title.classList.toggle('is-collapsed', collapsed);
    title.setAttribute('aria-expanded', collapsed ? 'false' : 'true');
  }

  function initLayersCollapse(){
    var pane = document.getElementById('pane-layers');
    if(!pane) return;

    var titles = pane.querySelectorAll('.grp-title');
    Array.prototype.forEach.call(titles, function(title){
      var body = title.nextElementSibling;
      if(!body || !body.classList.contains('grp')) return;
      if(title.style.display === 'none') return;   /* skip the hidden Interaction group */

      var label = (title.textContent || '').trim();

      /* rebuild the header: icon chip + label (chevron & accent stripe are CSS) */
      var ic = document.createElement('span');
      ic.className = 'grp-ic';
      ic.setAttribute('aria-hidden', 'true');
      ic.innerHTML = iconFor(label);
      var tx = document.createElement('span');
      tx.className = 'grp-tx';
      tx.textContent = label;
      title.textContent = '';
      title.appendChild(ic);
      title.appendChild(tx);

      title.classList.add('grp-toggle');
      title.setAttribute('role', 'button');
      title.setAttribute('tabindex', '0');

      /* Always start collapsed on every load/login, so only the headings
         show by default; a click is the only thing that expands a group,
         and that state is intentionally not remembered across reloads. */
      setState(title, body, true);

      function toggle(){
        var collapsed = !body.classList.contains('grp-collapsed');
        setState(title, body, collapsed);
      }

      title.addEventListener('click', toggle);
      title.addEventListener('keydown', function(e){
        if(e.key === 'Enter' || e.key === ' '){ e.preventDefault(); toggle(); }
      });
    });
  }

  if(document.readyState === 'loading'){
    document.addEventListener('DOMContentLoaded', initLayersCollapse);
  } else {
    initLayersCollapse();
  }
})();
