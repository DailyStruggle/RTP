package io.github.dailystruggle.rtp.common.metrics;

/**
 * Immutable per-(region, path) view of the probe-first cost governor (ADR-109).
 * Times are wall-clock milliseconds (moving averages); {@code NaN} until sampled.
 *
 * @param region            region name
 * @param path              candidate path ({@code fill} or {@code consume})
 * @param mode              current decision: {@code PROBE} (column probe before the full load) or {@code SKIP}
 * @param warmingUp         {@code true} until both arms have the minimum sample count
 * @param rejectRate        share of probes that rejected the candidate
 * @param probeMs           probe latency
 * @param loadAfterProbeMs  full-load latency after a probe passed or was inconclusive
 * @param loadDirectMs      full-load latency with the probe skipped
 * @param costProbeMs       expected cost per candidate with the probe
 * @param costDirectMs      expected cost per candidate without the probe
 * @param zScore            (costProbe - costDirect) / standard error; positive favours skipping
 * @param skipShare         recent share of candidates that skipped the probe
 * @param flips             decision changes since start
 * @param probeSamples      probes measured
 * @param directSamples     direct full loads measured
 */
public record ProbeGovernorRow(
        String region,
        String path,
        String mode,
        boolean warmingUp,
        double rejectRate,
        double probeMs,
        double loadAfterProbeMs,
        double loadDirectMs,
        double costProbeMs,
        double costDirectMs,
        double zScore,
        double skipShare,
        long flips,
        long probeSamples,
        long directSamples) {
}
