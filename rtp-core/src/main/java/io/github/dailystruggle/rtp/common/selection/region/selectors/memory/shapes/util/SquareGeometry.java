package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.util;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;

/**
 * Shared Chebyshev ring and square perimeter coordinate math used across square memory shapes.
 */
public final class SquareGeometry {

  private SquareGeometry() {}

  /**
   * Map a Chebyshev perimeter step onto (x, z) relative coordinates.
   *
   * @param radius Chebyshev radius of the ring
   * @param perimeterStep step along the perimeter
   * @param output mutable output coordinates container
   */
  public static void squareOct2Coords(long radius, double perimeterStep, MutableRTPCoords output) {
    int x, z;
    double shortStep = perimeterStep % radius;

    if (perimeterStep < radius * 4) {
      if (perimeterStep < radius * 2) {
        if (perimeterStep < radius) { // octant 1, from 0 to pi/4
          x = (int) radius;
          z = (int) shortStep;
        } else { // octant 2, from pi/4 to pi/2
          x = (int) (radius - shortStep);
          z = (int) radius;
        }
      } else {
        if (perimeterStep < radius * 3) { // octant 3
          x = (int) -shortStep;
          z = (int) radius;
        } else { // octant 4
          x = (int) -radius;
          z = (int) (radius - shortStep);
        }
      }
    } else {
      if (perimeterStep < radius * 6) {
        if (perimeterStep < radius * 5) { // octant 5
          x = (int) -radius;
          z = (int) -shortStep;
        } else { // octant 6
          x = (int) -(radius - shortStep);
          z = (int) -radius;
        }
      } else {
        if (perimeterStep < radius * 7) { // octant 7
          x = (int) shortStep;
          z = (int) -radius;
        } else { // octant 8
          x = (int) radius;
          z = (int) -(radius - shortStep);
        }
      }
    }
    output.setXZ(x, z);
  }

  /**
   * Perimeter index of a ring cell, counted counter-clockwise from (R, 0).
   *
   * @param x ring-relative x
   * @param z ring-relative z
   * @param radius Chebyshev radius of the ring
   * @return perimeter index in [0, 8 * radius)
   */
  public static long perimeterStep(long x, long z, long radius) {
    if (radius == 0L) return 0L;
    if (z == radius) return (radius * 2L) - x;
    if (x == -radius) return (radius * 4L) - z;
    if (z == -radius) return (radius * 6L) + x;
    return (z >= 0L) ? z : ((radius * 8L) + z);
  }

  /**
   * First index of ring r.
   *
   * @param r outer radius
   * @param cr inner radius
   * @return base index
   */
  public static long ringStart(long r, long cr) {
    long base = ((r * (r - 1L)) - (cr * (cr - 1L))) * 4L;
    return (cr == 0L) ? base + 1L : base;
  }

  /**
   * Ring holding location.
   *
   * @param location index
   * @param cr inner radius
   * @return ring radius
   */
  public static long ringOf(long location, long cr) {
    if (cr == 0L) {
      if (location <= 0L) return 0L;
      location -= 1L;
    }
    long target = (location / 4L) + (cr * (cr - 1L));
    long r = (long) ((1.0 + Math.sqrt(1.0 + (4.0 * (double) target))) / 2.0);
    if (r < 1L) r = 1L;
    while (r > 1L && (r * (r - 1L)) > target) r--;
    while (((r + 1L) * r) <= target) r++;
    return r;
  }
}
