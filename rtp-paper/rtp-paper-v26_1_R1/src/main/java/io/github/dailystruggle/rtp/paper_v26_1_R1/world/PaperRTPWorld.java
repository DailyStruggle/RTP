package io.github.dailystruggle.rtp.paper_v26_1_R1.world;

import io.github.dailystruggle.rtp.spigot.world.BukkitRTPWorld;
import org.bukkit.World;

import java.util.concurrent.CompletableFuture;

/**
 * Paper-specific RTPWorld that uses Paper's async chunk loading API
 * to avoid blocking the main server thread during chunk generation.
 */
public final class PaperRTPWorld extends BukkitRTPWorld {

  public PaperRTPWorld(World world) {
    super(world);
  }

  /**
   * Uses Paper's {@code World.getChunkAtAsync()} instead of the synchronous
   * {@code World.getChunkAt()} to prevent deadlocking the main server thread
   * on PaperMC's chunk scheduler.
   */
  @Override
  public CompletableFuture<Long> getChunkAt(int cx, int cz) {
    totalChunkLoads.incrementAndGet();
    return world.getChunkAtAsync(cx, cz)
        .thenApply(chunk -> {
          if (chunk == null) return null;
          cacheChunk(cx, cz, chunk);
          return ((long) cx & 0xffffffffL | ((long) cz << 32));
        })
        .exceptionally(t -> null);
  }
}
