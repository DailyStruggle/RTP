(function () {
  // RTP editor curve helper (ADR-106): Square_Normal (square spiral, ADR-001).
  // Same curve as Square: the normal distribution shapes sampling, not the mapping.
  // Ring R >= centerRadius holds its 8R perimeter cells counter-clockwise from (R, 0).
  // Mirrors SquareGeometry operation for operation. No runtime state.
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
      var radius = num(params, 'radius', 256);
      var cr = num(params, 'centerRadius', 64);
      if (radius <= cr) return 0;
      return ringStart(radius, cr);
    },

    locationToXZ: function (loc, params) {
      if (!isPosition(loc)) return null;
      var cr = num(params, 'centerRadius', 64);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      var r = ringOf(loc, cr);
      if (r === 0) return [cx | 0, cz | 0];
      var xz = squareOct2Coords(r, loc - ringStart(r, cr));
      return [(xz[0] + (cx | 0)) | 0, (xz[1] + (cz | 0)) | 0];
    },

    xzToLocation: function (x, z, params) {
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
