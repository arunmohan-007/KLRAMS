/* ============================================================
   KLRAMS viewer · 38b-composer-ui.js
   Map Composer — the screen.

   A six-step workflow down the left, a live sheet preview on the
   right. Every control writes into KLComposer.state() and asks for a
   fresh preview; nothing here knows how a map, a legend or a PDF is
   made — that is all 38-map-composer.js.

   Styling is injected by this file rather than added to css/app.css,
   the way launcher.js does it. Two reasons: the sheet preview has to
   look the same whatever the viewer's theme is doing (a dark-mode
   override on a white page preview would be actively wrong), and the
   dark stylesheet does not then need a matching !important block for
   every new class.
   ============================================================ */
(function () {
  'use strict';

  var CSS = `
  #mapComposer{position:absolute;inset:0 0 0 74px;z-index:8;display:none;
    background:radial-gradient(120% 90% at 100% 0%,#eef4fb 0%,#e9eef5 45%,#e6ebf2 100%);
    font-family:Inter,"Segoe UI",system-ui,sans-serif;color:#16233a;overflow:hidden}
  #mapComposer.open{display:flex;flex-direction:column}

  /* One compact bar. The title, the sheet description and every action live
     in it, so the whole rest of the screen belongs to the sheet — a preview
     is the thing this screen is for, and a paragraph of instructions read
     once was costing it 90 px on every visit. */
  .mc-top{display:flex;align-items:center;gap:14px;padding:0 16px;height:52px;flex-shrink:0;
    background:linear-gradient(115deg,#0b1a2e 0%,#123253 60%,#16496f 100%);color:#fff;
    box-shadow:0 4px 18px -10px rgba(9,20,36,.75)}
  .mc-top .mc-ttl{display:flex;align-items:baseline;gap:9px;flex-shrink:0}
  .mc-top .mc-ttl b{font-size:15.5px;font-weight:800;letter-spacing:.2px}
  .mc-top .mc-ttl i{font-style:normal;font-size:11px;color:#8fb0d0}
  .mc-top .sp{flex:1}
  .mc-sheet{font-size:11.4px;color:#a9c4de;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;
    max-width:44%;background:rgba(255,255,255,.07);padding:5px 11px;border-radius:8px}
  .mc-x{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.18);color:#dbe7f5;
    width:34px;height:34px;border-radius:10px;font-size:20px;cursor:pointer;line-height:1;flex-shrink:0}
  .mc-x:hover{background:rgba(255,255,255,.18);color:#fff}

  .mc-body{flex:1;display:flex;min-height:0}
  .mc-side{width:340px;flex-shrink:0;overflow-y:auto;padding:11px 11px 28px;border-right:1px solid #d3dce8;
    background:#f7fafd}
  .mc-side::-webkit-scrollbar{width:9px}
  .mc-side::-webkit-scrollbar-thumb{background:#c3d0e0;border-radius:6px;border:3px solid #f7fafd}
  .mc-stage{flex:1;min-width:0;display:flex;flex-direction:column;padding:11px 12px 11px}

  .mc-step{background:#fff;border:1px solid #dde5ef;border-radius:14px;margin-bottom:11px;overflow:hidden;
    box-shadow:0 1px 3px rgba(20,40,70,.05)}
  .mc-step>summary{list-style:none;cursor:pointer;display:flex;align-items:center;gap:11px;padding:12px 14px;
    font-size:13.5px;font-weight:700;color:#16233a;user-select:none}
  .mc-step>summary::-webkit-details-marker{display:none}
  .mc-step[open]>summary{border-bottom:1px solid #eaf0f7}
  .mc-num{width:23px;height:23px;border-radius:8px;background:linear-gradient(135deg,#1d6fb8,#0f4f8c);
    color:#fff;font-size:11.5px;font-weight:800;display:flex;align-items:center;justify-content:center;flex-shrink:0}
  .mc-step .mc-sum{flex:1;font-weight:500;font-size:11.5px;color:#6b7f99;text-align:right;
    white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:170px}
  .mc-in{padding:12px 14px 15px}

  .mc-gal{display:grid;grid-template-columns:1fr 1fr;gap:10px}
  .mc-card{border:1.5px solid #dde5ef;border-radius:12px;background:#fff;cursor:pointer;overflow:hidden;
    text-align:left;padding:0;font-family:inherit;transition:border-color .15s,box-shadow .15s,transform .12s;position:relative}
  .mc-card:hover{transform:translateY(-2px);box-shadow:0 10px 22px -14px rgba(15,60,110,.55);border-color:#9fc2e4}
  .mc-card.on{border-color:#1d6fb8;box-shadow:0 0 0 3px rgba(29,111,184,.15)}
  .mc-card svg{display:block;width:100%;height:auto;background:#eef2f7}
  .mc-cname{padding:7px 9px 8px;font-size:11.8px;font-weight:700;color:#16233a;line-height:1.25}
  .mc-cname i{display:block;font-style:normal;font-weight:500;font-size:10.2px;color:#7286a0;margin-top:2px;
    display:-webkit-box;-webkit-line-clamp:2;-webkit-box-orient:vertical;overflow:hidden}
  .mc-star{position:absolute;top:6px;right:6px;background:#f0b429;color:#3d2c00;font-size:8.5px;font-weight:800;
    letter-spacing:.4px;padding:3px 6px;border-radius:6px}
  .mc-tdel{position:absolute;top:6px;left:6px;background:rgba(255,255,255,.9);border:1px solid #d6dfea;
    color:#a3546a;width:20px;height:20px;border-radius:6px;font-size:13px;line-height:1;cursor:pointer;display:none}
  .mc-card:hover .mc-tdel{display:block}

  .mc-row{display:flex;align-items:center;gap:9px;padding:7px 2px;font-size:12.6px;cursor:pointer;border-radius:8px}
  .mc-row:hover{background:#f2f7fc}
  .mc-row input{accent-color:#1d6fb8;width:15px;height:15px;flex-shrink:0;cursor:pointer}
  .mc-row .mc-lbl{flex:1;min-width:0}
  .mc-row .mc-lbl b{display:block;font-weight:600;color:#16233a;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
  .mc-row .mc-lbl i{font-style:normal;font-size:10.8px;color:#7d8fa8;display:block;margin-top:1px}
  .mc-row.dis{opacity:.5;cursor:not-allowed}

  /* legend editor rows */
  .mc-lgrow{display:flex;align-items:center;gap:8px;padding:3px 2px 3px 22px}
  .mc-lgrow.dis{opacity:.42}
  .mc-lgrow input[type=checkbox]{accent-color:#1d6fb8;width:14px;height:14px;flex-shrink:0}
  .mc-sw{width:16px;height:9px;border-radius:2px;flex-shrink:0;box-shadow:0 0 0 1px rgba(20,40,70,.18)}
  .mc-lgname{flex:1;min-width:0;border:1px solid transparent;background:transparent;border-radius:6px;
    padding:3px 6px;font-size:11.8px;font-family:inherit;color:#2c4664}
  .mc-lgname:hover{border-color:#dbe4ee;background:#fff}
  .mc-lgname:focus{outline:none;border-color:#1d6fb8;background:#fff;box-shadow:0 0 0 2px rgba(29,111,184,.12)}
  /* The heading field sits where an entry's swatch would, so the rows below it
     stay aligned; the "H" chip is what marks it as the heading and not a row. */
  .mc-lgtitle-ic{width:16px;height:14px;flex-shrink:0;display:flex;align-items:center;justify-content:center;
    border-radius:3px;background:#e6eefa;color:#1d6fb8;font-size:9px;font-weight:800}
  .mc-lgtitle{font-weight:700;color:#16304e}

  .mc-grp{margin-top:9px}
  .mc-grp:first-child{margin-top:0}
  .mc-gt{font-size:9.8px;font-weight:800;letter-spacing:.9px;text-transform:uppercase;color:#8496ad;
    padding:5px 2px 3px;border-bottom:1px solid #edf2f8;display:flex;align-items:center;gap:8px}
  .mc-gt span{flex:1}
  .mc-mini{background:#eef4fa;border:1px solid #dbe6f2;color:#3c6a9c;font-size:9.5px;font-weight:700;
    padding:2px 6px;border-radius:5px;cursor:pointer;font-family:inherit}
  .mc-mini:hover{background:#e0ecf8}

  .mc-fld{margin-bottom:9px}
  .mc-fld label{display:block;font-size:10px;font-weight:800;letter-spacing:.7px;text-transform:uppercase;
    color:#7d8fa8;margin-bottom:4px}
  .mc-fld input,.mc-fld select,.mc-fld textarea{width:100%;box-sizing:border-box;border:1px solid #d6e0ec;
    border-radius:9px;padding:8px 10px;font-size:12.6px;font-family:inherit;color:#16233a;background:#fff}
  .mc-fld input:focus,.mc-fld select:focus,.mc-fld textarea:focus{outline:none;border-color:#1d6fb8;
    box-shadow:0 0 0 3px rgba(29,111,184,.13)}
  .mc-fld textarea{resize:vertical;min-height:44px}
  .mc-2{display:grid;grid-template-columns:1fr 1fr;gap:9px}

  .mc-chips{display:flex;flex-wrap:wrap;gap:6px}
  .mc-chip{border:1px solid #d6e0ec;background:#fff;border-radius:9px;padding:6px 11px;font-size:11.8px;
    font-weight:600;color:#43536b;cursor:pointer;font-family:inherit}
  .mc-chip:hover{border-color:#9fc2e4}
  .mc-chip.on{background:linear-gradient(135deg,#1d6fb8,#0f4f8c);border-color:#0f4f8c;color:#fff}

  .mc-note{font-size:11.4px;color:#6b7f99;line-height:1.5;margin:8px 2px 0}
  .mc-warn{font-size:11.4px;line-height:1.5;margin:8px 0 0;padding:8px 10px;border-radius:9px;
    background:#fff6e6;border:1px solid #f2dcb0;color:#7a5a12}

  .mc-prevwrap{flex:1;min-height:0;display:flex;align-items:center;justify-content:center;
    background:repeating-conic-gradient(#e3e9f1 0% 25%,#eef2f7 0% 50%) 50%/22px 22px;
    border:1px solid #d3dce8;border-radius:12px;overflow:hidden;padding:12px;position:relative}
  #mcCanvasBox{box-shadow:0 16px 40px -16px rgba(15,35,60,.55);background:#fff;line-height:0;
    max-width:100%;max-height:100%}
  /* The canvas is sized in JS (fitCanvas) rather than by CSS percentages:
     a percentage max-height resolves against a parent whose own height is
     content-derived, i.e. against nothing, and the sheet ended up taller
     than the pane with a scrollbar down the side of it. */
  #mcCanvasBox canvas{display:block}
  .mc-busy{position:absolute;inset:0;display:none;align-items:center;justify-content:center;flex-direction:column;
    gap:12px;background:rgba(238,243,249,.86);z-index:3;font-size:13px;font-weight:600;color:#2b4664}
  .mc-busy.on{display:flex}
  .mc-spin{width:34px;height:34px;border-radius:50%;border:3px solid #cddcec;border-top-color:#1d6fb8;
    animation:mcspin .8s linear infinite}
  @keyframes mcspin{to{transform:rotate(360deg)}}
  .mc-empty{text-align:center;color:#6b7f99;font-size:13px;max-width:420px;line-height:1.6;
    padding:26px 30px;white-space:normal}

  .mc-btn{border:1px solid rgba(255,255,255,.2);background:rgba(255,255,255,.1);font-family:inherit;
    font-size:12.3px;font-weight:700;padding:7px 13px;border-radius:9px;cursor:pointer;color:#e6f0fa;
    flex-shrink:0;white-space:nowrap}
  .mc-btn:hover{background:rgba(255,255,255,.2);color:#fff}
  .mc-btn.pri{background:linear-gradient(135deg,#2f8fdc,#1668b0);border-color:#1668b0;color:#fff}
  .mc-btn.pri:hover{filter:brightness(1.09);background:linear-gradient(135deg,#2f8fdc,#1668b0)}
  .mc-btn.go{background:linear-gradient(135deg,#17b57f,#0d8b5e);border-color:#0d8b5e;color:#fff}
  .mc-btn.go:hover{filter:brightness(1.09);background:linear-gradient(135deg,#17b57f,#0d8b5e)}
  .mc-btn[disabled]{opacity:.45;cursor:not-allowed}

  @media(max-width:1180px){.mc-side{width:330px}.mc-gal{grid-template-columns:1fr}}
  `;

  /* ---------------------------------------------------------------- */

  var built = false, previewToken = 0, previewTimer = null, savedFilters = [];

  function el(id) { return document.getElementById(id); }
  function esc(s) {
    return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;')
      .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  /* ================================================================
     Template thumbnails

     Drawn FROM the template's own JSON rather than shipped as images,
     so a template someone adds later gets a truthful thumbnail with no
     extra file — and a thumbnail can never drift from the layout it
     claims to show.
     ================================================================ */
  function thumb(t) {
    var th = t.theme || {};
    var landscape = (t.orientation !== 'portrait');
    var W = 200, H = landscape ? 132 : 172;
    var m = 8;
    var y = m, x = m, w = W - 2 * m, h = H - 2 * m;
    var s = [];

    s.push('<rect x="0" y="0" width="' + W + '" height="' + H + '" fill="' + esc(th.paper || '#fff') + '"/>');

    var hdr = t.header || {};
    if (hdr.show !== false && hdr.style !== 'overlay') {
      var hh = Math.max(11, (hdr.height || 18) * 0.62);
      if (hdr.style === 'band') {
        s.push('<rect x="' + x + '" y="' + y + '" width="' + w + '" height="' + hh + '" rx="2" fill="'
               + esc(th.band || '#0d3b66') + '"/>');
        s.push('<rect x="' + (x + 4) + '" y="' + (y + hh / 2 - 2.5) + '" width="' + (w * 0.42)
               + '" height="5" rx="2" fill="' + esc(th.bandInk || '#fff') + '" opacity=".9"/>');
      } else {
        var ax = hdr.align === 'center' ? (x + w / 2 - w * 0.2) : x;
        s.push('<rect x="' + ax + '" y="' + (y + 3) + '" width="' + (w * 0.4) + '" height="5.5" rx="2" fill="'
               + esc(th.ink || '#111') + '"/>');
        if (hdr.style === 'rule') {
          s.push('<line x1="' + x + '" y1="' + (y + hh) + '" x2="' + (x + w) + '" y2="' + (y + hh)
                 + '" stroke="' + esc(th.frame || '#111') + '" stroke-width="1"/>');
        }
      }
      y += hh + 3; h -= hh + 3;
    }

    var md = t.metadata || {};
    if (md.show !== false && md.position === 'bottom') {
      var mh = Math.max(8, (md.height || 13) * 0.55);
      s.push('<rect x="' + x + '" y="' + (y + h - mh) + '" width="' + w + '" height="' + mh
             + '" rx="1.5" fill="' + esc(th.panel || '#f2f6fb') + '" stroke="' + esc(th.rule || '#ccd')
             + '" stroke-width=".6"/>');
      for (var i = 0; i < (md.columns || 4); i++) {
        var cw = w / (md.columns || 4);
        s.push('<rect x="' + (x + i * cw + 3) + '" y="' + (y + h - mh + 3) + '" width="' + (cw * 0.55)
               + '" height="2" rx="1" fill="' + esc(th.muted || '#889') + '" opacity=".75"/>');
      }
      h -= mh + 3;
    }

    var lg = t.legend || {};
    var mx = x, mw = w;
    var side = (lg.position === 'left' || lg.position === 'right');
    if (lg.show !== false && side) {
      var lw = Math.min(w * 0.34, (lg.width || 52) * 0.62);
      if (lg.position === 'right') {
        s.push(legendBox(x + w - lw, y, lw, Math.min(h, 62), th));
        mw = w - lw - 3;
      } else {
        s.push(legendBox(x, y, lw, Math.min(h, 62), th));
        mx = x + lw + 3; mw = w - lw - 3;
      }
    }

    /* map body — a suggestion of a road network, not a real one */
    s.push('<rect x="' + mx + '" y="' + y + '" width="' + mw + '" height="' + h + '" fill="'
           + basemapWash(t.basemap) + '"/>');
    s.push('<g stroke="' + esc(th.accent || '#0d5c9e') + '" fill="none" stroke-linecap="round" opacity=".92">');
    s.push('<path d="M' + (mx + mw * .08) + ',' + (y + h * .78) + ' C' + (mx + mw * .3) + ',' + (y + h * .58)
           + ' ' + (mx + mw * .42) + ',' + (y + h * .72) + ' ' + (mx + mw * .92) + ',' + (y + h * .2)
           + '" stroke-width="' + (1.5 * (t.mapEmphasis && t.mapEmphasis.road || 1)) + '"/>');
    s.push('<path d="M' + (mx + mw * .12) + ',' + (y + h * .22) + ' C' + (mx + mw * .38) + ',' + (y + h * .42)
           + ' ' + (mx + mw * .5) + ',' + (y + h * .3) + ' ' + (mx + mw * .88) + ',' + (y + h * .74)
           + '" stroke-width="' + (1.1 * (t.mapEmphasis && t.mapEmphasis.road || 1)) + '" opacity=".65"/>');
    s.push('</g>');
    var ar = (t.mapEmphasis && t.mapEmphasis.asset) || 1;
    [[.32, .58], [.55, .45], [.72, .38]].forEach(function (p) {
      s.push('<circle cx="' + (mx + mw * p[0]) + '" cy="' + (y + h * p[1]) + '" r="' + (1.9 * ar)
             + '" fill="#e0722a" stroke="#fff" stroke-width=".6"/>');
    });

    if ((t.grid || {}).show) {
      s.push('<g stroke="' + esc(th.frame || '#111') + '" stroke-width=".4" opacity="'
             + ((t.grid.style === 'graticule') ? '.24' : '.6') + '">');
      for (var g = 1; g < 4; g++) {
        var gx = mx + mw * g / 4, gy = y + h * g / 4;
        if (t.grid.style === 'graticule') {
          s.push('<line x1="' + gx + '" y1="' + y + '" x2="' + gx + '" y2="' + (y + h) + '"/>');
          s.push('<line x1="' + mx + '" y1="' + gy + '" x2="' + (mx + mw) + '" y2="' + gy + '"/>');
        } else {
          s.push('<line x1="' + gx + '" y1="' + y + '" x2="' + gx + '" y2="' + (y + 3) + '"/>');
          s.push('<line x1="' + gx + '" y1="' + (y + h) + '" x2="' + gx + '" y2="' + (y + h - 3) + '"/>');
        }
      }
      s.push('</g>');
    }

    if (lg.show !== false && !side) {
      var flw = Math.min(mw * 0.38, 46);
      var flx = /right/.test(lg.position || '') ? (mx + mw - flw - 4) : (mx + 4);
      var fly = /top/.test(lg.position || '') ? (y + 4) : (y + h - 34);
      s.push(legendBox(flx, fly, flw, 30, th, true));
    }

    if ((t.northArrow || {}).show !== false) {
      var nx = /left/.test((t.northArrow || {}).position || 'map-top-right') ? (mx + 8) : (mx + mw - 8);
      s.push('<g transform="translate(' + nx + ',' + (y + 9) + ')" fill="' + esc(th.ink || '#111') + '">'
             + '<path d="M0,-6 L3,5 L0,3 L-3,5 Z"/></g>');
    }
    if ((t.scaleBar || {}).show !== false) {
      var sbY = (t.scaleBar.position === 'below-map') ? (y + h + 4) : (y + h - 7);
      var sbX = /right/.test(t.scaleBar.position || '') ? (mx + mw - 34) : (mx + 6);
      s.push('<g>' +
        '<rect x="' + sbX + '" y="' + sbY + '" width="14" height="3" fill="' + esc(th.ink || '#111') + '"/>' +
        '<rect x="' + (sbX + 14) + '" y="' + sbY + '" width="14" height="3" fill="#fff" stroke="'
          + esc(th.ink || '#111') + '" stroke-width=".5"/></g>');
    }

    var nl = t.neatline || {};
    if (nl.show !== false) {
      s.push('<rect x="' + mx + '" y="' + y + '" width="' + mw + '" height="' + h + '" fill="none" stroke="'
             + esc(th.frame || '#111') + '" stroke-width="' + Math.max(.5, (nl.width || .6)) + '"/>');
      if (nl.double) {
        s.push('<rect x="' + (mx - 2) + '" y="' + (y - 2) + '" width="' + (mw + 4) + '" height="' + (h + 4)
               + '" fill="none" stroke="' + esc(th.frame || '#111') + '" stroke-width=".4"/>');
      }
    }

    return '<svg viewBox="0 0 ' + W + ' ' + H + '" xmlns="http://www.w3.org/2000/svg">' + s.join('') + '</svg>';
  }

  function legendBox(x, y, w, h, th, floating) {
    var s = '<rect x="' + x + '" y="' + y + '" width="' + w + '" height="' + h + '" rx="' + (floating ? 3 : 2)
          + '" fill="' + esc(th.panel || '#f5f8fc') + '" stroke="' + esc(th.rule || '#ccd') + '" stroke-width=".6"/>';
    for (var i = 0; i < Math.min(5, Math.floor((h - 8) / 6)); i++) {
      var ly = y + 6 + i * 6;
      s += '<rect x="' + (x + 3) + '" y="' + (ly - 1.2) + '" width="6" height="2.4" rx="1" fill="'
         + ['#0d5c9e', '#e0722a', '#2ba66a', '#8a5cb8', '#c9563c'][i % 5] + '"/>';
      s += '<rect x="' + (x + 11) + '" y="' + (ly - 1.2) + '" width="' + Math.max(4, w - 15)
         + '" height="2.2" rx="1" fill="' + esc(th.muted || '#889') + '" opacity=".5"/>';
    }
    return s;
  }

  function basemapWash(bm) {
    return { sat: '#3b4a3a', dark: '#1d232c', topo: '#eee6d5', osm: '#e9e4dc', light: '#f2f5f8', none: '#ffffff' }[bm]
        || '#f2f5f8';
  }

  /* ================================================================
     Screen construction
     ================================================================ */
  function build() {
    if (built) return;
    built = true;

    var st = document.createElement('style');
    st.id = 'mc-css';
    st.textContent = CSS;
    document.head.appendChild(st);

    var scr = document.createElement('div');
    scr.id = 'mapComposer';
    scr.innerHTML =
      '<div class="mc-top">' +
        '<div class="mc-ttl"><b>Map Composer</b><i>template · extent · layers · export</i></div>' +
        '<span class="mc-sheet" id="mcSheetMeta">Preparing…</span>' +
        '<div class="sp"></div>' +
        '<button class="mc-btn" onclick="KLComposerUI.refresh(true)" title="Redraw the sheet">Regenerate</button>' +
        '<button class="mc-btn pri" id="mcPng" onclick="KLComposerUI.exportPng()">PNG</button>' +
        '<button class="mc-btn go" id="mcPdf" onclick="KLComposerUI.exportPdf()">PDF</button>' +
        '<button class="mc-x" onclick="closeMapComposer()" title="Back to map">&times;</button>' +
      '</div>' +
      '<div class="mc-body">' +
        '<div class="mc-side" id="mcSide"></div>' +
        '<div class="mc-stage">' +
          '<div class="mc-prevwrap">' +
            '<div id="mcCanvasBox"><div class="mc-empty" id="mcEmpty">Preparing the first preview…</div></div>' +
            '<div class="mc-busy" id="mcBusy"><div class="mc-spin"></div><div id="mcBusyTxt">Composing…</div></div>' +
          '</div>' +
        '</div>' +
      '</div>';

    /* Sits alongside the other full-screen modules, inside #main. */
    var host = document.getElementById('main') || document.body;
    host.appendChild(scr);

    var fi = document.createElement('input');
    fi.type = 'file'; fi.accept = '.json,application/json'; fi.id = 'mcImportFile';
    fi.style.display = 'none';
    fi.addEventListener('change', onImportFile);
    scr.appendChild(fi);
  }

  /* ================================================================
     Sidebar
     ================================================================ */
  function renderSide() {
    var s = KLComposer.state();
    var tpl = KLComposer.Templates.byId(s.templateId);
    var side = el('mcSide');
    if (!side) return;
    var open = {};
    side.querySelectorAll('details.mc-step').forEach(function (d) { open[d.dataset.k] = d.open; });
    if (!Object.keys(open).length) open = { tpl: true, extent: true, layers: true, info: false, page: false };

    side.innerHTML =
      step('tpl', 1, 'Template', tpl.name, open.tpl, galleryHtml()) +
      step('extent', 2, 'Map extent', extentSummary(), open.extent, extentHtml()) +
      step('layers', 3, 'Layers', layerSummary(), open.layers, '<div id="mcLayerBox">' + layersHtml() + '</div>') +
      step('legend', 4, 'Legend', legendSummary(), open.legend, '<div id="mcLegendBox">' + legendHtml() + '</div>') +
      step('info', 5, 'Map information', '', open.info, infoHtml()) +
      step('page', 6, 'Page &amp; furniture', pageSummary(), open.page, pageHtml());

    wireSide();
  }

  function step(key, n, title, summary, isOpen, inner) {
    return '<details class="mc-step" data-k="' + key + '"' + (isOpen ? ' open' : '') + '>' +
      '<summary><span class="mc-num">' + n + '</span>' + title +
      '<span class="mc-sum">' + esc(summary || '') + '</span></summary>' +
      '<div class="mc-in">' + inner + '</div></details>';
  }

  function galleryHtml() {
    var s = KLComposer.state();
    var cards = KLComposer.Templates.all().map(function (t) {
      return '<button class="mc-card' + (t.id === s.templateId ? ' on' : '') + '" data-tpl="' + esc(t.id) + '">' +
        thumb(t) +
        (t.recommended ? '<span class="mc-star">RECOMMENDED</span>' : '') +
        (t.custom ? '<span class="mc-tdel" data-del="' + esc(t.id) + '" title="Remove this template">&times;</span>' : '') +
        '<span class="mc-cname">' + esc(t.name) + '<i>' + esc(t.tagline || '') + '</i></span>' +
      '</button>';
    }).join('');
    return '<div class="mc-gal">' + cards + '</div>' +
      '<div class="mc-note">Switching template changes the LAYOUT only — your filter, layers and extent stay exactly as they are.</div>' +
      '<div class="mc-chips" style="margin-top:9px">' +
        '<button class="mc-chip" id="mcImport">+ Import template</button>' +
        '<button class="mc-chip" id="mcSaveTpl">+ Save current layout</button>' +
        '<button class="mc-chip" id="mcExportTpl">Export this template</button>' +
      '</div>';
  }

  function extentHtml() {
    var s = KLComposer.state();
    var active = KLComposer.Filters.activeText();
    var hasSel = KLComposer.Extent.hasSelection();

    var modes = [
      ['filter', 'Current Road Network filter', active ? ('Active: ' + active) : 'No filter is active — this will use the whole network'],
      ['network', 'Entire road network', 'Ignores any active filter'],
      ['selected', 'Selected feature(s)', hasSel ? 'A road is selected on the map' : 'Nothing is selected yet'],
      ['view', 'Current map view', 'Exactly what the map window is showing'],
      ['layers', 'Selected layers', 'Combined extent of everything ticked in step 3']
    ];

    var html = modes.map(function (m) {
      var dis = (m[0] === 'selected' && !hasSel);
      return '<label class="mc-row' + (dis ? ' dis' : '') + '">' +
        '<input type="radio" name="mcExtent" value="' + m[0] + '"' + (s.extentMode === m[0] ? ' checked' : '') +
        (dis ? ' disabled' : '') + '>' +
        '<span class="mc-lbl"><b>' + m[1] + '</b><i>' + esc(m[2]) + '</i></span></label>';
    }).join('');

    var opts = savedFilters.map(function (f) {
      return '<option value="' + esc(f.id) + '">' + esc(f.name) +
             (f.mine ? '' : ' (shared by ' + esc(f.owner) + ')') + '</option>';
    }).join('');

    html += '<div class="mc-grp"><div class="mc-gt"><span>Saved Road Network filter</span></div>' +
      '<div class="mc-fld" style="margin-top:8px">' +
        '<select id="mcSavedSel"><option value="">— No filter (entire network) —</option>' + opts + '</select>' +
      '</div>' +
      '<div class="mc-chips">' +
        '<button class="mc-chip" id="mcApplyFilter">Apply this filter</button>' +
        '<button class="mc-chip" id="mcClearFilter">Clear filter</button>' +
      '</div>' +
      '<div class="mc-note" id="mcFilterNote">' +
        (active ? 'Currently filtered by <b>' + esc(active) + '</b>.' : 'No Road Network filter is active.') +
        '<br>Only the Road Network and the layers KLRAMS already links to a road (condition, PCI, assets, ' +
        'traffic) follow this filter. Boundaries, user layers, temporary layers and drone data are never ' +
        'filtered by it.<br><b>Note:</b> applying a filter here also applies it to the map behind this screen — ' +
        'KLRAMS has one filter, not two.' +
      '</div></div>';

    return html;
  }

  function layersHtml() {
    var groups = KLComposer.Layers.grouped();
    if (!groups.length) return '<div class="mc-note">No map layers are registered yet.</div>';
    return groups.map(function (g) {
      var rows = g.items.map(function (it) {
        var on = KLComposer.Layers.selected(it);
        var hint = it.kind === 'drone' ? 'Drone raster'
                 : it.kind === 'user' ? (it.layerRow.temporary ? 'Temporary layer' : 'User layer')
                 : (it.built ? '' : 'loads when composed');
        return '<label class="mc-row"><input type="checkbox" data-lyr="' + esc(it.id) + '"' + (on ? ' checked' : '') + '>' +
          '<span class="mc-lbl"><b>' + esc(it.label) + '</b>' + (hint ? '<i>' + esc(hint) + '</i>' : '') + '</span></label>';
      }).join('');
      return '<div class="mc-grp"><div class="mc-gt"><span>' + esc(g.label) + '</span>' +
        '<button class="mc-mini" data-all="' + esc(g.group) + '">All</button>' +
        '<button class="mc-mini" data-none="' + esc(g.group) + '">None</button></div>' + rows + '</div>';
    }).join('') +
    '<div class="mc-note">The list is built from the layers registered in the viewer right now, so temporary ' +
    'layers, uploaded GeoJSON and published drone data appear here automatically. Ticking a layer here does ' +
    'not switch it on in the map behind.</div>';
  }

  /**
   * The legend editor.
   *
   * Built from the LAST composed sheet's full legend (`legendAll`), not from
   * a fresh read of the style: the entries only exist once the layers have
   * been built, which the compose does. Before the first preview the step
   * says so rather than showing an empty list.
   *
   * Two levels of control, because both are real requests: drop a whole
   * layer's block from the legend while keeping the layer on the MAP, and
   * drop individual classes (an NH row on a sheet that has no national
   * highways in frame, the "Other" catch-all, a district that is only
   * clipped at the corner). Renaming is offered alongside because a legend
   * label is the one piece of a map sheet that most often needs departmental
   * wording rather than a column value.
   */
  function legendHtml() {
    var last = KLComposer.last();
    var blocks = last && last.legendAll;
    if (!blocks || !blocks.length) {
      return '<div class="mc-note">The legend is read from the layers themselves, so it is listed here ' +
        'once the first preview has been drawn.</div>';
    }
    var s = KLComposer.state();
    var out = blocks.map(function (b) {
      var bHidden = !!s.legendHide[b.key];
      var rows = b.entries.map(function (e) {
        var k = b.key + '|' + KLComposer.Legend.entryKey(e);
        var on = !s.legendHide[k];
        var label = (k in s.legendLabel) ? s.legendLabel[k] : (e.label || b.title);
        return '<div class="mc-lgrow' + (bHidden ? ' dis' : '') + '">' +
          '<input type="checkbox" data-lgentry="' + esc(k) + '"' + (on ? ' checked' : '') +
          (bHidden ? ' disabled' : '') + '>' +
          '<span class="mc-sw" style="' + swatchCss(e) + '"></span>' +
          '<input type="text" class="mc-lgname" data-lglabel="' + esc(k) + '" value="' + esc(label) + '"' +
          (bHidden ? ' disabled' : '') + '>' +
        '</div>';
      }).join('');
      /* The HEADING is editable too, not just the rows under it. The engine
         has always honoured a rename keyed on the block (applyEdits reads
         legendLabel[b.key]); there was simply no field to type it into, so a
         heading the layers named badly could only be hidden, never fixed. */
      var tKey = b.key;
      var tVal = (tKey in s.legendLabel) ? s.legendLabel[tKey] : b.title;
      return '<div class="mc-grp">' +
        '<label class="mc-row" style="padding:5px 2px">' +
          '<input type="checkbox" data-lgblock="' + esc(b.key) + '"' + (bHidden ? '' : ' checked') + '>' +
          '<span class="mc-lbl"><b>' + esc(b.title) + '</b>' +
          '<i>' + b.entries.length + ' item(s) in the legend</i></span>' +
        '</label>' +
        '<div class="mc-lgrow' + (bHidden ? ' dis' : '') + '">' +
          '<span class="mc-lgtitle-ic" title="Legend heading">H</span>' +
          '<input type="text" class="mc-lgname mc-lgtitle" data-lglabel="' + esc(tKey) + '"' +
          ' value="' + esc(tVal) + '" placeholder="Legend heading"' +
          (bHidden ? ' disabled' : '') + '>' +
        '</div>' + rows + '</div>';
    }).join('');

    return out +
      '<div class="mc-chips" style="margin-top:10px">' +
        '<button class="mc-chip" id="mcLgReset">Reset legend</button>' +
      '</div>' +
      '<div class="mc-note">Unticking here removes the row from the LEGEND only — the layer stays on the ' +
      'map. Rename a row by typing over it. These choices belong to this sheet, not to the template: a ' +
      'template describes layout, never which of your layers or classes to show.</div>';
  }

  function swatchCss(e) {
    if (e.kind === 'gradient') {
      var stops = e.stops.map(function (s) { return s.color; }).join(',');
      return 'background:linear-gradient(90deg,' + stops + ')';
    }
    if (e.kind === 'line') return 'background:' + (e.color || '#889') + ';height:4px';
    if (e.kind === 'point' || e.kind === 'icon') {
      return 'background:' + (e.color || '#889') + ';border-radius:50%;width:11px;height:11px';
    }
    if (e.kind === 'raster') return 'background:linear-gradient(135deg,#7f8c99,#c2ccd6)';
    return 'background:' + (e.color || '#889') + ';opacity:.65';
  }

  function legendSummary() {
    var last = KLComposer.last();
    if (!last || !last.legend) return '';
    var n = 0;
    last.legend.forEach(function (b) { n += b.entries.length; });
    return n + ' row(s)';
  }

  function infoHtml() {
    var info = KLComposer.Meta.merged(null);
    var s = KLComposer.state();
    function f(key, label, type) {
      var v = (s.infoTouched[key] && s.info[key] != null) ? s.info[key] : info[key];
      if (type === 'area') {
        return '<div class="mc-fld"><label>' + label + '</label>' +
          '<textarea data-info="' + key + '" placeholder="optional">' + esc(v || '') + '</textarea></div>';
      }
      return '<div class="mc-fld"><label>' + label + '</label>' +
        '<input type="text" data-info="' + key + '" value="' + esc(v || '') + '"></div>';
    }
    return f('title', 'Map title') + f('subtitle', 'Subtitle') + f('filterText', 'Filter line') +
      '<div class="mc-2">' + f('date', 'Date') + f('district', 'District') + '</div>' +
      '<div class="mc-2">' + f('preparedBy', 'Prepared by') + f('source', 'Data source') + '</div>' +
      f('notes', 'Notes', 'area') +
      '<div class="mc-chips"><button class="mc-chip" id="mcResetInfo">Reset to automatic</button></div>' +
      '<div class="mc-note">Everything above is filled in from KLRAMS — the filter, the district it resolves to, ' +
      'today\'s date and the signed-in user. Type over anything you want to change.</div>';
  }

  function pageHtml() {
    var s = KLComposer.state();
    var tpl = KLComposer.Templates.byId(s.templateId);
    function chips(name, cur, list) {
      return '<div class="mc-chips">' + list.map(function (o) {
        return '<button class="mc-chip' + (String(cur) === String(o[0]) ? ' on' : '') + '" data-' + name + '="' +
          esc(o[0]) + '">' + esc(o[1]) + '</button>';
      }).join('') + '</div>';
    }
    var showDefaults = {
      legend: (tpl.legend || {}).show !== false, north: (tpl.northArrow || {}).show !== false,
      scale: (tpl.scaleBar || {}).show !== false, grid: !!(tpl.grid || {}).show,
      metadata: (tpl.metadata || {}).show !== false, logo: !!(tpl.logo || {}).show,
      header: (tpl.header || {}).show !== false
    };
    var toggles = [['legend', 'Legend'], ['north', 'North arrow'], ['scale', 'Scale bar'],
                   ['grid', 'Coordinate grid'], ['logo', 'Logo'], ['metadata', 'Metadata footer'],
                   ['header', 'Title header']].map(function (t) {
      var on = (s.show[t[0]] != null) ? s.show[t[0]] : showDefaults[t[0]];
      return '<label class="mc-row"><input type="checkbox" data-show="' + t[0] + '"' + (on ? ' checked' : '') + '>' +
        '<span class="mc-lbl"><b>' + t[1] + '</b></span></label>';
    }).join('');

    return '<div class="mc-fld"><label>Page size</label>' +
        chips('page', s.pageSize, [['A4', 'A4'], ['A3', 'A3'], ['A2', 'A2'], ['Letter', 'Letter'], ['Screen', 'Screen 16:9'], ['Custom', 'Custom']]) +
      '</div>' +
      (s.pageSize === 'Custom'
        ? '<div class="mc-2"><div class="mc-fld"><label>Width (mm)</label><input type="number" id="mcCw" value="' + s.custom.w + '"></div>' +
          '<div class="mc-fld"><label>Height (mm)</label><input type="number" id="mcCh" value="' + s.custom.h + '"></div></div>'
        : '') +
      '<div class="mc-fld"><label>Orientation</label>' +
        chips('orient', s.orientation, [['auto', 'Automatic'], ['landscape', 'Landscape'], ['portrait', 'Portrait']]) +
        '<div class="mc-note">Automatic picks the orientation that suits the shape of the area being mapped.</div>' +
      '</div>' +
      /* "Light" and "Dark" are CARTO, which now answers unkeyed requests with
         an "API KEY REQUIRED" watermark tile — printed across a departmental
         map sheet. They are reachable through "Same as map" if the viewer is
         ever given a key, but they are not offered as a choice here. */
      '<div class="mc-fld"><label>Base map</label>' +
        chips('bm', s.basemap, [['template', 'Template default'], ['none', 'None (plain paper)'],
                                ['osm', 'Street'], ['sat', 'Satellite'], ['topo', 'Topographic'],
                                ['same', 'Same as the map']]) +
        '<div class="mc-note">Plain paper is the cleanest for a printed sheet — switch on the district ' +
        'or constituency boundary in step 3 to give it geographic context.</div>' +
      '</div>' +
      '<div class="mc-grp"><div class="mc-gt"><span>Show on this sheet</span></div>' + toggles + '</div>';
  }

  /* ---- summaries shown collapsed ---- */
  function extentSummary() {
    var s = KLComposer.state();
    return { filter: 'Road filter', network: 'Whole network', selected: 'Selection',
             view: 'Map view', layers: 'Layers' }[s.extentMode] || '';
  }
  function layerSummary() {
    var n = KLComposer.Layers.selectedItems().length;
    return n + ' selected';
  }
  function pageSummary() {
    var s = KLComposer.state();
    return s.pageSize + ' · ' + (s.orientation === 'auto' ? 'auto' : s.orientation);
  }

  /* ================================================================
     Wiring
     ================================================================ */
  function wireSide() {
    var side = el('mcSide');

    side.querySelectorAll('[data-tpl]').forEach(function (b) {
      b.addEventListener('click', function (e) {
        if (e.target.closest('[data-del]')) return;
        KLComposer.set({ templateId: b.dataset.tpl });
        renderSide(); refresh();
      });
    });
    side.querySelectorAll('[data-del]').forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();
        if (!confirm('Remove this saved template?')) return;
        KLComposer.Templates.remove(b.dataset.del);
        var s = KLComposer.state();
        if (s.templateId === b.dataset.del) KLComposer.set({ templateId: KLComposer.Templates.all()[0].id });
        renderSide(); refresh();
      });
    });

    var imp = el('mcImport');
    if (imp) imp.addEventListener('click', function () { el('mcImportFile').click(); });
    var sv = el('mcSaveTpl');
    if (sv) sv.addEventListener('click', saveCurrentTemplate);
    var ex = el('mcExportTpl');
    if (ex) ex.addEventListener('click', function () {
      var s = KLComposer.state();
      var json = KLComposer.Templates.exportJson(s.templateId);
      KLComposer.Export.download(new Blob([json], { type: 'application/json' }), s.templateId + '.json');
    });

    side.querySelectorAll('input[name="mcExtent"]').forEach(function (r) {
      r.addEventListener('change', function () {
        KLComposer.set({ extentMode: r.value });
        renderSide(); refresh();
      });
    });

    var ap = el('mcApplyFilter');
    if (ap) ap.addEventListener('click', function () {
      var sel = el('mcSavedSel');
      var f = savedFilters.find(function (x) { return String(x.id) === sel.value; });
      if (!f) { applyNoFilter(); return; }
      KLComposer.Filters.apply(f).then(function () {
        KLComposer.set({ extentMode: 'filter' });
        touchNothing();
        renderSide(); refresh();
      }).catch(function (e) { alert(e.message); });
    });
    var cl = el('mcClearFilter');
    if (cl) cl.addEventListener('click', applyNoFilter);

    var selEl = el('mcSavedSel');
    if (selEl) selEl.addEventListener('change', function () {
      var f = savedFilters.find(function (x) { return String(x.id) === selEl.value; });
      var note = el('mcFilterNote');
      if (f && note) {
        note.innerHTML = '<b>' + esc(f.name) + '</b>: ' + esc(KLComposer.Filters.describe(f)) +
          '<br>Press <b>Apply this filter</b> to use it.';
      }
    });

    wireLayers();
    wireLegend();

    side.querySelectorAll('[data-info]').forEach(function (inp) {
      inp.addEventListener('input', function () {
        var s = KLComposer.state();
        s.info[inp.dataset.info] = inp.value;
        s.infoTouched[inp.dataset.info] = true;
        refresh();
      });
    });
    var ri = el('mcResetInfo');
    if (ri) ri.addEventListener('click', function () {
      KLComposer.set({ info: {}, infoTouched: {} });
      renderSide(); refresh();
    });

    side.querySelectorAll('[data-page]').forEach(function (b) {
      b.addEventListener('click', function () {
        KLComposer.set({ pageSize: b.dataset.page });
        renderSide(); refresh();
      });
    });
    side.querySelectorAll('[data-orient]').forEach(function (b) {
      b.addEventListener('click', function () {
        KLComposer.set({ orientation: b.dataset.orient });
        renderSide(); refresh();
      });
    });
    side.querySelectorAll('[data-bm]').forEach(function (b) {
      b.addEventListener('click', function () {
        KLComposer.set({ basemap: b.dataset.bm });
        renderSide(); refresh();
      });
    });
    side.querySelectorAll('[data-show]').forEach(function (cb) {
      cb.addEventListener('change', function () {
        KLComposer.state().show[cb.dataset.show] = cb.checked;
        refresh();
      });
    });
    ['mcCw', 'mcCh'].forEach(function (id) {
      var e = el(id);
      if (!e) return;
      e.addEventListener('change', function () {
        var s = KLComposer.state();
        s.custom = { w: +el('mcCw').value || 297, h: +el('mcCh').value || 210 };
        refresh();
      });
    });
  }

  function touchNothing() {
    /* A newly applied filter should refresh the automatic title and
       filter line — but only the fields the user has not typed into. */
    var s = KLComposer.state();
    ['title', 'subtitle', 'filterText', 'district'].forEach(function (k) {
      if (!s.infoTouched[k]) delete s.info[k];
    });
  }

  function applyNoFilter() {
    KLComposer.Filters.clear().then(function () {
      var sel = el('mcSavedSel');
      if (sel) sel.value = '';
      touchNothing();
      renderSide(); refresh();
    });
  }

  /** Wire only the layer step's controls — same reason as wireLegend(): the
   *  "All / None" buttons rebuild the list, and rewiring the whole sidebar
   *  to match would double every other listener on it. */
  function wireLayers() {
    var side = el('mcSide');
    if (!side) return;
    side.querySelectorAll('[data-lyr]').forEach(function (cb) {
      cb.addEventListener('change', function () {
        KLComposer.state().layers[cb.dataset.lyr] = cb.checked;
        updateSummary('layers', layerSummary());
        refresh();
      });
    });
    side.querySelectorAll('[data-all],[data-none]').forEach(function (b) {
      b.addEventListener('click', function () {
        var g = b.dataset.all || b.dataset.none;
        var on = !!b.dataset.all;
        var s = KLComposer.state();
        KLComposer.Layers.all().forEach(function (it) { if (it.group === g) s.layers[it.id] = on; });
        var box = el('mcLayerBox');
        if (box) { box.innerHTML = layersHtml(); wireLayers(); }
        updateSummary('layers', layerSummary());
        refresh();
      });
    });
  }

  /**
   * Wire only the legend step's controls.
   *
   * Its own function because the legend editor is rebuilt after every
   * preview, and calling the whole of wireSide() to do that added a second
   * listener to every OTHER control on the sidebar each time — by the fifth
   * preview a single click on a template card was firing five composes.
   */
  function wireLegend() {
    var side = el('mcSide');
    if (!side) return;
    side.querySelectorAll('[data-lgblock]').forEach(function (cb) {
      cb.addEventListener('change', function () {
        var s = KLComposer.state();
        if (cb.checked) delete s.legendHide[cb.dataset.lgblock];
        else s.legendHide[cb.dataset.lgblock] = true;
        redrawLegendStep();
        refresh();
      });
    });
    side.querySelectorAll('[data-lgentry]').forEach(function (cb) {
      cb.addEventListener('change', function () {
        var s = KLComposer.state();
        if (cb.checked) delete s.legendHide[cb.dataset.lgentry];
        else s.legendHide[cb.dataset.lgentry] = true;
        updateSummary('legend', legendSummary());
        refresh();
      });
    });
    side.querySelectorAll('[data-lglabel]').forEach(function (inp) {
      inp.addEventListener('input', function () {
        KLComposer.state().legendLabel[inp.dataset.lglabel] = inp.value;
        refresh();
      });
    });
    var lr = el('mcLgReset');
    if (lr) lr.addEventListener('click', function () {
      KLComposer.set({ legendHide: {}, legendLabel: {} });
      redrawLegendStep();
      refresh();
    });
  }

  /**
   * Refresh the legend editor in place.
   *
   * In place rather than through renderSide(), so opening steps and the
   * sidebar's scroll position survive a preview finishing. Skipped entirely
   * while the user is typing inside it — rebuilding the markup under a
   * focused text box would take the caret with it on every keystroke.
   */
  function redrawLegendStep() {
    var box = el('mcLegendBox');
    if (!box) return;
    if (document.activeElement && box.contains(document.activeElement)) return;
    box.innerHTML = legendHtml();
    wireLegend();
    updateSummary('legend', legendSummary());
  }

  function updateSummary(key, text) {
    var d = document.querySelector('#mcSide details[data-k="' + key + '"] .mc-sum');
    if (d) d.textContent = text;
  }

  function saveCurrentTemplate() {
    var name = prompt('Name this layout:', 'My layout');
    if (!name) return;
    var s = KLComposer.state();
    var base = KLComposer.Templates.byId(s.templateId);
    var tplShow = {};
    /* The per-sheet furniture toggles become the template's own defaults. */
    if (s.show.legend != null) tplShow.legend = Object.assign({}, base.legend, { show: s.show.legend });
    if (s.show.north != null) tplShow.northArrow = Object.assign({}, base.northArrow, { show: s.show.north });
    if (s.show.scale != null) tplShow.scaleBar = Object.assign({}, base.scaleBar, { show: s.show.scale });
    if (s.show.grid != null) tplShow.grid = Object.assign({}, base.grid, { show: s.show.grid });
    if (s.show.metadata != null) tplShow.metadata = Object.assign({}, base.metadata, { show: s.show.metadata });
    if (s.show.logo != null) tplShow.logo = Object.assign({}, base.logo, { show: s.show.logo });
    if (s.show.header != null) tplShow.header = Object.assign({}, base.header, { show: s.show.header });

    var t = KLComposer.Templates.saveCurrent(name, base, Object.assign({
      pageSize: s.pageSize === 'Custom' ? base.pageSize : s.pageSize,
      orientation: s.orientation === 'auto' ? base.orientation : s.orientation,
      basemap: s.basemap === 'template' ? base.basemap : (s.basemap === 'same' ? base.basemap : s.basemap)
    }, tplShow));
    KLComposer.set({ templateId: t.id });
    renderSide();
    refresh();
  }

  function onImportFile(e) {
    var f = e.target.files && e.target.files[0];
    e.target.value = '';
    if (!f) return;
    f.text().then(function (txt) {
      var t = KLComposer.Templates.importJson(txt);
      KLComposer.set({ templateId: t.id });
      renderSide(); refresh();
    }).catch(function (err) {
      alert('That template could not be imported.\n\n' + err.message);
    });
  }

  /* ================================================================
     Preview
     ================================================================ */
  function busy(on, text) {
    var b = el('mcBusy');
    if (!b) return;
    b.classList.toggle('on', !!on);
    var t = el('mcBusyTxt');
    if (t && text) t.textContent = text;
    ['mcPng', 'mcPdf'].forEach(function (id) { var e = el(id); if (e) e.disabled = !!on; });
  }

  function refresh(immediate) {
    clearTimeout(previewTimer);
    previewTimer = setTimeout(run, immediate ? 0 : 340);
  }

  /**
   * Scale the sheet to fill the preview pane, whole and without scrollbars.
   *
   * Done in JavaScript because the pane's height comes from a flex chain,
   * not from a length CSS can resolve a percentage against — the canvas
   * kept its full pixel height and the pane grew a scrollbar. Never scales
   * a small sheet UP past 1:1, which would just show a blurry canvas.
   */
  function fitCanvas() {
    var wrap = document.querySelector('#mapComposer .mc-prevwrap');
    var c = document.querySelector('#mcCanvasBox canvas');
    if (!wrap || !c) return;
    var padding = 24;
    var aw = Math.max(60, wrap.clientWidth - padding);
    var ah = Math.max(60, wrap.clientHeight - padding);
    var k = Math.min(aw / c.width, ah / c.height, 1);
    c.style.width = Math.round(c.width * k) + 'px';
    c.style.height = Math.round(c.height * k) + 'px';
  }
  window.addEventListener('resize', function () {
    if (document.getElementById('mapComposer') &&
        document.getElementById('mapComposer').classList.contains('open')) fitCanvas();
  });

  /**
   * Keep the fit correct as the pane's own size settles.
   *
   * The first preview finishes while the screen is still being laid out —
   * measured once, the sheet came out 60 px wide against a pane that had
   * not been given its height yet. A ResizeObserver re-fits whenever the
   * pane actually resolves, which covers that, the sidebar's steps opening
   * and closing, and the window being resized.
   */
  var fitObserver = null;
  function watchFit() {
    if (fitObserver || typeof ResizeObserver === 'undefined') return;
    var wrap = document.querySelector('#mapComposer .mc-prevwrap');
    if (!wrap) return;
    fitObserver = new ResizeObserver(function () { fitCanvas(); });
    fitObserver.observe(wrap);
  }

  function run() {
    var token = ++previewToken;
    busy(true, 'Composing…');
    KLComposer.compose({
      onProgress: function (t) { if (token === previewToken) busy(true, t); }
    }).then(function (r) {
      if (token !== previewToken) return;
      busy(false);
      var box = el('mcCanvasBox');
      box.innerHTML = '';
      box.appendChild(r.canvas);
      fitCanvas();
      redrawLegendStep();
      var meta = el('mcSheetMeta');
      var pg = KLComposer.PAGES[r.pageSize];
      meta.innerHTML = esc((pg ? pg.label : r.pageSize) + ' ' + r.orientation + ' · ' +
        Math.round(r.page.w) + '×' + Math.round(r.page.h) + ' mm · ' + r.template.name +
        ' · ' + r.items.length + ' layer(s) · ' + (r.extent.source || ''));
      var old = document.getElementById('mcWarn');
      if (old) old.remove();
      if (r.warnings && r.warnings.length) {
        var w = document.createElement('div');
        w.id = 'mcWarn';
        w.className = 'mc-warn';
        w.innerHTML = r.warnings.map(esc).join('<br>');
        el('mcSide').insertBefore(w, el('mcSide').firstChild);
      }
    }).catch(function (e) {
      if (token !== previewToken) return;
      busy(false);
      var box = el('mcCanvasBox');
      box.innerHTML = '<div class="mc-empty"><b>Nothing to draw.</b><br>' + esc(e.message || String(e)) +
        '</div>';
      var meta = el('mcSheetMeta');
      if (meta) meta.textContent = '';
    });
  }

  function exportRun(kind) {
    busy(true, 'Rendering at export quality…');
    var fn = (kind === 'pdf') ? KLComposer.Export.pdf : KLComposer.Export.png;
    fn(function (t) { busy(true, t); })
      .then(function () { busy(false); refresh(true); })
      .catch(function (e) {
        busy(false);
        alert('The map could not be exported.\n\n' + (e.message || e));
      });
  }

  /* ================================================================
     Open / close
     ================================================================ */
  window.openMapComposer = function () {
    build();
    ['dashboard', 'pciScreen', 'condScreen', 'regScreen', 'reportHub', 'nsvScreen'].forEach(function (id) {
      var e = document.getElementById(id);
      if (e) e.classList.remove('open');
    });
    var scr = el('mapComposer');
    scr.classList.add('open');
    var fp = document.getElementById('fpanes');
    if (fp) fp.classList.add('hidden');
    document.querySelectorAll('#iconrail .railbtn').forEach(function (b) {
      b.classList.toggle('active', b.dataset.pane === 'composer');
    });

    Promise.all([KLComposer.Templates.load(), KLComposer.Layers.loadDrone(), KLComposer.Filters.list()])
      .then(function (res) {
        savedFilters = res[2] || [];
        var s = KLComposer.state();
        /* First open: start on the template's own page size, and on the
           filter extent if one is active. */
        if (!s._booted) {
          var t = KLComposer.Templates.byId(s.templateId);
          KLComposer.set({
            pageSize: t.pageSize || 'A4',
            extentMode: KLComposer.Filters.isActive() ? 'filter' : 'network',
            _booted: true
          });
        }
        renderSide();
        watchFit();
        refresh(true);
      });
  };

  window.closeMapComposer = function () {
    var scr = el('mapComposer');
    if (scr) scr.classList.remove('open');
    /* Give the WebGL context back — a browser allows only a handful, and
       the viewer's own map needs one of them. */
    try { KLComposer.dispose(); } catch (e) { /* nothing to dispose */ }
    if (typeof railSyncToPanes === 'function') railSyncToPanes();
  };

  window.KLComposerUI = {
    refresh: refresh,
    exportPng: function () { exportRun('png'); },
    exportPdf: function () { exportRun('pdf'); },
    rerenderSide: renderSide
  };
})();
