package io.github.dailystruggle.rtp.folia.world;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;

import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.NotNull;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;

public final class FoliaRTPWorld extends RTPWorld<World> {
  private static final AtomicBoolean biomeException = new AtomicBoolean(false);
  private static Function<Location, String> getBiome =
      location -> {
        if (biomeException.get()) return Biome.PLAINS.name();
        try {
          World world = Objects.requireNonNull(location.getWorld());
          int x = location.getBlockX();
          int y = location.getBlockY();
          int z = location.getBlockZ();
          return world.getBiome(x, y, z).name();
        } catch (Throwable t) {
          return Biome.PLAINS.name();
        }
      };

  private static @NotNull Function<RTPWorld<?>, Set<String>> getBiomes =
      (rtpWorld) ->
          Arrays.stream(Biome.values())
              .map(biome -> biome.name().toUpperCase())
              .collect(Collectors.toSet());

  private final UUID id;
  private final String name;

  private final ConcurrentHashMap<Long, WeakReference<Chunk>> chunkCache = new ConcurrentHashMap<>();

  @RegionThread
  public FoliaRTPWorld(World world) {
    super(world);
    if (world == null) {
      this.id = null;
      this.name = null;
    } else {
      this.id = world.getUID();
      this.name = world.getName();
    }
  }

  public static void setBiomeGetter(@NotNull Function<Location, String> getBiome) {
    FoliaRTPWorld.getBiome = getBiome;
  }

  public static void setBiomesGetter(@NotNull Function<RTPWorld<?>, Set<String>> getBiomes) {
    FoliaRTPWorld.getBiomes = getBiomes;
  }

  public static Set<String> getBiomes(RTPWorld<?> world) {
    return getBiomes.apply(world);
  }

  @RegionThread
  public void cacheChunk(int x, int z, org.bukkit.Chunk chunk) {
    long key = ((long) x & 0xffffffffL | ((long) z << 32));
    chunkCache.put(key, new WeakReference<>(chunk));
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public UUID id() {
    return id;
  }

  @Override
  @RegionThread
  public CompletableFuture<Long> getChunkAt(int cx, int cz) {
    totalChunkLoads.incrementAndGet();
    return world
        .getChunkAtAsync(cx, cz, true)
        .thenApply(
            chunk -> {
              if (chunk == null) return null;
              cacheChunk(cx, cz, chunk);
              return ((long) cx & 0xffffffffL | ((long) cz << 32));
            });
  }

  @Override
  @RegionThread
  public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
    totalChunkLoads.incrementAndGet();
    return world.getChunkAtAsync(cx, cz).thenApply(chunk -> {
      cacheChunk(cx, cz, chunk);
      return new ChunkSet(this, cx, cz, Collections.singletonList(CompletableFuture.completedFuture(((long) cx & 0xffffffffL | ((long) cz << 32)))), new CompletableFuture<>());
    });
  }

  /**
   * Folia-specific stale-chunk guard (ADR-015 / REQ-RTP-S-005). Uses Bukkit's
   * {@code World#isChunkLoaded(int,int)}, which is a non-loading status query and
   * does NOT dispatch to the Region Thread. This guards the race between
   * {@link #getChunkAtAsync(int,int)} future resolution and the subsequent
   * block-evaluation task executing on a backlogged Count-Bound pipe, during
   * which Folia's native chunk GC may have unloaded the chunk.
   *
   * <p>Never call this from a hot inner loop that expects pin semantics — this
   * is a best-effort status read, not a guarantee the chunk will remain loaded
   * on the following line.</p>
   */
  @Override
  public boolean isChunkLoaded(int cx, int cz) {
    try {
      return world.isChunkLoaded(cx, cz);
    } catch (Throwable t) {
      return false;
    }
  }

