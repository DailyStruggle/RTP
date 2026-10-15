package io.github.dailystruggle.rtp.common.predict;

/**
 * Paces task execution within available time budgets using predictive cost modeling.
 *
 * <p>Enforces two critical boundary guarantees (REQ-PRED-F-003):
 * <ul>
 *   <li><b>Forward Progress:</b> A positive available budget always executes at least one task
 *       ({@code ranAny == false}), preventing starvation under micro-budgets.</li>
 *   <li><b>Overrun Protection:</b> An exhausted or negative budget ({@code availableNanos <= 0})
 *       immediately yields (runs 0 tasks) to prevent overrunning tick deadlines.</li>
 * </ul>
 *
 * <p>Thread-safe and portable with zero external engine dependencies (REQ-PRED-NF-001, REQ-PRED-NF-002).
 */
public final class BudgetPacer {

    private final EwmaEstimator estimator;
    private final double safetyMultiplier;

    private BudgetPacer(EwmaEstimator estimator, double safetyMultiplier) {
        if (estimator == null) throw new IllegalArgumentException("Estimator cannot be null");
        if (Double.isNaN(safetyMultiplier) || safetyMultiplier <= 0.0) {
            throw new IllegalArgumentException("Safety multiplier must be positive, got " + safetyMultiplier);
        }
        this.estimator = estimator;
        this.safetyMultiplier = safetyMultiplier;
    }

    /**
     * Creates a budget pacer configured for historical task pipe pacing (7/8 integer decay, safety multiplier 1.0).
     *
     * @param initialAvgNanos initial predicted execution cost in nanoseconds (e.g. 50ms)
     * @return configured budget pacer
     */
    public static BudgetPacer ofTaskPipe(long initialAvgNanos) {
        return new BudgetPacer(EwmaEstimator.ofTaskPipe(initialAvgNanos), 1.0);
    }

    /**
     * Creates a budget pacer wrapping the given estimator with a configurable safety margin.
     *
     * @param estimator        cost estimator tracking task durations
     * @param safetyMultiplier safety margin multiplier (&ge; 1.0, e.g. 1.05 or 1.10)
     * @return configured budget pacer
     */
    public static BudgetPacer ofEstimator(EwmaEstimator estimator, double safetyMultiplier) {
        return new BudgetPacer(estimator, safetyMultiplier);
    }

    /**
     * Creates a budget pacer wrapping the given estimator with no safety margin (1.0).
     *
     * @param estimator cost estimator tracking task durations
     * @return configured budget pacer
     */
    public static BudgetPacer ofEstimator(EwmaEstimator estimator) {
        return new BudgetPacer(estimator, 1.0);
    }

    /**
     * Evaluates whether the next task can be admitted within the available time budget.
     *
     * @param elapsedNanos   time elapsed so far in current pulse (nanoseconds)
     * @param availableNanos total budget allocated for current pulse (nanoseconds)
     * @param ranAny         {@code true} if at least one task has already executed in this pulse
     * @return {@code true} to execute, {@code false} to yield
     */
    public boolean canExecute(long elapsedNanos, long availableNanos, boolean ranAny) {
        if (availableNanos <= 0L) {
            return false; // Zero or negative budget never executes anything
        }
        if (!ranAny) {
            return true; // Forward progress guarantee: always run at least once on positive budget
        }

        long predicted = predictedCostNanos();
        if (elapsedNanos > availableNanos - predicted) {
            return false;
        }
        return true;
    }

    /**
     * Convenience check assuming {@code ranAny = (elapsedNanos > 0)}.
     *
     * @param elapsedNanos   time elapsed so far in current pulse (nanoseconds)
     * @param availableNanos total budget allocated for current pulse (nanoseconds)
     * @return {@code true} to execute, {@code false} to yield
     */
    public boolean canExecute(long elapsedNanos, long availableNanos) {
        return canExecute(elapsedNanos, availableNanos, elapsedNanos > 0L);
    }

    /**
     * Records the execution duration of a completed task into the underlying estimator.
     *
     * @param durationNanos measured task duration in nanoseconds
     */
    public void recordDuration(long durationNanos) {
        estimator.recordNanos(durationNanos);
    }

    /**
     * Returns the predicted execution cost for the upcoming task in nanoseconds.
     *
     * @return predicted duration in nanoseconds
     */
    public long predictedCostNanos() {
        if (safetyMultiplier == 1.0) {
            return estimator.meanNanos();
        }
        return Math.round(estimator.mean() * safetyMultiplier);
    }

    /**
     * Returns the underlying estimator.
     *
     * @return estimator
     */
    public EwmaEstimator estimator() {
        return estimator;
    }

    /**
     * Returns the safety margin multiplier.
     *
     * @return safety multiplier
     */
    public double safetyMultiplier() {
        return safetyMultiplier;
    }
}
