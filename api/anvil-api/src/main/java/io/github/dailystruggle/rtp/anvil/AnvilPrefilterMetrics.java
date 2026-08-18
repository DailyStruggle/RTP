package io.github.dailystruggle.rtp.anvil;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-global telemetry counters for the ADR-016 Anvil read-only
 * pre-filter. Each counter is incremented exactly once per probe, keyed by the
 * returned {@link Verdict}:
 * <ul>
 *   <li>{@link #accepts} - probe returned {@link Verdict#ACCEPT}
 *       (candidate fell through to the live-load / Anvil-backed chunk path).</li>
 *   <li>{@link #rejects} - probe returned {@link Verdict#REJECT}
 *       (candidate short-circuited off-thread without a live chunk load).</li>
 *   <li>{@link #unknowns} - probe returned {@link Verdict#UNKNOWN}
 *       (decode failure / unsupported format / ungenerated chunk; live load
 *       remains authoritative).</li>
 * </ul>
 *
 * <p>The counters are plain {@link AtomicLong}s so that increments from the
 * pre-filter's {@code ForkJoinPool.commonPool()} worker threads and reads
 * from the {@code rtp test anvil-prefilter} diagnostic command are both
 * lock-free and safe under any scheduler. Reset semantics are intentionally
 * left to tests (see {@code TestAnvilPrefilterCmdTest}); production code
 * only ever increments.
 *
 * <p>Exposed via {@code TestAnvilPrefilterCmd.snapshot()} per
 * {@code ADR-016 §10}.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>S-005:</b> no chunk I/O, no main-thread wait - three atomics only.</li>
 * </ul>
 */
public final class AnvilPrefilterMetrics {

  /** Probes that returned {@link Verdict#ACCEPT}. */
  public static final AtomicLong accepts = new AtomicLong();

  /** Probes that returned {@link Verdict#REJECT}. */
  public static final AtomicLong rejects = new AtomicLong();

  /** Probes that returned {@link Verdict#UNKNOWN}. */
  public static final AtomicLong unknowns = new AtomicLong();

  private AnvilPrefilterMetrics() {}

  /**
   * Records the given verdict in the corresponding counter. Callers in
   * {@code AnvilPrefilter} / {@code BukkitRTPWorld} invoke this exactly once
   * per probe so the three counters always sum to the total probe count.
   */
  public static void record(Verdict verdict) {
    if (verdict == null) return;
    switch (verdict) {
      case ACCEPT -> accepts.incrementAndGet();
      case REJECT -> rejects.incrementAndGet();
      case UNKNOWN -> unknowns.incrementAndGet();
    }
  }
}
