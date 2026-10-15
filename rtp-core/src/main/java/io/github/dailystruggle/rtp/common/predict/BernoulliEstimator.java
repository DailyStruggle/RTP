package io.github.dailystruggle.rtp.common.predict;

/**
 * Running Bernoulli outcome estimator tracking success, rejection, or failure probabilities over time.
 *
 * <p>Employs exponential moving average smoothing over binary outcomes:
 * <ul>
 *   <li>{@code outcome = true}: success / event occurred (target value 1.0)</li>
 *   <li>{@code outcome = false}: rejection / event did not occur (target value 0.0)</li>
 * </ul>
 *
 * <p>Thread-safe and portable with zero external engine dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002).
 */
public final class BernoulliEstimator {

    private final double alpha;
    private final boolean unbiasEarlySamples;
    private double rate;
    private long count = 0;

    private BernoulliEstimator(int windowSize, double initialRate, boolean unbiasEarlySamples) {
        if (windowSize < 1) throw new IllegalArgumentException("windowSize must be >= 1, got " + windowSize);
        this.alpha = 2.0 / (windowSize + 1.0);
        this.rate = initialRate;
        this.unbiasEarlySamples = unbiasEarlySamples;
    }

    /**
     * Creates an uninitialized Bernoulli estimator with early-sample unbiasing (1 / n).
     *
     * @param windowSize effective sample window size (must be >= 1)
     * @return configured estimator
     */
    public static BernoulliEstimator ofWindow(int windowSize) {
        return new BernoulliEstimator(windowSize, Double.NaN, true);
    }

    /**
     * Creates a Bernoulli estimator initialized with a prior probability rate.
     *
     * @param windowSize  effective sample window size (must be >= 1)
     * @param initialRate initial prior rate in [0.0, 1.0]
     * @return configured estimator
     */
    public static BernoulliEstimator ofWindow(int windowSize, double initialRate) {
        if (Double.isNaN(initialRate) || initialRate < 0.0 || initialRate > 1.0) {
            throw new IllegalArgumentException("initialRate must be in [0.0, 1.0], got " + initialRate);
        }
        return new BernoulliEstimator(windowSize, initialRate, false);
    }

    /**
     * Records a binary trial outcome.
     *
     * @param outcome {@code true} for success/target event, {@code false} for rejection/non-event
     */
    public synchronized void record(boolean outcome) {
        count++;
        double target = outcome ? 1.0 : 0.0;
        if (count == 1 && Double.isNaN(rate)) {
            rate = target;
            return;
        }

        double a = unbiasEarlySamples ? Math.max(1.0 / count, alpha) : alpha;
        rate += a * (target - rate);
        if (rate < 0.0) rate = 0.0;
        else if (rate > 1.0) rate = 1.0;
    }

    /**
     * Returns the current running estimated probability rate in [0.0, 1.0].
     *
     * @return estimated rate, or {@code 0.0} if uninitialized and no samples recorded
     */
    public synchronized double rate() {
        return Double.isNaN(rate) ? 0.0 : rate;
    }

    /**
     * Returns the total number of binary trials recorded.
     *
     * @return sample count
     */
    public synchronized long sampleCount() {
        return count;
    }
}
