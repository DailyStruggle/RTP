package io.github.dailystruggle.rtp.common.benchmark;

import java.util.ArrayDeque;

/**
 * Prices discarded usable ground by <b>what it is next to</b> rather than by area.
 *
 * <p>Every accuracy figure in the ADR-083/084/085 line of work counted discarded usable chunks and
 * divided. That treats all usable ground as interchangeable, which it is not. A chunk on the shore
 * of a 20 000-chunk ocean is a beach: it is usable, but it is the least valuable usable ground in
 * the domain - flanked by water on one side, and one of a large supply of equivalent shore chunks.
 * A chunk in the middle of a continent that a coalescer refuses because a pond three cells away in
 * <i>key order</i> swallowed it is a full loss: it was prime ground and there is nothing equivalent
 * about it.
 *
 * <p>Traffic-light framing, which is where this came from: ocean is a red light, land is a green
 * light, and a beach is a yellow light. The distinction that matters is <b>which body</b> the
 * yellow light is next to. Land beside a massive water body is genuinely marginal. Land beside a
 * one-chunk pool or a river is not marginal at all - the pool is an obstacle, not a frontier, and
 * the ground around it is ordinary inland ground.
 *
 * <p>So the weight is a function of two measured quantities per usable chunk:
 *
 * <ul>
 *   <li><b>distance</b> to the nearest unusable chunk, in chunk steps (1 = touching), and
 *   <li><b>the size of the connected unusable body</b> that distance reaches.
 * </ul>
 *
 * <p>Only ground that is both <i>close to</i> and <i>beside a large</i> body is discounted. Ground
 * near a small body keeps full weight however close it is, and ground far from anything keeps full
 * weight by construction. The discount and the two thresholds are policy, so they are inputs
 * ({@link Weighting}) rather than constants, and the benchmark sweeps them - a conclusion that only
 * holds at one discount is a conclusion about the discount.
 *
 * <p><b>What this does not claim.</b> That a beach is worth exactly a quarter of an inland chunk is
 * not a measured fact, and no measurement here can make it one; it is a stated preference about
 * destination quality. What is measured is the <i>composition</i> of each curve's losses - how much
 * of what a curve discards is shore and how much is interior - and that comparison stands whatever
 * discount is chosen, because both curves are priced by the same function on the same terrain.
 *
 * <p>Bodies are four-connected. Eight-connectivity would join two oceans across a diagonal touch
 * and inflate body sizes, and the question here is whether a body is big enough to make its shore
 * marginal, which a diagonal thread does not achieve.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public final class ProximityWeightedLoss {

  /** Pass/fail over a chunk window. */
  public interface ChunkOccupancy {
    /**
     * @param cx chunk x
     * @param cz chunk z
     * @return true when the chunk is a viable starting position
     */
    boolean usable(int cx, int cz);
  }

  /** Which light a chunk shows, for drawn output and for reporting loss composition. */
  public enum Light {
    /** Usable, and not on the shore of a large body. Full-value ground. */
    GREEN,
    /** Usable, but within {@code beachDepth} of a body of at least {@code largeBody} chunks. */
    YELLOW,
    /** Unusable, part of a body of at least {@code largeBody} chunks. */
    RED,
    /** Unusable, part of a small body - a pond, a pool, a river thread, a single dot. */
    AMBER_SMALL
  }

  /**
   * The policy in the weighting, stated as an input.
   *
   * @param beachDepthChunks how many chunk steps from a large body still counts as shore
   * @param largeBodyChunks connected unusable chunks a body needs before its shore is discounted
   * @param beachValue value of a shore chunk relative to an interior chunk, in {@code [0, 1]}
   */
  public record Weighting(int beachDepthChunks, long largeBodyChunks, double beachValue) {

    /** @return a weighting that prices all usable ground alike, i.e. the legacy flat metric */
    public static Weighting flat() {
      return new Weighting(0, Long.MAX_VALUE, 1.0d);
    }
  }

  /** Loss under one weighting, with the composition that produced it. */
  public record Loss(
      double flat,
      double weighted,
      long discardedChunks,
      long discardedShoreChunks,
      long discardedInteriorChunks) {

    /**
     * @return share of discarded chunks that were shore rather than interior; 0 when nothing was
     *     discarded
     */
    public double shoreShareOfLoss() {
      return discardedChunks == 0L ? 0.0d : discardedShoreChunks / (double) discardedChunks;
    }
  }

  private final int radius;
  private final int side;
  private final boolean[] usable;

  /** Chunk steps to the nearest unusable chunk; 0 for unusable chunks themselves. */
  private final int[] distance;

  /** Size of the connected unusable body the nearest unusable chunk belongs to. */
  private final int[] nearestBodySize;

  private final long usableChunks;
  private final long bodyCount;
  private final long largestBody;

  /**
   * @param radiusChunks half-edge of the origin-centred square window, in chunks
   * @param occupancy pass/fail source
   */
  public ProximityWeightedLoss(int radiusChunks, ChunkOccupancy occupancy) {
    this.radius = radiusChunks;
    this.side = 2 * radiusChunks;
    int cells = side * side;
    this.usable = new boolean[cells];
    this.distance = new int[cells];
    this.nearestBodySize = new int[cells];

    long usableCount = 0L;
    for (int z = 0; z < side; z++) {
      for (int x = 0; x < side; x++) {
        boolean ok = occupancy.usable(x - radius, z - radius);
        usable[x + z * side] = ok;
        if (ok) usableCount++;
      }
    }
    this.usableChunks = usableCount;

    int[] bodyOf = new int[cells];
    int[] bodySizes = label(bodyOf);
    this.bodyCount = bodySizes.length;
    long biggest = 0L;
    for (int size : bodySizes) biggest = Math.max(biggest, size);
    this.largestBody = biggest;

    spread(bodyOf, bodySizes);
  }

  /**
   * Four-connected labelling of the unusable set.
   *
   * <p>Iterative, with an explicit stack. A recursive flood fill over an ocean spanning a
   * 512-chunk window is a quarter of a million frames deep and overflows, which is not a
   * hypothetical - it is why the clustering statistics elsewhere in this package are iterative too.
   *
   * @param bodyOf destination, one body index per cell; {@code -1} for usable cells
   * @return size of each body, indexed by body number
   */
  private int[] label(int[] bodyOf) {
    java.util.Arrays.fill(bodyOf, -1);
    int[] sizes = new int[64];
    int bodies = 0;
    ArrayDeque<Integer> stack = new ArrayDeque<>();
    for (int start = 0; start < bodyOf.length; start++) {
      if (usable[start] || bodyOf[start] >= 0) continue;
      if (bodies == sizes.length) sizes = java.util.Arrays.copyOf(sizes, sizes.length * 2);
      int body = bodies++;
      int size = 0;
      bodyOf[start] = body;
      stack.push(start);
      while (!stack.isEmpty()) {
        int index = stack.pop();
        size++;
        int x = index % side;
        int z = index / side;
        if (x > 0) push(stack, bodyOf, body, index - 1);
        if (x < side - 1) push(stack, bodyOf, body, index + 1);
        if (z > 0) push(stack, bodyOf, body, index - side);
        if (z < side - 1) push(stack, bodyOf, body, index + side);
      }
      sizes[body] = size;
    }
    return java.util.Arrays.copyOf(sizes, bodies);
  }

  private void push(ArrayDeque<Integer> stack, int[] bodyOf, int body, int index) {
    if (usable[index] || bodyOf[index] >= 0) return;
    bodyOf[index] = body;
    stack.push(index);
  }

  /**
   * Multi-source breadth-first spread out of the unusable set, carrying the source body's size.
   *
   * <p>Breadth-first rather than a two-pass chamfer transform because the value being propagated is
   * not the distance alone - it is <i>which</i> body was reached first, and a chamfer pass that
   * approximates Euclidean distance can hand a cell the size of a body that is not its nearest.
   * The metric is therefore taxicab, stated rather than approximated. Ties are broken by the larger
   * body, so a chunk squeezed between an ocean and a pool is priced as shore; the alternative would
   * let a single pool cell beside an ocean recover full weight for ground that is plainly beach.
   *
   * @param bodyOf body index per cell, from {@link #label}
   * @param bodySizes size per body index
   */
  private void spread(int[] bodyOf, int[] bodySizes) {
    ArrayDeque<Integer> frontier = new ArrayDeque<>();
    for (int index = 0; index < usable.length; index++) {
      if (usable[index]) {
        distance[index] = Integer.MAX_VALUE;
        nearestBodySize[index] = 0;
      } else {
        distance[index] = 0;
        nearestBodySize[index] = bodySizes[bodyOf[index]];
        frontier.add(index);
      }
    }
    while (!frontier.isEmpty()) {
      int index = frontier.poll();
      int x = index % side;
      int z = index / side;
      if (x > 0) relax(frontier, index, index - 1);
      if (x < side - 1) relax(frontier, index, index + 1);
      if (z > 0) relax(frontier, index, index - side);
      if (z < side - 1) relax(frontier, index, index + side);
    }
  }

  private void relax(ArrayDeque<Integer> frontier, int from, int to) {
    if (!usable[to]) return;
    int candidate = distance[from] + 1;
    if (candidate < distance[to]) {
      distance[to] = candidate;
      nearestBodySize[to] = nearestBodySize[from];
      frontier.add(to);
    } else if (candidate == distance[to] && nearestBodySize[from] > nearestBodySize[to]) {
      // Same distance, bigger body wins - see the tie-break note above. No re-queue: the distance
      // did not change, so nothing downstream of this cell can shorten.
      nearestBodySize[to] = nearestBodySize[from];
    }
  }

  // -------------------------------------------------------------------------------------
  // queries
  // -------------------------------------------------------------------------------------

  /**
   * @param cx chunk x
   * @param cz chunk z
   * @return value of this chunk relative to an interior chunk, in {@code [0, 1]}; 0 when the chunk
   *     is unusable or outside the window
   */
  public double valueOf(Weighting weighting, int cx, int cz) {
    int index = indexOf(cx, cz);
    if (index < 0 || !usable[index]) return 0.0d;
    return isShore(weighting, index) ? weighting.beachValue() : 1.0d;
  }

  /**
   * @param cx chunk x
   * @param cz chunk z
   * @return which light the chunk shows under this weighting
   */
  public Light lightAt(Weighting weighting, int cx, int cz) {
    int index = indexOf(cx, cz);
    if (index < 0) return Light.AMBER_SMALL;
    if (!usable[index]) {
      return nearestBodySize[index] >= weighting.largeBodyChunks() ? Light.RED : Light.AMBER_SMALL;
    }
    return isShore(weighting, index) ? Light.YELLOW : Light.GREEN;
  }

  private boolean isShore(Weighting weighting, int index) {
    return distance[index] <= weighting.beachDepthChunks()
        && nearestBodySize[index] >= weighting.largeBodyChunks();
  }

  /**
   * Flat and weighted loss for one discard predicate, in one pass.
   *
   * <p>Both are reported from the same sweep so they cannot disagree about which chunks were
   * discarded, and the shore/interior split is carried along because it is the part that does not
   * depend on the chosen discount.
   *
   * @param weighting the pricing policy
   * @param discarded true when the table no longer offers this chunk
   * @return the loss, with its composition
   */
  public Loss lossOf(Weighting weighting, ChunkOccupancy discarded) {
    double valueTotal = 0.0d;
    double valueLost = 0.0d;
    long lost = 0L;
    long lostShore = 0L;
    for (int z = 0; z < side; z++) {
      for (int x = 0; x < side; x++) {
        int index = x + z * side;
        if (!usable[index]) continue;
        boolean shore = isShore(weighting, index);
        double value = shore ? weighting.beachValue() : 1.0d;
        valueTotal += value;
        if (!discarded.usable(x - radius, z - radius)) continue;
        lost++;
        if (shore) lostShore++;
        valueLost += value;
      }
    }
    double flat = usableChunks == 0L ? 0.0d : lost / (double) usableChunks;
    double weighted = valueTotal <= 0.0d ? 0.0d : valueLost / valueTotal;
    return new Loss(flat, weighted, lost, lostShore, lost - lostShore);
  }

  /** @return usable chunks in the window */
  public long usableChunks() {
    return usableChunks;
  }

  /** @return connected unusable bodies in the window */
  public long bodyCount() {
    return bodyCount;
  }

  /** @return chunks in the largest connected unusable body */
  public long largestBody() {
    return largestBody;
  }

  /**
   * @param weighting the pricing policy
   * @return share of usable ground this weighting prices as shore
   */
  public double shoreShareOfUsable(Weighting weighting) {
    if (usableChunks == 0L) return 0.0d;
    long shore = 0L;
    for (int index = 0; index < usable.length; index++) {
      if (usable[index] && isShore(weighting, index)) shore++;
    }
    return shore / (double) usableChunks;
  }

  private int indexOf(int cx, int cz) {
    int x = cx + radius;
    int z = cz + radius;
    if (x < 0 || z < 0 || x >= side || z >= side) return -1;
    return x + z * side;
  }
}
