package io.github.dailystruggle.rtp.api.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Async batch of chunks around a candidate teleport location. Construction wires an
 * {@code allOf} fan-in to {@link #complete}: {@code true} when every chunk future
 * resolves, {@code false} if any completes exceptionally. {@code x}/{@code z} are
 * <em>chunk</em> coordinates (block &gt;&gt; 4) of the centre chunk. Thread-safe.
 *
 * @param world    target world
 * @param x        centre chunk X
 * @param z        centre chunk Z
 * @param chunks   per-chunk load futures (each yields a platform chunk-ticket handle)
 * @param complete single fan-in future ({@code true} = all loaded, {@code false} = any failed)
 */
public record ChunkSet(RTPWorld<?> world, int x, int z, List<CompletableFuture<Long>> chunks, CompletableFuture<Boolean> complete) {

  public ChunkSet {
    CompletableFuture.allOf(chunks.toArray(new CompletableFuture[0]))
        .whenComplete(
            (res, err) -> {
              if (err != null) {
                complete.complete(false);
              } else {
                complete.complete(true);
              }
            });
  }

  /**
   * Returns the chunk X coordinate of the centre chunk.
   *
   * @return chunk X
   */
  public int getX() {
    return x;
  }

  /**
   * Returns the chunk Z coordinate of the centre chunk.
   *
   * @return chunk Z
   */
  public int getZ() {
    return z;
  }
}
