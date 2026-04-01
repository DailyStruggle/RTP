package io.github.dailystruggle.rtp.api.world;

import java.util.concurrent.CompletableFuture;

/** Interface for managing chunks asynchronously */
public interface RTPChunkManager {
  /**
   * Get the chunk at the specified coordinates asynchronously
   *
   * @param world the world
   * @param x the x coordinate
   * @param z the z coordinate
   * @return a future that completes with the chunk key
   */
  CompletableFuture<Long> getChunkAtAsync(RTPWorld<?> world, int x, int z);

  /**
   * Get a chunk set at the specified coordinates
   *
   * @param world the world
   * @param x the x coordinate
   * @param z the z coordinate
   * @return the chunk set, or null if it doesn't exist
   */
  ChunkSet getChunkSet(RTPWorld<?> world, int x, int z);

  /**
   * Get a chunk set at the specified coordinates
   *
   * @param coords the coordinates
   * @return the chunk set, or null if it doesn't exist
   */
  ChunkSet getChunkSet(RTPCoords coords);
}
