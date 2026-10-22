package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.metrics.api.FoliaRegionSample;
import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;

import io.github.dailystruggle.rtp.common.metrics.CoreMetrics;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.logging.Level;

/**
 * Tick-health gate for background cache work ({@code performance.yml#minTPS}, {@code #maxMSPT}).
 *
 * <p>{@link #shouldPause()} is the server-wide gate. Single-loop hosts read the server values
 * ({@link MetricsBinding#worstTps1m()} / {@link MetricsBinding#worstMspt()}). Regional hosts
 * ({@link MetricsBinding#regionalTicking()}, Folia) read the mean TPS and skip MSPT: their sampler
 * measures tick intervals, not work, and one slow region must not pause the healthy ones.
 *
 * <p>{@link #regionUnhealthy(UUID, int, int)} is the per-region gate (ADR-087 regional circuit
 * breaker): a slow region stops receiving background work while healthy regions continue.
 *
 * <p>Stalled tick: hosts that call {@link #tickStarted()} / {@link #tickFinished()} also pause
 * while one tick has run for more than {@value #STALL_MS} ms. TPS / MSPT readings freeze during a
 * stall, so they cannot detect a main thread blocked waiting on the chunk system. A server that
 * is paused between ticks (vanilla {@code pause-when-empty-seconds}) is not stalled.
 *
 * <p>Serving players already waiting is never gated. Unsampled metrics fail open.
 */
public final class ServerHealthGate {
  private static final long SAMPLE_INTERVAL_MS = 250L;
  private static final long WARN_INTERVAL_MS = 30_000L;
  public static final double DEFAULT_MIN_TPS = 19.0;
  public static final double DEFAULT_MAX_MSPT = 45.0;
  /** Resume only once TPS clears {@code minTPS} by this margin, so the gate does not flap. */
  static final double TPS_HYSTERESIS = 0.5;
  /** Resume only once MSPT drops this far below {@code maxMSPT}. */
  static final double MSPT_HYSTERESIS = 5.0;
  /** A tick that has run longer than this counts as stalled. */
  static final long STALL_MS = 1_000L;

  /** {@link System#nanoTime()} at the start of the running tick; 0 when no tick is running. */
  private static final AtomicLong tickStartNanos = new AtomicLong(0L);
  private static final AtomicLong lastStallWarnMs = new AtomicLong(0L);

  private static final AtomicLong lastSampleMs = new AtomicLong(0L);
  private static final AtomicLong lastWarnMs = new AtomicLong(0L);
  private static volatile boolean cachedPaused = false;
  private static volatile double lastTps = Double.NaN;
  private static volatile double lastMspt = Double.NaN;
  /** Thresholds for {@link #regionUnhealthy}, refreshed at most every {@value #SAMPLE_INTERVAL_MS} ms. */
  private static final AtomicLong lastThresholdMs = new AtomicLong(0L);
  private static volatile double cachedMinTps = DEFAULT_MIN_TPS;
  private static volatile double cachedMaxMspt = DEFAULT_MAX_MSPT;

  /** Test seam: overrides the TPS / MSPT readings; {@code null} uses the metrics binding. */
  static volatile DoubleSupplier tpsOverride;
  static volatile DoubleSupplier msptOverride;

  private ServerHealthGate() {}

