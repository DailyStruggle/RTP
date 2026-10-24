package io.github.dailystruggle.rtp.common.governor;

/**
 * Diagnostic reason explaining why a {@link ComputeBudget} knob holds its current effective value (ADR-087 Section 2 & 6).
 *
 * @governedBy ADR-087 (Adaptive Compute Governor)
 */
public enum GovernorReason {
    /** Knob is at its operator-configured ceiling (or autoTune is disabled / observe-only). */
    CONFIGURED,
    /** Knob is shaped by single-loop tick-time headroom B_cpu or a tick MSPT/TPS breach. */
    CPU_HEADROOM,
    /** Knob is shaped by process CPU saturation with material RTP async worker contribution. */
    ASYNC_CPU,
    /** Knob is shaped by post-GC tenured heap headroom or a heap threshold breach. */
    HEAP_HEADROOM,
    /** Knob is idled because all regions have zero deliverable demand. */
    IDLE,
    /** Knob is reduced because native chunk-load latency exceeds 2x its windowed minimum. */
    CHUNK_LATENCY,
    /** Knob is reduced due to Folia tick-region overload (ADR-123). */
    REGION_OVERLOAD,
    /** Knob is held at its previous effective value because a driving signal became stale (Invariant I6). */
    STALE_SIGNAL,
    /** Hard gate backstop (minTPS, maxMSPT, maxHeapPercent, or tick stall) is active (Invariant I7). */
    BACKSTOP;

    /** Lowercase space-separated label for {@code /rtp info} diagnostics (ADR-087 Section 6.2). */
    public String displayName() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }
}
