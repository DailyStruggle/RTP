package io.github.dailystruggle.rtp.common.benchmark;

import java.util.ArrayDeque;

/**
 * Distance from every chunk to the nearest chunk of a desired biome, measured two ways.
 *
 * <p><b>Why this exists.</b> Every biome-constrained figure so far has scored a destination on
 * membership - "is this chunk in biome B". That is not what is being asked for. A player who wants
 * a jungle wants a jungle they can <i>reach</i>, and a chunk one chunk from the jungle edge is a
 * correct answer to that request. So the predicate is accessibility, not membership, and the radius
 * is not arbitrary:
 *
 * <ul>
 *   <li>the hard-loaded / ticking area around a player is 5x5 chunks - <b>radius 2</b>;
 *   <li>the minimum permitted view distance is 4 - <b>radius 4</b>, i.e. the biome is visible and
 *       walkable-to rather than merely loaded.
 * </ul>
 *
 * <p>So {@code d = 2..4} is grounded in vanilla behaviour, and 5 is a generous upper bound. That
 * puts it in a different class from the beach discount in {@link ProximityWeightedLoss}, which is a
 * stated preference; this one is citable, and reports should say so rather than presenting the two
 * as equally arbitrary.
 *
 * <p><b>Geodesic, not Euclidean.</b> Accessibility across water is not accessibility. A chunk three
 * chunks from a jungle with an ocean channel between them is not "in reach of jungle" in any sense
 * a player recognises. The primary metric therefore spreads only over usable ground. A Euclidean
 * spread is computed alongside - deliberately, because the claim that the distinction matters needs
 * evidence, and the gap between the two counts is that evidence. If they agree, the refinement was
 * unnecessary and the report should say so.
 *
 * <p><b>What this makes possible.</b> The accessible set is a <i>dilation</i> of the desired set:
 * morphologically a close, so its perimeter falls and its small holes fill. A run-length table over
 * a dilated set is therefore cheaper than one over the raw biome map, and - this is the point - the
 * error is bounded and justified at {@code d} chunks rather than being a precision loss with no
 * defence. It is the only coarsening lever in this line of work that does not trade accuracy for
 * bytes; it redefines accuracy to what was being asked for in the first place.
 *
 * <p>Four-connected, and taxicab by construction. Eight-connectivity would let accessibility hop a
 * diagonal touch between two land masses, which is not walkable.
 *
 * <p><b>Test scope only.</b> ADR-080 opt-in tier.
 */
public final class BiomeAccessibility {

  /** Unreachable, at any distance. */
  public static final int UNREACHABLE = Integer.MAX_VALUE;

  /** A per-chunk predicate over the measured window. */
  public interface ChunkPredicate {
    /**
     * @param cx chunk x
     * @param cz chunk z
     * @return the predicate's value for that chunk
     */
    boolean test(int cx, int cz);
  }

  private final int radius;
  private final int side;
  private final boolean[] usable;
  private final boolean[] desired;

  /** Chunk steps to the nearest desired chunk, walking only over usable ground. */
  private final int[] geodesic;

  /** Chunk steps to the nearest desired chunk, ignoring whether the path is walkable. */
  private final int[] euclidean;

  private final long usableChunks;
  private final long desiredChunks;

  /**
   * @param radiusChunks half-edge of the origin-centred square window, in chunks
   * @param usableAt true when the chunk is a viable destination on safety grounds
   * @param desiredAt true when the chunk is of the requested biome; only usable chunks are taken as
   *     sources, since an ocean chunk classified as the desired biome is not somewhere to walk from
   */
  public BiomeAccessibility(int radiusChunks, ChunkPredicate usableAt, ChunkPredicate desiredAt) {
    this.radius = radiusChunks;
    this.side = 2 * radiusChunks;
    int cells = side * side;
    this.usable = new boolean[cells];
    this.desired = new boolean[cells];

    long usableCount = 0L;
    long desiredCount = 0L;
    for (int z = 0; z < side; z++) {
      for (int x = 0; x < side; x++) {
        int index = x + z * side;
        boolean ok = usableAt.test(x - radius, z - radius);
        usable[index] = ok;
        if (ok) usableCount++;
        boolean want = ok && desiredAt.test(x - radius, z - radius);
        desired[index] = want;
        if (want) desiredCount++;
      }
    }
    this.usableChunks = usableCount;
    this.desiredChunks = desiredCount;

    this.geodesic = spread(true);
    this.euclidean = spread(false);
  }

