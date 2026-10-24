package io.github.dailystruggle.rtp.common.predict;

/**
 * Exponentially-weighted online simple linear regression {@code y = intercept + slope * x}
 * for amortised batched or coalesced operation costs (ADR-109, ADR-087).
 *
 * <p>Designed to separate per-item cost from shared batch-open overhead when a batch of size
 * {@code g} shares a fixed setup cost: setting {@code x = 1 / g} models {@code T(g) = perItem + open / g}.
 * Until {@code x} exhibits variance above {@code minXVariance} across at least {@code minSamples}
 * observations, {@link #slope()} returns {@code 0.0} and {@link #predict(double)} falls back to
 * the running mean {@link #meanY()}.
 *
 * <p>Thread-safe and zero-dependency (Tier 1 {@code common.predict}, ADR-115).
 */
public final class EwmaLinearFit {

    private final int windowSize;
    private final double alpha;
    private final int minSamples;
    private final double minXVariance;
    private final boolean nonNegativeSlope;

    private long count;
    private double meanX = Double.NaN;
    private double meanY = Double.NaN;
    private double varX;
    private double varY;
    private double covXY;

    public EwmaLinearFit(int windowSize, int minSamples, double minXVariance, boolean nonNegativeSlope) {
        if (windowSize < 1) throw new IllegalArgumentException("windowSize must be >= 1, got " + windowSize);
        if (minSamples < 1) throw new IllegalArgumentException("minSamples must be >= 1, got " + minSamples);
        if (Double.isNaN(minXVariance) || minXVariance < 0.0) {
            throw new IllegalArgumentException("minXVariance must be non-negative, got " + minXVariance);
        }
        this.windowSize = windowSize;
        this.alpha = 2.0 / (windowSize + 1.0);
        this.minSamples = minSamples;
        this.minXVariance = minXVariance;
        this.nonNegativeSlope = nonNegativeSlope;
    }

    /**
     * Factory for coalesced batch cost fitting {@code T(g) = perUnit + open / g} with non-negative open cost.
     *
     * @param windowSize   effective EWMA sample window
     * @param minSamples   minimum sample count before fitting slope
     * @param minXVariance minimum variance of {@code x = 1/g} before fitting slope
     * @return configured linear fit
     */
    public static EwmaLinearFit ofCoalescedCost(int windowSize, int minSamples, double minXVariance) {
        return new EwmaLinearFit(windowSize, minSamples, minXVariance, true);
    }

    /**
     * Records an observation pair {@code (x, y)}.
     *
     * @param x regressor (e.g. {@code 1.0 / groupSize})
     * @param y response (e.g. per-item duration in nanoseconds or milliseconds)
     */
    public synchronized void record(double x, double y) {
        if (Double.isNaN(x) || Double.isInfinite(x) || Double.isNaN(y) || Double.isInfinite(y)) {
            return;
        }
        count++;
        if (count == 1) {
            meanX = x;
            meanY = y;
            varX = 0.0;
            varY = 0.0;
            covXY = 0.0;
            return;
        }
        double a = Math.max(1.0 / count, alpha);
        double dx = x - meanX;
        double dy = y - meanY;
        meanX += a * dx;
        meanY += a * dy;
        varX = Math.max(0.0, (1.0 - a) * (varX + a * dx * dx));
        varY = Math.max(0.0, (1.0 - a) * (varY + a * dy * dy));
        covXY = (1.0 - a) * (covXY + a * dx * dy);
    }

    /**
     * Returns {@code true} once enough samples and regressor variance have been observed to identify the slope.
     *
     * @return whether slope is identified
     */
    public synchronized boolean isIdentified() {
        return count >= minSamples && varX > minXVariance;
    }

    /**
     * Returns the fitted slope, clamped to {@code >= 0.0} when {@code nonNegativeSlope} is enabled,
     * or {@code 0.0} when not yet identified.
     *
     * @return fitted slope
     */
    public synchronized double slope() {
        if (!isIdentified()) return 0.0;
        double raw = covXY / varX;
        return nonNegativeSlope ? Math.max(0.0, raw) : raw;
    }

    /**
     * Evaluates the fitted line at {@code x}, clamped to {@code >= 0.0}.
     *
     * @param x target regressor value
     * @return predicted response at {@code x}, or {@code Double.NaN} if no samples recorded
     */
    public synchronized double predict(double x) {
        if (count == 0) return Double.NaN;
        return Math.max(0.0, meanY + slope() * (x - meanX));
    }

    /**
     * Returns the squared standard error of {@link #predict(double)} at {@code x},
     * accounting for both residual variance and extrapolation distance from {@link #meanX()}.
     *
     * @param x target regressor value
     * @return squared standard error
     */
    public synchronized double standardErrorSquaredAt(double x) {
        if (count == 0) return 0.0;
        double neff = Math.min(count, windowSize);
        if (!isIdentified()) return varY / neff;
        double b = slope();
        double resid = Math.max(0.0, varY - 2.0 * b * covXY + b * b * varX);
        double dx = x - meanX;
        return (resid / neff) * (1.0 + (dx * dx) / varX);
    }

    public synchronized long sampleCount() {
        return count;
    }

    public synchronized double meanX() {
        return meanX;
    }

    public synchronized double meanY() {
        return meanY;
    }

    public synchronized double varianceX() {
        return varX;
    }

    public synchronized double varianceY() {
        return varY;
    }

    public synchronized double covarianceXY() {
        return covXY;
    }
}
