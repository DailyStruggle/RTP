package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Task for cleaning up chunks after a teleportation */
public final class ChunkCleanup extends RTPRunnable {
  /** Actions to perform before chunk cleanup */
  public static final List<Consumer<ChunkCleanup>> preActions = new ArrayList<>();

  /** Actions to perform after chunk cleanup */
  public static final List<Consumer<ChunkCleanup>> postActions = new ArrayList<>();

  private final RTPCoords coords;
  private final Region region;

  /**
   * Constructor for ChunkCleanup
   *
   * @param coords the location of the teleportation
   * @param region the region of the teleportation
   */
  public ChunkCleanup(RTPCoords coords, Region region) {
    this.coords = coords;
    this.region = region;
  }

  @Override
  public void run() {
    try {
      preActions.forEach(consumer -> consumer.accept(this));
      ChunkSet chunkSet = region.locAssChunks.get(coords);
      if (chunkSet == null) return;
      RTPWorld<?> rtpWorld = region.getWorld();

      CompletableFuture<Void> cleanupFuture = new CompletableFuture<>();
      Consumer<Boolean> cleanupAction = (success) -> {
        try {
          chunkSet.keep(false, rtpWorld);
          chunkSet.chunks.forEach(cf -> {
            if (cf.isDone()) {
              Long key = cf.getNow(null);
              if (key != null) {
                RTPChunk<?> chunk = rtpWorld.getCachedChunk(key);
                if (chunk != null) chunk.unload();
              }
            }
          });
          cleanupFuture.complete(null);
        } catch (Exception e) {
          cleanupFuture.completeExceptionally(e);
        }
      };

      if (chunkSet.complete.isDone()) {
        cleanupAction.accept(chunkSet.complete.getNow(false));
      } else {
        chunkSet.complete.thenAccept(cleanupAction);
      }

      cleanupFuture.whenComplete((v, throwable) -> {
        if (throwable != null) {
          RTP.log(Level.SEVERE, "Failed to cleanup chunks for " + coords, throwable);
          // Aggressive forceful removal
          chunkSet.chunks.forEach(cf -> {
            if (cf.isDone()) {
              Long key = cf.getNow(null);
              if (key != null) {
                RTPChunk<?> chunk = rtpWorld.getCachedChunk(key);
                if (chunk != null) {
                  try {
                    chunk.keep(false);
                    chunk.unload();
                  } catch (Exception ignored) {
                  }
                }
              }
            }
          });
        }
        region.removeChunks(coords);
        postActions.forEach(consumer -> consumer.accept(this));
      });
    } catch (Exception e) {
      RTP.log(Level.SEVERE, "Error during ChunkCleanup task for " + coords, e);
    }
  }

  /**
   * Get the location of the teleportation
   *
   * @return the location
   */
  public RTPLocation location() {
    return new RTPLocation(
        RTP.serverAccessor.getRTPWorld(coords.worldName()), coords.x(), coords.y(), coords.z());
  }

  /**
   * Get the coords of the teleportation
   *
   * @return the coords
   */
  public RTPCoords coords() {
    return coords;
  }

  /**
   * Get the region of the teleportation
   *
   * @return the region
   */
  public Region region() {
    return region;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    ChunkCleanup that = (ChunkCleanup) obj;
    return Objects.equals(this.coords, that.coords) && Objects.equals(this.region, that.region);
  }

  @Override
  public int hashCode() {
    return Objects.hash(coords, region);
  }

  @Override
  public String toString() {
    return "ChunkCleanup[" + "coords=" + coords + ", " + "region=" + region + ']';
  }
}
