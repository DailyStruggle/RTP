package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** A set of chunks that are being loaded */
public final class ChunkSet {
  /** List of futures for the chunk keys in the set */
  public final List<CompletableFuture<Long>> chunks;

  /** Future that completes when all chunks are loaded */
  public final CompletableFuture<Boolean> complete;

  private boolean isKept = false;

  /**
   * Constructor for ChunkSet
   *
   * @param chunks the list of futures for the chunks
   * @param complete the future that completes when all chunks are loaded
   */
  public ChunkSet(List<CompletableFuture<Long>> chunks, CompletableFuture<Boolean> complete) {
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

  /**
   * Perform an action when all chunks are loaded
   *
   * @param consumer the action to perform
   */
  public void whenComplete(Consumer<Boolean> consumer) {
    if (complete.isDone()) {
      consumer.accept(complete.getNow(false));
      return;
    }

    complete.thenAccept(consumer);
  }
}
