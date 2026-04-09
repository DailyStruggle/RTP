package io.github.dailystruggle.rtp.api.world;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Interface for managing chunks asynchronously */
public interface RTPChunkManager {
  java.util.Set<ChunkSet> GLOBAL_CHUNKS = java.util.concurrent.ConcurrentHashMap.newKeySet();

  AtomicLong ACTIVE_CHUNK_TICKETS = new AtomicLong(0);
  AtomicLong TOTAL_CHUNK_LOADS = new AtomicLong(0);

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
   * Set whether the chunks in the set should be kept loaded
   *
   * @param chunkSet the chunk set to keep or release
   * @param keep true to keep loaded, false otherwise
   * @param world the world the chunks are in
   */
  default void keep(ChunkSet chunkSet, boolean keep, RTPWorld<?> world) {
    if (keep) {
      if (!GLOBAL_CHUNKS.add(chunkSet)) return;
      ACTIVE_CHUNK_TICKETS.addAndGet(chunkSet.chunks().size());
    } else {
      if (!GLOBAL_CHUNKS.remove(chunkSet)) return;
      ACTIVE_CHUNK_TICKETS.addAndGet(-chunkSet.chunks().size());
    }

    chunkSet.chunks().forEach(
        chunk -> {
          if (chunk.isDone()) {
            Long key = chunk.getNow(null);
            if (key != null) {
              RTPChunk<?> rtpChunk = world.getCachedChunk(key);
              if (rtpChunk != null) rtpChunk.keep(keep);
            }
          } else {
            chunk.thenAccept(
                key -> {
                  try {
                    RTPChunk<?> rtpChunk = world.getCachedChunk(key);
                    if (rtpChunk != null) {
                      rtpChunk.keep(keep);
                    }
                  } finally {
                    // ensures that even if something fails, the future chain doesn't hang
                  }
                });
          }
        });
  }

  /**
   * Perform an action when all chunks are loaded
   *
   * @param chunkSet the chunk set to wait for
   * @param consumer the action to perform
   */
  default java.util.concurrent.CompletableFuture<Void> whenComplete(ChunkSet chunkSet, Consumer<Boolean> consumer) {
    return chunkSet.complete().thenAccept(consumer);
  }
}
