package io.github.dailystruggle.rtp.paper.world;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.spigot.world.BukkitRTPWorld;
import java.util.concurrent.CompletableFuture;

public class PaperRTPChunkManager implements RTPChunkManager {
  @Override
  public CompletableFuture<Long> getChunkAtAsync(RTPWorld<?> world, int x, int z) {
    if (!(world instanceof BukkitRTPWorld)) {
      return CompletableFuture.failedFuture(
          new IllegalArgumentException("World is not a BukkitRTPWorld"));
    }

    return ((BukkitRTPWorld) world).getChunkAt(x, z);
  }

  @Override
  public ChunkSet getChunkSet(RTPWorld<?> world, int x, int z) {
    return ChunkSet.getGlobal(world, x, z);
  }

  @Override
  public ChunkSet getChunkSet(RTPCoords coords) {
    RTPWorld<?> world = RTP.serverAccessor.getRTPWorld(coords.worldName());
    if (world == null) return null;
    return getChunkSet(world, coords.x() >> 4, coords.z() >> 4);
  }
}
