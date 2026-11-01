package io.github.dailystruggle.rtp.common.governor;

/**
 * Sampling state of a telemetry input consumed by {@link ComputeGovernor} (ADR-087 Principle 5, Invariant I6).
 *
 * @governedBy ADR-087 (Adaptive Compute Governor)
 */
public enum SignalState {
    /** Signal has never been sampled on this platform/runtime; governed knobs remain at configured values. */
    NEVER_SAMPLED,
    /** Signal has a current reading within its freshness window. */
    FRESH,
    /** Signal was previously sampled but its reading is now older than the freshness threshold; knobs hold and never climb. */
    STALE
}
