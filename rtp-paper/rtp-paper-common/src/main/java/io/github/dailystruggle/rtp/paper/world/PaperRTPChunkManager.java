package io.github.dailystruggle.rtp.paper.world;

import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.spigot.world.BukkitRTPWorld;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

public class PaperRTPChunkManager implements RTPChunkManager {
  @Override
  public CompletableFuture<Long> getChunkAtAsync(RTPWorld<?> world, int x, int z) {
    if (!(world instanceof BukkitRTPWorld bukkitWorld)) {
      return CompletableFuture.failedFuture(
              new IllegalArgumentException("World is not a BukkitRTPWorld"));
    }

      long key = ((long) x & 0xffffffffL | ((long) z << 32));

    if (bukkitWorld.isForceLoaded(x, z)) {
      if (bukkitWorld.getCachedChunk(key) != null) {
        return CompletableFuture.completedFuture(key);
      }
    }

    World spigotWorld = bukkitWorld.world();
    CompletableFuture<Long> future = new CompletableFuture<>();

    // The Bukkit Consumer strictly blocks execution until ChunkStatus.FULL is achieved
    spigotWorld.getChunkAtAsync(x, z, true, chunk -> {
      if (chunk == null) {
        future.complete(null);
        return;
      }

      bukkitWorld.cacheChunk(x, z, chunk);
      future.complete(key);
    });

    return future;
  }
}
