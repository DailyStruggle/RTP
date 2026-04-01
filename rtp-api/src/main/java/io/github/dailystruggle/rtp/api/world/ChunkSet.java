package io.github.dailystruggle.rtp.api.world;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** A set of chunks that are being loaded */
public final class ChunkSet {
  private static final ConcurrentHashMap<String, ChunkSet> GLOBAL_CHUNKS = new ConcurrentHashMap<>();

  public static final AtomicLong ACTIVE_CHUNK_TICKETS = new AtomicLong(0);
  public static final java.util.concurrent.atomic.AtomicLong TOTAL_CHUNK_LOADS = new AtomicLong(0);

  /** List of futures for the chunk keys in the set */
  public final List<CompletableFuture<Long>> chunks;

  /** Future that completes when all chunks are loaded */
  public final CompletableFuture<Boolean> complete;

  private boolean isKept = false;

  private static String getGlobalKey(UUID worldId, int x, int z) {
    return worldId.toString() + ":" + x + ":" + z;
  }

  public static void register(RTPWorld<?> world, int x, int z, ChunkSet chunkSet) {
    GLOBAL_CHUNKS.put(getGlobalKey(world.id(), x, z), chunkSet);
  }

  public static void unregister(RTPWorld<?> world, int x, int z) {
    GLOBAL_CHUNKS.remove(getGlobalKey(world.id(), x, z));
  }

  public static ChunkSet getGlobal(RTPWorld<?> world, int x, int z) {
    return GLOBAL_CHUNKS.get(getGlobalKey(world.id(), x, z));
  }

  /**
   * Constructor for ChunkSet
   *
   * @param chunks the list of futures for the chunks
   * @param complete the future that completes when all chunks are loaded
   */
  public ChunkSet(List<CompletableFuture<Long>> chunks, CompletableFuture<Boolean> complete) {
    TOTAL_CHUNK_LOADS.addAndGet(chunks.size());
    this.chunks = chunks;
    this.complete = complete;

    CompletableFuture.allOf(chunks.toArray(new CompletableFuture[0]))
        .thenRun(() -> this.complete.complete(true));
  }

  /**
   * Set whether the chunks in the set should be kept loaded
   *
   * @param keep true to keep loaded, false otherwise
   */
  public void keep(boolean keep, RTPWorld<?> world) {
    if (this.isKept == keep) return;
    if (keep) ACTIVE_CHUNK_TICKETS.addAndGet(chunks.size());
    else ACTIVE_CHUNK_TICKETS.addAndGet(-chunks.size());

    this.isKept = keep;
    chunks.forEach(
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
                      if (keep && !isKept) rtpChunk.keep(false);
                      else rtpChunk.keep(keep);
                    }
                  } finally {
                    // ensures that even if something fails, the future chain doesn't hang
                  }
                });
          }
        });
  }

  public boolean keep() {
    return isKept;
  }

  /**
   * Perform an action when all chunks are loaded
   *
   * @param consumer the action to perform
   */
  public void whenComplete(Consumer<Boolean> consumer) {
    complete.thenAccept(consumer);
  }
}
