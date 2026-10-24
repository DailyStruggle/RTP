package io.github.dailystruggle.rtp.anvil;

import java.nio.file.Path;
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
 * {@code ADR-016 section 10}.
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

  /** Probes that failed with an unrecognized or unsupported compression scheme (ADR-121 CBIT). */
  public static final AtomicLong unsupportedCompressions = new AtomicLong();

  /** Probes that encountered an unparseable chunk section, heightmap, or corrupt entry (ADR-121 CBIT). */
  public static final AtomicLong unparseableSections = new AtomicLong();

  private static volatile String lastUnsupportedCompressionRegion = "";
  private static volatile String lastUnsupportedCompressionChunk = "";
  private static volatile String lastUnsupportedCompressionDetail = "";

  private static volatile String lastUnparseableSectionRegion = "";
  private static volatile String lastUnparseableSectionChunk = "";
  private static volatile String lastUnparseableSectionReason = "";

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

  /**
   * Records an unrecognized or unsupported compression scheme bailout with its region path and chunk coordinate.
   */
  public static void recordUnsupportedCompression(Path regionPath, int cx, int cz, String detail) {
    unsupportedCompressions.incrementAndGet();
    lastUnsupportedCompressionRegion = regionPath == null ? "<unknown>" : regionPath.toString();
    lastUnsupportedCompressionChunk = "(" + cx + "," + cz + ")";
    lastUnsupportedCompressionDetail = detail == null ? "" : detail;
  }

  /**
   * Records an unparseable chunk section or corrupt entry bailout with its region path and chunk coordinate.
   */
  public static void recordUnparseableSection(Path regionPath, int cx, int cz, String reason) {
    unparseableSections.incrementAndGet();
    lastUnparseableSectionRegion = regionPath == null ? "<unknown>" : regionPath.toString();
    lastUnparseableSectionChunk = "(" + cx + "," + cz + ")";
    lastUnparseableSectionReason = reason == null ? "" : reason;
  }

  public static String lastUnsupportedCompressionRegion() {
    return lastUnsupportedCompressionRegion;
  }

  public static String lastUnsupportedCompressionChunk() {
    return lastUnsupportedCompressionChunk;
  }

  public static String lastUnsupportedCompressionDetail() {
    return lastUnsupportedCompressionDetail;
  }

  public static String lastUnparseableSectionRegion() {
    return lastUnparseableSectionRegion;
  }

  public static String lastUnparseableSectionChunk() {
    return lastUnparseableSectionChunk;
  }

  public static String lastUnparseableSectionReason() {
    return lastUnparseableSectionReason;
  }

  /** Clears CBIT bailout counters and last-seen metadata for test isolation. */
  public static void resetBailouts() {
    unsupportedCompressions.set(0);
    unparseableSections.set(0);
    lastUnsupportedCompressionRegion = "";
    lastUnsupportedCompressionChunk = "";
    lastUnsupportedCompressionDetail = "";
    lastUnparseableSectionRegion = "";
    lastUnparseableSectionChunk = "";
    lastUnparseableSectionReason = "";
  }
}
