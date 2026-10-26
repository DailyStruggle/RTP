package io.github.dailystruggle.rtp.common.predict;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Lock-free probed cell-group / region share allocator computing AIMD shares {@code S_k},
 * steering weights {@code w_k} and {@code w'_k}, bounded pool loans {@code e_k} under {@code Pi},
 * Jain's fairness index {@code J}, bystander exposure {@code E}, split/merge state transfer,
 * and candidate aging bounds.
 *
 * <p>Zero external dependencies, zero locks, and zero thread creation.
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 * @governedBy ADR-123
 */
public final class ProbedShareAllocator {

    public static final long DEFAULT_CONFIGURED_SYNC_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    public static final long MAX_BORROWED_TOTAL_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    public static final long HALF_MILLI_NANOS = TimeUnit.MICROSECONDS.toNanos(500);
    public static final long GROUP_IDLE_EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final long POPULATED_GROWTH_COOLDOWN_NANOS = TimeUnit.SECONDS.toNanos(60);
    public static final long LOAN_NO_BREACH_NANOS = TimeUnit.SECONDS.toNanos(10);
    public static final long OVERLOAD_VETO_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(5);
    public static final long CANDIDATE_AGING_NANOS = 60_000_000_000L;
    public static final int CANDIDATE_MAX_DEFERRALS = 8;
    public static final double HEADROOM_STEERING_THRESHOLD = 0.5;
    public static final double FULL_RECOVERY_SECONDS = 50.0;

    /**
     * Per-group telemetry input for a single allocator evaluation.
     */
    public record GroupSignal(
            String groupId,
            double interactiveNanosPerTick,
            double backgroundNanosPerTick,
            double recentTwoSecAttributedNanosPerTick,
            double queuedBackgroundNanos,
            boolean queuedInteractiveWork,
            int population,
            boolean breached,
            boolean exclusiveThreadInLastSecond
    ) {
        public GroupSignal {
            if (groupId == null) groupId = "";
            if (Double.isNaN(interactiveNanosPerTick) || interactiveNanosPerTick < 0.0) interactiveNanosPerTick = 0.0;
            if (Double.isNaN(backgroundNanosPerTick) || backgroundNanosPerTick < 0.0) backgroundNanosPerTick = 0.0;
            if (Double.isNaN(recentTwoSecAttributedNanosPerTick) || recentTwoSecAttributedNanosPerTick < 0.0) {
                recentTwoSecAttributedNanosPerTick = 0.0;
            }
            if (Double.isNaN(queuedBackgroundNanos) || queuedBackgroundNanos < 0.0) queuedBackgroundNanos = 0.0;
            population = Math.max(0, population);
        }
    }

    /**
     * Computed share, loan, outer thread ceiling, and steering weights for one group {@code k}.
     */
    public record GroupAllocation(
            String groupId,
            long limitNanos,
            long shareNanos,
            long backgroundShareNanos,
            long loanNanos,
            long effectiveGroupBudgetNanos,
            long outerThreadCeilingNanos,
            double weight,
            double adjustedWeight,
            int population,
            double interactiveNanosPerTick,
            double backgroundNanosPerTick,
            boolean exclusiveThread
    ) {}

    /**
     * Steering weight view for a candidate's group.
     *
     * @param weight                          headroom weight {@code w_k = (S_k^b + e_k) / S_cfg}
     * @param adjustedWeight                  bystander-adjusted weight {@code w'_k = w_k / (1 + P_k / 4)}
     * @param maxAdjustedWeightWithQueuedWork {@code max_j w'_j} across groups with queued background work
     */
    public record SteeringWeight(
            double weight,
            double adjustedWeight,
            double maxAdjustedWeightWithQueuedWork
    ) {
        public static final SteeringWeight DEFAULT_UNOWNED =
                new SteeringWeight(0.5, 0.5, 0.5);

        /**
         * Returns {@code true} when a non-aged candidate should be processed immediately:
         * {@code w_k >= 0.5} and {@code w'_k >= 0.5 * max_j w'_j}.
         */
        public boolean shouldProcessNow() {
            return weight + 1e-9 >= HEADROOM_STEERING_THRESHOLD
                    && adjustedWeight + 1e-9 >= 0.5 * maxAdjustedWeightWithQueuedWork;
        }
    }

    /**
     * Immutable allocation snapshot produced by {@link #evaluate}.
     */
    public record AllocationSnapshot(
            long configuredSyncNanos,
            int activeGroups,
            int distinctThreads,
            long poolBudgetNanos,
            long lendablePoolNanos,
            long totalLentNanos,
            double maxAdjustedWeightWithQueuedWork,
            double fairnessIndexJ,
            double bystanderExposureMs,
            long breachesNotAttributedTotal,
            Map<String, GroupAllocation> groups
    ) {
        public static final AllocationSnapshot EMPTY = new AllocationSnapshot(
                DEFAULT_CONFIGURED_SYNC_NANOS,
                0,
                0,
                0L,
                0L,
                0L,
                0.5,
                1.0,
                0.0,
                0L,
                Collections.emptyMap()
        );

        public AllocationSnapshot {
            groups = (groups == null || groups.isEmpty())
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(groups));
        }

        public SteeringWeight steeringWeightForGroup(String groupId, boolean permitsSaturated) {
            if (groupId != null) {
                GroupAllocation g = groups.get(groupId);
                if (g != null) {
                    return new SteeringWeight(
                            g.weight(),
                            g.adjustedWeight(),
                            maxAdjustedWeightWithQueuedWork
                    );
                }
            }
            double w = permitsSaturated ? 0.0 : 0.5;
            return new SteeringWeight(w, w, maxAdjustedWeightWithQueuedWork);
        }
    }

    private record GroupState(
            double limitNanos,
            double loanLimitNanos,
            long lastBreachNanos,
            long lastEvaluatedNanos,
            long lastActiveNanos,
            double interactiveNanosPerTick,
            double backgroundNanosPerTick
    ) {}

    private final AllocationSnapshot emptySnapshot;
    private final ConcurrentHashMap<String, GroupState> groupStates = new ConcurrentHashMap<>();
    private final AtomicLong lastAnyOverloadNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong breachesNotAttributedTotal = new AtomicLong(0L);
    private final AtomicLong deferredTotal = new AtomicLong(0L);
    private final AtomicLong agedTotal = new AtomicLong(0L);
    private volatile AllocationSnapshot latestSnapshot;

    public ProbedShareAllocator() {
        this(DEFAULT_CONFIGURED_SYNC_NANOS);
    }

    public ProbedShareAllocator(long defaultConfiguredSyncNanos) {
        long resolvedDefaultSyncNanos = Math.max(1_000L, defaultConfiguredSyncNanos);
        this.emptySnapshot = (resolvedDefaultSyncNanos == DEFAULT_CONFIGURED_SYNC_NANOS)
                ? AllocationSnapshot.EMPTY
                : new AllocationSnapshot(
                        resolvedDefaultSyncNanos,
                        0, 0, 0L, 0L, 0L, 0.5, 1.0, 0.0, 0L, Collections.emptyMap());
        this.latestSnapshot = this.emptySnapshot;
    }

    /**
     * Evaluates shares, weights, loans, Jain's fairness index {@code J}, and bystander exposure {@code E}
     * at {@code nowNanos}.
     *
     * @param configuredSyncNanos    configured per-thread sync budget {@code S_cfg} in nanoseconds
     * @param minShareNanos          one-task floor {@code S_min} in nanoseconds
     * @param distinctThreads        number of distinct tick threads {@code Theta} that ran work in the last 1 s
     * @param anyExternalOverloadNow {@code true} if any sampled region reported an external overload now
     * @param permitsSaturated       {@code true} if regional permits are saturated
     * @param signals                per-group signals
     * @param nowNanos               monotonic timestamp in nanoseconds
     * @return immutable {@link AllocationSnapshot}
     */
    public AllocationSnapshot evaluate(
            long configuredSyncNanos,
            long minShareNanos,
            int distinctThreads,
            boolean anyExternalOverloadNow,
            boolean permitsSaturated,
            Collection<GroupSignal> signals,
            long nowNanos
    ) {
        return evaluate(
                configuredSyncNanos,
                minShareNanos,
                distinctThreads,
                anyExternalOverloadNow,
                permitsSaturated,
                signals,
                Function.identity(),
                nowNanos
        );
    }

    /**
     * Evaluates shares, weights, loans, Jain's fairness index {@code J}, and bystander exposure {@code E}
     * at {@code nowNanos} by mapping arbitrary caller signal objects through {@code signalAdapter}
     * without allocating an intermediate signal list.
     */
    public <S> AllocationSnapshot evaluate(
            long configuredSyncNanos,
            long minShareNanos,
            int distinctThreads,
            boolean anyExternalOverloadNow,
            boolean permitsSaturated,
            Collection<S> rawSignals,
            Function<S, GroupSignal> signalAdapter,
            long nowNanos
    ) {
        long sCfg = Math.max(1_000L, configuredSyncNanos);
        long sMin = Math.max(1L, Math.min(minShareNanos, sCfg));
        double newGroupPrior = Math.max(sMin, 0.5 * sCfg);
        double contributionThreshold = Math.max(HALF_MILLI_NANOS, sCfg / 10.0);
        double stdRatePerSec = Math.max(0.0, (sCfg - sMin) / FULL_RECOVERY_SECONDS);

        evictExpiredGroups(nowNanos);

        if (anyExternalOverloadNow) {
            lastAnyOverloadNanos.set(nowNanos);
        }

        if (rawSignals == null || rawSignals.isEmpty() || signalAdapter == null) {
            AllocationSnapshot empty = new AllocationSnapshot(
                    sCfg, 0, Math.max(0, distinctThreads), 0L, 0L, 0L, 0.5, 1.0, 0.0,
                    breachesNotAttributedTotal.get(), Collections.emptyMap());
            this.latestSnapshot = empty;
            return empty;
        }

        Map<String, GroupSignal> signalById = new LinkedHashMap<>();
        Map<String, Long> rawShares = new LinkedHashMap<>();
        Map<String, Long> rawBgShares = new LinkedHashMap<>();
        Map<String, Long> limitNanosMap = new LinkedHashMap<>();
        long sumRawShares = 0L;

        for (S rawSig : rawSignals) {
            if (rawSig == null) continue;
            GroupSignal sig = signalAdapter.apply(rawSig);
            if (sig == null || sig.groupId().isEmpty()) continue;
            String id = sig.groupId();
            signalById.put(id, sig);

            GroupState prev = groupStates.get(id);
            double limit = (prev != null) ? clamp(prev.limitNanos(), sMin, sCfg) : newGroupPrior;
            double loanLimit = (prev != null) ? clamp(prev.loanLimitNanos(), 0.0, sCfg) : 0.0;
            long lastBreach = (prev != null) ? prev.lastBreachNanos() : Long.MIN_VALUE;
            double dtSec = (prev != null && nowNanos > prev.lastEvaluatedNanos())
                    ? (double) (nowNanos - prev.lastEvaluatedNanos()) / 1_000_000_000.0
                    : 0.25;

            if (sig.breached()) {
                lastAnyOverloadNanos.set(nowNanos);
                loanLimit = 0.5 * loanLimit;
                if (sig.recentTwoSecAttributedNanosPerTick() >= contributionThreshold) {
                    limit = Math.max((double) sMin, 0.5 * limit);
                    lastBreach = nowNanos;
                } else {
                    breachesNotAttributedTotal.incrementAndGet();
                }
            } else if (prev != null) {
                boolean canGrowFullRate = sig.population() <= 1 || sig.queuedInteractiveWork();
                if (canGrowFullRate) {
                    limit = Math.min((double) sCfg, limit + stdRatePerSec * dtSec);
                } else {
                    long cooldownEndNanos = (lastBreach != Long.MIN_VALUE)
                            ? (lastBreach + POPULATED_GROWTH_COOLDOWN_NANOS)
                            : Long.MIN_VALUE;
                    long growthStartNanos = Math.max(prev.lastEvaluatedNanos(), cooldownEndNanos);
                    if (nowNanos > growthStartNanos) {
                        double eligibleDtSec = (double) (nowNanos - growthStartNanos) / 1_000_000_000.0;
                        limit = Math.min((double) sCfg, limit + 0.25 * stdRatePerSec * eligibleDtSec);
                    }
                }
            }

            long sK = clampLong(Math.round(limit), sMin, sCfg);
            long cKi = Math.max(0L, Math.round(sig.interactiveNanosPerTick()));
            long sKb = Math.max(0L, sK - cKi);

            groupStates.put(id, new GroupState(
                    limit,
                    loanLimit,
                    lastBreach,
                    nowNanos,
                    nowNanos,
                    sig.interactiveNanosPerTick(),
                    sig.backgroundNanosPerTick()
            ));

            limitNanosMap.put(id, Math.round(limit));
            rawShares.put(id, sK);
            rawBgShares.put(id, sKb);
            sumRawShares += sK;
        }

        int kCount = signalById.size();
        int theta = Math.max(0, distinctThreads);
        long poolPi = sCfg * Math.min(kCount, theta);

        Map<String, Long> effectiveShares = new LinkedHashMap<>();
        Map<String, Long> effectiveBgShares = new LinkedHashMap<>();
        Map<String, Long> loans = new LinkedHashMap<>();
        long lendablePoolL = 0L;
        long totalLent = 0L;

        long lastOverload = lastAnyOverloadNanos.get();
        boolean anyOverloadLast5s = anyExternalOverloadNow
                || (lastOverload != Long.MIN_VALUE
                        && nowNanos >= lastOverload
                        && (nowNanos - lastOverload) < OVERLOAD_VETO_WINDOW_NANOS);

        if (sumRawShares > poolPi) {
            double scale = (sumRawShares > 0L) ? ((double) poolPi / (double) sumRawShares) : 0.0;
            for (Map.Entry<String, GroupSignal> e : signalById.entrySet()) {
                String id = e.getKey();
                GroupSignal sig = e.getValue();
                long scaledShare = Math.max(0L, (long) Math.floor(rawShares.get(id) * scale));
                long cKi = Math.max(0L, Math.round(sig.interactiveNanosPerTick()));
                long scaledBg = Math.min((long) Math.floor(rawBgShares.get(id) * scale),
                        Math.max(0L, scaledShare - cKi));
                effectiveShares.put(id, scaledShare);
                effectiveBgShares.put(id, Math.max(0L, scaledBg));
                loans.put(id, 0L);
            }
        } else {
            effectiveShares.putAll(rawShares);
            effectiveBgShares.putAll(rawBgShares);
            lendablePoolL = Math.max(0L, poolPi - sumRawShares);

            Map<String, Double> loanRequests = new LinkedHashMap<>();
            double sumLoanRequests = 0.0;

            for (Map.Entry<String, GroupSignal> e : signalById.entrySet()) {
                String id = e.getKey();
                GroupSignal sig = e.getValue();
                GroupState st = groupStates.get(id);
                long sK = effectiveShares.get(id);
                long sKb = effectiveBgShares.get(id);

                boolean hasQueuedExcess = sig.queuedBackgroundNanos() > (double) sKb;
                boolean atCeiling = sK >= sCfg;
                boolean noRecentBreach = st.lastBreachNanos() == Long.MIN_VALUE
                        || (nowNanos >= st.lastBreachNanos() && (nowNanos - st.lastBreachNanos()) >= LOAN_NO_BREACH_NANOS);
                boolean eligible = lendablePoolL > 0L
                        && hasQueuedExcess
                        && atCeiling
                        && noRecentBreach
                        && sig.exclusiveThreadInLastSecond()
                        && !anyOverloadLast5s
                        && !permitsSaturated;

                if (eligible) {
                    double nextLoanLimit = Math.min((double) sCfg, st.loanLimitNanos() + Math.max((double) sMin, stdRatePerSec));
                    groupStates.put(id, new GroupState(
                            st.limitNanos(), nextLoanLimit, st.lastBreachNanos(),
                            st.lastEvaluatedNanos(), st.lastActiveNanos(),
                            st.interactiveNanosPerTick(), st.backgroundNanosPerTick()));
                    double cap25 = Math.max(0.0, (double) (MAX_BORROWED_TOTAL_NANOS - sK));
                    double qExcess = Math.max(0.0, sig.queuedBackgroundNanos() - sKb);
                    double req = Math.min(Math.min(sCfg, cap25), Math.min(nextLoanLimit, qExcess));
                    if (req > 0.0) {
                        loanRequests.put(id, req);
                        sumLoanRequests += req;
                    }
                } else if (!sig.exclusiveThreadInLastSecond() || anyOverloadLast5s || permitsSaturated) {
                    loans.put(id, 0L);
                }
            }

            double loanScale = (sumLoanRequests > 0.0)
                    ? Math.min(1.0, (double) lendablePoolL / sumLoanRequests)
                    : 0.0;

            for (String id : signalById.keySet()) {
                double req = loanRequests.getOrDefault(id, 0.0);
                long sK = effectiveShares.get(id);
                long maxAllowedLoan = Math.max(0L, Math.min(sCfg, MAX_BORROWED_TOTAL_NANOS - sK));
                long eK = clampLong((long) Math.floor(req * loanScale + 1e-6), 0L, maxAllowedLoan);
                if (totalLent + eK > lendablePoolL) {
                    eK = Math.max(0L, lendablePoolL - totalLent);
                }
                loans.put(id, eK);
                totalLent += eK;
            }
        }

        Map<String, GroupAllocation> allocations = new LinkedHashMap<>();
        double maxAdjWeightQueued = 0.0;
        double maxAdjWeightAny = 0.0;
        double sumXk = 0.0;
        double sumXkSq = 0.0;
        int activeWorkRegions = 0;
        double sumWeightedBgMs = 0.0;
        long sumPopulation = 0L;

        for (Map.Entry<String, GroupSignal> e : signalById.entrySet()) {
            String id = e.getKey();
            GroupSignal sig = e.getValue();
            long limitK = limitNanosMap.get(id);
            long sK = effectiveShares.get(id);
            long sKb = effectiveBgShares.get(id);
            long eK = loans.getOrDefault(id, 0L);
            long effGroupBudget = sK + eK;
            long outerCeiling = (eK > 0L && sig.exclusiveThreadInLastSecond())
                    ? Math.min(Math.min(2L * sCfg, MAX_BORROWED_TOTAL_NANOS), sK + eK)
                    : sCfg;

            double wK = clamp(((double) (sKb + eK)) / (double) sCfg, 0.0, 2.0);
            double wPrimeK = wK / (1.0 + ((double) sig.population() / 4.0));
            maxAdjWeightAny = Math.max(maxAdjWeightAny, wPrimeK);
            if (sig.queuedBackgroundNanos() > 0.0) {
                maxAdjWeightQueued = Math.max(maxAdjWeightQueued, wPrimeK);
            }

            double cK = sig.interactiveNanosPerTick() + sig.backgroundNanosPerTick();
            if (cK > 0.0) {
                double xK = cK / Math.max(1.0, (double) limitK);
                sumXk += xK;
                sumXkSq += xK * xK;
                activeWorkRegions++;
            }

            if (sig.population() > 0) {
                double bgMs = sig.backgroundNanosPerTick() / 1_000_000.0;
                sumWeightedBgMs += (double) sig.population() * bgMs;
                sumPopulation += sig.population();
            }

            allocations.put(id, new GroupAllocation(
                    id,
                    limitK,
                    sK,
                    sKb,
                    eK,
                    effGroupBudget,
                    outerCeiling,
                    wK,
                    wPrimeK,
                    sig.population(),
                    sig.interactiveNanosPerTick(),
                    sig.backgroundNanosPerTick(),
                    sig.exclusiveThreadInLastSecond()
            ));
        }

        double effectiveMaxWPrime = (maxAdjWeightQueued > 0.0) ? maxAdjWeightQueued : maxAdjWeightAny;
        double jainJ = (activeWorkRegions > 0 && sumXkSq > 0.0)
                ? ((sumXk * sumXk) / ((double) activeWorkRegions * sumXkSq))
                : 1.0;
        double exposureE = (sumPopulation > 0L)
                ? (sumWeightedBgMs / (double) sumPopulation)
                : 0.0;

        AllocationSnapshot snapshot = new AllocationSnapshot(
                sCfg,
                kCount,
                theta,
                poolPi,
                lendablePoolL,
                totalLent,
                effectiveMaxWPrime,
                clamp(jainJ, 0.0, 1.0),
                Math.max(0.0, exposureE),
                breachesNotAttributedTotal.get(),
                allocations
        );
        this.latestSnapshot = snapshot;
        return snapshot;
    }

    /**
     * Records a group split: the new split group starts at {@code min(oldLimit, S_cfg / 2)}.
     */
    public void recordSplit(String oldGroupId, String newGroupId, long configuredSyncNanos, long nowNanos) {
        if (newGroupId == null || newGroupId.isEmpty()) return;
        double prior = 0.5 * Math.max(1_000L, configuredSyncNanos);
        GroupState old = (oldGroupId != null) ? groupStates.get(oldGroupId) : null;
        double startLimit = (old != null) ? Math.min(old.limitNanos(), prior) : prior;
        long lastBreach = (old != null) ? old.lastBreachNanos() : Long.MIN_VALUE;
        groupStates.put(newGroupId, new GroupState(
                startLimit, 0.0, lastBreach, nowNanos, nowNanos, 0.0, 0.0));
    }

    /**
     * Records a group merge: the surviving group keeps {@code min(limit_A, limit_B)} and sums
     * their interactive and background ledgers.
     */
    public void recordMerge(String survivingGroupId, String absorbedGroupId, long nowNanos) {
        if (survivingGroupId == null || absorbedGroupId == null || survivingGroupId.equals(absorbedGroupId)) {
            return;
        }
        GroupState a = groupStates.get(survivingGroupId);
        GroupState b = groupStates.remove(absorbedGroupId);
        if (b == null) return;
        if (a == null) {
            groupStates.put(survivingGroupId, new GroupState(
                    b.limitNanos(), b.loanLimitNanos(), b.lastBreachNanos(),
                    nowNanos, nowNanos, b.interactiveNanosPerTick(), b.backgroundNanosPerTick()));
            return;
        }
        double mergedLimit = Math.min(a.limitNanos(), b.limitNanos());
        double mergedLoanLimit = Math.min(a.loanLimitNanos(), b.loanLimitNanos());
        long mergedBreach = Math.max(a.lastBreachNanos(), b.lastBreachNanos());
        groupStates.put(survivingGroupId, new GroupState(
                mergedLimit,
                mergedLoanLimit,
                mergedBreach,
                nowNanos,
                nowNanos,
                a.interactiveNanosPerTick() + b.interactiveNanosPerTick(),
                a.backgroundNanosPerTick() + b.backgroundNanosPerTick()
        ));
    }

    /**
     * Returns {@code true} when a deferred background candidate has reached the aging bound
     * ({@value #CANDIDATE_AGING_NANOS} ns of unpaused time or {@value #CANDIDATE_MAX_DEFERRALS} deferrals).
     */
    public static boolean isCandidateAged(int deferralCount, long unpausedDeferredNanos) {
        return deferralCount >= CANDIDATE_MAX_DEFERRALS
                || unpausedDeferredNanos >= CANDIDATE_AGING_NANOS;
    }

    public void recordCandidateDeferred() {
        deferredTotal.incrementAndGet();
    }

    public void recordCandidateAged() {
        agedTotal.incrementAndGet();
    }

    public long deferredTotal() {
        return deferredTotal.get();
    }

    public long agedTotal() {
        return agedTotal.get();
    }

    public long breachesNotAttributedTotal() {
        return breachesNotAttributedTotal.get();
    }

    public AllocationSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    public int trackedGroupCount(long nowNanos) {
        evictExpiredGroups(nowNanos);
        return groupStates.size();
    }

    public Double currentGroupLimitNanos(String groupId) {
        GroupState st = (groupId != null) ? groupStates.get(groupId) : null;
        return (st != null) ? st.limitNanos() : null;
    }

    public void reset() {
        groupStates.clear();
        lastAnyOverloadNanos.set(Long.MIN_VALUE);
        breachesNotAttributedTotal.set(0L);
        deferredTotal.set(0L);
        agedTotal.set(0L);
        latestSnapshot = emptySnapshot;
    }

    private void evictExpiredGroups(long nowNanos) {
        groupStates.entrySet().removeIf(e -> {
            GroupState st = e.getValue();
            return st == null || (nowNanos >= st.lastActiveNanos()
                    && (nowNanos - st.lastActiveNanos()) > GROUP_IDLE_EXPIRY_NANOS);
        });
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long clampLong(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
