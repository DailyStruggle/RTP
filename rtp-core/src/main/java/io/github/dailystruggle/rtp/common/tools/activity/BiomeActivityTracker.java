package io.github.dailystruggle.rtp.common.tools.activity;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * In-memory accumulator of player biome occupancy.
 */
public final class BiomeActivityTracker {

  /** Constructs an empty tracker. */
  public BiomeActivityTracker() {
  }

  private final ConcurrentHashMap<String, AtomicLong> biomeCounts = new ConcurrentHashMap<>();
  private final AtomicLong totalSamples = new AtomicLong();

  /**
   * Records a single occupancy observation for {@code biome}. {@code null} or
   * blank biome names are ignored (the player's chunk could not be read).
   * Biome names are upper-cased so casing differences across platforms collapse
   * to one bucket.
   *
   * @param biome the biome name to record; {@code null} or blank is ignored
   */
  public void record(String biome) {
    if (biome == null) return;
    String key = biome.trim().toUpperCase(Locale.ROOT);
    if (key.isEmpty()) return;
    biomeCounts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    totalSamples.incrementAndGet();
  }

  /**
   * Dispatches one occupancy read per online player to owning thread and records resolved biome.
   *
   * @param players players to sample
   * @return list of futures completing with resolved biome name or {@code null}
   */
  public List<CompletableFuture<String>> sample(Collection<? extends RTPPlayer> players) {
    List<CompletableFuture<String>> futures = new ArrayList<>();
    if (players == null) return futures;
    for (RTPPlayer player : players) {
      if (player == null || !player.isOnline()) continue;
      CompletableFuture<String> future = new CompletableFuture<>();
      futures.add(future);
      RTPRunnable task =
          new RTPRunnable(
              () -> {
                try {
                  RTPLocation loc = player.getLocation();
                  if (loc == null || loc.world() == null) {
                    future.complete(null);
                    return;
                  }
                  RTPWorld<?> world = loc.world();
                  String biome = world.getBiome(loc.x(), loc.y(), loc.z());
                  record(biome);
                  future.complete(biome);
                } catch (Throwable t) {
                  future.completeExceptionally(t);
                }
              });
      task.setTarget(player);
      try {
        RTP.scheduler.runTaskForPlayer(player, task, 0L);
      } catch (Throwable t) {
        // Never let a single mis-scheduled player abort the whole sweep.
        RTP.log(Level.FINE, "[RTP] biome activity sample dispatch failed", t);
        future.completeExceptionally(t);
      }
    }
    return futures;
  }

  /**
   * Returns the total number of occupancy observations recorded since the last reset.
   *
   * @return total sample count
   */
  public long totalSamples() {
    return totalSamples.get();
  }

  /**
   * Returns the number of distinct biomes observed since the last reset.
   *
   * @return distinct biome count
   */
  public int distinctBiomes() {
    return biomeCounts.size();
  }

  /**
   * Returns an occupancy snapshot ordered by descending sample count (ties
   * broken by biome name for stable output). The returned map is a copy and is
   * safe to iterate without further synchronization.
   *
   * @return a copy of the current biome counts, ordered by descending count
   */
  public Map<String, Long> snapshot() {
    List<Map.Entry<String, Long>> entries = new ArrayList<>(biomeCounts.size());
    for (Map.Entry<String, AtomicLong> e : biomeCounts.entrySet()) {
      entries.add(Map.entry(e.getKey(), e.getValue().get()));
    }
    entries.sort(
        (a, b) -> {
          int byCount = Long.compare(b.getValue(), a.getValue());
          return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
    LinkedHashMap<String, Long> out = new LinkedHashMap<>();
    for (Map.Entry<String, Long> e : entries) out.put(e.getKey(), e.getValue());
    return out;
  }

  /** Clears all accumulated occupancy data. */
  public void reset() {
    biomeCounts.clear();
    totalSamples.set(0L);
  }
}
