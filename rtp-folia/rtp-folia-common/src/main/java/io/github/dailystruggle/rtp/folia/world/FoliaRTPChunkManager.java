package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.concurrent.CompletableFuture;

public class FoliaRTPChunkManager implements RTPChunkManager {
  @Override
  public CompletableFuture<Long> getChunkAtAsync(RTPWorld<?> world, int x, int z) {
    if (!(world instanceof FoliaRTPWorld)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("World is not a FoliaRTPWorld"));
    }

    return ((FoliaRTPWorld) world).getChunkAt(x, z);
  }
}
