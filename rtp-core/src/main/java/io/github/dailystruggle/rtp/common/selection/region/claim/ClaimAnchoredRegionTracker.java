package io.github.dailystruggle.rtp.common.selection.region.claim;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SubspaceShape;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Manages spatial anchor stability, recomputation cooldowns, and cross-region memory ingestion
 * for claim-anchored and faction-anchored RTP destinations.
 *
 * <p><b>Invariant (Center Stability):</b> To preserve $O(1)$ bijection mapping in spiral and
 * Hilbert space and avoid unnecessary cache clearing, an anchored center {@code (X0, Z0)} is
 * preserved as long as it remains within the claim boundary. When the centroid drifts due to
 * territory expansion or trimming, the center is not re-anchored unless:
 * <ol>
 *   <li>The existing pinned center is no longer within the claim boundary, OR</li>
 *   <li>The configured cooldown period has elapsed since the last recomputation.</li>
 * </ol>
 *
 * <p><b>Memory Ingestion:</b> When an anchored region is constructed or re-anchored, spatial
 * hazard memory (known-bad chunks) is ingested from overlapping or parent {@link Region} instances,
 * preventing cold starts and redundant scanning of known hazard terrain.
 */
public class ClaimAnchoredRegionTracker {

  /**
   * Pinned state record for a claim/faction anchor.
   */
  public static class ClaimAnchorState {
    private final String claimId;
    private final String world;
    private int pinnedCenterX;
    private int pinnedCenterZ;
    private long lastRecomputeEpochMillis;

    public ClaimAnchorState(String claimId, String world, int centerX, int centerZ, long nowMillis) {
      this.claimId = claimId;
      this.world = world;
      this.pinnedCenterX = centerX;
      this.pinnedCenterZ = centerZ;
      this.lastRecomputeEpochMillis = nowMillis;
    }

    public String getClaimId() {
      return claimId;
    }

    public String getWorld() {
      return world;
    }

    public int getPinnedCenterX() {
      return pinnedCenterX;
    }

    public int getPinnedCenterZ() {
      return pinnedCenterZ;
    }

    public long getLastRecomputeEpochMillis() {
      return lastRecomputeEpochMillis;
    }

    public void updateCenter(int newX, int newZ, long nowMillis) {
      this.pinnedCenterX = newX;
      this.pinnedCenterZ = newZ;
      this.lastRecomputeEpochMillis = nowMillis;
    }
  }

  private final ConcurrentHashMap<String, ClaimAnchorState> anchorCache = new ConcurrentHashMap<>();
  private final long cooldownMillis;

  /**
   * Constructs a tracker with the given cooldown duration.
   *
   * @param cooldownDuration duration
   * @param unit time unit
   */
  public ClaimAnchoredRegionTracker(long cooldownDuration, TimeUnit unit) {
    if (cooldownDuration < 0) {
      throw new IllegalArgumentException("cooldownDuration cannot be negative: " + cooldownDuration);
    }
    this.cooldownMillis = Objects.requireNonNull(unit, "unit cannot be null").toMillis(cooldownDuration);
  }

  /**
   * Constructs a tracker with a default cooldown of 300 seconds (5 minutes).
   */
  public ClaimAnchoredRegionTracker() {
    this(300, TimeUnit.SECONDS);
  }

  public long getCooldownMillis() {
    return cooldownMillis;
  }

  /**
   * Resolves the stable anchor coordinates {@code [x, z]} for the specified claim boundary,
   * honoring boundary containment and recomputation cooldowns.
   *
   * @param boundary the claim boundary (never {@code null})
   * @param nowMillis current epoch time in milliseconds
   * @return resolved {@code [x, z]} anchor coordinates
   */
  public int[] resolveAnchor(ClaimBoundary boundary, long nowMillis) {
    Objects.requireNonNull(boundary, "boundary cannot be null");
    String id = boundary.id();
    int[] currentCentroid = boundary.centroid();
    if (currentCentroid == null || currentCentroid.length < 2) {
      throw new IllegalArgumentException("ClaimBoundary centroid must provide [x, z]");
    }

    ClaimAnchorState state = anchorCache.get(id);
    if (state == null) {
      ClaimAnchorState newState = new ClaimAnchorState(
          id, boundary.world(), currentCentroid[0], currentCentroid[1], nowMillis);
      anchorCache.put(id, newState);
      return new int[] {newState.getPinnedCenterX(), newState.getPinnedCenterZ()};
    }

    synchronized (state) {
      boolean centerInside = boundary.contains(state.getPinnedCenterX(), state.getPinnedCenterZ());
      long elapsed = nowMillis - state.getLastRecomputeEpochMillis();

      // Case 1: Pinned center is outside the territory -> MUST update immediately regardless of cooldown.
      if (!centerInside) {
        state.updateCenter(currentCentroid[0], currentCentroid[1], nowMillis);
        return new int[] {state.getPinnedCenterX(), state.getPinnedCenterZ()};
      }

      // Case 2: Pinned center is still inside. Check if cooldown elapsed.
      if (elapsed >= cooldownMillis) {
        // Cooldown elapsed: update to new centroid if it moved
        if (state.getPinnedCenterX() != currentCentroid[0] || state.getPinnedCenterZ() != currentCentroid[1]) {
          state.updateCenter(currentCentroid[0], currentCentroid[1], nowMillis);
        }
      }

      // Preserve existing pinned center
      return new int[] {state.getPinnedCenterX(), state.getPinnedCenterZ()};
    }
  }

