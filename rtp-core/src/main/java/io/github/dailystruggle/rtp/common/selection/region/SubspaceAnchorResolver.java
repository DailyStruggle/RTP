package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.group.AnchorSource;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Resolves {@link AnchorSource} strategies into world-bound {@link RTPCoords} or {@link GenerationResult}
 * for subspace placements.
 */
public final class SubspaceAnchorResolver {

  private SubspaceAnchorResolver() {}

  /**
   * Resolves an anchor coordinate or pre-warmed generation result from the given region and anchor source.
   *
   * @param region the parent region (never {@code null})
   * @param anchorSource the anchor source strategy (defaults to region queue if null)
   * @return a future completing with the resolved {@link GenerationResult}; never null
   */
  public static CompletableFuture<GenerationResult> resolveAnchor(Region region, AnchorSource anchorSource) {
    if (region == null) {
      return CompletableFuture.completedFuture(null);
    }
    AnchorSource source = (anchorSource != null) ? anchorSource : AnchorSource.regionQueue();

    // 1. RegionQueueAnchorSource: draw from pre-warmed queue (kept/hot queue)
    if (source instanceof AnchorSource.RegionQueueAnchorSource) {
      return region.getLocation(Collections.emptySet());
    }

    // 2. ClaimHazardAnchorSource: find a claim perimeter from MemoryShape bad causes
    if (source instanceof AnchorSource.ClaimHazardAnchorSource) {
      return CompletableFuture.supplyAsync(
          () -> {
            RTPCoords claimPerimeter = resolveClaimPerimeter(region);
            if (claimPerimeter != null) {
              return new GenerationResult(claimPerimeter, 1, null);
            }
            return null;
          });
    }

    // 3. General AnchorSource (Fixed, Entity, custom)
    return source
        .resolveAnchor(region)
        .thenApply(
            coords -> {
              if (coords == null) return null;
              // If world name is missing or empty, inherit from region world
              String worldName = coords.worldName();
              if ((worldName == null || worldName.isBlank()) && region.getWorld() != null) {
                coords = new RTPCoords(region.getWorld().name(), coords.x(), coords.y(), coords.z());
              }
              return new GenerationResult(coords, 1, null);
            })
        .exceptionally(
            ex -> {
              RTP.log(Level.WARNING, "[group] anchor resolution failed for region '" + region.name + "'", ex);
              return null;
            });
  }

  /**
   * Extracts a claim boundary coordinate from the parent region's spatial memory.
   * Under ADR-079/ADR-095, external verifier rejections are tagged with FailTypes.safetyExternal.
   * A coordinate adjacent to or on the edge of a safetyExternal run serves as the claim perimeter anchor.
   */
  public static RTPCoords resolveClaimPerimeter(Region region) {
    if (region == null || !(region.getShape() instanceof MemoryShape<?> memShape)) {
      return null;
    }
    byte[] causes = memShape.badCausesSnapshot();
    long[] keys = memShape.badKeysSnapshot();
    if (causes == null || keys == null || causes.length == 0 || keys.length == 0) {
      return null;
    }

    final byte safetyExternalByte = (byte) LocationGenerator.FailTypes.safetyExternal.ordinal();
    String worldName = (region.getWorld() != null) ? region.getWorld().name() : "";

    for (int i = 0; i < causes.length && i < keys.length; i++) {
      if (causes[i] == safetyExternalByte) {
        long key = keys[i];
        int[] xz = memShape.locationToXZ(key);
        if (xz != null && xz.length >= 2) {
          // Found a safetyExternal (claim) chunk. Convert chunk coordinates to world block coords.
          int blockX = (xz[0] << 4) + 8;
          int blockZ = (xz[1] << 4) + 8;
          return new RTPCoords(worldName, blockX, 64, blockZ);
        }
      }
    }
    return null;
  }
}
