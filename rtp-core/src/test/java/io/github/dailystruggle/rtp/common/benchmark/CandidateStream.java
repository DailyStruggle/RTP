package io.github.dailystruggle.rtp.common.benchmark;

import java.util.Random;

/**
 * Shared candidate source and terrain oracle for the allocation benchmark (ADR-080).
 *
 * <p><b>Common random numbers.</b> Every model draws from the same pre-generated tape at the same
 * cursor positions, so two models that make the same number of draws see literally the same
 * coordinates. Differences between models are then attributable to the operations they chose to
 * perform, never to luck of the draw. {@link #reset()} rewinds the cursor, so replays are identical.
 *
 * <p><b>Allocation-free by construction.</b> The tape and the terrain bitmap are built once, before
 * any allocation delta is sampled, and every accessor afterwards returns primitives. If this class
 * allocated per draw, its garbage would land inside every model's measurement equally - which sounds
 * harmless but silently compresses the ratios, which are the published figure.
 *
 * <p><b>Terrain.</b> Unsafe ground is spatially correlated in a real world (oceans, lava lakes,
 * badlands), so it is generated as coarse blobs rather than per-chunk coin flips. The unsafe
 * fraction is a parameter anchored to the 35-65% range measured on real worlds; correlation length
 * is a parameter too, because a model that only wins under one correlation length has not won.
 */
final class CandidateStream {

  /** Random values on [0,1). Consumed two at a time per candidate. */
  private final double[] tape;

  /** Coarse unsafe-terrain field, one bit per blob cell, indexed by (blobX, blobZ). */
  private final long[] unsafeBits;

  private final int blobsPerSide;
  private final int chunksPerBlob;
  private final int chunkRadius;

  private int cursor;

  CandidateStream(int tapeSize, int chunkRadius, int chunksPerBlob, double unsafeFraction, long seed) {
    this.chunkRadius = chunkRadius;
    this.chunksPerBlob = chunksPerBlob;
    this.blobsPerSide = Math.max(1, (2 * chunkRadius) / chunksPerBlob + 1);

    Random rng = new Random(seed);
    this.tape = new double[Math.max(2, tapeSize)];
    for (int i = 0; i < tape.length; i++) tape[i] = rng.nextDouble();

    int cells = blobsPerSide * blobsPerSide;
    this.unsafeBits = new long[(cells + 63) / 64];
    for (int i = 0; i < cells; i++) {
      if (rng.nextDouble() < unsafeFraction) unsafeBits[i >>> 6] |= 1L << (i & 63);
    }
  }

  void reset() {
    cursor = 0;
  }

  private double draw() {
    double v = tape[cursor];
    cursor = (cursor + 1) % tape.length;
    return v;
  }

  /** @return packed chunk coordinates for a uniformly-sampled candidate */
  long nextUniform() {
    int cx = (int) (draw() * (2 * chunkRadius)) - chunkRadius;
    int cz = (int) (draw() * (2 * chunkRadius)) - chunkRadius;
    return pack(cx, cz);
  }

  /**
   * @param previous packed coordinates of the last pick
   * @param spread chunks of jitter either side
   * @return packed coordinates near {@code previous}, the clustered-selection strategy class
   */
  long nextNear(long previous, int spread) {
    int cx = unpackX(previous) + (int) (draw() * (2 * spread)) - spread;
    int cz = unpackZ(previous) + (int) (draw() * (2 * spread)) - spread;
    return pack(clamp(cx), clamp(cz));
  }

  /** @return true when the chunk is safe ground, i.e. a teleport there would succeed */
  boolean safeGround(long packed) {
    int bx = Math.floorDiv(unpackX(packed) + chunkRadius, chunksPerBlob);
    int bz = Math.floorDiv(unpackZ(packed) + chunkRadius, chunksPerBlob);
    if (bx < 0 || bz < 0 || bx >= blobsPerSide || bz >= blobsPerSide) return true;
    int idx = bz * blobsPerSide + bx;
    return (unsafeBits[idx >>> 6] & (1L << (idx & 63))) == 0L;
  }

  private int clamp(int c) {
    return Math.max(-chunkRadius, Math.min(chunkRadius - 1, c));
  }

  static long pack(int cx, int cz) {
    return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
  }

  static int unpackX(long packed) {
    return (int) (packed >> 32);
  }

  static int unpackZ(long packed) {
    return (int) packed;
  }
}