  /**
   * Whether background cache generation should pause because the server tick is unhealthy.
   * Cached for {@value #SAMPLE_INTERVAL_MS} ms; safe to call from any thread, many times per pulse.
   */
  public static boolean shouldPause() {
    if (tickStalled(System.nanoTime())) {
      warnStalled();
      return true;
    }
    long now = System.currentTimeMillis();
    long last = lastSampleMs.get();
    if (now - last >= SAMPLE_INTERVAL_MS && lastSampleMs.compareAndSet(last, now)) {
      boolean was = cachedPaused;
      double minTps = configured(PerformanceKeys.minTPS, DEFAULT_MIN_TPS);
      double maxMspt = configured(PerformanceKeys.maxMSPT, DEFAULT_MAX_MSPT);
      cachedMinTps = minTps;
      cachedMaxMspt = maxMspt;
      lastThresholdMs.set(now);
      MetricsBinding binding = binding();
      boolean regional = isRegional(binding);
      double tps = readTps(binding, regional);
      // Regional MSPT is a tick interval; a single region's overrun is regionUnhealthy's job.
      double mspt = regional ? Double.NaN : readMspt(binding);
      lastTps = tps;
      lastMspt = mspt;
      boolean paused = evaluate(was, tps, mspt, minTps, maxMspt);
      cachedPaused = paused;
      String reading = regional
          ? String.format(Locale.ROOT, "mean TPS %.2f across regions", tps)
          : String.format(Locale.ROOT, "TPS %.2f, MSPT %.1f", tps, mspt);
      if (paused) {
        long lastWarn = lastWarnMs.get();
        if (now - lastWarn >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(lastWarn, now)) {
          String detail = "";
          if (regional) {
            try {
              detail = formatRegionalDetail(binding.foliaRegions());
            } catch (Throwable ignored) {
              // fail open on diagnostic read
            }
          }
          RTPRuntime.log(Level.WARNING, String.format(Locale.ROOT,
              "[RTP] Server tick unhealthy (%s%s; minTPS %.2f, maxMSPT %.1f); "
                  + "pausing background cache generation. Waiting players are still served.",
              reading, detail, minTps, maxMspt));
        }
      } else if (was) {
        RTPRuntime.log(Level.INFO, String.format(Locale.ROOT,
            "[RTP] Server tick recovered (%s); resuming background cache generation.", reading));
      }
    }
    return cachedPaused;
  }

  /**
   * Whether the tick loop owning chunk {@code (chunkX, chunkZ)} is too slow for background work.
   * Single-loop hosts return {@link #shouldPause()}. Regional hosts read that region alone, with no
   * hysteresis (the sampler EMA is already smooth); unsampled regions fail open. Non-blocking, any
   * thread: called per regional task slice.
   */
  public static boolean regionUnhealthy(UUID worldId, int chunkX, int chunkZ) {
    MetricsBinding binding = binding();
    if (!isRegional(binding)) return shouldPause();
    refreshThresholds();
    double tps;
    double mspt;
    try {
      tps = sampled(binding.regionTps1m(worldId, chunkX, chunkZ));
      mspt = sampled(binding.regionMspt(worldId, chunkX, chunkZ));
    } catch (Throwable ignored) {
      return false;
    }
    return evaluate(false, tps, mspt, cachedMinTps, cachedMaxMspt);
  }

  /**
   * Pure gate decision with hysteresis. {@code minTps <= 0} or {@code maxMspt <= 0} disables that
   * half; NaN readings never pause.
   */
  static boolean evaluate(boolean wasPaused, double tps, double mspt, double minTps, double maxMspt) {
    boolean tpsBad = false;
    if (minTps > 0.0 && !Double.isNaN(tps)) {
      double resumeAt = Math.min(minTps + TPS_HYSTERESIS, 20.0);
      tpsBad = wasPaused ? tps < resumeAt : tps < minTps;
    }
    boolean msptBad = false;
    if (maxMspt > 0.0 && !Double.isNaN(mspt)) {
      double resumeBelow = Math.max(maxMspt - MSPT_HYSTERESIS, 0.0);
      msptBad = wasPaused ? mspt > resumeBelow : mspt > maxMspt;
    }
    return tpsBad || msptBad;
  }

  /**
   * Records that a server tick started. Called from the server thread by single-loop hosts
   * (Spigot, Fabric, NeoForge); never on regional hosts.
   */
  public static void tickStarted() {
    long now = System.nanoTime();
    tickStartNanos.set(now == 0L ? 1L : now);
  }

  /** Records that the running server tick finished (or that the server is idle between ticks). */
  public static void tickFinished() {
    tickStartNanos.set(0L);
  }

  /** Whether a tick is running and has lasted more than {@value #STALL_MS} ms. */
  public static boolean tickStalled(long nowNanos) {
    long start = tickStartNanos.get();
    return start != 0L && nowNanos - start > STALL_MS * 1_000_000L;
  }

  private static void warnStalled() {
    long now = System.currentTimeMillis();
    long lastWarn = lastStallWarnMs.get();
    if (now - lastWarn < WARN_INTERVAL_MS || !lastStallWarnMs.compareAndSet(lastWarn, now)) return;
    long start = tickStartNanos.get();
    long stalledMs = start == 0L ? 0L : (System.nanoTime() - start) / 1_000_000L;
    RTPRuntime.log(Level.WARNING, String.format(Locale.ROOT,
        "[RTP] The current server tick has run for %d ms; pausing background cache generation."
            + " Waiting players are still served.", stalledMs));
  }

