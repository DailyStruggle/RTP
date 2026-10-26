package io.github.dailystruggle.rtp.common.predict;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Domain-agnostic behavioral policy actuator evaluating comparative branch costs, variance-aware
 * hysteresis, and adaptive exploration shares (ADR-109, ADR-114).
 *
 * <p>Supports two composable evaluation modes:
 * <ul>
 *   <li><b>Stateless context policy ({@link #of(Function)}):</b> Evaluates shadow-weighted expected
 *       costs and thresholds from a {@link CostContext} (e.g. ADR-114 thread-weighted deduplication).</li>
 *   <li><b>Variance-gated hysteresis ({@link #ofVarianceHysteresis(HysteresisConfig)}):</b> Evaluates
 *       the standardized gap {@code z = (costA - costB) / sqrt(SE_A^2 + SE_B^2)} between two arms,
 *       switching only when {@code |z| > zSwitch} is sustained for {@code sustainSamples} consecutive
 *       observations and at least {@code minFlipInterval} observations have elapsed since the previous flip,
 *       while maintaining a bounded {@code 1 / (1 + |z|)} exploration floor in {@code [minShare, maxShare]}
 *       and optional coalesced trial credit accrual.</li>
 * </ul>
 *
 * <p>Separates pure mathematical cost estimation from runtime behavioral policy (REQ-PRED-F-004).
 * Zero external dependencies (Tier 1 {@code common.predict}, ADR-115).
 *
 * @param <T> decision outcome type (typically an enum representing execution branches)
 */
public final class ComparativeCostGovernor<T extends Enum<T>> {

    /**
     * Configuration for standard-error-gated hysteresis and adaptive exploration.
     *
     * @param initialArm      starting arm (arm A, whose expected cost is {@code costA})
     * @param alternateArm    alternate arm (arm B, whose expected cost is {@code costB})
     * @param zSwitch         z-score threshold required to count towards switching (e.g. 3.0)
     * @param sustainSamples  consecutive samples beyond {@code zSwitch} required to flip (e.g. 128)
     * @param minFlipInterval minimum samples between consecutive flips after the first flip (e.g. 256)
     * @param minExploreShare minimum exploration share for the losing arm (e.g. 1/32)
     * @param maxExploreShare maximum exploration share for the losing arm (e.g. 1/4)
     * @param <T>             arm enum type
     */
    public record HysteresisConfig<T extends Enum<T>>(
            T initialArm,
            T alternateArm,
            double zSwitch,
            int sustainSamples,
            long minFlipInterval,
            double minExploreShare,
            double maxExploreShare
    ) {
        public HysteresisConfig {
            if (initialArm == null || alternateArm == null || initialArm == alternateArm) {
                throw new IllegalArgumentException("initialArm and alternateArm must be distinct non-null values");
            }
            if (Double.isNaN(zSwitch) || zSwitch <= 0.0) {
                throw new IllegalArgumentException("zSwitch must be > 0, got " + zSwitch);
            }
            if (sustainSamples < 1) {
                throw new IllegalArgumentException("sustainSamples must be >= 1, got " + sustainSamples);
            }
            if (minFlipInterval < 0L) {
                throw new IllegalArgumentException("minFlipInterval must be >= 0, got " + minFlipInterval);
            }
            if (Double.isNaN(minExploreShare) || Double.isNaN(maxExploreShare)
                    || minExploreShare < 0.0 || maxExploreShare > 1.0 || minExploreShare > maxExploreShare) {
                throw new IllegalArgumentException("Invalid explore share bounds: [" + minExploreShare + ", " + maxExploreShare + "]");
            }
        }
    }

    /**
     * Snapshot of a variance-gated hysteresis evaluation step.
     *
     * @param mode         active arm after evaluation
     * @param previousMode active arm before evaluation
     * @param flipped      {@code true} if this evaluation triggered a mode flip
     * @param costA        expected cost of {@code initialArm}
     * @param costB        expected cost of {@code alternateArm}
     * @param zScore       standardized cost difference {@code (costA - costB) / SE}
     * @param exploreShare bounded exploration share {@code 1 / (1 + |z|)} in {@code [minShare, maxShare]}
     * @param flips        total flips recorded so far
     * @param <T>          arm enum type
     */
    public record VarianceDecision<T extends Enum<T>>(
            T mode,
            T previousMode,
            boolean flipped,
            double costA,
            double costB,
            double zScore,
            double exploreShare,
            long flips
    ) {}

    /**
     * Lock-free credit accumulator for batched/coalesced exploration trials in the alternate arm (ADR-109).
     *
     * <p>Accrues {@code exploreShare} credit on each decision and spends 1.0 credit per trial item
     * for up to {@code trialRun} items within the same bin (e.g. region file) over {@code trialWindow}
     * decisions, allowing sparse exploration trials to coalesce into realistic batch sizes while
     * preserving the exact long-run exploration rate.
     */
    public static final class CoalescedTrialAccrual {
        private record State(double credit, long bin, int left, long until) {}

        private final long noBinKey;
        private final int trialRun;
        private final int trialWindow;
        private final AtomicReference<State> state;

        public CoalescedTrialAccrual(long noBinKey, int trialRun, int trialWindow) {
            if (trialRun < 1) throw new IllegalArgumentException("trialRun must be >= 1, got " + trialRun);
            if (trialWindow < 1) throw new IllegalArgumentException("trialWindow must be >= 1, got " + trialWindow);
            this.noBinKey = noBinKey;
            this.trialRun = trialRun;
            this.trialWindow = trialWindow;
            this.state = new AtomicReference<>(new State(0.0, noBinKey, 0, 0L));
        }

        /**
         * Evaluates whether the current candidate in {@code bin} should run a coalesced exploration trial.
         *
         * @param bin              coalescing bin identifier (or {@code noBinKey} for unbinned)
         * @param share            current exploration share in [0, 1]
         * @param currentDecisions monotonic decision counter
         * @return {@code true} to run the trial arm on this candidate
         */
        public boolean shouldTrial(long bin, double share, long currentDecisions) {
            while (true) {
                State cur = state.get();
                double credit = Math.min(cur.credit() + share, 1.0);
                int left = cur.left();
                long trialBin = cur.bin();
                long until = cur.until();

                if (left > 0 && currentDecisions > until) {
                    left = 0;
                }
                boolean trialNow;
                if (left > 0 && bin != noBinKey && bin == trialBin) {
                    left--;
                    credit -= 1.0;
                    trialNow = true;
                } else if (credit >= 1.0) {
                    credit -= 1.0;
                    trialBin = bin;
                    left = (bin == noBinKey) ? 0 : trialRun - 1;
                    until = currentDecisions + trialWindow;
                    trialNow = true;
                } else {
                    trialNow = false;
                }
                State next = new State(credit, trialBin, left, until);
                if (state.compareAndSet(cur, next)) {
                    return trialNow;
                }
            }
        }

        /** Resets accrued trial credit and active bin run (e.g. on mode flip). */
        public void reset() {
            state.set(new State(0.0, noBinKey, 0, 0L));
        }
    }

    private final Function<CostContext, T> policy;
    private final HysteresisConfig<T> hysteresisConfig;

    // Stateful hysteresis fields guarded by synchronized when hysteresisConfig != null
    private volatile VarianceDecision<T> latestDecision;
    private T currentMode;
    private long flips;
    private long sinceFlip;
    private int beyond;

    private ComparativeCostGovernor(Function<CostContext, T> policy, HysteresisConfig<T> hysteresisConfig) {
        if (policy == null) {
            throw new IllegalArgumentException("Decision policy function cannot be null");
        }
        this.policy = policy;
        this.hysteresisConfig = hysteresisConfig;
        if (hysteresisConfig != null) {
            this.currentMode = hysteresisConfig.initialArm();
            this.latestDecision = new VarianceDecision<>(
                    hysteresisConfig.initialArm(),
                    hysteresisConfig.initialArm(),
                    false,
                    Double.NaN,
                    Double.NaN,
                    Double.NaN,
                    hysteresisConfig.maxExploreShare(),
                    0L);
        }
    }

    /**
     * Constructs a comparative governor governed by the given evaluation policy.
     *
     * @param policy evaluation function mapping context to branch decision
     * @param <T>    enum decision type
     * @return configured governor
     */
    public static <T extends Enum<T>> ComparativeCostGovernor<T> of(Function<CostContext, T> policy) {
        return new ComparativeCostGovernor<>(policy, null);
    }

    /**
     * Constructs a comparative governor with standard-error-gated hysteresis and adaptive exploration.
     * Calling {@link #evaluate(CostContext)} reads {@code costA}, {@code costB}, and {@code se2} from
     * the context and delegates to {@link #evaluateVarianceGated(double, double, double)}.
     *
     * @param config hysteresis and exploration configuration
     * @param <T>    enum decision type
     * @return configured variance-aware governor
     */
    public static <T extends Enum<T>> ComparativeCostGovernor<T> ofVarianceHysteresis(HysteresisConfig<T> config) {
        if (config == null) {
            throw new IllegalArgumentException("HysteresisConfig cannot be null");
        }
        @SuppressWarnings("unchecked")
        final ComparativeCostGovernor<T>[] holder = new ComparativeCostGovernor[1];
        Function<CostContext, T> defaultPolicy = ctx -> holder[0].evaluateVarianceGated(
                ctx.get("costA"),
                ctx.get("costB"),
                ctx.get("se2", 0.0)
        ).mode();
        ComparativeCostGovernor<T> gov = new ComparativeCostGovernor<>(defaultPolicy, config);
        holder[0] = gov;
        return gov;
    }

    /**
     * Evaluates the decision policy against the given cost context.
     *
     * @param context runtime cost and performance metrics
     * @return selected execution branch
     */
    public T evaluate(CostContext context) {
        if (context == null) throw new IllegalArgumentException("CostContext cannot be null");
        return policy.apply(context);
    }

    /**
     * Evaluates one variance-gated comparison step between {@code initialArm} ({@code costA})
     * and {@code alternateArm} ({@code costB}) given their combined squared standard error {@code combinedSeSquared}.
     *
     * @param costA             expected cost of {@code initialArm}
     * @param costB             expected cost of {@code alternateArm}
     * @param combinedSeSquared combined squared standard error {@code SE_A^2 + SE_B^2}
     * @return immutable decision snapshot including whether a flip occurred
     */
    public synchronized VarianceDecision<T> evaluateVarianceGated(double costA, double costB, double combinedSeSquared) {
        if (hysteresisConfig == null) {
            throw new IllegalStateException("This governor was not configured with HysteresisConfig");
        }
        double gap = costA - costB;
        double z = computeZScore(gap, combinedSeSquared);
        sinceFlip++;
        boolean losing = (currentMode == hysteresisConfig.initialArm())
                ? (z > hysteresisConfig.zSwitch())
                : (z < -hysteresisConfig.zSwitch());
        beyond = losing ? beyond + 1 : 0;

        T prev = currentMode;
        boolean flipped = false;
        if (beyond >= hysteresisConfig.sustainSamples()
                && (flips == 0L || sinceFlip >= hysteresisConfig.minFlipInterval())) {
            currentMode = (currentMode == hysteresisConfig.initialArm())
                    ? hysteresisConfig.alternateArm()
                    : hysteresisConfig.initialArm();
            flips++;
            sinceFlip = 0L;
            beyond = 0;
            flipped = true;
        }
        double explore = computeExploreShare(z, hysteresisConfig.minExploreShare(), hysteresisConfig.maxExploreShare());
        VarianceDecision<T> decision = new VarianceDecision<>(currentMode, prev, flipped, costA, costB, z, explore, flips);
        this.latestDecision = decision;
        return decision;
    }

    /**
     * Returns the most recent variance-gated decision snapshot without synchronization.
     *
     * @return latest variance decision, or {@code null} if not configured with hysteresis
     */
    public VarianceDecision<T> latestDecision() {
        return latestDecision;
    }

    /**
     * Computes standardized z-score {@code costDiff / sqrt(combinedSeSquared)}.
     *
     * @param costDiff          difference in expected cost {@code costA - costB}
     * @param combinedSeSquared combined squared standard error
     * @return standardized z-score
     */
    public static double computeZScore(double costDiff, double combinedSeSquared) {
        if (Double.isNaN(costDiff) || Double.isNaN(combinedSeSquared)) return Double.NaN;
        if (combinedSeSquared > 0.0) {
            return costDiff / Math.sqrt(combinedSeSquared);
        }
        if (costDiff > 0.0) return Double.POSITIVE_INFINITY;
        if (costDiff < 0.0) return Double.NEGATIVE_INFINITY;
        return 0.0;
    }

    /**
     * Computes bounded exploration share {@code 1 / (1 + |z|)} clamped to {@code [minShare, maxShare]}.
     *
     * @param zScore   standardized z-score
     * @param minShare minimum exploration share
     * @param maxShare maximum exploration share
     * @return exploration share in {@code [minShare, maxShare]}
     */
    public static double computeExploreShare(double zScore, double minShare, double maxShare) {
        if (Double.isInfinite(zScore)) {
            return minShare;
        }
        double az = Double.isNaN(zScore) ? 0.0 : Math.abs(zScore);
        return Math.max(minShare, Math.min(maxShare, 1.0 / (1.0 + az)));
    }

    /**
     * Deterministic stride check: returns {@code true} on every {@code round(1 / share)}-th decision.
     *
     * @param decisionIndex 1-based monotonic decision index
     * @param share         exploration share in (0, 1]
     * @return {@code true} if this decision should explore the alternate arm
     */
    public static boolean shouldExploreDeterministic(long decisionIndex, double share) {
        if (share <= 0.0) return false;
        long stride = Math.max(1L, Math.round(1.0 / share));
        return decisionIndex % stride == 0L;
    }

    /**
     * Returns the underlying decision policy function.
     *
     * @return policy function
     */
    public Function<CostContext, T> policy() {
        return policy;
    }
}
