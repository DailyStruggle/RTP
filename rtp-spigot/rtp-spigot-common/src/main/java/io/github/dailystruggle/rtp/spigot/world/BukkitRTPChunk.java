package io.github.dailystruggle.rtp.spigot.world;

import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;

public final class BukkitRTPChunk extends RTPChunk<Chunk> {
  public BukkitRTPChunk(Chunk chunk) {
    super(chunk);
  }

  @Override
  public int x() {
    return chunk.getX();
  }

  @Override
  public int z() {
    return chunk.getZ();
  }

  @Override
  public RTPWorld<?> getWorld() {
    return RTP.serverAccessor.getRTPWorld(chunk.getWorld().getUID());
  }

  @Override
  public boolean isGenerated() {
    return chunk.getWorld().isChunkGenerated(chunk.getX(), chunk.getZ());
  }

  @Override
  public boolean isLoaded() {
    return chunk.isLoaded();
  }

  @Override
  public void keep(boolean keep) {
    chunk.getWorld().setChunkForceLoaded(chunk.getX(), chunk.getZ(), keep);
  }

  @Override
  public boolean isAir(int x, int y, int z) {
    return chunk.getBlock(x & 0xF, y, z & 0xF).getType().isAir();
  }

  @Override
  public int getSkyLight(int x, int y, int z) {
    return chunk.getBlock(x & 0xF, y, z & 0xF).getLightFromSky();
  }

  @Override
  public int getSurfaceHeight(int x, int z) {
    x = Math.max(0, Math.min(15, x));
    z = Math.max(0, Math.min(15, z));
    int globalX = (chunk.getX() << 4) + x;
    int globalZ = (chunk.getZ() << 4) + z;
    return chunk.getWorld().getHighestBlockYAt(globalX, globalZ, HeightMap.MOTION_BLOCKING_NO_LEAVES);
  }

  @Override
  public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
    String materialName = chunk.getBlock(x & 0xF, y, z & 0xF).getType().name();
    return !unsafeBlocks.contains(materialName);
  }

  @Override
  public void unload() {
    if (Bukkit.isPrimaryThread()) chunk.unload(false);
    else {
      RTP instance = RTP.getInstance();
      if (instance != null) instance.chunksToUnload.offer(this);
    }
  }
}
