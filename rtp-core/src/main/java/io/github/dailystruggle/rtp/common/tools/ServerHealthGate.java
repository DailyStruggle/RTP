package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.metrics.api.MetricsBinding;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;

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
          RTP.log(Level.WARNING, String.format(Locale.ROOT,
              "[RTP] Server tick unhealthy (%s; minTPS %.2f, maxMSPT %.1f); "
                  + "pausing background cache generation. Waiting players are still served.",
              reading, minTps, maxMspt));
        }
      } else if (was) {
        RTP.log(Level.INFO, String.format(Locale.ROOT,
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
  }

  private static MetricsBinding binding() {
    try {
      return RTP.metrics.getBinding();
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
      if (RTP.serverAccessor != null) {
        double fallback = RTP.serverAccessor.getTPS(20);
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
      if (RTP.configs != null) {
        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> perf =
            (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        if (perf != null) return perf.getNumber(key, def).doubleValue();
      }
    } catch (Throwable ignored) {
      // fall through to default
    }
    return def;
  }
}
