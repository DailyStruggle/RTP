package io.github.dailystruggle.rtp.common.selection.region.claim;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.SubspaceShape;
import java.util.Comparator;
import java.util.List;
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
    private volatile long lastAccessEpochMillis;

    public ClaimAnchorState(String claimId, String world, int centerX, int centerZ, long nowMillis) {
      this.claimId = claimId;
      this.world = world;
      this.pinnedCenterX = centerX;
      this.pinnedCenterZ = centerZ;
      this.lastRecomputeEpochMillis = nowMillis;
      this.lastAccessEpochMillis = nowMillis;
    }

    public long getLastAccessEpochMillis() {
      return lastAccessEpochMillis;
    }

    void touch(long nowMillis) {
      this.lastAccessEpochMillis = nowMillis;
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

  /**
   * Default cap on tracked claims. Evicting an entry only re-pins that claim at its current
   * centroid on next resolve, so the bound trades a possible anchor shift for bounded heap.
   */
  public static final int DEFAULT_MAX_ENTRIES = 4096;

  private static final long MIN_IDLE_TTL_MILLIS = TimeUnit.HOURS.toMillis(1);

  private final ConcurrentHashMap<String, ClaimAnchorState> anchorCache = new ConcurrentHashMap<>();
  private final long cooldownMillis;
  private final int maxEntries;
  private final long idleTtlMillis;

  /**
   * Constructs a tracker with the given cooldown duration.
   *
   * @param cooldownDuration duration
   * @param unit time unit
   */
  public ClaimAnchoredRegionTracker(long cooldownDuration, TimeUnit unit) {
    this(cooldownDuration, unit, DEFAULT_MAX_ENTRIES);
  }

  /**
   * Constructs a tracker with the given cooldown duration and entry cap.
   *
   * @param cooldownDuration duration
   * @param unit time unit
   * @param maxEntries maximum tracked claims (&gt;= 1)
   */
  public ClaimAnchoredRegionTracker(long cooldownDuration, TimeUnit unit, int maxEntries) {
    if (cooldownDuration < 0) {
      throw new IllegalArgumentException("cooldownDuration cannot be negative: " + cooldownDuration);
    }
    if (maxEntries < 1) {
      throw new IllegalArgumentException("maxEntries must be >= 1: " + maxEntries);
    }
    this.cooldownMillis = Objects.requireNonNull(unit, "unit cannot be null").toMillis(cooldownDuration);
    this.maxEntries = maxEntries;
    // Idle TTL never undercuts the cooldown, so an active claim is not re-pinned early by eviction.
    this.idleTtlMillis = Math.max(MIN_IDLE_TTL_MILLIS, 2 * cooldownMillis);
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
  @SuppressWarnings("PMD.PreferNonLockingExecution") // ADR-094: internal per-ClaimAnchorState synchronization for centroid updates
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
      ClaimAnchorState prior = anchorCache.putIfAbsent(id, newState);
      if (prior == null) {
        evictIfOverCapacity(nowMillis, id);
        return new int[] {newState.getPinnedCenterX(), newState.getPinnedCenterZ()};
      }
      state = prior;
    }
    state.touch(nowMillis);

    synchronized (state) {
      boolean centerInside = boundary.contains(state.getPinnedCenterX(), state.getPinnedCenterZ());
      long elapsed = nowMillis - state.getLastRecomputeEpochMillis();

      // Case 1: Pinned center is outside the territory -> MUST update immediately regardless of cooldown.
      if (!centerInside) {
        state.updateCenter(currentCentroid[0], currentCentroid[1], nowMillis);
        return new int[] {state.getPinnedCenterX(), state.getPinnedCenterZ()};
      }

      // Case 2: Pinned center is still inside. Check if cooldown elapsed.
      if (elapsed >= cooldownMillis
          && (state.getPinnedCenterX() != currentCentroid[0] || state.getPinnedCenterZ() != currentCentroid[1])) {
        state.updateCenter(currentCentroid[0], currentCentroid[1], nowMillis);
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

        // Memory shapes are keyed in chunk units (PregenTask maps a selection to (c << 4) + 7).
        if (sourceShape.contains(cx, cz) && sourceShape.isKnownBad(cx, cz)) {
          int causeOrdinal = sourceShape.causeAt(cx, cz);
          LocationGenerator.FailTypes cause = LocationGenerator.FailTypes.misc;
          if (causeOrdinal >= 0 && causeOrdinal < LocationGenerator.FailTypes.values().length) {
            cause = LocationGenerator.FailTypes.values()[causeOrdinal];
          }

          if (targetShape.contains(cx, cz) && !targetShape.isKnownBad(cx, cz)) {
            long targetLoc = targetShape.xzToLocation(cx, cz);
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

    SubspaceShape subspace = new SubspaceShape(anchor, blockRadius, 0, parentRegion, true);

    if (parentRegion.getShape() instanceof MemoryShape<?> memShape) {
      // Ingest known hazard memory from parent region directly into memory shape if applicable
      ingestMemoryFromRegion(parentRegion, memShape, boundary);
    }

    return subspace;
  }

  /**
   * Encapsulates an entire claim envelope in {@link MemoryShape} when a candidate coordinate fails
   * an external claim verifier (REQ-RTP-S-003).
   *
   * <p>Queries {@link io.github.dailystruggle.rtp.api.hooks.ClaimBoundaryRegistry#resolveAt(String, int, int)}
   * to resolve the boundary envelope, converts the bounding box into key-space indices in {@code shape},
   * and records them as {@link LocationGenerator.FailTypes#safetyExternal} hazard runs with the
   * configured cause-based TTL (ADR-079). Subsequent candidate selections in this region skip the
   * claim envelope in $O(\log N)$ time.
   *
   * @param shape the memory shape to mark
   * @param worldName world identifier
   * @param x hit X block coordinate
   * @param z hit Z block coordinate
   * @param failedVerifierClass optional verifier class that failed
   * @return count of newly marked bad locations/chunks
   */
  public static int encapsulateClaim(
      MemoryShape<?> shape,
      String worldName,
      int x,
      int z,
      Class<?> failedVerifierClass) {
    if (shape == null || worldName == null) {
      return 0;
    }

    long effectiveTtl = io.github.dailystruggle.rtp.common.selection.region.selectors.memory.TtlConfig
        .resolveTtlSeconds(LocationGenerator.FailTypes.safetyExternal, failedVerifierClass);

    ClaimBoundary boundary = null;
    try {
      if (io.github.dailystruggle.rtp.api.RTPAPI.hooks != null) {
        io.github.dailystruggle.rtp.api.hooks.ClaimBoundaryRegistry registry =
            io.github.dailystruggle.rtp.api.RTPAPI.hooks.claimBoundaries();
        if (registry != null) {
          boundary = registry.resolveAt(worldName, x, z).orElse(null);
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING, "[ClaimAnchoredRegionTracker] Failed to resolve claim boundary at (" + x + "," + z + ")", t);
    }

    if (boundary == null) {
      // Fallback: mark the hit's chunk; shape keys are chunk units, x/z are blocks.
      long loc = shape.xzToLocation(x >> 4, z >> 4);
      if (loc >= 0) {
        return shape.addBadChunk(loc, LocationGenerator.FailTypes.safetyExternal, effectiveTtl);
      }
      return 0;
    }

    int minCX = boundary.minChunkX();
    int maxCX = boundary.maxChunkX();
    int minCZ = boundary.minChunkZ();
    int maxCZ = boundary.maxChunkZ();

    int bMinX = boundary.minX();
    int bMaxX = boundary.maxX();
    int bMinZ = boundary.minZ();
    int bMaxZ = boundary.maxZ();

    int marked = 0;
    // Every spiral index decoding into a claimed chunk; bounded by the claim's chunk area.
    for (int cx = minCX; cx <= maxCX; cx++) {
      for (int cz = minCZ; cz <= maxCZ; cz++) {
        if (!boundary.containsChunk(cx, cz)) {
          continue;
        }

        // The representative index too: a rounding spiral can leave a chunk with no exact preimage,
        // and isKnownBad(cx, cz) reads the representative.
        long representative = shape.xzToLocation(cx, cz);
        if (representative >= 0 && representative < shape.getEffectiveRange()
            && !shape.isKnownBad(representative)) {
          shape.addBadLocation(representative, LocationGenerator.FailTypes.safetyExternal, effectiveTtl);
          marked++;
        }
        long[] preimages = shape.chunkToLocations(cx, cz);
        if (preimages != null && preimages.length > 0) {
          for (long p : preimages) {
            if (p >= 0 && p < shape.getEffectiveRange() && p != representative && !shape.isKnownBad(p)) {
              shape.addBadLocation(p, LocationGenerator.FailTypes.safetyExternal, effectiveTtl);
              marked++;
            }
          }
        }
      }
    }

    if (marked > 0) {
      shape.flushAndRebuildIfNeeded(shape.spatialResolution());
      RTP.log(Level.FINE, "[ClaimAnchoredRegionTracker] Encapsulated claim boundary '" + boundary.id()
          + "' [" + bMinX + "," + bMinZ + " -> " + bMaxX + "," + bMaxZ
          + "], marked " + marked + " locations with TTL " + effectiveTtl + "s");
    }

    return marked;
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

  /**
   * Returns the number of tracked claim anchors.
   *
   * @return tracked entry count
   */
  public int size() {
    return anchorCache.size();
  }

  /**
   * Drops idle entries, then the least recently resolved ones, once the cap is exceeded. Trims to
   * ~90% of the cap so steady churn does not rescan the map on every insert.
   */
  private void evictIfOverCapacity(long nowMillis, String keepId) {
    if (anchorCache.size() <= maxEntries) return;
    anchorCache.entrySet().removeIf(e -> !e.getKey().equals(keepId)
        && nowMillis - e.getValue().getLastAccessEpochMillis() > idleTtlMillis);
    int excess = anchorCache.size() - maxEntries;
    if (excess <= 0) return;
    List<String> victims = anchorCache.values().stream()
        .filter(s -> !s.getClaimId().equals(keepId))
        .sorted(Comparator.comparingLong(ClaimAnchorState::getLastAccessEpochMillis))
        .limit((long) excess + maxEntries / 10)
        .map(ClaimAnchorState::getClaimId)
        .toList();
    for (String victim : victims) {
      anchorCache.remove(victim);
    }
  }
}
