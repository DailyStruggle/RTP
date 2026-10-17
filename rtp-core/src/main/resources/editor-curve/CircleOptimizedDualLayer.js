(function () {
  // RTP editor curve helper (ADR-106): CircleOptimizedDualLayer.
  // Chebyshev macro-ring spiral of P x P Hilbert tiles (ADR-085) clipped to the annulus
  // centerRadius <= d <= rEff. Mirrors the Java mapping operation for operation; the shared
  // Hilbert walk and orientation table are inlined so the helper stays self-contained.
  // state: {p, rEff}; missing p is derived from radius, missing rEff is radius.
  'use strict';

  function jlong(v) {
    if (v !== v) return 0;
    if (v >= 9223372036854775807) return 9223372036854775807;
    if (v <= -9223372036854775808) return -9223372036854775808;
    return Math.trunc(v);
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

  function floorDiv(a, b) {
    var q = idiv(a, b);
    if (a - q * b !== 0 && ((a < 0) !== (b < 0))) q -= 1;
    return q;
  }

  function num(params, key, def) {
    var v = params ? params[key] : undefined;
    if (v === undefined || v === null) return def;
    if (typeof v === 'boolean') return v ? 1 : 0;
    v = Number(v);
    return jlong(v);
  }

  function derivePFromRadius(r) {
    if (r <= 0) return 1;
    var maxP = idiv(2 * r, 64);
    if (maxP < 1) return 1;
    var candidates = [1, 2, 4, 8, 16, 32, 64, 128];
    var chosen = 1;
    for (var i = 0; i < candidates.length; i++) {
      if (candidates[i] <= maxP && candidates[i] <= 32) chosen = candidates[i];
    }
    return chosen;
  }

  function pointEdge(params, state) {
    var p = state ? state.p : undefined;
    if (typeof p === 'number' && p >= 1 && p <= 32768 && Math.floor(p) === p && (p & (p - 1)) === 0) {
      return p;
    }
    return derivePFromRadius(num(params, 'radius', 256));
  }

  function effectiveRadius(params, state) {
    var rEff = state ? state.rEff : undefined;
    if (typeof rEff === 'number' && rEff >= 0 && Math.floor(rEff) === rEff) return rEff;
    return num(params, 'radius', 256);
  }

  function innerRing(cr, p) {
    return cr <= 0 ? 0 : jlong(Math.floor((cr / Math.sqrt(2.0)) / p));
  }

  function orientationFor(px, pz) {
    var kX = px >= 0 ? px + 1 : -px;
    var kZ = pz >= 0 ? pz + 1 : -pz;
    var K = Math.max(kX, kZ);
    if (px === K - 1 && pz > -K) return 1;
    if (pz === K - 1 && px < K - 1) return px === -K ? 5 : 4;
    if (px === -K && pz < K - 1) return 5;
    return 0;
  }

  function applyOrientation(x, y, n, o) {
    var max = n - 1;
    switch (o % 8) {
      case 1: return [y, x];
      case 2: return [max - y, x];
      case 3: return [max - x, y];
      case 4: return [max - x, max - y];
      case 5: return [max - y, max - x];
      case 6: return [y, max - x];
      case 7: return [x, max - y];
      default: return [x, y];
    }
  }

  function unapplyOrientation(x, y, n, o) {
    var max = n - 1;
    switch (o % 8) {
      case 1: return [y, x];
      case 2: return [y, max - x];
      case 3: return [max - x, y];
      case 4: return [max - x, max - y];
      case 5: return [max - y, max - x];
      case 6: return [max - y, x];
      case 7: return [x, max - y];
      default: return [x, y];
    }
  }

  function xyToHilbert(x, y, n, o) {
    var X = x, Y = y, d = 0, rx, ry, t;
    if (o !== 0) {
      t = applyOrientation(x, y, n, o);
      X = t[0];
      Y = t[1];
    }
    for (var s = (n / 2) | 0; s > 0; s = (s / 2) | 0) {
      rx = (X & s) > 0 ? 1 : 0;
      ry = (Y & s) > 0 ? 1 : 0;
      d += s * s * ((3 * rx) ^ ry);
      if (ry === 0) {
        if (rx === 1) {
          X = (2 * s - 1 - X) | 0;
          Y = (s - 1 - Y) | 0;
        }
        t = X;
        X = Y;
        Y = t;
      }
    }
    return d;
  }

  function hilbertToXY(d, n, o) {
    var t = d, x = 0, y = 0, rx, ry, tmp;
    for (var s = 1; s < n; s *= 2) {
      rx = 1 & ((t / 2) | 0);
      ry = 1 & (t ^ rx);
      if (ry === 0) {
        if (rx === 1) {
          x = s - 1 - x;
          y = s - 1 - y;
        }
        tmp = x;
        x = y;
        y = tmp;
      }
      x = x + s * rx;
      y = y + s * ry;
      t = (t / 4) | 0;
    }
    return o !== 0 ? unapplyOrientation(x, y, n, o) : [x, y];
  }

  function isPosition(loc) {
    return typeof loc === 'number' && loc >= 0 && Math.floor(loc) === loc;
  }

  return {
    range: function (params, state) {
      var p = pointEdge(params, state);
      var area = p * p;
      var r = num(params, 'radius', 256);
      var cr = num(params, 'centerRadius', 64);
      if (r <= cr) return 0;
      var kOuter = Math.max(1, idiv(r + p - 1, p));
      var kInner = innerRing(cr, p);
      if (kOuter <= kInner) return 0;
      return (4 * kOuter * kOuter - 4 * kInner * kInner) * area;
    },

    locationToXZ: function (loc, params, state) {
      if (!isPosition(loc)) return null;
      var p = pointEdge(params, state);
      var area = p * p;
      var cr = num(params, 'centerRadius', 64);
      var cenX = num(params, 'centerX', 0);
      var cenZ = num(params, 'centerZ', 0);

      var kInner = innerRing(cr, p);
      var macroLoc = idiv(loc, area);
      var h = loc % area;
      var fullMacroIdx = macroLoc + 4 * kInner * kInner;

      var target = idiv(fullMacroIdx, 4);
      var K = jlong(Math.floor(Math.sqrt(target))) + 1;
      while ((K - 1) * (K - 1) > target) K--;
      while (K * K <= target) K++;

      var ringBase = 4 * (K - 1) * (K - 1);
      var step = fullMacroIdx - ringBase;
      var sideLen = 2 * K - 1;
      var side = idiv(step, sideLen);
      var sideStep = step % sideLen;

      var px, pz;
      if (side === 0) {
        px = K - 1;
        pz = -(K - 1) + sideStep;
      } else if (side === 1) {
        pz = K - 1;
        px = (K - 2) - sideStep;
      } else if (side === 2) {
        px = -K;
        pz = (K - 2) - sideStep;
      } else {
        pz = -K;
        px = (-K + 1) + sideStep;
      }

      var local = hilbertToXY(h | 0, p, orientationFor(px, pz));
      return [(cenX + px * p + local[0]) | 0, (cenZ + pz * p + local[1]) | 0];
    },

    xzToLocation: function (cx, cz, params, state) {
      var p = pointEdge(params, state);
      var area = p * p;
      var cr = num(params, 'centerRadius', 64);
      var rEff = effectiveRadius(params, state);
      var cenX = num(params, 'centerX', 0);
      var cenZ = num(params, 'centerZ', 0);

      var relX = cx - cenX;
      var relZ = cz - cenZ;
      var distSq = relX * relX + relZ * relZ;
      if (distSq < cr * cr) return -1;
      if (distSq > rEff * rEff) return -1;

      var px = floorDiv(relX, p);
      var pz = floorDiv(relZ, p);
      var kX = px >= 0 ? px + 1 : -px;
      var kZ = pz >= 0 ? pz + 1 : -pz;
      var K = Math.max(kX, kZ);

      var kInner = innerRing(cr, p);
      var kOuterEff = Math.max(1, idiv(rEff + p - 1, p));
      if (K <= kInner) return -1;
      if (K > kOuterEff) return -1;

      var side, sideStep;
      if (px === K - 1 && pz > -K) {
        side = 0;
        sideStep = pz + (K - 1);
      } else if (pz === K - 1 && px < K - 1) {
        side = 1;
        sideStep = (K - 2) - px;
      } else if (px === -K && pz < K - 1) {
        side = 2;
        sideStep = (K - 2) - pz;
      } else {
        side = 3;
        sideStep = px - (-K + 1);
      }

      var fullMacroIdx = 4 * (K - 1) * (K - 1) + side * (2 * K - 1) + sideStep;
      var macroLoc = fullMacroIdx - 4 * kInner * kInner;
      if (macroLoc < 0) return -1;

      var lx = (relX - px * p) | 0;
      var lz = (relZ - pz * p) | 0;
      return macroLoc * area + xyToHilbert(lx, lz, p, orientationFor(px, pz));
    }
  };
})()
