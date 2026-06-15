package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Heap-pressure gate for background cache work.
 *
 * <p>RTP fills its location caches (cold-&gt;hot promotion in
 * {@link io.github.dailystruggle.rtp.common.selection.region.Region#execute(long)}
 * and the L3 backlog refill) on every async pulse. Each kept location retains a
 * chunk ticket, so an unbounded fill on a small-heap server steadily grows the
 * retained set until the JVM spends most of its time in garbage collection and
 * the server appears to hang ("server becomes unresponsive" with no console
 * error). This monitor lets the fill paths back off while the heap is under
 * pressure: background <em>generation</em> pauses, while serving already-cached
 * locations to waiting players keeps working.
 *
 * <p>The fraction is sampled from {@link Runtime} (used / max heap) and cached
 * for a short interval so the per-pulse check is effectively free and never
 * triggers extra allocation. The threshold is read from
 * {@link PerformanceKeys#maxHeapPercent} (percent of max heap; default 85).
 * A value at or below 0, or at or above 100, disables the gate.
 */
public final class HeapPressureMonitor {
  /** Minimum interval between fresh heap samples. */
  private static final long SAMPLE_INTERVAL_MS = 250L;
  /** Minimum interval between throttled "under pressure" warnings. */
  private static final long WARN_INTERVAL_MS = 30_000L;
  /** Default threshold (percent of max heap) when the config knob is absent. */
  private static final double DEFAULT_MAX_HEAP_PERCENT = 85.0;

  private static final AtomicLong lastSampleMs = new AtomicLong(0L);
  private static final AtomicLong lastWarnMs = new AtomicLong(0L);
  private static volatile boolean cachedUnderPressure = false;
  private static volatile double cachedUsedPercent = 0.0;

  private HeapPressureMonitor() {}

  /**
   * Returns the configured heap-usage threshold as a fraction of max heap in
   * the range (0.0, 1.0]. Returns a value &gt;= 1.0 when the gate is disabled.
   */
  private static double thresholdFraction() {
    double percent = DEFAULT_MAX_HEAP_PERCENT;
    try {
      if (RTP.configs != null) {
        @SuppressWarnings("unchecked")
        ConfigParser<PerformanceKeys> perf =
            (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        if (perf != null) {
          percent = perf.getNumber(PerformanceKeys.maxHeapPercent, DEFAULT_MAX_HEAP_PERCENT)
              .doubleValue();
        }
      }
    } catch (Throwable t) {
      percent = DEFAULT_MAX_HEAP_PERCENT;
    }
    if (percent <= 0.0 || percent >= 100.0) return Double.MAX_VALUE; // gate disabled
    return percent / 100.0;
  }

  /**
   * Whether the JVM heap is currently above the configured pressure threshold.
   * The reading is cached for {@value #SAMPLE_INTERVAL_MS} ms, so this is cheap
   * to call once (or many times) per pulse.
   *
   * @return {@code true} when background cache generation should pause
   */
  public static boolean underPressure() {
    long now = System.currentTimeMillis();
    long last = lastSampleMs.get();
    if (now - last >= SAMPLE_INTERVAL_MS && lastSampleMs.compareAndSet(last, now)) {
      double threshold = thresholdFraction();
      if (threshold == Double.MAX_VALUE) {
        cachedUnderPressure = false;
        cachedUsedPercent = 0.0;
      } else {
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long used = runtime.totalMemory() - runtime.freeMemory();
        double usedFraction = (max <= 0L) ? 0.0 : ((double) used / (double) max);
        cachedUsedPercent = usedFraction * 100.0;
        cachedUnderPressure = usedFraction >= threshold;
        if (cachedUnderPressure) {
          long lastWarn = lastWarnMs.get();
          if (now - lastWarn >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(lastWarn, now)) {
            RTP.log(Level.WARNING,
                String.format(
                    "[RTP] Heap usage %.1f%% of max exceeds maxHeapPercent threshold %.1f%%; "
                        + "pausing background cache generation until memory is reclaimed. "
                        + "Lower cacheCap/activeChunkCap or raise the JVM -Xmx if this persists.",
                    cachedUsedPercent, threshold * 100.0));
          }
        }
      }
    }
    return cachedUnderPressure;
  }

  /** Most recent sampled heap usage as a percent of max heap (for diagnostics). */
  public static double lastUsedPercent() {
    return cachedUsedPercent;
  }
}
