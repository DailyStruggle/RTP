package io.github.dailystruggle.rtp.proxy.common.selector.curve;

/**
 * Pure, deterministic mapping from a normalized input in {@code [0,1]} to a
 * normalized output in {@code [0,1]}. Inputs outside the range are clamped by
 * the implementation; outputs are likewise clamped.
 *
 * <p>Curves are the per-term shaping function in the weighted-average scoring
 * formula from rtp-proxy-ADR-004:</p>
 *
 * <pre>
 * score(b) = ( Sigma_i  weight_i * curve_i( normalize_i( metric_i(b) ) ) ) / backendWeight(b)
 * </pre>
 *
 * <p>Implementations shall be pure (no I/O, no mutable global state) and
 * thread-safe (REQ-RTP-PROXY-COMMON-002).</p>
 */
public interface Curve {

    /**
     * Maps a normalized input in {@code [0,1]} (clamped) to a normalized
     * output in {@code [0,1]} (clamped).
     */
    double apply(double normalizedInput);

    /** Stable lowercase identifier for YAML / logs (e.g. {@code "linear"}). */
    String id();

    /** Clamp helper shared by all implementations. */
    static double clamp01(double v) {
        if (v < 0.0) return 0.0;
        if (v > 1.0) return 1.0;
        if (Double.isNaN(v)) return 0.0;
        return v;
    }
}