  /**
   * Ingests known-bad chunk memory from a parent or neighboring {@link Region} into a destination
   * {@link MemoryShape} within the claim's chunk bounding box.
   *
   * @param sourceRegion source region to copy hazard memory from (e.g. world master region)
   * @param targetShape destination shape to populate
   * @param boundary claim boundary bounding box
   * @return count of newly ingested bad chunk markers
   */
  public static int ingestMemoryFromRegion(Region sourceRegion, MemoryShape<?> targetShape, ClaimBoundary boundary) {
    if (sourceRegion == null || targetShape == null || boundary == null) {
      return 0;
    }
    if (!(sourceRegion.getShape() instanceof MemoryShape<?> sourceShape)) {
      return 0;
    }

    int minCX = boundary.minChunkX();
    int maxCX = boundary.maxChunkX();
    int minCZ = boundary.minChunkZ();
    int maxCZ = boundary.maxChunkZ();

    int ingestedCount = 0;
    for (int cx = minCX; cx <= maxCX; cx++) {
      for (int cz = minCZ; cz <= maxCZ; cz++) {
        // Only inspect chunks relevant to this claim
        if (!boundary.containsChunk(cx, cz)) {
          continue;
        }

        // Query source shape at chunk center column
        int bx = (cx << 4) + 8;
        int bz = (cz << 4) + 8;

        if (sourceShape.contains(bx, bz) && sourceShape.isKnownBad(bx, bz)) {
          int causeOrdinal = sourceShape.causeAt(bx, bz);
          LocationGenerator.FailTypes cause = LocationGenerator.FailTypes.misc;
          if (causeOrdinal >= 0 && causeOrdinal < LocationGenerator.FailTypes.values().length) {
            cause = LocationGenerator.FailTypes.values()[causeOrdinal];
          }

          if (targetShape.contains(bx, bz) && !targetShape.isKnownBad(bx, bz)) {
            long targetLoc = targetShape.xzToLocation(bx, bz);
            targetShape.addBadChunk(targetLoc, cause);
            ingestedCount++;
          }
        }
      }
    }

    if (ingestedCount > 0) {
      try {
        RTP.log(Level.INFO, "[ClaimAnchoredRegionTracker] Ingested " + ingestedCount
            + " hazard chunks from region " + sourceRegion.name + " into claim " + boundary.id());
      } catch (Exception ignored) {
      }
    }
    return ingestedCount;
  }

  /**
   * Creates a {@link SubspaceShape} anchored at the preserved claim center with memory inherited
   * from the parent region.
   *
   * @param boundary claim boundary
   * @param blockRadius radius in blocks for the subspace
   * @param parentRegion parent fallback/master region
   * @param nowMillis current epoch time in milliseconds
   * @return configured SubspaceShape
   */
  public SubspaceShape createClaimSubspace(
      ClaimBoundary boundary, int blockRadius, Region parentRegion, long nowMillis) {
    Objects.requireNonNull(parentRegion, "parentRegion cannot be null");
    int[] anchorXZ = resolveAnchor(boundary, nowMillis);

    // Anchor RTPLocation uses world from boundary and Y=64 (will be vertical-adjusted by validator)
    String worldName = parentRegion.getWorld() != null ? parentRegion.getWorld().name() : boundary.world();
    RTPLocation anchor = new RTPLocation(
        new io.github.dailystruggle.rtp.api.world.RTPCoords(worldName, anchorXZ[0], 64, anchorXZ[1]), 1);

    SubspaceShape subspace = new SubspaceShape(anchor, blockRadius, parentRegion);

    if (parentRegion.getShape() instanceof MemoryShape<?> memShape) {
      // Ingest known hazard memory from parent region directly into memory shape if applicable
      ingestMemoryFromRegion(parentRegion, memShape, boundary);
    }

    return subspace;
  }

  /**
   * Clear cache entry for a specific claim (e.g. upon faction disband).
   *
   * @param claimId identifier
   */
  public void invalidate(String claimId) {
    if (claimId != null) {
      anchorCache.remove(claimId);
    }
  }

  /**
   * Clear all cached anchors.
   */
  public void clear() {
    anchorCache.clear();
  }
}
