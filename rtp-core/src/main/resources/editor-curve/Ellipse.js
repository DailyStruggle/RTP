(function () {
  // RTP editor curve helper (ADR-106): Ellipse (Archimedean spiral, ADR-001).
  // The 1D range is the spiral of the bounding circle (max of the two radii); the ellipse itself
  // bounds selection, not the mapping. rotation (degrees) is applied with Shape.rotate's int
  // truncation. Mirrors the Java arithmetic and casts operation for operation. No runtime state.
  'use strict';

  var DEGREES_TO_RADIANS = 0.017453292519943295; // java.lang.Math.toRadians factor

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

  // Shape.rotate(int[], long): int inputs, int-truncated outputs.
  function rotate(x, z, degrees) {
    var angle = degrees * DEGREES_TO_RADIANS;
    var s = Math.sin(angle);
    var c = Math.cos(angle);
    return [jint(x * c - z * s), jint(x * s + z * c)];
  }

  function outerRadius(params) {
    return Math.max(num(params, 'radius', 256), num(params, 'radius2', 256));
  }

  function innerRadius(params) {
    return Math.max(num(params, 'centerRadius', 64), num(params, 'centerRadius2', 64));
  }

  function isPosition(loc) {
    return typeof loc === 'number' && loc >= 0 && Math.floor(loc) === loc;
  }

  return {
    range: function (params) {
      var radius = outerRadius(params);
      var cr = innerRadius(params);
      return jlong((radius - cr) * (radius + cr) * Math.PI);
    },

    locationToXZ: function (location, params) {
      if (!isPosition(location)) return null;
      var cr = innerRadius(params);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      var degrees = num(params, 'rotation', 0);

      var preciseRadius = Math.sqrt(location / Math.PI + cr * cr);
      var R = jlong(preciseRadius);
      var startLoc = R * R - cr * cr;
      var currentLocation = jlong(location / Math.PI);
      var remainingLength = currentLocation - startLoc;
      var totalRingLength = 2 * R + 1;
      var proportion = remainingLength / totalRingLength;
      var rotation = (proportion + 0.000069) * 2.0 * Math.PI;

      var px = jint(R * Math.cos(rotation) + 0.5);
      var pz = jint(R * Math.sin(rotation) + 0.5);
      if (degrees !== 0) {
        var rotated = rotate(px, pz, degrees);
        px = rotated[0];
        pz = rotated[1];
      }
      return [(px + (cx | 0)) | 0, (pz + (cz | 0)) | 0];
    },

    xzToLocation: function (x, z, params) {
      var cr = innerRadius(params);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      var degrees = num(params, 'rotation', 0);

      x = x - cx + 0; // + 0 folds a -0 input to +0, as Java's long arithmetic does
      z = z - cz + 0;
      if (degrees !== 0) {
        var rotated = rotate(x | 0, z | 0, -degrees);
        x = rotated[0] + 0;
        z = rotated[1] + 0;
      }

      var rotation = ((Math.atan(z / x) / (2 * Math.PI)) + 1) % 0.25;
      if ((z < 0) && (x < 0)) {
        rotation += 0.5;
      } else if (z < 0) {
        rotation += 0.75;
      } else if (x < 0) {
        rotation += 0.25;
      }

      var radius = jlong(Math.sqrt(x * x + z * z));
      var loc = jlong((radius * radius - cr * cr) * Math.PI + rotation * (2 * radius * Math.PI));
      return loc < 0 ? -1 : loc;
    }
  };
})()
