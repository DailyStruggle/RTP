(function () {
  // RTP editor curve helper (ADR-106): Polygon (ADR-034).
  // The curve is the Square spiral over the vertices' bounding box; the outside-polygon mask is
  // hazard data, not geometry. When params.vertices ([[x, z], ...] or [{x, z}, ...]) is given,
  // radius / centre are derived exactly as Polygon.setVertices does. No runtime state.
  'use strict';

  function jlong(v) {
    if (v !== v) return 0;
    if (v >= 9223372036854775807) return 9223372036854775807;
    if (v <= -9223372036854775808) return -9223372036854775808;
    return Math.trunc(v);
  }

  function jint(v) {
    if (v !== v) return 0;
    if (v >= 2147483647) return 2147483647;
    if (v <= -2147483648) return -2147483648;
    return Math.trunc(v) | 0;
  }

  function idiv(a, b) {
    var q = Math.trunc(a / b);
    var r = a - q * b;
    var sb = b > 0 ? 1 : -1;
    var ab = Math.abs(b);
    if (a >= 0) {
      if (r < 0) q -= sb; else if (r >= ab) q += sb;
    } else {
      if (r > 0) q += sb; else if (-r >= ab) q -= sb;
    }
    return q;
  }

  function num(params, key, def) {
    var v = params ? params[key] : undefined;
    if (v === undefined || v === null) return def;
    if (typeof v === 'boolean') return v ? 1 : 0;
    v = Number(v);
    return jlong(v);
  }

  function vertexAt(v) {
    if (Array.isArray(v) && v.length >= 2) return [Number(v[0]) | 0, Number(v[1]) | 0];
    if (v && typeof v === 'object') return [Number(v.x) | 0, Number(v.z) | 0];
    return null;
  }

  // Polygon.setVertices: AABB half-extents and centre, centerRadius 0.
  function settings(params) {
    var verts = params ? params.vertices : undefined;
    if (Array.isArray(verts) && verts.length >= 3) {
      var lx = Infinity, lz = Infinity, hx = -Infinity, hz = -Infinity, ok = true;
      for (var i = 0; i < verts.length; i++) {
        var v = vertexAt(verts[i]);
        if (v === null) { ok = false; break; }
        lx = Math.min(lx, v[0]);
        hx = Math.max(hx, v[0]);
        lz = Math.min(lz, v[1]);
        hz = Math.max(hz, v[1]);
      }
      if (ok && lx !== hx && lz !== hz) {
        var halfX = idiv(hx - lx + 1, 2);
        var halfZ = idiv(hz - lz + 1, 2);
        return {
          radius: Math.max(halfX, halfZ) | 0,
          centerRadius: 0,
          centerX: idiv(lx + hx, 2) | 0,
          centerZ: idiv(lz + hz, 2) | 0
        };
      }
    }
    return params;
  }

  function ringStart(r, cr) {
    var base = ((r * (r - 1)) - (cr * (cr - 1))) * 4;
    return cr === 0 ? base + 1 : base;
  }

  function ringOf(location, cr) {
    if (cr === 0) {
      if (location <= 0) return 0;
      location -= 1;
    }
    var target = idiv(location, 4) + (cr * (cr - 1));
    var r = jlong((1.0 + Math.sqrt(1.0 + (4.0 * target))) / 2.0);
    if (r < 1) r = 1;
    while (r > 1 && (r * (r - 1)) > target) r--;
    while (((r + 1) * r) <= target) r++;
    return r;
  }

  function perimeterStep(x, z, radius) {
    if (radius === 0) return 0;
    if (z === radius) return (radius * 2) - x;
    if (x === -radius) return (radius * 4) - z;
    if (z === -radius) return (radius * 6) + x;
    return z >= 0 ? z : (radius * 8) + z;
  }

  function squareOct2Coords(radius, step) {
    var shortStep = step % radius;
    var octant = jint(step / radius);
    switch (octant) {
      case 0: return [radius | 0, jint(shortStep)];
      case 1: return [jint(radius - shortStep), radius | 0];
      case 2: return [jint(-shortStep), radius | 0];
      case 3: return [(-radius) | 0, jint(radius - shortStep)];
      case 4: return [(-radius) | 0, jint(-shortStep)];
      case 5: return [jint(-(radius - shortStep)), (-radius) | 0];
      case 6: return [jint(shortStep), (-radius) | 0];
      default: return [radius | 0, jint(-(radius - shortStep))];
    }
  }

  function isPosition(loc) {
    return typeof loc === 'number' && loc >= 0 && Math.floor(loc) === loc;
  }

  return {
    range: function (params) {
      params = settings(params);
      var radius = num(params, 'radius', 256);
      var cr = num(params, 'centerRadius', 64);
      if (radius <= cr) return 0;
      return ringStart(radius, cr);
    },

    locationToXZ: function (loc, params) {
      if (!isPosition(loc)) return null;
      params = settings(params);
      var cr = num(params, 'centerRadius', 64);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      var r = ringOf(loc, cr);
      if (r === 0) return [cx | 0, cz | 0];
      var xz = squareOct2Coords(r, loc - ringStart(r, cr));
      return [(xz[0] + (cx | 0)) | 0, (xz[1] + (cz | 0)) | 0];
    },

    xzToLocation: function (x, z, params) {
      params = settings(params);
      var cr = num(params, 'centerRadius', 64);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      x = x - cx;
      z = z - cz;
      var radius = Math.max(Math.abs(x), Math.abs(z));
      if (radius < cr) return -1;
      if (radius === 0) return 0;
      var loc = ringStart(radius, cr) + perimeterStep(x, z, radius);
      return loc < 0 ? -1 : loc;
    }
  };
})()
