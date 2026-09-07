package io.github.dailystruggle.rtp.common.benchmark;

import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;

/**
 * Counts the run table a shape's curve would hold, without building it.
 *
 * <p>Why this exists: a 100 km border is a 6250-chunk radius, i.e. about 156 million addressed
 * chunks, of which more than half are unusable. Marking those through {@code addBadLocation} and
 * rebuilding is not a measurement that fits in a test JVM - the mark set alone exceeds the heap
 * before a single run is coalesced - so the footprint at border scale cannot be obtained the way
 * the small-radius rows were.
 *
 * <p>What this does instead is walk the key space once in ascending order and record, for each
 * consecutive pair of bad keys, the <b>gap</b> between them and how many usable addressed chunks lie
 * inside that gap. That is sufficient to reconstruct the coalesced table at <i>every</i> setting of
 * {@code spatialResolution} at once, because the shipped merge test is exactly {@code nextKey <=
 * runStart + runLength + resolution}, and a run's {@code start + length} is one past its last bad
 * key - so two consecutive bad keys merge precisely when their gap is at most {@code resolution + 1}:
 *
 * <ul>
 *   <li>runs at setting {@code res} = one, plus the number of recorded gaps exceeding {@code res +
 *       1};
 *   <li>usable ground discarded at {@code res} = the usable chunks inside gaps of at most {@code res
 *       + 1}, since exactly those gaps are bridged and everything a run spans is refused.
 * </ul>
 *
 * <p>The {@code +1} is the reason neither curve is lossless at the finest setting: at {@code res =
 * 1} a gap of two merges, so a lone usable chunk between two bad ones is swallowed. Both curves pay
 * it identically.
 *
 * <p>Gaps are histogrammed by length, so the pass holds constant memory regardless of domain size,
 * and gaps longer than the largest swept setting need only be counted since no setting merges them.
 *
 * <p><b>This is a re-implementation of the shipped coalescer's arithmetic, not the shipped code.</b>
 * It is therefore only trustworthy to the extent it agrees with a real {@code flushAndRebuild}, and
 * the accompanying benchmark asserts that agreement at radii small enough for the shape itself to
 * be built. Any figure produced here at border scale rests on that equivalence check.
 */
final class KeySpaceRunEncoder {

  /**
   * Largest gap tracked individually, in key units.
   *
   * <p>Must be at least the largest {@code spatialResolution} the caller asks about: a longer gap is
   * never bridged, so only its count matters and its usable interior is never discarded.
   */
  static final int MAX_TRACKED_GAP = 65_536;

  /** Is this chunk usable ground? */
  interface Occupancy {
    boolean usable(int cx, int cz);
  }

  private final long[] gapCount = new long[MAX_TRACKED_GAP + 1];
  private final long[] gapUsable = new long[MAX_TRACKED_GAP + 1];

  private long longGaps;
  private long badKeys;
  private long usableCells;
  private long addressedCells;
  private long keysWalked;

  /**
   * Walks the whole key space of {@code shape} once.
   *
   * @param shape curve under test; only its {@code getRange} and {@code locationToXZ} are used
   * @param windowChunks comparison window, in chunks: a cell counts when both coordinates lie in
   *     {@code [-windowChunks, windowChunks)}. A key mapping outside it is treated as unaddressed -
   *     it still occupies key space, and therefore still contributes to gap length, but it is
   *     neither usable ground nor a mark. The window exists because the two curves do not address
   *     identical sets at an arbitrary radius: the hybrid's coarse grid rounds up to whole points,
   *     so its outer shell is ragged, and a footprint comparison across different domains is not a
   *     comparison of curves
   * @param occupancy ground truth
   */
  void encode(MemoryShape<?> shape, int windowChunks, Occupancy occupancy) {
    long range = shape.getRange();
    MutableRTPCoords coords = new MutableRTPCoords(0, 0);
    long previousBad = Long.MIN_VALUE;
    long pendingUsable = 0L;

    for (long key = 0L; key < range; key++) {
      shape.locationToXZ(key, coords);
      int cx = coords.x;
      int cz = coords.z;
      keysWalked++;
      if (cx < -windowChunks || cx >= windowChunks || cz < -windowChunks || cz >= windowChunks) {
        continue;
      }
      addressedCells++;
      if (occupancy.usable(cx, cz)) {
        usableCells++;
        pendingUsable++;
        continue;
      }
      badKeys++;
      if (previousBad != Long.MIN_VALUE) {
        long gap = key - previousBad;
        if (gap > MAX_TRACKED_GAP) {
          longGaps++;
        } else {
          int g = (int) gap;
          gapCount[g]++;
          gapUsable[g] += pendingUsable;
        }
      }
      previousBad = key;
      pendingUsable = 0L;
    }
  }

  /** Largest gap the shipped coalescer bridges at this setting. */
  private static int bridgedThrough(long resolution) {
    return (int) Math.min(resolution + 1L, MAX_TRACKED_GAP);
  }

  /** @return entries the coalesced table holds at this {@code spatialResolution} */
  long runsAt(long resolution) {
    if (badKeys == 0L) return 0L;
    long runs = 1L + longGaps;
    for (int g = bridgedThrough(resolution) + 1; g <= MAX_TRACKED_GAP; g++) {
      runs += gapCount[g];
    }
    return runs;
  }

  /** @return share of usable ground the coalesced table refuses at this {@code spatialResolution} */
  double lossAt(long resolution) {
    if (usableCells == 0L) return 0.0d;
    long lost = 0L;
    int through = bridgedThrough(resolution);
    for (int g = 1; g <= through; g++) {
      lost += gapUsable[g];
    }
    return lost / (double) usableCells;
  }

  long badKeys() {
    return badKeys;
  }

  long usableCells() {
    return usableCells;
  }

  long addressedCells() {
    return addressedCells;
  }

  long keysWalked() {
    return keysWalked;
  }

  /** @return usable share of the addressed domain, realised rather than expected */
  double usableShare() {
    return addressedCells == 0L ? 0.0d : usableCells / (double) addressedCells;
  }
}
