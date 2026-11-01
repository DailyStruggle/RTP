package io.github.dailystruggle.rtp.proxy.common.selector.curve;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Catalogue of built-in {@link Curve} implementations enumerated by
 * rtp-proxy-ADR-004 (linear, exponential, logarithmic, sigmoid, step, power).
 *
 * <p>All curves map {@code [0,1] -> [0,1]} after input clamping and produce a
 * clamped output. Parameter bounds match ADR-004:
 * {@code k in [0.1, 20]}, {@code p in [0.1, 8]}, {@code threshold in [0,1]}.</p>
 */
public final class Curves {

    private Curves() {}

    private static final double K_MIN = 0.1;
    private static final double K_MAX = 20.0;
    private static final double P_MIN = 0.1;
    private static final double P_MAX = 8.0;

    /** Identity: {@code f(x) = x}. */
    public static Curve linear() {
        return Linear.INSTANCE;
    }

    /**
     * Convex curve {@code (exp(k*x) - 1) / (exp(k) - 1)}. Larger {@code k}
     * makes the curve flat near 0 and steep near 1 (penalize only when
     * approaching the cap).
     */
    public static Curve exponential(double k) {
        return new Exponential(clamp(k, K_MIN, K_MAX));
    }

    /**
     * Concave curve {@code log(1 + k*x) / log(1 + k)}. Larger {@code k} makes
     * the curve steep near 0 and flat near 1 (penalize early; diminishing
     * returns near the cap).
     */
    public static Curve logarithmic(double k) {
        return new Logarithmic(clamp(k, K_MIN, K_MAX));
    }

    /**
     * Sigmoid (S-curve) centered at {@code x0} with steepness {@code k},
     * renormalized so that {@code f(0) = 0} and {@code f(1) = 1}.
     */
    public static Curve sigmoid(double k, double x0) {
        return new Sigmoid(clamp(k, K_MIN, K_MAX), Curve.clamp01(x0));
    }

    /** Step function: {@code 0} below {@code threshold}, {@code 1} at or above. */
    public static Curve step(double threshold) {
        return new Step(Curve.clamp01(threshold));
    }

    /** Power curve {@code x^p}. {@code p > 1} convex, {@code p < 1} concave. */
    public static Curve power(double p) {
        return new Power(clamp(p, P_MIN, P_MAX));
    }

    /**
     * Builds a curve from a YAML / config sub-section. Recognized keys:
     * <ul>
     *   <li>{@code type}: one of {@code linear|exponential|logarithmic|sigmoid|step|power}</li>
     *   <li>{@code k}: steepness parameter (exponential, logarithmic, sigmoid). Default 3.0.</li>
     *   <li>{@code x0}: sigmoid midpoint. Default 0.5.</li>
     *   <li>{@code threshold}: step threshold. Default 0.5.</li>
     *   <li>{@code p}: power exponent. Default 2.0.</li>
     * </ul>
     * Unknown {@code type} values throw {@link IllegalArgumentException}.
     */
    public static Curve fromConfig(Map<String, Object> section) {
        Objects.requireNonNull(section, "section");
        String type = String.valueOf(section.getOrDefault("type", "linear"))
                .toLowerCase(Locale.ROOT).trim();
        return switch (type) {
            case "linear" -> linear();
            case "exponential", "exp" -> exponential(asDouble(section.get("k"), 3.0));
            case "logarithmic", "log" -> logarithmic(asDouble(section.get("k"), 9.0));
            case "sigmoid", "s", "scurve", "s-curve" -> sigmoid(
                    asDouble(section.get("k"), 8.0),
                    asDouble(section.get("x0"), 0.5));
            case "step" -> step(asDouble(section.get("threshold"), 0.5));
            case "power", "pow" -> power(asDouble(section.get("p"), 2.0));
            default -> throw new IllegalArgumentException(
                    "Unknown curve type '" + type + "'. Expected one of: " +
                    "linear, exponential, logarithmic, sigmoid, step, power.");
        };
    }

    private static double asDouble(Object o, double fallback) {
        if (o instanceof Number n) return n.doubleValue();
        if (o instanceof String s && !s.isBlank()) {
            try { return Double.parseDouble(s.trim()); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    private static double clamp(double v, double lo, double hi) {
        if (Double.isNaN(v)) return lo;
        return Math.max(lo, Math.min(hi, v));
    }

    // ----- implementations ---------------------------------------------------

    private static final class Linear implements Curve {
        static final Linear INSTANCE = new Linear();
        @Override public double apply(double x) { return Curve.clamp01(x); }
        @Override public String id() { return "linear"; }
    }

    private record Exponential(double k) implements Curve {
        @Override public double apply(double x) {
            double xc = Curve.clamp01(x);
            double denom = Math.expm1(k);
            if (denom <= 0.0) return xc;
            return Curve.clamp01(Math.expm1(k * xc) / denom);
        }
        @Override public String id() { return "exponential"; }
    }

    private record Logarithmic(double k) implements Curve {
        @Override public double apply(double x) {
            double xc = Curve.clamp01(x);
            double denom = Math.log1p(k);
            if (denom <= 0.0) return xc;
            return Curve.clamp01(Math.log1p(k * xc) / denom);
        }
        @Override public String id() { return "logarithmic"; }
    }

    private record Sigmoid(double k, double x0) implements Curve {
        @Override public double apply(double x) {
            double xc = Curve.clamp01(x);
            double raw = sig(xc);
            double lo = sig(0.0);
            double hi = sig(1.0);
            double span = hi - lo;
            if (span <= 1.0e-12) return xc;
            return Curve.clamp01((raw - lo) / span);
        }
        private double sig(double v) { return 1.0 / (1.0 + Math.exp(-k * (v - x0))); }
        @Override public String id() { return "sigmoid"; }
    }

    private record Step(double threshold) implements Curve {
        @Override public double apply(double x) { return Curve.clamp01(x) >= threshold ? 1.0 : 0.0; }
        @Override public String id() { return "step"; }
    }

    private record Power(double p) implements Curve {
        @Override public double apply(double x) { return Curve.clamp01(Math.pow(Curve.clamp01(x), p)); }
        @Override public String id() { return "power"; }
    }
}
