package io.github.dailystruggle.rtp.common.predict;

import java.util.concurrent.atomic.AtomicLong;

/**
 * High-performance running statistical cost estimator tracking moving mean, variance, and sample counts.
 *
 * <p>Supports dual operational modes:
 * <ul>
 *   <li>{@link DecayMode#FRACTIONAL}: Floating-point Exponential Weighted Moving Average (EWMA) with
 *       configurable window size {@code N} (&alpha; = 2 / (N + 1)), optional early-sample unbiasing
 *       (1 / n), and Welford-style incremental variance tracking.</li>
 *   <li>{@link DecayMode#INTEGER_SEVEN_EIGHTHS}: Fixed-ratio integer arithmetic
 *       ({@code ((avg * 7) / 8) + (diff / 8)}) providing 100% bitwise parity with historical
 *       task-pipe and database queue pacing without floating-point conversion overhead.</li>
 * </ul>
 *
 * <p>Thread-safe and portable with zero external engine dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002).
 */
public final class EwmaEstimator {

    /** Decay mode controlling statistical update arithmetic. */
    public enum DecayMode {
        /** Floating-point EWMA tracking mean and variance. */
        FRACTIONAL,
        /** Historical exact integer ratio decay (7/8 history, 1/8 new). */
        INTEGER_SEVEN_EIGHTHS
    }

    private final DecayMode mode;
    private final double alpha;
    private final int weightHistory;
    private final int weightNew;
    private final int totalWeight;
    private final boolean unbiasEarlySamples;

    // Fractional state guarded by synchronized monitor
    private double mean = Double.NaN;
    private double variance = 0.0;
    private long count = 0;

    // Lock-free atomic state for integer decay mode
    private final AtomicLong integerMean;
    private final AtomicLong sampleCounter = new AtomicLong(0);

    private EwmaEstimator(DecayMode mode, double alpha, int weightHistory, int weightNew,
                          long initialValueNanos, double initialMean, boolean unbiasEarlySamples) {
        this.mode = mode;
        this.alpha = alpha;
        this.weightHistory = weightHistory;
        this.weightNew = weightNew;
        this.totalWeight = weightHistory + weightNew;
        this.unbiasEarlySamples = unbiasEarlySamples;
        this.integerMean = new AtomicLong(initialValueNanos);

        if (mode == DecayMode.FRACTIONAL) {
            if (!Double.isNaN(initialMean)) {
                this.mean = initialMean;
                this.variance = 0.0;
            }
        }
    }

    /**
     * Creates an uninitialized fractional EWMA estimator with the given sample window.
     * Uses early-sample unbiasing (1 / n) until sample count exceeds the effective window.
     *
     * @param windowSize effective sample window size (must be >= 1)
     * @return configured estimator
     */
    public static EwmaEstimator ofWindow(int windowSize) {
        if (windowSize < 1) throw new IllegalArgumentException("windowSize must be >= 1, got " + windowSize);
        double a = 2.0 / (windowSize + 1.0);
        return new EwmaEstimator(DecayMode.FRACTIONAL, a, 0, 0, 0L, Double.NaN, true);
    }

    /**
     * Creates a fractional EWMA estimator initialized with a prior mean estimate.
     * Updates apply the fixed &alpha; weight immediately without early-sample unbiasing.
     *
     * @param windowSize  effective sample window size (must be >= 1)
     * @param initialMean pre-calibrated initial mean value
     * @return configured estimator
     */
    public static EwmaEstimator ofWindow(int windowSize, double initialMean) {
        if (windowSize < 1) throw new IllegalArgumentException("windowSize must be >= 1, got " + windowSize);
        if (Double.isNaN(initialMean) || initialMean < 0.0) {
            throw new IllegalArgumentException("initialMean must be non-negative and finite, got " + initialMean);
        }
        double a = 2.0 / (windowSize + 1.0);
        return new EwmaEstimator(DecayMode.FRACTIONAL, a, 0, 0, 0L, initialMean, false);
    }

    /**
     * Creates an integer decay estimator using exact integer weights.
     *
     * @param weightHistory     numerator weight for historical average (e.g. 7)
     * @param weightNew         numerator weight for new sample (e.g. 1)
     * @param initialValueNanos initial average in nanoseconds (0L to initialize from first sample)
     * @return configured integer estimator
     */
    public static EwmaEstimator ofIntegerDecay(int weightHistory, int weightNew, long initialValueNanos) {
        if (weightHistory < 1 || weightNew < 1) {
            throw new IllegalArgumentException("Weights must be >= 1");
        }
        return new EwmaEstimator(DecayMode.INTEGER_SEVEN_EIGHTHS, 0.0, weightHistory, weightNew,
                Math.max(0L, initialValueNanos), Double.NaN, false);
    }

