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
 * In-memory accumulator of <em>biome occupancy</em> - how much of their time
 * online players spend standing in each biome, sampled periodically over the
 * server's lifetime. This is deliberately distinct from teleport landings
 * (which mirror biome surface area) and from the player-requested biome filter
 * (players cannot generally pick a destination biome): it answers the
 * world-designer question "where do players actually choose to stay?".
 *
 * <p><b>Collector model.</b> {@link #sample(Collection)} does not read player
 * positions itself. Reading a player's location and biome must happen on the
 * thread that owns that player (the entity-region thread on Folia; the main
 * thread elsewhere), so each player is dispatched to its owning thread via
 * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskForPlayer
 * RTP.scheduler.runTaskForPlayer(...)} and reports back through its own
 * {@link CompletableFuture}. The futures are independent, so a slow or stalled
 * region never blocks the collection of the others. Counts accumulate as each
 * future resolves; callers may ignore the returned futures (fire-and-forget) or
 * await them for deterministic tests.
 *
 * <p><b>Lifetime.</b> Counts live only in memory and reset on restart/reload
 * (no schema, no persistence) - matching the project's flat-file/in-memory
 * metrics posture and keeping behaviour identical between the full and lite
 * editions.
 *
 * <p>All mutating operations are thread-safe.
 */
public final class BiomeActivityTracker {
  private final ConcurrentHashMap<String, AtomicLong> biomeCounts = new ConcurrentHashMap<>();
  private final AtomicLong totalSamples = new AtomicLong();

  /**
   * Records a single occupancy observation for {@code biome}. {@code null} or
   * blank biome names are ignored (the player's chunk could not be read).
   * Biome names are upper-cased so casing differences across platforms collapse
   * to one bucket.
   */
  public void record(String biome) {
    if (biome == null) return;
    String key = biome.trim().toUpperCase(Locale.ROOT);
    if (key.isEmpty()) return;
    biomeCounts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    totalSamples.incrementAndGet();
  }

  /**
   * Dispatches one occupancy read per online player to that player's owning
   * thread and records the resolved biome as each read completes.
   *
   * @param players the players to sample; {@code null} entries and offline
   *                players are skipped
   * @return one future per dispatched player, each completing with the resolved
   *         biome name (or {@code null} if the location/world could not be read)
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

  /** Total number of occupancy observations recorded since the last reset. */
  public long totalSamples() {
    return totalSamples.get();
  }

  /** Number of distinct biomes observed since the last reset. */
  public int distinctBiomes() {
    return biomeCounts.size();
  }

  /**
   * Returns an occupancy snapshot ordered by descending sample count (ties
   * broken by biome name for stable output). The returned map is a copy and is
   * safe to iterate without further synchronization.
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
