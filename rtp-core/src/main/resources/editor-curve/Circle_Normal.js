(function () {
  // RTP editor curve helper (ADR-106): Circle_Normal (Archimedean spiral, ADR-001).
  // Circle's curve with two Java differences kept on purpose: no origin guard in xzToLocation
  // (atan(0/0) is NaN, which Java's long cast turns into 0) and locationToXZ scaling by the
  // unrounded radius. The normal distribution shapes sampling, not the mapping. No runtime state.
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

  function num(params, key, def) {
    var v = params ? params[key] : undefined;
    if (v === undefined || v === null) return def;
    if (typeof v === 'boolean') return v ? 1 : 0;
    v = Number(v);
    return jlong(v);
  }

  function isPosition(loc) {
    return typeof loc === 'number' && loc >= 0 && Math.floor(loc) === loc;
  }

  return {
    range: function (params) {
      var radius = num(params, 'radius', 256);
      var cr = num(params, 'centerRadius', 64);
      return jlong((radius - cr) * (radius + cr) * Math.PI);
    },

    locationToXZ: function (location, params) {
      if (!isPosition(location)) return null;
      var cr = num(params, 'centerRadius', 64);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);

      var preciseRadius = Math.sqrt(location / Math.PI + cr * cr);
      var R = jlong(preciseRadius);
      var startLoc = R * R - cr * cr;
      var currentLocation = jlong(location / Math.PI);
      var remainingLength = currentLocation - startLoc;
      var totalRingLength = 2 * R + 1;
      var proportion = remainingLength / totalRingLength;
      var rotation = (proportion + 0.000069) * 2.0 * Math.PI;

      var cosRes = Math.cos(rotation);
      var sinRes = Math.sin(rotation);
      return [jint(preciseRadius * cosRes + cx + 0.5), jint(preciseRadius * sinRes + cz + 0.5)];
    },

    xzToLocation: function (x, z, params) {
      var cr = num(params, 'centerRadius', 64);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      x = x - cx + 0; // + 0 folds a -0 input to +0, as Java's long arithmetic does
      z = z - cz + 0;

      var rotation = ((Math.atan(z / x) / (2 * Math.PI)) + 1) % 0.25;
      if ((z < 0) && (x < 0)) {
        rotation += 0.5;
      } else if (z < 0) {
        rotation += 0.75;
      } else if (x < 0) {
        rotation += 0.25;
      }

      var radius = jlong(Math.sqrt(x * x + z * z));
      var loc = jlong(((radius * radius - cr * cr) * Math.PI) + rotation * (2 * radius * Math.PI));
      return loc < 0 ? -1 : loc;
    }
  };
})()
