package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Set;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;

public final class FoliaRTPChunk extends RTPChunk<Chunk> {
  @RegionThread
  public FoliaRTPChunk(Chunk chunk) {
    super(chunk);
  }

  @Override
  @RegionThread
  public int x() {
    return chunk.getX();
  }

  @Override
  @RegionThread
  public int z() {
    return chunk.getZ();
  }

  @Override
  @GlobalRegionThread
  public RTPWorld<?> getWorld() {
    return RTP.serverAccessor.getRTPWorld(chunk.getWorld().getUID());
  }

  @Override
  @RegionThread
  public boolean isGenerated() {
    return chunk.getWorld().isChunkGenerated(chunk.getX(), chunk.getZ());
  }

  @Override
  @RegionThread
  public boolean isLoaded() {
    return chunk.isLoaded();
  }

  @Override
  @GlobalRegionThread
  public void keep(boolean keep) {
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin == null || !plugin.isEnabled()) return;
    RTP.serverAccessor.getScheduler().runTask(() -> {
      if (keep) {
        if (!chunk.getPluginChunkTickets().contains(plugin)) {
          chunk.addPluginChunkTicket(plugin);
        }
      } else {
        chunk.removePluginChunkTicket(plugin);
      }
    });
  }

  @Override
  @RegionThread
  public boolean isAir(int x, int y, int z) {
    return chunk.getBlock(x & 0xF, y, z & 0xF).getType().isAir();
  }

  @Override
  @RegionThread
  public int getSkyLight(int x, int y, int z) {
    return chunk.getBlock(x & 0xF, y, z & 0xF).getLightFromSky();
  }

  @Override
  @RegionThread
  public int getSurfaceHeight(int x, int z) {
    x = Math.max(0, Math.min(15, x));
    z = Math.max(0, Math.min(15, z));
    int globalX = (chunk.getX() << 4) + x;
    int globalZ = (chunk.getZ() << 4) + z;
    return chunk.getWorld().getHighestBlockYAt(globalX, globalZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
  }

  @Override
  @RegionThread
  public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
    String materialName = chunk.getBlock(x & 0xF, y, z & 0xF).getType().name();
    return !unsafeBlocks.contains(materialName);
  }

  @Override
  @RegionThread
  public void unload() {
  }
}
