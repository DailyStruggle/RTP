package io.github.dailystruggle.rtp.anvil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-global telemetry for ADR-016 section 13.1 biome-source attribution.
 * Each {@code getBiome(x,y,z)} increments exactly one reason bucket; the
 * legacy {@link #anvilHits} / {@link #liveHits} totals are updated in lock-step
 * so the two views cannot drift. See {@link Reasons} for the canonical keys.
 * Surfaced via {@code rtp test biome-source}. Atomics + ConcurrentHashMap
 * only - no chunk I/O (S-005).
 */
public final class BiomeSourceMetrics {

  /** Canonical reason keys for the reason-keyed counter map. */
  public static final class Reasons {
    public static final String ANVIL_HIT = "anvil-hit";
    public static final String NO_VIEW_CACHED = "no-view-cached";
    public static final String VIEW_MISSING_BIOME = "view-missing-biome";
    public static final String ANVIL_THROW = "anvil-throw";

    private Reasons() {}
  }

  /** Biome reads answered from a cached {@link AnvilChunkView}. */
  public static final AtomicLong anvilHits = new AtomicLong();

  /** Biome reads that fell through to the live {@code World#getBiome}. */
  public static final AtomicLong liveHits = new AtomicLong();

  private static final ConcurrentHashMap<String, AtomicLong> REASONS = new ConcurrentHashMap<>();

  private BiomeSourceMetrics() {}

  /**
   * Legacy two-way entry point &mdash; retained so external callers and the
   * older {@code TestBiomeSourceCmdTest} continue to compile. Delegates to
   * {@link #record(String)} with the canonical reason {@link Reasons#ANVIL_HIT}
   * or {@link Reasons#NO_VIEW_CACHED}.
   */
  public static void record(boolean fromAnvil) {
    record(fromAnvil ? Reasons.ANVIL_HIT : Reasons.NO_VIEW_CACHED);
  }

  /**
   * Reason-keyed entry point. Bumps the matching reason counter and also
   * updates the high-level {@link #anvilHits} / {@link #liveHits} totals so
   * the two views cannot drift. Unknown reason strings are accepted verbatim
   * and counted as live-tier (defensive: callers should use {@link Reasons}
   * constants).
   */
  public static void record(String reason) {
    if (reason == null) reason = Reasons.NO_VIEW_CACHED;
    REASONS.computeIfAbsent(reason, k -> new AtomicLong()).incrementAndGet();
    if (Reasons.ANVIL_HIT.equals(reason)) {
      anvilHits.incrementAndGet();
    } else {
      liveHits.incrementAndGet();
    }
  }

  /**
   * Unmodifiable snapshot of the reason-keyed counters, iteration-order
   * stable (LinkedHashMap) so console output is predictable.
   */
  public static Map<String, Long> reasonCounters() {
    LinkedHashMap<String, Long> out = new LinkedHashMap<>();
    // Emit canonical reasons first, in a stable order, even when zero.
    for (String r : new String[] {
        Reasons.ANVIL_HIT,
        Reasons.NO_VIEW_CACHED,
        Reasons.VIEW_MISSING_BIOME,
        Reasons.ANVIL_THROW}) {
      AtomicLong a = REASONS.get(r);
      out.put(r, a == null ? 0L : a.get());
    }
    // Then any non-canonical reasons that callers might have introduced.
    for (Map.Entry<String, AtomicLong> e : REASONS.entrySet()) {
      if (!out.containsKey(e.getKey())) {
        out.put(e.getKey(), e.getValue().get());
      }
    }
    return Collections.unmodifiableMap(out);
  }

  /** Reset all counters. Intended for tests only. */
  public static void resetForTest() {
    anvilHits.set(0L);
    liveHits.set(0L);
    REASONS.clear();
  }
}
