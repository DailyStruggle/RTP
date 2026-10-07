package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Adaptive claim boundary estimator for claim systems that only expose a point-in-claim predicate.
 *
 * <p>Probes outward radially along cardinal axes with doubling chunk increments
 * (16, 32, 64, 128, 256, 512 blocks) and binary-searches the claim edges to construct a
 * synthetic bounding box {@code [minX, minZ] -> [maxX, maxZ]}.
 */
public final class AdaptiveClaimProber {

  private static final int[] RADIAL_STEPS = {16, 32, 64, 128, 256, 512};
  private static final int MAX_PROBE_DISTANCE = 512;

  private AdaptiveClaimProber() {}

  /**
   * Probes outward from (originX, originZ) in the given world to determine the approximate bounding box
   * of the enclosing claim.
   *
   * @param worldName target world
   * @param originX   hit X coordinate (known to be in claim)
   * @param originZ   hit Z coordinate (known to be in claim)
   * @param claimTest predicate returning true if the given coords are claimed
   * @return resolved synthetic claim boundary, or empty if origin itself is not claimed
   */
  public static Optional<ClaimBoundary> probeBoundary(
      String worldName, int originX, int originZ, Predicate<RTPCoords> claimTest) {
    Objects.requireNonNull(worldName, "worldName cannot be null");
    Objects.requireNonNull(claimTest, "claimTest cannot be null");

    RTPCoords origin = new RTPCoords(worldName, originX, 64, originZ);
    if (!claimTest.test(origin)) {
      return Optional.empty();
    }

    int minX = findBoundary(originX, -1, delta -> claimTest.test(new RTPCoords(worldName, originX + delta, 64, originZ)));
    int maxX = findBoundary(originX, 1, delta -> claimTest.test(new RTPCoords(worldName, originX + delta, 64, originZ)));
    int minZ = findBoundary(originZ, -1, delta -> claimTest.test(new RTPCoords(worldName, originX, 64, originZ + delta)));
    int maxZ = findBoundary(originZ, 1, delta -> claimTest.test(new RTPCoords(worldName, originX, 64, originZ + delta)));

    // Bounded chunk flood-fill to discover irregular, non-convex, and L-shaped claims
    int startCx = originX >> 4;
    int startCz = originZ >> 4;
    int maxChunkRadius = MAX_PROBE_DISTANCE >> 4; // 32 chunks = 512 blocks
    int maxFloodChunks = 1024;

    ArrayDeque<Long> queue = new ArrayDeque<>();
    Set<Long> visitedChunks = new HashSet<>();
    Set<Long> claimedChunks = new HashSet<>();

    long startKey = (((long) startCx) << 32) | (startCz & 0xFFFFFFFFL);
    visitedChunks.add(startKey);
    claimedChunks.add(startKey);
    queue.add(startKey);

    // Seed chunks intersecting the primary axis-probed box
    for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
      for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
        long k = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
        if (visitedChunks.add(k)) {
          claimedChunks.add(k);
          queue.add(k);
        }
      }
    }

    int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    while (!queue.isEmpty() && claimedChunks.size() < maxFloodChunks) {
      long curKey = queue.poll();
      int curCx = (int) (curKey >> 32);
      int curCz = (int) curKey;

      for (int[] dir : directions) {
        int ncx = curCx + dir[0];
        int ncz = curCz + dir[1];

        if (Math.abs(ncx - startCx) > maxChunkRadius || Math.abs(ncz - startCz) > maxChunkRadius) {
          continue;
        }

        long nKey = (((long) ncx) << 32) | (ncz & 0xFFFFFFFFL);
        if (!visitedChunks.add(nKey)) {
          continue;
        }

        int cMinX = ncx << 4;
        int cMaxX = cMinX + 15;
        int cMinZ = ncz << 4;
        int cMaxZ = cMinZ + 15;

        // Sample center, and if outside, sample corners
        boolean isClaimed = claimTest.test(new RTPCoords(worldName, cMinX + 8, 64, cMinZ + 8))
            || claimTest.test(new RTPCoords(worldName, cMinX, 64, cMinZ))
            || claimTest.test(new RTPCoords(worldName, cMaxX, 64, cMaxZ))
            || claimTest.test(new RTPCoords(worldName, cMinX, 64, cMaxZ))
            || claimTest.test(new RTPCoords(worldName, cMaxX, 64, cMinZ));

        if (isClaimed) {
          claimedChunks.add(nKey);
          queue.add(nKey);
          if (claimedChunks.size() >= maxFloodChunks) {
            break;
          }
        }
      }
    }

    int minChunkX = Integer.MAX_VALUE;
    int minChunkZ = Integer.MAX_VALUE;
    int maxChunkX = Integer.MIN_VALUE;
    int maxChunkZ = Integer.MIN_VALUE;

    for (long k : claimedChunks) {
      int cx = (int) (k >> 32);
      int cz = (int) k;
      if (cx < minChunkX) minChunkX = cx;
      if (cx > maxChunkX) maxChunkX = cx;
      if (cz < minChunkZ) minChunkZ = cz;
      if (cz > maxChunkZ) maxChunkZ = cz;
    }

    int boundMinX = (minChunkX < (minX >> 4)) ? (minChunkX << 4) : minX;
    int boundMaxX = (maxChunkX > (maxX >> 4)) ? ((maxChunkX << 4) + 15) : maxX;
    int boundMinZ = (minChunkZ < (minZ >> 4)) ? (minChunkZ << 4) : minZ;
    int boundMaxZ = (maxChunkZ > (maxZ >> 4)) ? ((maxChunkZ << 4) + 15) : maxZ;

    String id = "probed_" + worldName + "_" + boundMinX + "_" + boundMinZ + "_" + boundMaxX + "_" + boundMaxZ;
    return Optional.of(new SyntheticClaimBoundary(id, worldName, boundMinX, boundMinZ, boundMaxX, boundMaxZ, claimedChunks));
  }

  private static int findBoundary(int origin, int direction, Predicate<Integer> insideClaim) {
    int lastInsideDelta = 0;
    int firstOutsideDelta = -1;

    for (int step : RADIAL_STEPS) {
      int testDelta = direction * step;
      if (insideClaim.test(testDelta)) {
        lastInsideDelta = testDelta;
      } else {
        firstOutsideDelta = testDelta;
        break;
      }
    }

    if (firstOutsideDelta == -1) {
      // Reached max probe distance and still inside claim
      return origin + (direction * MAX_PROBE_DISTANCE);
    }

    // Binary search between lastInsideDelta and firstOutsideDelta
    int low = Math.min(lastInsideDelta, firstOutsideDelta);
    int high = Math.max(lastInsideDelta, firstOutsideDelta);

    if (direction > 0) {
      // Searching for max positive delta inside claim: low is inside, high is outside
      while (low + 1 < high) {
        int mid = low + (high - low) / 2;
        if (insideClaim.test(mid)) {
          low = mid;
        } else {
          high = mid;
        }
      }
      return origin + low;
    } else {
      // Searching for min negative delta inside claim: high is inside, low is outside
      while (low + 1 < high) {
        int mid = low + (high - low) / 2;
        if (insideClaim.test(mid)) {
          high = mid;
        } else {
          low = mid;
        }
      }
      return origin + high;
    }
  }

  public static class SyntheticClaimBoundary implements ClaimBoundary {
    private final String id;
    private final String world;
    private final int minX;
    private final int minZ;
    private final int maxX;
    private final int maxZ;
    private final int minChunkX;
    private final int minChunkZ;
    private final int maxChunkX;
    private final int maxChunkZ;
    private final int[] centroid;
    private final Set<Long> claimedChunks;

    public SyntheticClaimBoundary(String id, String world, int minX, int minZ, int maxX, int maxZ) {
      this(id, world, minX, minZ, maxX, maxZ, null);
    }

    public SyntheticClaimBoundary(
        String id, String world, int minX, int minZ, int maxX, int maxZ, Set<Long> claimedChunks) {
      this.id = id;
      this.world = world;
      this.minX = minX;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxZ = maxZ;
      this.minChunkX = minX >> 4;
      this.minChunkZ = minZ >> 4;
      this.maxChunkX = maxX >> 4;
      this.maxChunkZ = maxZ >> 4;
      this.centroid = new int[] {minX + (maxX - minX) / 2, minZ + (maxZ - minZ) / 2};
      this.claimedChunks = (claimedChunks != null) ? claimedChunks : Set.of();
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public String world() {
      return world;
    }

    @Override
    public boolean contains(int x, int z) {
      if (x < minX || x > maxX || z < minZ || z > maxZ) {
        return false;
      }
      if (claimedChunks.isEmpty()) {
        return true;
      }
      long key = (((long) (x >> 4)) << 32) | ((z >> 4) & 0xFFFFFFFFL);
      return claimedChunks.contains(key);
    }

    @Override
    public boolean containsChunk(int cx, int cz) {
      if (cx < minChunkX || cx > maxChunkX || cz < minChunkZ || cz > maxChunkZ) {
        return false;
      }
      if (claimedChunks.isEmpty()) {
        return true;
      }
      long key = (((long) cx) << 32) | (cz & 0xFFFFFFFFL);
      return claimedChunks.contains(key);
    }

    @Override
    public int[] centroid() {
      return centroid;
    }

    @Override
    public int minChunkX() {
      return minChunkX;
    }

    @Override
    public int minChunkZ() {
      return minChunkZ;
    }

    @Override
    public int maxChunkX() {
      return maxChunkX;
    }

    @Override
    public int maxChunkZ() {
      return maxChunkZ;
    }

    @Override
    public int minX() {
      return minX;
    }

    @Override
    public int minZ() {
      return minZ;
    }

    @Override
    public int maxX() {
      return maxX;
    }

    @Override
    public int maxZ() {
      return maxZ;
    }
  }
}
