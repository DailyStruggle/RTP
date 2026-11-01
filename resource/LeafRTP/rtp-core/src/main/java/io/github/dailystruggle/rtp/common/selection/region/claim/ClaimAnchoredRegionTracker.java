package io.github.dailystruggle.rtp.common.selection.region.claim;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionClaimTracker;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import java.util.concurrent.TimeUnit;

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
 *
 * @governedBy ADR-115 (Package DAG Disentanglement and Finite Decision-Preservation Hypergraph)
 */
public class ClaimAnchoredRegionTracker extends RegionClaimTracker {

  /**
   * Constructs a tracker with the given cooldown duration.
   *
   * @param cooldownDuration duration
   * @param unit time unit
   */
  public ClaimAnchoredRegionTracker(long cooldownDuration, TimeUnit unit) {
    super(cooldownDuration, unit, DEFAULT_MAX_ENTRIES);
  }

  /**
   * Constructs a tracker with the given cooldown duration and entry cap.
   *
   * @param cooldownDuration duration
   * @param unit time unit
   * @param maxEntries maximum tracked claims (&gt;= 1)
   */
  public ClaimAnchoredRegionTracker(long cooldownDuration, TimeUnit unit, int maxEntries) {
    super(cooldownDuration, unit, maxEntries);
  }

  /**
   * Constructs a tracker with a default cooldown of 300 seconds (5 minutes).
   */
  public ClaimAnchoredRegionTracker() {
    super(300, TimeUnit.SECONDS);
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
    return RegionClaimTracker.ingestMemoryFromRegion(sourceRegion, targetShape, boundary);
  }

  /**
   * Encapsulates an entire claim envelope in {@link MemoryShape} when a candidate coordinate fails
   * an external claim verifier (REQ-RTP-S-003).
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
    return RegionClaimTracker.encapsulateClaim(shape, worldName, x, z, failedVerifierClass);
  }
}
