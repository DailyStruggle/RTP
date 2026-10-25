package io.github.dailystruggle.rtp.common.predict;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

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

    /**
     * Lock-free time-windowed Ordinary Least Squares (OLS) slope estimator with post-event
     * exclusion window, minimum sample count, minimum regressor relative range, and clamped
     * prior fallback (ADR-087 Section 4.2).
     *
     * <p>Holds zero locks and creates zero threads (F-001, ADR-087 Invariant I8).
     */
    public static final class WindowedOlsSlope {

        private record Sample(long timestampNanos, double x, double y) {}

        private final long windowNanos;
        private final long exclusionNanos;
        private final int minSamples;
        private final double minRelativeXRange;
        private final double priorSlope;
        private final double minSlope;
        private final double maxSlope;

        private final ConcurrentLinkedDeque<Sample> window = new ConcurrentLinkedDeque<>();
        private final AtomicLong lastExclusionNanos = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong estimateBits;

        public WindowedOlsSlope(
                long windowNanos,
                long exclusionNanos,
                int minSamples,
                double minRelativeXRange,
                double priorSlope,
                double minSlope,
                double maxSlope
        ) {
            if (windowNanos < 1L) {
                throw new IllegalArgumentException("windowNanos must be >= 1, got " + windowNanos);
            }
            if (exclusionNanos < 0L) {
                throw new IllegalArgumentException("exclusionNanos must be >= 0, got " + exclusionNanos);
            }
            if (minSamples < 2) {
                throw new IllegalArgumentException("minSamples must be >= 2, got " + minSamples);
            }
            if (Double.isNaN(minRelativeXRange) || minRelativeXRange < 0.0) {
                throw new IllegalArgumentException("minRelativeXRange must be >= 0, got " + minRelativeXRange);
            }
            if (Double.isNaN(priorSlope) || Double.isNaN(minSlope) || Double.isNaN(maxSlope) || maxSlope < minSlope) {
                throw new IllegalArgumentException("Invalid slope bounds: prior=" + priorSlope
                        + ", min=" + minSlope + ", max=" + maxSlope);
            }
            this.windowNanos = windowNanos;
            this.exclusionNanos = exclusionNanos;
            this.minSamples = minSamples;
            this.minRelativeXRange = minRelativeXRange;
            this.priorSlope = Math.max(minSlope, Math.min(maxSlope, priorSlope));
            this.minSlope = minSlope;
            this.maxSlope = maxSlope;
            this.estimateBits = new AtomicLong(Double.doubleToRawLongBits(this.priorSlope));
        }

        /**
         * Records a contraction or invalidation event at {@code nowNanos} so samples within the
         * next {@code exclusionNanos} are ignored.
         *
         * @param nowNanos monotonic timestamp in nanoseconds
         */
        public void recordExclusionEvent(long nowNanos) {
            lastExclusionNanos.set(nowNanos);
        }

        /**
         * Records a sample {@code (x, y)} at {@code nowNanos}, evicts expired samples outside
         * {@code windowNanos}, and updates the clamped OLS slope estimate when identification
         * criteria are met.
         *
         * @param x        non-negative regressor value (e.g. held chunk tickets)
         * @param y        non-negative response value (e.g. post-GC tenured bytes)
         * @param nowNanos monotonic timestamp in nanoseconds
         * @return updated or retained slope estimate
         */
        public double recordSample(double x, double y, long nowNanos) {
            if (Double.isNaN(x) || Double.isInfinite(x) || x < 0.0
                    || Double.isNaN(y) || Double.isInfinite(y) || y < 0.0) {
                return estimate();
            }
            long lastExcl = lastExclusionNanos.get();
            if (lastExcl != Long.MIN_VALUE && nowNanos >= lastExcl && (nowNanos - lastExcl) <= exclusionNanos) {
                return estimate();
            }

            window.addLast(new Sample(nowNanos, x, y));
            while (!window.isEmpty()) {
                Sample head = window.peekFirst();
                if (head == null || nowNanos - head.timestampNanos() <= windowNanos) {
                    break;
                }
                window.pollFirst();
            }

            List<Sample> samples = new ArrayList<>(window);
            if (samples.size() < minSamples) {
                estimateBits.set(Double.doubleToRawLongBits(priorSlope));
                return priorSlope;
            }

            double sumX = 0.0;
            double sumY = 0.0;
            double minX = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            for (Sample s : samples) {
                sumX += s.x();
                sumY += s.y();
                minX = Math.min(minX, s.x());
                maxX = Math.max(maxX, s.x());
            }
            int n = samples.size();
            double meanX = sumX / n;
            double meanY = sumY / n;

            if (meanX <= 0.0 || (maxX - minX) < minRelativeXRange * meanX) {
                estimateBits.set(Double.doubleToRawLongBits(priorSlope));
                return priorSlope;
            }

            double sxx = 0.0;
            double sxy = 0.0;
            for (Sample s : samples) {
                double dx = s.x() - meanX;
                double dy = s.y() - meanY;
                sxx += dx * dx;
                sxy += dx * dy;
            }
            if (sxx <= 1e-9) {
                estimateBits.set(Double.doubleToRawLongBits(priorSlope));
                return priorSlope;
            }

            double slope = sxy / sxx;
            double clamped = Math.max(minSlope, Math.min(maxSlope, slope));
            estimateBits.set(Double.doubleToRawLongBits(clamped));
            return clamped;
        }

        /** Returns the current slope estimate. */
        public double estimate() {
            return Double.longBitsToDouble(estimateBits.get());
        }

        /** Returns the configured prior slope. */
        public double priorSlope() {
            return priorSlope;
        }

        /** Returns the current number of samples retained in the sliding window. */
        public int windowSampleCount() {
            return window.size();
        }

        /** Clears the sliding window, exclusion timestamp, and resets the estimate to {@link #priorSlope()}. */
        public void reset() {
            window.clear();
            lastExclusionNanos.set(Long.MIN_VALUE);
            estimateBits.set(Double.doubleToRawLongBits(priorSlope));
        }
    }
}
