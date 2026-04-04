package io.github.dailystruggle.rtp.spigot.world;

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

import io.papermc.lib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.NotNull;

public final class BukkitRTPWorld extends RTPWorld<World> {
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
    return PaperLib.getChunkAtAsync(world, cx, cz, true)
        .thenApply(
            chunk -> {
              if (chunk == null) return null;
              cacheChunk(cx, cz, chunk);
              return ((long) cx & 0xffffffffL | ((long) cz << 32));
            });
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
    world.setChunkForceLoaded(cx, cz, true);
  }

  @Override
  public void forgetChunkAt(int cx, int cz) {
    world.setChunkForceLoaded(cx, cz, false);
    long key = ((long) cx & 0xffffffffL | ((long) cz << 32));
    chunkCache.remove(key);
  }

  @Override
  public void forgetChunks() {
    // Explicitly un-force-load everything we know about before clearing
    chunkCache.forEach((key, ref) -> {
      int cx = (int) (key & 0xffffffffL);
      int cz = (int) (key >> 32);
      world.setChunkForceLoaded(cx, cz, false);
    });
    chunkCache.clear();
  }

  @Override
  public String getBiome(int x, int y, int z) {
    return getBiome.apply(new Location(world, x, y, z));
  }

  @Override
  public void platform(RTPLocation location) {
    // Implementation
  }

  @Override
  public boolean isInactive() {
    return Bukkit.getWorld(id) == null;
  }

  @Override
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

  public int getCacheSize() {
    return chunkCache.size();
  }
}