  public static double lastTps() { return lastTps; }

  public static double lastMspt() { return lastMspt; }

  /** Test seam: pins the TPS / MSPT readings and forces a fresh sample on the next call. */
  public static void setReadingsForTesting(DoubleSupplier tps, DoubleSupplier mspt) {
    tpsOverride = tps;
    msptOverride = mspt;
    lastSampleMs.set(0L);
  }

  public static void resetForTesting() {
    lastSampleMs.set(0L);
    lastWarnMs.set(0L);
    lastThresholdMs.set(0L);
    cachedMinTps = DEFAULT_MIN_TPS;
    cachedMaxMspt = DEFAULT_MAX_MSPT;
    cachedPaused = false;
    lastTps = Double.NaN;
    lastMspt = Double.NaN;
    tpsOverride = null;
    msptOverride = null;
    tickStartNanos.set(0L);
    lastStallWarnMs.set(0L);
  }

  /** Test seam: sets the running tick's start ({@link System#nanoTime()} units; 0 = no tick). */
  public static void setTickStartNanosForTesting(long nanos) {
    tickStartNanos.set(nanos);
  }

  static String formatRegionalDetail(List<FoliaRegionSample> samples) {
    if (samples == null || samples.isEmpty()) return "";
    StringBuilder sb = new StringBuilder(" [");
    int limit = Math.min(samples.size(), 8);
    for (int i = 0; i < limit; i++) {
      if (i > 0) sb.append(", ");
      FoliaRegionSample s = samples.get(i);
      sb.append(s.regionId).append(": tps=").append(String.format(Locale.ROOT, "%.1f", s.tps1m));
      if (s.sampleCount > 0) {
        sb.append(", n=").append(s.sampleCount);
      }
      if (s.ageMillis > 0) {
        sb.append(", age=").append(s.ageMillis / 1000).append("s");
      }
    }
    if (samples.size() > limit) {
      sb.append(", +").append(samples.size() - limit).append(" more");
    }
    sb.append("]");
    return sb.toString();
  }

  private static MetricsBinding binding() {
    try {
      return CoreMetrics.instance().getBinding();
    } catch (Throwable ignored) {
      return null;
    }
  }

  private static boolean isRegional(MetricsBinding binding) {
    try {
      return binding != null && binding.regionalTicking();
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static void refreshThresholds() {
    long now = System.currentTimeMillis();
    long last = lastThresholdMs.get();
    if (now - last < SAMPLE_INTERVAL_MS || !lastThresholdMs.compareAndSet(last, now)) return;
    cachedMinTps = configured(PerformanceKeys.minTPS, DEFAULT_MIN_TPS);
    cachedMaxMspt = configured(PerformanceKeys.maxMSPT, DEFAULT_MAX_MSPT);
  }

  /** NaN for unsampled or non-positive readings, so they fail open. */
  private static double sampled(double v) {
    return (Double.isNaN(v) || v <= 0.0) ? Double.NaN : v;
  }

  private static double readTps(MetricsBinding binding, boolean regional) {
    DoubleSupplier o = tpsOverride;
    if (o != null) return o.getAsDouble();
    try {
      double v = Double.NaN;
      if (binding != null) {
        v = regional ? binding.tps1m() : binding.worstTps1m();
      }
      if (!Double.isNaN(v) && v > 0.0) return v;
      if (RTPRuntime.serverAccessor() != null) {
        double fallback = RTPRuntime.serverAccessor().getTPS(20);
        if (fallback > 0.0) return fallback;
      }
    } catch (Throwable ignored) {
      // fail open
    }
    return Double.NaN;
  }

  private static double readMspt(MetricsBinding binding) {
    DoubleSupplier o = msptOverride;
    if (o != null) return o.getAsDouble();
    try {
      return binding == null ? Double.NaN : sampled(binding.worstMspt());
    } catch (Throwable ignored) {
      return Double.NaN;
    }
  }

  private static double configured(PerformanceKeys key, double def) {
    try {
      if (ActiveConfigs.get() != null) {
        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> perf =
            (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        if (perf != null) return perf.getNumber(key, def).doubleValue();
      }
    } catch (Throwable ignored) {
      // fall through to default
    }
    return def;
  }
}
