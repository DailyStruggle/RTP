package io.github.dailystruggle.rtp.spigot.world;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.concurrent.CompletableFuture;

public class BukkitRTPChunkManager implements RTPChunkManager {
  @Override
  public CompletableFuture<Long> getChunkAtAsync(RTPWorld<?> world, int x, int z) {
    if (!(world instanceof BukkitRTPWorld)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("World is not a BukkitRTPWorld"));
    }

    return ((BukkitRTPWorld) world).getChunkAt(x, z);
  }
}
