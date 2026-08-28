/*
 * kml-reader.js — KML and KMZ, read in the browser, handed on as GeoJSON.
 *
 * Same contract as shpjs: give it a File, get back a FeatureCollection, and the
 * importer that already knows how to load GeoJSON does the rest. Written here
 * rather than pulled from a CDN because the two things it needs are already in
 * the browser — DOMParser for the XML, DecompressionStream for the KMZ's zip —
 * and one fewer network dependency matters on the office connections this is
 * used from.
 *
 * Deliberately 2D: altitude is dropped. A layer's geom column is declared
 * geometry(TYPE,4326) with no Z, and PostGIS rejects a 3D geometry outright, so
 * a Google Earth export (which nearly always carries a third ordinate) would
 * fail on every row if we passed it through.
 */
(function () {
  'use strict';

  /** Does this file name look like something this module can read? */
  function handles(name) {
    var n = String(name || '').toLowerCase();
    return n.endsWith('.kml') || n.endsWith('.kmz');
  }

  /** File -> Promise<FeatureCollection>. Rejects with a message worth showing. */
  function read(file) {
    var name = (file && file.name || '').toLowerCase();
    if (name.endsWith('.kmz')) {
      return kmlFromKmz(file).then(parse);
    }
    return file.text().then(parse);
  }

  /* ------------------------------------------------------------------
     KMZ — a zip whose useful member is the .kml inside it
     ------------------------------------------------------------------ */

  /**
   * Pull the document out of a KMZ.
   *
   * A KMZ may also carry icons, overlay images and nested KML; the spec says
   * the first .kml in the archive is the one to open, which is what Google
   * Earth does, so that is what we take. Anything else in the archive is not
   * geometry and has nowhere to go in a layer.
   */
  function kmlFromKmz(file) {
    return file.arrayBuffer().then(function (buf) {
      var entries = zipEntries(new DataView(buf));
      var pick = null;
      for (var i = 0; i < entries.length; i++) {
        if (!/\.kml$/i.test(entries[i].name)) continue;
        // doc.kml is the conventional root; take it outright, else the first .kml.
        if (/(^|\/)doc\.kml$/i.test(entries[i].name)) { pick = entries[i]; break; }
        if (!pick) pick = entries[i];
      }
      if (!pick) throw new Error('That KMZ has no .kml inside it.');
      return inflate(buf, pick);
    });
  }

  /**
   * Zip central directory walk.
   *
   * Read from the end of file backwards: the central directory is the only
   * place an entry's compressed size is reliably recorded (a local header may
   * defer it to a trailing data descriptor), and a KMZ written by Google Earth
   * does exactly that.
   */
  function zipEntries(dv) {
    var len = dv.byteLength;
    var eocd = -1;
    // Comment field is at most 65535 bytes, so the signature is within the last 64k + 22.
    for (var i = len - 22; i >= Math.max(0, len - 65557); i--) {
      if (dv.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
    }
    if (eocd < 0) throw new Error('That file is not a readable KMZ archive.');

    var count = dv.getUint16(eocd + 10, true);
    var off = dv.getUint32(eocd + 16, true);
    var out = [];
    for (var n = 0; n < count && off + 46 <= len; n++) {
      if (dv.getUint32(off, true) !== 0x02014b50) break;
      var method = dv.getUint16(off + 10, true);
      var csize = dv.getUint32(off + 20, true);
      var nameLen = dv.getUint16(off + 28, true);
      var extraLen = dv.getUint16(off + 30, true);
      var cmtLen = dv.getUint16(off + 32, true);
      var local = dv.getUint32(off + 42, true);
      var nameBytes = new Uint8Array(dv.buffer, off + 46, nameLen);
      out.push({
        name: new TextDecoder().decode(nameBytes),
        method: method,
        csize: csize,
        local: local
      });
      off += 46 + nameLen + extraLen + cmtLen;
    }
    return out;
  }

  /** Entry bytes -> text. Stored entries are copied; deflated ones inflated. */
  function inflate(buf, entry) {
    var dv = new DataView(buf);
    if (dv.getUint32(entry.local, true) !== 0x04034b50) {
      throw new Error('That KMZ is damaged — its file table does not match its contents.');
    }
    var nameLen = dv.getUint16(entry.local + 26, true);
    var extraLen = dv.getUint16(entry.local + 28, true);
    var start = entry.local + 30 + nameLen + extraLen;
    var bytes = new Uint8Array(buf, start, entry.csize);

    if (entry.method === 0) return new TextDecoder().decode(bytes);
    if (entry.method !== 8) {
      throw new Error('That KMZ uses a compression method this browser cannot open — ' +
                      're-save it, or upload the .kml on its own.');
    }
    if (typeof DecompressionStream === 'undefined') {
      throw new Error('This browser cannot open a KMZ. Unzip it and upload the .kml inside.');
    }
    var stream = new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return new Response(stream).text();
  }

  /* ------------------------------------------------------------------
     KML -> GeoJSON
     ------------------------------------------------------------------ */

  function parse(text) {
    var doc = new DOMParser().parseFromString(text, 'application/xml');
    if (doc.getElementsByTagName('parsererror').length) {
      throw new Error('That KML is not valid XML.');
    }
    var marks = all(doc, 'Placemark');
    if (!marks.length) throw new Error('That KML has no placemarks in it.');

    var feats = [];
    for (var i = 0; i < marks.length; i++) {
      var g = geometryOf(marks[i]);
      if (!g) continue;   // a placemark may be a bare description or a ground overlay
      feats.push({ type: 'Feature', properties: propertiesOf(marks[i]), geometry: g });
    }
    if (!feats.length) throw new Error('None of that KML\'s placemarks carry geometry.');
    return { type: 'FeatureCollection', features: feats };
  }

  /**
   * Everything the placemark says about itself, flattened to one property bag.
   *
   * <name> and <description> are what most KML actually carries — a file drawn
   * by hand in Google Earth has nothing else — and ExtendedData is where an
   * export from a real dataset puts its columns, in either of the two shapes
   * the schema allows. The enclosing folder's name is kept too, because that is
   * how people group a KML and it is otherwise lost on import.
   */
  function propertiesOf(pm) {
    var p = {};
    var name = childText(pm, 'name');
    if (name) p.name = name;
    var desc = childText(pm, 'description');
    if (desc) p.description = desc;

    var data = all(pm, 'Data');
    for (var i = 0; i < data.length; i++) {
      var k = data[i].getAttribute('name');
      if (k) p[k] = childText(data[i], 'value');
    }
    var simple = all(pm, 'SimpleData');
    for (var j = 0; j < simple.length; j++) {
      var sk = simple[j].getAttribute('name');
      if (sk) p[sk] = text(simple[j]);
    }

    var folder = folderName(pm);
    if (folder && p.folder == null) p.folder = folder;
    return p;
  }

  function folderName(pm) {
    for (var el = pm.parentNode; el && el.nodeType === 1; el = el.parentNode) {
      if (el.localName === 'Folder') return childText(el, 'name');
    }
    return '';
  }

  /**
   * The placemark's geometry.
   *
   * A MultiGeometry of one kind becomes the matching Multi* type; mixed kinds
   * become a GeometryCollection, which PostGIS reads and the layer's own
   * ST_Multi/ST_GeometryN normalisation then handles the same way it handles a
   * mixed shapefile.
   */
  function geometryOf(pm) {
    var parts = [];
    collect(pm, parts);
    if (!parts.length) return null;
    if (parts.length === 1) return parts[0];

    var kinds = {};
    parts.forEach(function (g) { kinds[g.type] = true; });
    var only = Object.keys(kinds);
    if (only.length === 1) {
      return {
        type: 'Multi' + only[0],
        coordinates: parts.map(function (g) { return g.coordinates; })
      };
    }
    return { type: 'GeometryCollection', geometries: parts };
  }

  /** Walk the placemark's geometry children in document order. */
  function collect(el, out) {
    for (var i = 0; i < el.childNodes.length; i++) {
      var c = el.childNodes[i];
      if (c.nodeType !== 1) continue;
      switch (c.localName) {
        case 'Point': {
          var pt = coords(childText(c, 'coordinates'));
          if (pt.length) out.push({ type: 'Point', coordinates: pt[0] });
          break;
        }
        case 'LineString':
        case 'LinearRing': {
          var line = coords(childText(c, 'coordinates'));
          if (line.length > 1) out.push({ type: 'LineString', coordinates: line });
          break;
        }
        case 'Polygon': {
          var poly = rings(c);
          if (poly.length) out.push({ type: 'Polygon', coordinates: poly });
          break;
        }
        case 'Track': {   // gx:Track — a timestamped path; the path is the useful part
          var track = trackCoords(c);
          if (track.length > 1) out.push({ type: 'LineString', coordinates: track });
          break;
        }
        case 'MultiGeometry':
        case 'MultiTrack':
          collect(c, out);
          break;
        default:
          break;
      }
    }
  }

  /** Outer ring first, then holes — the order GeoJSON requires. */
  function rings(poly) {
    var out = [];
    var outer = all(poly, 'outerBoundaryIs');
    for (var i = 0; i < outer.length; i++) {
      var r = coords(deepText(outer[i], 'coordinates'));
      if (r.length > 2) out.push(close(r));
    }
    if (!out.length) return [];
    var inner = all(poly, 'innerBoundaryIs');
    for (var j = 0; j < inner.length; j++) {
      var h = coords(deepText(inner[j], 'coordinates'));
      if (h.length > 2) out.push(close(h));
    }
    return out;
  }

  /** A ring that does not repeat its first point is invalid GeoJSON. */
  function close(ring) {
    var a = ring[0], b = ring[ring.length - 1];
    if (a[0] !== b[0] || a[1] !== b[1]) ring.push([a[0], a[1]]);
    return ring;
  }

  /** gx:coord is space separated ("lon lat alt"), unlike every other KML tuple. */
  function trackCoords(track) {
    var out = [];
    for (var i = 0; i < track.childNodes.length; i++) {
      var c = track.childNodes[i];
      if (c.nodeType !== 1 || c.localName !== 'coord') continue;
      var n = text(c).split(/\s+/).filter(Boolean).map(Number);
      if (n.length >= 2 && isFinite(n[0]) && isFinite(n[1])) out.push([n[0], n[1]]);
    }
    return out;
  }

  /**
   * "lon,lat,alt lon,lat,alt …" -> [[lon,lat], …].
   *
   * Tuples are separated by any whitespace and KML in the wild is freely
   * wrapped and indented, so the split has to be on whitespace runs, not on
   * newlines alone. Altitude is discarded; see the module note.
   */
  function coords(s) {
    var out = [];
    var tuples = String(s || '').trim().split(/\s+/);
    for (var i = 0; i < tuples.length; i++) {
      if (!tuples[i]) continue;
      var n = tuples[i].split(',');
      var lng = parseFloat(n[0]), lat = parseFloat(n[1]);
      if (isFinite(lng) && isFinite(lat)) out.push([lng, lat]);
    }
    return out;
  }

  /* -------- small XML helpers (namespace-agnostic: KML files vary) -------- */

  function text(el) {
    return el ? String(el.textContent || '').trim() : '';
  }

  /** Direct child only — <name> inside a nested <Style> must not win. */
  function childText(el, tag) {
    for (var i = 0; i < el.childNodes.length; i++) {
      var c = el.childNodes[i];
      if (c.nodeType === 1 && c.localName === tag) return text(c);
    }
    return '';
  }

  function deepText(el, tag) {
    var found = all(el, tag);
    return found.length ? text(found[0]) : '';
  }

  /**
   * Descendants by local name, whatever namespace prefix the file uses.
   *
   * Most KML declares the KML namespace as the default and the plain tag name
   * matches, but an export that writes <kml:Placemark> is equally valid and
   * would find nothing at all with getElementsByTagName.
   */
  function all(root, tag) {
    return root.getElementsByTagNameNS('*', tag);
  }

  window.KLKml = { handles: handles, read: read };
})();
