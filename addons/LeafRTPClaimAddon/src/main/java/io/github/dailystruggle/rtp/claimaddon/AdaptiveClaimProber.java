package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import java.util.Objects;
import java.util.Optional;
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

    String id = "probed_" + worldName + "_" + minX + "_" + minZ + "_" + maxX + "_" + maxZ;
    return Optional.of(new SyntheticClaimBoundary(id, worldName, minX, minZ, maxX, maxZ));
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
    private final int[] centroid;

    public SyntheticClaimBoundary(String id, String world, int minX, int minZ, int maxX, int maxZ) {
      this.id = id;
      this.world = world;
      this.minX = minX;
      this.minZ = minZ;
      this.maxX = maxX;
      this.maxZ = maxZ;
      this.centroid = new int[] {minX + (maxX - minX) / 2, minZ + (maxZ - minZ) / 2};
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
      return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    @Override
    public int[] centroid() {
      return centroid;
    }

    @Override
    public int minChunkX() {
      return minX >> 4;
    }

    @Override
    public int minChunkZ() {
      return minZ >> 4;
    }

    @Override
    public int maxChunkX() {
      return maxX >> 4;
    }

    @Override
    public int maxChunkZ() {
      return maxZ >> 4;
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
