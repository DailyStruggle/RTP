package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.claim.ClaimBoundary;
import io.github.dailystruggle.rtp.api.group.AnchorSource;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTracker;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Resolves {@link AnchorSource} strategies into world-bound {@link RTPCoords} or {@link GenerationResult}
 * for subspace placements.
 */
public final class SubspaceAnchorResolver {

  private static final ClaimAnchoredRegionTracker CLAIM_TRACKER = new ClaimAnchoredRegionTracker();

  private SubspaceAnchorResolver() {}

  public static ClaimAnchoredRegionTracker getClaimTracker() {
    return CLAIM_TRACKER;
  }

  /**
   * Resolves an anchor coordinate or pre-warmed generation result from the given region and anchor source.
   *
   * @param region the parent region (never {@code null})
   * @param anchorSource the anchor source strategy (defaults to region queue if null)
   * @return a future completing with the resolved {@link GenerationResult}; never null
   */
  public static CompletableFuture<GenerationResult> resolveAnchor(Region region, AnchorSource anchorSource) {
    if (region == null) {
      RTP.log(Level.WARNING, "[group] resolveAnchor: region is null");
      return CompletableFuture.completedFuture(null);
    }
    AnchorSource source = (anchorSource != null) ? anchorSource : AnchorSource.regionQueue();
    RTP.log(
        Level.FINE,
        "[group] resolveAnchor: region='"
            + region.name
            + "', strategy="
            + source.getClass().getSimpleName());

    // 1. RegionQueueAnchorSource: draw from pre-warmed queue (kept/hot queue)
    if (source instanceof AnchorSource.RegionQueueAnchorSource) {
      if (region.queueManager != null) {
        CompletableFuture<RTPLocation> pollFuture = region.queueManager.poll(null);
        if (pollFuture != null) {
          return pollFuture.thenApply(loc -> {
            if (loc == null || loc.coords() == null) {
              RTP.log(Level.FINE, "[group] resolveAnchor: queueManager.poll returned null for region '" + region.name + "'");
              return null;
            }
            RTP.log(Level.FINE, "[group] resolveAnchor: queueManager.poll resolved anchor at " + loc.coords());
            return new GenerationResult(loc.coords(), loc.attempts(), null, loc.reservation());
          });
        }
      }
      RTP.log(Level.FINE, "[group] resolveAnchor: queueManager was null or had no poll, falling back to getLocation for '" + region.name + "'");
      return region.getLocation(Collections.emptySet()).thenApply(res -> {
        RTP.log(Level.FINE, "[group] resolveAnchor: region.getLocation completed: " + (res != null ? res.coords() : "null"));
        return res;
      });
    }

    // 2. ClaimHazardAnchorSource: find a claim perimeter from MemoryShape bad causes
    if (source instanceof AnchorSource.ClaimHazardAnchorSource) {
      CompletableFuture<GenerationResult> future = new CompletableFuture<>();
      Runnable task = () -> {
        try {
          RTPCoords claimPerimeter = resolveClaimPerimeter(region);
          if (claimPerimeter != null) {
            future.complete(new GenerationResult(claimPerimeter, 1, null));
          } else {
            future.complete(null);
          }
        } catch (Throwable t) {
          future.completeExceptionally(t);
        }
      };
      if (RTP.scheduler != null) {
        RTP.scheduler.runTaskAsynchronously(task);
      } else {
        task.run();
      }
      return future;
    }

    // 3. ClaimBoundaryAnchorSource: resolve anchor with center preservation and ingest hazard memory
    if (source instanceof AnchorSource.ClaimBoundaryAnchorSource claimSource) {
      ClaimBoundary boundary = claimSource.boundary();
      int[] xz = CLAIM_TRACKER.resolveAnchor(boundary, System.currentTimeMillis());
      String worldName = (region.getWorld() != null) ? region.getWorld().name() : boundary.world();
      RTPCoords coords = new RTPCoords(worldName, xz[0], 64, xz[1]);

      // Ingest known hazard memory from region into region's memory shape within boundary if applicable
      if (region.getShape() instanceof MemoryShape<?> memShape) {
        ClaimAnchoredRegionTracker.ingestMemoryFromRegion(region, memShape, boundary);
      }
      return CompletableFuture.completedFuture(new GenerationResult(coords, 1, null));
    }

    // 4. General AnchorSource (Fixed, Entity, custom)
    return source
        .resolveAnchor(region)
        .thenApply(
            coords -> {
              if (coords == null) {
                RTP.log(Level.WARNING, "[group] resolveAnchor returned null coords for strategy " + source);
                return null;
              }
              // If world name is missing or empty, inherit from region world
              String worldName = coords.worldName();
              if ((worldName == null || worldName.isBlank()) && region.getWorld() != null) {
                coords = new RTPCoords(region.getWorld().name(), coords.x(), coords.y(), coords.z());
              }
              RTP.log(Level.FINE, "[group] resolveAnchor successfully resolved anchor at " + coords);
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
