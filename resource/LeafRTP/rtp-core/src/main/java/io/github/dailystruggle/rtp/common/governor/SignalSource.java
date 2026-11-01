package io.github.dailystruggle.rtp.common.governor;

/**
 * Injectable signal sampler supplying {@link ComputeSignals} to {@link ComputeGovernor} (ADR-087 Principle 7).
 *
 * @governedBy ADR-087 (Adaptive Compute Governor)
 */
@FunctionalInterface
public interface SignalSource {

    /**
     * Samples current telemetry, configuration ceilings, and regional demand at {@code nowNanos}.
     *
     * @param nowNanos monotonic clock reading in nanoseconds
     * @return immutable signal snapshot
     */
    ComputeSignals sample(long nowNanos);
}
