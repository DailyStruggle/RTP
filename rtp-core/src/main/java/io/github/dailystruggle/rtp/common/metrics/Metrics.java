package io.github.dailystruggle.rtp.common.metrics;

/**
 * Platform-portable metrics facade. The single read-only entry point for runtime health
 * signals consumed by {@code rtp test full}, the {@code /rtp info} health block, the
 * bStats integration, and the multi-server telemetry publisher.
 *
 * <p>See {@code docs/dev/METRICS_PLAN.md} for the full plan, phasing, and SPI contract.
 *
 * <p>Implementations shall be:
 * <ul>
 *     <li>Snapshot-based &mdash; each call returns a fresh immutable {@link MetricsSnapshot};
 *         no streaming, no callbacks.</li>
 *     <li>Non-blocking &mdash; readers shall not perform I/O, acquire contended locks,
 *         or call platform APIs that may park the calling thread (S-005 spirit).</li>
 *     <li>Side-effect free for the caller &mdash; calling {@link #snapshot()} shall not
 *         mutate the underlying samplers.</li>
 * </ul>
 *
 * <p>Phase M0 ships this interface, the {@link MetricsSnapshot} carrier, and the
 * {@link #NOOP} default. Platform-binding implementations land in Phase M1+.
 */
public interface Metrics {

    /**
     * Returns the current health snapshot. Cheap to call repeatedly; callers may cache
     * the result for a tick if they need to read multiple fields atomically with respect
     * to one another.
     */
    MetricsSnapshot snapshot();

    /**
     * Default no-op implementation used before any platform binding has been installed.
     * Returns a snapshot with all platform-sourced fields set to {@link MetricsSnapshot#UNSAMPLED}
     * (or equivalent zero-sentinel for integers) so callers can render a deterministic
     * "metrics unavailable" view rather than crashing.
     *
     * <p>Per the {@code Metrics SPI &mdash; require-by-contract} rule, addons that need a
     * real binding should detect {@code NOOP} via reference equality and degrade
     * gracefully; the SPI deliberately does not throw {@link IllegalStateException} here
     * because read-only health probes must remain safe to call at any lifecycle stage.
     */
    Metrics NOOP = () -> new MetricsSnapshot(
            MetricsSnapshot.UNSAMPLED,
            MetricsSnapshot.UNSAMPLED,
            MetricsSnapshot.UNSAMPLED,
            MetricsSnapshot.UNSAMPLED,
            0,
            0,
            HeapSampler.heapUsedBytes(),
            HeapSampler.heapMaxBytes(),
            0,
            0,
            0,
            0,
            Double.NaN,
            -1,
            System.currentTimeMillis()
    );
}
