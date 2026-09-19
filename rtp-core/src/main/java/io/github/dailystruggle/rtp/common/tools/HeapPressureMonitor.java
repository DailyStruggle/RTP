package io.github.dailystruggle.rtp.common.tools;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.metrics.HeapSampler;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Heap-pressure gate for background cache generation and promotion tasks.
 * Samples generational tenured/old memory usage against {@link PerformanceKeys#maxHeapPercent},
 * preventing false pauses caused by ephemeral young-generation allocation churn (e.g. prescan).
 */
public final class HeapPressureMonitor {
  /** Minimum interval between fresh heap samples. */
  private static final long SAMPLE_INTERVAL_MS = 250L;
  /** Minimum interval between throttled "under pressure" warnings. */
  private static final long WARN_INTERVAL_MS = 30_000L;
  /** Default threshold (percent of max heap) when the config knob is absent. */
  private static final double DEFAULT_MAX_HEAP_PERCENT = 85.0;

  /** Minimum absolute free heap headroom (512 MiB). If available heap headroom exceeds this,
   * the server is not under imminent OOM pressure regardless of percentage on large heaps. */
  private static final long MIN_ABSOLUTE_HEADROOM_BYTES = 512L * 1024L * 1024L;

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
        // Prioritize tenured/old generation pool to isolate retained memory from young-gen churn
        long tenuredUsed = HeapSampler.tenuredUsedBytes();
        long tenuredMax = HeapSampler.tenuredMaxBytes();

        // Also check total heap bounds
        Runtime runtime = Runtime.getRuntime();
        long totalMax = runtime.maxMemory();
        long totalUsed = runtime.totalMemory() - runtime.freeMemory();
        long totalFree = (totalMax > totalUsed) ? (totalMax - totalUsed) : 0L;

        // Ensure tenuredMax is at least totalMax if tenured pool max is unconstrained/undefined
        long effectiveTenuredMax = Math.max(tenuredMax, totalMax);

        // If overall JVM has ample absolute headroom (>= 512 MiB), do not flag as under pressure
        if (totalFree >= MIN_ABSOLUTE_HEADROOM_BYTES && totalMax >= 2L * MIN_ABSOLUTE_HEADROOM_BYTES) {
          double fraction = (effectiveTenuredMax > 0L) ? ((double) tenuredUsed / (double) effectiveTenuredMax) : 0.0;
          cachedUsedPercent = fraction * 100.0;
          // Only trip if tenured/old generation itself is critically saturated (> threshold)
          cachedUnderPressure = fraction >= threshold;
        } else {
          // Constrained heap (< 512 MiB total free): evaluate both tenured and total heap
          double tenuredFrac = (effectiveTenuredMax > 0L) ? ((double) tenuredUsed / (double) effectiveTenuredMax) : 0.0;
          double totalFrac = (totalMax > 0L) ? ((double) totalUsed / (double) totalMax) : 0.0;
          double effectiveFrac = Math.max(tenuredFrac, totalFrac);
          cachedUsedPercent = effectiveFrac * 100.0;
          cachedUnderPressure = effectiveFrac >= threshold;
        }

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
