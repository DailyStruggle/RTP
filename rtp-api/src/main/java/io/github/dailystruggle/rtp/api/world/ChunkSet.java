package io.github.dailystruggle.rtp.api.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A set of chunks surrounding a candidate teleport location that are being loaded
 * asynchronously as part of the pre-generation pipeline.
 *
 * <p>On construction, a {@link CompletableFuture#allOf(CompletableFuture[])} fan-in
 * is wired to {@link #complete}: when every individual chunk future resolves,
 * {@code complete} completes with {@code true}; if any chunk future completes
 * exceptionally, {@code complete} completes with {@code false}.
 *
 * <p>The chunk coordinates ({@link #x}, {@link #z}) are chunk coordinates
 * (block coordinates divided by 16), not block coordinates.
 *
 * <p><b>Thread safety:</b> Record fields are final; {@link CompletableFuture} instances
 * are individually thread-safe. Safe to share across threads.
 *
 * @param world    the world in which the chunks are being loaded
 * @param x        chunk X coordinate of the centre chunk
 * @param z        chunk Z coordinate of the centre chunk
 * @param chunks   the individual per-chunk load futures, each completing with a
 *                 platform-specific chunk ticket handle ({@code Long})
 * @param complete a single future that completes with {@code true} when all chunks
 *                 are loaded, or {@code false} if any chunk load failed
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
