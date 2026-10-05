(function () {
  // RTP editor curve helper (ADR-106): Rectangle.
  // Row-major walk of a width x height grid centred on (centerX, centerZ), rotated by
  // rotation degrees with Shape.rotate's int truncation. xzToLocation mirrors the Java method as
  // is, including its missing half-extent offset. No runtime state.
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

  // Shape.rotate: int inputs, int-truncated outputs.
  function rotate(x, z, degrees) {
    var angle = degrees * DEGREES_TO_RADIANS;
    var s = Math.sin(angle);
    var c = Math.cos(angle);
    return [jint(x * c - z * s), jint(x * s + z * c)];
  }

  function isPosition(loc) {
    return typeof loc === 'number' && loc >= 0 && Math.floor(loc) === loc;
  }

  return {
    range: function (params) {
      return num(params, 'width', 256) * num(params, 'height', 256);
    },

    locationToXZ: function (location, params) {
      if (!isPosition(location)) return null;
      var degrees = num(params, 'rotation', 0);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      var width = num(params, 'width', 256);
      var height = num(params, 'height', 256);

      if (width <= 0 || height <= 0) return [cx | 0, cz | 0];

      var x = (location % width) | 0;
      var z = idiv(location, width) | 0;
      x = (x - (idiv(width, 2) | 0)) | 0;
      z = (z - (idiv(height, 2) | 0)) | 0;
      var rotated = rotate(x, z, degrees);
      return [(rotated[0] + (cx | 0)) | 0, (rotated[1] + (cz | 0)) | 0];
    },

    xzToLocation: function (x, z, params) {
      var degrees = num(params, 'rotation', 0);
      var cx = num(params, 'centerX', 0);
      var cz = num(params, 'centerZ', 0);
      var width = num(params, 'width', 256);

      var rotated = rotate((x - cx) | 0, (z - cz) | 0, -degrees);
      var loc = rotated[1] * width + rotated[0];
      return loc < 0 ? -1 : loc;
    }
  };
})()
