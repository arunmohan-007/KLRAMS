/* ============================================================
   KLRAMS · kl-symbols.js
   The symbol set a point layer can be drawn with, in one place.

   Two screens need these and they must not drift apart: the map
   viewer (34-layer-style.js) turns a shape plus a colour into a
   MapLibre image, and the Style Management screen
   (style-management.js) draws the very same shape into the picker
   and the live preview. If the two held separate copies, choosing a
   symbol would eventually mean picking one glyph and getting
   another.

   Each shape is the INNER markup of a 32x32 viewBox, with no colours
   of its own — fill and stroke are applied by the caller, because a
   symbol's colour is a style decision and the shape is not. Where a
   glyph needs a detail knocked out of it (the white centre of a pin,
   the rungs of the bridge) that part sets its own colour explicitly
   and is deliberately left out of the recolouring.
   ============================================================ */
var KLSymbols = (function () {
  'use strict';

  var SHAPES = {
    circle:  '<circle cx="16" cy="16" r="11"/>',
    ring:    '<circle cx="16" cy="16" r="11" fill="none" stroke-width="5"/>',
    square:  '<rect x="6" y="6" width="20" height="20"/>',
    'square-rounded': '<rect x="6" y="6" width="20" height="20" rx="5"/>',
    diamond: '<path d="M16 4 28 16 16 28 4 16Z"/>',
    triangle:'<path d="M16 4 29 27H3Z"/>',
    pentagon:'<path d="M16 3l12.4 9-4.7 14.6H8.3L3.6 12Z"/>',
    hexagon: '<path d="M16 3l11.3 6.5v13L16 29 4.7 22.5v-13Z"/>',
    star:    '<path d="M16 3l3.9 8.6 9.4 1-7 6.3 1.9 9.2-8.2-4.7-8.2 4.7 1.9-9.2-7-6.3 9.4-1Z"/>',
    pin:     '<path d="M16 2c-5.5 0-10 4.4-10 9.9C6 19.4 16 30 16 30s10-10.6 10-18.1C26 6.4 21.5 2 16 2Z"/>'
           + '<circle cx="16" cy="12" r="3.8" fill="#ffffff" stroke="none"/>',
    cross:   '<path d="M7 10.5 10.5 7 16 12.5 21.5 7 25 10.5 19.5 16l5.5 5.5-3.5 3.5L16 19.5 10.5 25 7 21.5 12.5 16Z"/>',
    plus:    '<path d="M13 4h6v9h9v6h-9v9h-6v-9H4v-6h9Z"/>',
    target:  '<circle cx="16" cy="16" r="11" fill="none" stroke-width="3.5"/><circle cx="16" cy="16" r="4"/>',
    flag:    '<path d="M9 3v26h3V18l14-4.5L12 9V3Z"/>',
    bridge:  '<rect x="2" y="4" width="28" height="24" rx="6"/>'
           + '<path d="M7 23v-5c0-6 18-6 18 0v5M7 23h18M11 18.6V23M16 17.2V23M21 18.6V23" '
           + 'fill="none" stroke="#ffffff" stroke-width="2.2"/>',
    culvert: '<circle cx="16" cy="16" r="12"/>'
           + '<circle cx="16" cy="16" r="6" fill="none" stroke="#ffffff" stroke-width="2.4"/>'
           + '<path d="M12 16h8" stroke="#ffffff" stroke-width="2"/>',
    sign:    '<circle cx="16" cy="16" r="12"/>'
           + '<path d="M16 7.5 23.5 20h-15Z" fill="#ffffff" stroke="none"/>'
           + '<rect x="15" y="20" width="2" height="5" fill="#ffffff" stroke="none"/>',
    signal:  '<rect x="10" y="3" width="12" height="22" rx="5"/>'
           + '<circle cx="16" cy="9" r="2.4" fill="#ffffff" stroke="none"/>'
           + '<circle cx="16" cy="15" r="2.4" fill="#ffffff" stroke="none"/>'
           + '<circle cx="16" cy="21" r="2.4" fill="#ffffff" stroke="none"/>',
    light:   '<circle cx="16" cy="16" r="12"/>'
           + '<path d="M16 8v4M16 20v4M8 16h4M20 16h4M10.5 10.5l2.8 2.8M18.7 18.7l2.8 2.8'
           + 'M21.5 10.5l-2.8 2.8M13.3 18.7l-2.8 2.8" stroke="#ffffff" stroke-width="2"/>'
           + '<circle cx="16" cy="16" r="3" fill="#ffffff" stroke="none"/>',
    camera:  '<rect x="3" y="9" width="26" height="16" rx="4"/>'
           + '<circle cx="16" cy="17" r="5" fill="none" stroke="#ffffff" stroke-width="2.4"/>'
           + '<rect x="11" y="5" width="10" height="4" rx="1.6"/>',
    tree:    '<path d="M16 3 26 20H6Z"/><rect x="14" y="19" width="4" height="9"/>',
    water:   '<path d="M16 3c6 8 9 12 9 16a9 9 0 1 1-18 0c0-4 3-8 9-16Z"/>',
    drop:    '<path d="M16 4c5 7 8 10.5 8 14a8 8 0 1 1-16 0c0-3.5 3-7 8-14Z"/>',
    hazard:  '<path d="M16 3 30 28H2Z"/>'
           + '<path d="M16 12v7" stroke="#ffffff" stroke-width="2.8"/>'
           + '<circle cx="16" cy="23.5" r="1.7" fill="#ffffff" stroke="none"/>',
    wrench:  '<path d="M23 4a8 8 0 0 0-9.6 10.2L4 23.6 8.4 28l9.4-9.4A8 8 0 0 0 28 9l-4.6 4.6-4-4L24 5Z"/>',
    arrow:   '<path d="M16 3 27 27l-11-6-11 6Z"/>',
    chevron: '<path d="M16 5 28 17l-4 4-8-8-8 8-4-4Z"/>',
    bolt:    '<path d="M18 2 6 18h7l-3 12 12-16h-7Z"/>',
    pit:     '<circle cx="16" cy="16" r="12"/>'
           + '<path d="M8 19c2-2 4 2 8 0s4 2 8 0" fill="none" stroke="#ffffff" stroke-width="2.2"/>'
           + '<circle cx="11" cy="12" r="1.6" fill="#ffffff" stroke="none"/>'
           + '<circle cx="18" cy="11" r="1.3" fill="#ffffff" stroke="none"/>',
    core:    '<circle cx="16" cy="16" r="12"/>'
           + '<rect x="11.5" y="8" width="9" height="16" rx="2.2" fill="none" stroke="#ffffff" stroke-width="2.2"/>'
           + '<path d="M11.5 13.5h9M11.5 18.5h9" stroke="#ffffff" stroke-width="1.8"/>'
  };

  /** The shape names, in the order the picker should show them. */
  function names() {
    return Object.keys(SHAPES);
  }

  /** The inner markup of one shape, falling back to a plain dot. */
  function body(shape) {
    return SHAPES[shape] || SHAPES.circle;
  }

  /**
   * One symbol as a complete standalone SVG document.
   *
   * `size` is the rendered pixel box; the viewBox is always 32 so a
   * shape is authored once and scales everywhere it is used.
   */
  function svg(shape, fill, stroke, strokeWidth, size) {
    var px = size || 32;
    return '<svg xmlns="http://www.w3.org/2000/svg" width="' + px + '" height="' + px + '" '
         + 'viewBox="0 0 32 32">'
         + group(shape, fill, stroke, strokeWidth)
         + '</svg>';
  }

  /**
   * One symbol as an SVG fragment, for dropping inside an SVG that
   * already exists — the live preview draws its markers this way so
   * they share the preview's own coordinate space.
   */
  function group(shape, fill, stroke, strokeWidth) {
    return '<g fill="' + fill + '" stroke="' + (stroke || 'none') + '" '
         + 'stroke-width="' + (strokeWidth == null ? 0 : strokeWidth) + '" '
         + 'stroke-linejoin="round" stroke-linecap="round">' + body(shape) + '</g>';
  }

  return { SHAPES: SHAPES, names: names, body: body, svg: svg, group: group };
})();
