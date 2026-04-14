package io.github.dailystruggle.rtp.api.world;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/** A set of chunks that are being loaded */
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

  public int getX() {
    return x;
  }

  public int getZ() {
    return z;
  }
}
