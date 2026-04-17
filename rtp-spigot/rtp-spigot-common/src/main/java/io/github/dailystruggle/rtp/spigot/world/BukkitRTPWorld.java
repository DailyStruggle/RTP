package io.github.dailystruggle.rtp.spigot.world;

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

public class BukkitRTPWorld extends RTPWorld<World> {
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

  public BukkitRTPWorld(World world) {
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
    BukkitRTPWorld.getBiome = getBiome;
  }

  public static void setBiomesGetter(@NotNull Function<RTPWorld<?>, Set<String>> getBiomes) {
    BukkitRTPWorld.getBiomes = getBiomes;
  }

  public static Set<String> getBiomes(RTPWorld<?> world) {
    return getBiomes.apply(world);
  }

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
  public CompletableFuture<Long> getChunkAt(int cx, int cz) {
    totalChunkLoads.incrementAndGet();
    CompletableFuture<Long> future = new CompletableFuture<>();

    Runnable loadChunkTask = () -> {
      try {
        // world.getChunkAt() is strictly synchronous and guarantees ChunkStatus.FULL
        org.bukkit.Chunk chunk = world.getChunkAt(cx, cz);
        cacheChunk(cx, cz, chunk);
        future.complete(((long) cx & 0xffffffffL | ((long) cz << 32)));
      } catch (Throwable t) {
        future.complete(null); // Safely fail the future if generation crashes
      }
    };

    // Spigot mandates that synchronous chunk generation must occur on the primary thread
    if (org.bukkit.Bukkit.isPrimaryThread()) {
      loadChunkTask.run();
    } else {
      org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
      if (plugin != null) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, loadChunkTask);
      } else {
        future.complete(null);
      }
    }

    return future;
  }

  @Override
  public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
    return getChunkAt(cx, cz).thenApply(key -> {
      return new ChunkSet(this, cx, cz, Collections.singletonList(CompletableFuture.completedFuture(key)), new CompletableFuture<>());
    });
  }

  @Override
  protected void setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
    org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
    if (plugin == null || !plugin.isEnabled()) return;
    if (org.bukkit.Bukkit.isPrimaryThread()) {
      if (forceLoad) {
        if (!world.getPluginChunkTickets(cx, cz).contains(plugin)) {
          world.addPluginChunkTicket(cx, cz, plugin);
        }
      } else {
        world.removePluginChunkTicket(cx, cz, plugin);
      }
    } else {
      org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
        if (forceLoad) {
          if (!world.getPluginChunkTickets(cx, cz).contains(plugin)) {
            world.addPluginChunkTicket(cx, cz, plugin);
          }
        } else {
          world.removePluginChunkTicket(cx, cz, plugin);
        }
      });
    }
  }

  @Override
  public java.util.concurrent.CompletableFuture<Integer> getServerForceLoadedCount() {
    java.util.concurrent.CompletableFuture<Integer> future = new java.util.concurrent.CompletableFuture<>();
    io.github.dailystruggle.rtp.common.RTP.serverAccessor.getScheduler().runTask(() -> {
      org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
      if (plugin == null) {
        future.complete(0);
        return;
      }

      int count = 0;
      for (org.bukkit.Chunk chunk : world.getForceLoadedChunks()) {
        if (chunk.getPluginChunkTickets().contains(plugin)) {
          count++;
        }
      }
      future.complete(count);
    });
    return future;
  }

  @Override
  public RTPChunk<?> getCachedChunk(long key) {
    WeakReference<org.bukkit.Chunk> ref = chunkCache.get(key);
    if (ref == null) return null;

    org.bukkit.Chunk chunk = ref.get();
    if (chunk == null || !chunk.isLoaded()) {
      chunkCache.remove(key); // Cleanup stale reference
      return null;
    }
    return new BukkitRTPChunk(chunk);
  }


  @Override
  public void keepChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      chunkCache.put(((long) cx & 0xffffffffL | ((long) cz << 32)), new WeakReference<>(world.getChunkAt(cx, cz)));
      setForceLoaded(cx, cz, true);
    });
  }

  @Override
  public void forgetChunkAt(int cx, int cz) {
    RTP.scheduler.runTask(this, cx, cz, () -> {
      setForceLoaded(cx, cz, false);
      chunkCache.remove(((long) cx & 0xffffffffL | ((long) cz << 32)));
    });
  }

  @Override
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
  public String getBiome(int x, int y, int z) {
    return getBiome.apply(new Location(world, x, y, z));
  }

  @Override
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
  public boolean isInactive() {
    return Bukkit.getWorld(id) == null;
  }

  public boolean isForceLoaded(int cx, int cz) {
    return world.isChunkForceLoaded(cx, cz);
  }

  @Override
  public void save() {
    world.save();
  }

  @Override
  public int getMaxHeight() {
    return world.getMaxHeight();
  }

  @Override
  public int getMinHeight() {
    return world.getMinHeight();
  }

  @Override
  public int getCacheSize() {
    return chunkCache.size();
  }

  @Override
  public long getSeed() {
    return world.getSeed();
  }
}