  /**
   * Multi-source breadth-first spread out of the desired set.
   *
   * @param overUsableOnly when true the walk may only cross usable ground
   * @return distance per cell, {@link #UNREACHABLE} where the spread never arrived
   */
  private int[] spread(boolean overUsableOnly) {
    int[] distance = new int[usable.length];
    java.util.Arrays.fill(distance, UNREACHABLE);
    ArrayDeque<Integer> frontier = new ArrayDeque<>();
    for (int index = 0; index < desired.length; index++) {
      if (!desired[index]) continue;
      distance[index] = 0;
      frontier.add(index);
    }
    while (!frontier.isEmpty()) {
      int index = frontier.poll();
      int x = index % side;
      int z = index / side;
      int next = distance[index] + 1;
      if (x > 0) relax(frontier, distance, next, index - 1, overUsableOnly);
      if (x < side - 1) relax(frontier, distance, next, index + 1, overUsableOnly);
      if (z > 0) relax(frontier, distance, next, index - side, overUsableOnly);
      if (z < side - 1) relax(frontier, distance, next, index + side, overUsableOnly);
    }
    return distance;
  }

  private void relax(
      ArrayDeque<Integer> frontier, int[] distance, int next, int to, boolean overUsableOnly) {
    // The walk crosses only usable ground in the geodesic case. The destination cell itself is
    // allowed to be unusable in neither case, because an unusable chunk is not a destination and
    // spreading through it would let accessibility tunnel under an ocean.
    if (overUsableOnly && !usable[to]) return;
    if (distance[to] <= next) return;
    distance[to] = next;
    frontier.add(to);
  }

  // -------------------------------------------------------------------------------------
  // queries
  // -------------------------------------------------------------------------------------

  /**
   * @param cx chunk x
   * @param cz chunk z
   * @return true when the chunk itself is usable ground of the desired biome
   */
  public boolean isDesired(int cx, int cz) {
    int index = indexOf(cx, cz);
    return index >= 0 && desired[index];
  }

  /**
   * The accessibility predicate: usable ground within {@code d} walkable chunk steps of the biome.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @param d admitted distance in chunks; 0 collapses to membership
   * @return true when the chunk satisfies the request
   */
  public boolean isAccessible(int cx, int cz, int d) {
    int index = indexOf(cx, cz);
    if (index < 0 || !usable[index]) return false;
    return geodesic[index] <= d;
  }

  /**
   * Same predicate, but ignoring whether the path is walkable.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @param d admitted distance in chunks
   * @return true when the chunk is within {@code d} in a straight taxicab line
   */
  public boolean isAccessibleEuclidean(int cx, int cz, int d) {
    int index = indexOf(cx, cz);
    if (index < 0 || !usable[index]) return false;
    return euclidean[index] <= d;
  }

  /**
   * Value of a chunk under a linear falloff, which is the weighting form the accessibility argument
   * implies: full value inside the biome, decaying to zero at the cutoff.
   *
   * @param cx chunk x
   * @param cz chunk z
   * @param d cutoff in chunks; beyond it the chunk is worth nothing
   * @return value in {@code [0, 1]}
   */
  public double valueOf(int cx, int cz, int d) {
    int index = indexOf(cx, cz);
    if (index < 0 || !usable[index]) return 0.0d;
    int distance = geodesic[index];
    if (distance > d) return 0.0d;
    if (d <= 0) return distance == 0 ? 1.0d : 0.0d;
    return 1.0d - (distance / (double) (d + 1));
  }

  /**
   * @param d admitted distance in chunks
   * @return usable chunks satisfying the geodesic accessibility predicate
   */
  public long accessibleChunks(int d) {
    long n = 0L;
    for (int index = 0; index < usable.length; index++) {
      if (usable[index] && geodesic[index] <= d) n++;
    }
    return n;
  }

  /**
   * @param d admitted distance in chunks
   * @return usable chunks the Euclidean predicate admits and the geodesic one refuses, i.e. ground
   *     that is close to the biome but not walkably close
   */
  public long tunnelledChunks(int d) {
    long n = 0L;
    for (int index = 0; index < usable.length; index++) {
      if (usable[index] && euclidean[index] <= d && geodesic[index] > d) n++;
    }
    return n;
  }

  /** @return usable chunks in the window */
  public long usableChunks() {
    return usableChunks;
  }

  /** @return usable chunks of the desired biome */
  public long desiredChunks() {
    return desiredChunks;
  }

  private int indexOf(int cx, int cz) {
    int x = cx + radius;
    int z = cz + radius;
    if (x < 0 || z < 0 || x >= side || z >= side) return -1;
    return x + z * side;
  }
}