    /**
     * Factory helper creating an integer decay estimator configured for historical task pipe pacing (7/8 decay).
     *
     * @param initialAvgNanos initial predicted cost in nanoseconds (e.g. 50ms)
     * @return configured integer estimator
     */
    public static EwmaEstimator ofTaskPipe(long initialAvgNanos) {
        return ofIntegerDecay(7, 1, initialAvgNanos);
    }

    /**
     * Records a numeric sample into the moving estimator.
     *
     * @param value execution measurement (e.g. duration or latency)
     */
    public void record(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value < 0.0) {
            return; // Guard against NaN, infinity, and negative values (REQ-PRED-NF-003)
        }

        if (mode == DecayMode.INTEGER_SEVEN_EIGHTHS) {
            recordNanos(Math.round(value));
            return;
        }

        synchronized (this) {
            count++;
            if (count == 1 && Double.isNaN(mean)) {
                mean = value;
                variance = 0.0;
                return;
            }

            double a = unbiasEarlySamples ? Math.max(1.0 / count, alpha) : alpha;
            double diff = value - mean;
            double inc = a * diff;
            mean += inc;
            variance = (1.0 - a) * (variance + diff * inc);
            if (variance < 0.0) variance = 0.0; // Guard against floating-point catastrophic cancellation
        }
    }

    /**
     * Records a nanosecond duration sample into the estimator.
     * In integer mode, executes lock-free atomic CAS updates.
     *
     * @param nanos measured duration in nanoseconds
     */
    public void recordNanos(long nanos) {
        if (nanos < 0L) nanos = 0L; // Guard against negative clock deltas

        if (mode == DecayMode.FRACTIONAL) {
            record((double) nanos);
            return;
        }

        sampleCounter.incrementAndGet();
        long current;
        long updated;
        do {
            current = integerMean.get();
            if (current == 0L) {
                updated = nanos;
            } else {
                if (weightHistory == 7 && weightNew == 1) {
                    if (current > (Long.MAX_VALUE / 7L)) {
                        updated = ((current / 8L) * 7L) + (nanos / 8L);
                    } else {
                        updated = ((current * 7L) / 8L) + (nanos / 8L);
                    }
                } else {
                    updated = ((current * weightHistory) / totalWeight) + (nanos / totalWeight);
                }
            }
        } while (!integerMean.compareAndSet(current, updated));
    }

    /**
     * Returns the current running mean estimate.
     *
     * @return estimated mean, or {@code Double.NaN} if no samples recorded and no prior set
     */
    public double mean() {
        if (mode == DecayMode.INTEGER_SEVEN_EIGHTHS) {
            return (double) integerMean.get();
        }
        synchronized (this) {
            return mean;
        }
    }

    /**
     * Returns the current running mean rounded to the nearest integer nanoseconds.
     *
     * @return estimated mean in nanoseconds, or {@code 0L} if uninitialized
     */
    public long meanNanos() {
        if (mode == DecayMode.INTEGER_SEVEN_EIGHTHS) {
            return integerMean.get();
        }
        synchronized (this) {
            return Double.isNaN(mean) ? 0L : Math.round(mean);
        }
    }

    /**
     * Returns the running sample variance (fractional mode only).
     *
     * @return estimated variance, or {@code 0.0} in integer mode
     */
    public double variance() {
        if (mode == DecayMode.INTEGER_SEVEN_EIGHTHS) return 0.0;
        synchronized (this) {
            return variance;
        }
    }

    /**
     * Returns the running standard deviation (fractional mode only).
     *
     * @return standard deviation, or {@code 0.0} in integer mode
     */
    public double standardDeviation() {
        if (mode == DecayMode.INTEGER_SEVEN_EIGHTHS) return 0.0;
        synchronized (this) {
            return Math.sqrt(variance);
        }
    }

    /**
     * Returns the total number of samples recorded.
     *
     * @return sample count
     */
    public long sampleCount() {
        if (mode == DecayMode.INTEGER_SEVEN_EIGHTHS) {
            return sampleCounter.get();
        }
        synchronized (this) {
            return count;
        }
    }

    /**
     * Returns the decay mode configured for this estimator.
     *
     * @return decay mode
     */
    public DecayMode decayMode() {
        return mode;
    }
}