  @Override
  @GlobalRegionThread
  protected void setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin == null || !plugin.isEnabled()) return;
    RTP.serverAccessor.getScheduler().runTask(() -> {
      try {
        if (forceLoad) {
          if (!world.getPluginChunkTickets(cx, cz).contains(plugin)) {
            world.addPluginChunkTicket(cx, cz, plugin);
          }
        } else {
          world.removePluginChunkTicket(cx, cz, plugin);
        }
      } catch (Exception e) {
        // Silently catch exceptions from lingering async tasks attempting to fire after shutdown
      }
    });
  }

  @Override
  @GlobalRegionThread
  public java.util.concurrent.CompletableFuture<Integer> getServerForceLoadedCount() {
    java.util.concurrent.CompletableFuture<Integer> future = new java.util.concurrent.CompletableFuture<>();
    io.github.dailystruggle.rtp.common.RTP.serverAccessor.getScheduler().runTask(() -> {
      org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
      if (plugin == null) {
        future.complete(0);
        return;
      }

      try {
        int count = 0;
        for (org.bukkit.Chunk chunk : world.getForceLoadedChunks()) {
          if (chunk.getPluginChunkTickets().contains(plugin)) {
            count++;
          }
        }
        future.complete(count);
      } catch (Exception e) {
        future.complete(-1); // Fallback in case of unexpected global region failure
      }
    });
    return future;
  }

  @Override
  @RegionThread
  public RTPChunk<?> getCachedChunk(long key) {
    WeakReference<Chunk> ref = chunkCache.get(key);
    if (ref == null) return null;

    org.bukkit.Chunk chunk = ref.get();
    if (chunk == null || !chunk.isLoaded()) {
      chunkCache.remove(key); // Cleanup stale reference
      return null;
    }
    return new FoliaRTPChunk(chunk);
  }


  @Override
  @RegionThread
  public void keepChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      chunkCache.put(((long) cx & 0xffffffffL | ((long) cz << 32)), new WeakReference<>(world.getChunkAt(cx, cz)));
      setForceLoaded(cx, cz, true);
    });
  }

  @Override
  @RegionThread
  public void forgetChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      setForceLoaded(cx, cz, false);
      chunkCache.remove(((long) cx & 0xffffffffL | ((long) cz << 32)));
    });
  }

  @Override
  @RegionThread
  public void forgetChunks() {
    // Explicitly un-force-load everything we know about before clearing
    chunkTickets.forEach((key, count) -> {
      int cx = (int) (key & 0xffffffffL);
      int cz = (int) (key >> 32);
      while (count.get() > 0) {
        setForceLoaded(cx, cz, false);
      }
    });
    chunkCache.clear();
  }

  @Override
  @RegionThread
  public String getBiome(int x, int y, int z) {
    return getBiome.apply(new Location(world, x, y, z));
  }

  @Override
  @RegionThread
  public void platform(RTPLocation location) {
    try {
      ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
      int radius = safety.getNumber(SafetyKeys.platformRadius, 0).intValue();
      int airHeight = safety.getNumber(SafetyKeys.platformAirHeight, 0).intValue();
      int depth = safety.getNumber(SafetyKeys.platformDepth, 0).intValue();
      Material material;
      try {
        material = Material.valueOf(safety.getConfigValue(SafetyKeys.platformMaterial, "GLASS").toString().toUpperCase());
      } catch (IllegalArgumentException e) {
        material = Material.GLASS;
      }

      int lx = location.getBlockX();
      int ly = location.getBlockY();
      int lz = location.getBlockZ();

      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          for (int dy = -depth; dy < 0; dy++) {
            world.getBlockAt(lx + dx, ly + dy, lz + dz).setType(material);
          }
          for (int dy = 0; dy < airHeight; dy++) {
            world.getBlockAt(lx + dx, ly + dy, lz + dz).setType(Material.AIR);
          }
        }
      }
    } finally {
      if (location.getReservation() != null) location.getReservation().close();
    }
  }

  @Override
  @GlobalRegionThread
  public boolean isInactive() {
    return Bukkit.getWorld(id) == null;
  }

  @Override
  @GlobalRegionThread
  public void save() {
    world.save();
  }

  @Override
  @RegionThread
  public int getMaxHeight() {
    return world.getMaxHeight();
  }

  @Override
  @RegionThread
  public int getMinHeight() {
    return world.getMinHeight();
  }

  @Override
  @RegionThread
  public int getCacheSize() {
    return chunkCache.size();
  }

  @Override
  @RegionThread
  public long getSeed() {
    return world.getSeed();
  }
}
