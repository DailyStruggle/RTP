package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.concurrent.CompletableFuture;

public class FoliaRTPChunkManager implements RTPChunkManager {
  @Override
  public CompletableFuture<Long> getChunkAtAsync(RTPWorld<?> world, int x, int z) {
    if (!(world instanceof FoliaRTPWorld foliaRTPWorld)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("World is not a FoliaRTPWorld"));
    }

    long key = ((long) x & 0xffffffffL | ((long) z << 32));

    if (foliaRTPWorld.isForceLoaded(x, z)) {
      if (foliaRTPWorld.getCachedChunk(key) != null) {
        return CompletableFuture.completedFuture(key);
      }
    }

    return foliaRTPWorld.world().getChunkAtAsyncUrgently(x, z).thenApply(chunk -> {
      if (chunk == null) return null;
      foliaRTPWorld.cacheChunk(x, z, chunk);
      return key;
    });
  }
}
