package io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes;

import java.math.BigInteger;
import java.util.function.LongFunction;

/**
 * Curve check hash of ADR-106 section 4.4: the hosted editor draws a region from its shape's
 * JavaScript helper only when the helper reproduces this value.
 *
 * <p>With {@code R = getRange()}, samples are {@code s_i = floor(i * R / 256)} for
 * {@code i = 0..255} in exact integer arithmetic (every position when {@code R <= 256}, none when
 * {@code R <= 0}). The hash is 32-bit FNV-1a over {@code R} as little-endian int64, then each
 * sample's {@code x} and {@code z} from {@code locationToXZ(s_i)} as little-endian int32; an
 * off-curve ({@code null}) result hashes as {@code (0x80000000, 0x80000000)}.
 */
public final class CurveHash {

  /** Sample count. */
  public static final int SAMPLES = 256;

  private static final int FNV_OFFSET = 0x811C9DC5;
  private static final int FNV_PRIME = 0x01000193;
  private static final int OFF_CURVE = 0x80000000;

  private CurveHash() {}

  /**
   * @param shape the shape, read at its current settings and state
   * @return 8 lowercase hex digits
   */
  public static String of(MemoryShape<?> shape) {
    return of(shape.getRange(), shape::locationToXZ);
  }

  /**
   * Hash of an arbitrary curve; lets the parity tests hash a helper's output with the same code.
   *
   * @param range curve range {@code R}
   * @param locationToXZ position to {@code {x, z}}, or {@code null} when off the curve
   * @return 8 lowercase hex digits
   */
  public static String of(long range, LongFunction<int[]> locationToXZ) {
    int h = FNV_OFFSET;
    for (int b = 0; b < 8; b++) {
      h = mix(h, (int) (range >>> (8 * b)));
    }
    for (long s : samplePositions(range)) {
      int[] xz = locationToXZ.apply(s);
      boolean on = xz != null && xz.length >= 2;
      h = mixInt(h, on ? xz[0] : OFF_CURVE);
      h = mixInt(h, on ? xz[1] : OFF_CURVE);
    }
    return String.format("%08x", h);
  }

  /**
   * @param range curve range {@code R}
   * @return the sample positions, ascending; empty when {@code R <= 0}
   */
  public static long[] samplePositions(long range) {
    if (range <= 0L) return new long[0];
    if (range <= SAMPLES) {
      long[] all = new long[(int) range];
      for (int i = 0; i < all.length; i++) all[i] = i;
      return all;
    }
    long[] out = new long[SAMPLES];
    boolean exactInLong = range <= Long.MAX_VALUE / SAMPLES;
    BigInteger bigRange = exactInLong ? null : BigInteger.valueOf(range);
    for (int i = 0; i < SAMPLES; i++) {
      out[i] = exactInLong
          ? (i * range) / SAMPLES
          : bigRange.multiply(BigInteger.valueOf(i)).shiftRight(8).longValue();
    }
    return out;
  }

  private static int mixInt(int h, int v) {
    for (int b = 0; b < 4; b++) {
      h = mix(h, v >>> (8 * b));
    }
    return h;
  }

  private static int mix(int h, int octet) {
    return (h ^ (octet & 0xFF)) * FNV_PRIME;
  }
}
