package io.github.dailystruggle.rtp.common.governor;

import io.github.dailystruggle.rtp.common.predict.ProbedShareAllocator;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Pure Folia tick-region share allocator computing probed regional shares {@code S_k},
 * steering weights {@code w_k} and {@code w'_k}, bounded pool loans {@code e_k} under {@code Pi},
 * Jain's fairness index {@code J}, and bystander exposure {@code E} (ADR-123 Sections 3, 6-9).
 *
 * <p>Holds zero locks and creates zero threads (F-001, ADR-087 Invariant I8).
 *
 * @governedBy ADR-123 (Folia Tick-Region Allocation: Work Placement, Probed Shares, Steering and Lending)
 * @invariant I1: Server-wide background sync spend within Pi <= S_cfg * Theta and per-thread within S_cfg
 *                (or min(2 * S_cfg, 25 ms) on an exclusive borrower thread)
 * @invariant I9: Steering defers background candidates for at most 60 s or 8 deferrals of unpaused time
 */
public final class RegionShareAllocator {

    public static final long MAX_BORROWED_TOTAL_NANOS = ProbedShareAllocator.MAX_BORROWED_TOTAL_NANOS;
    public static final long HALF_MILLI_NANOS = ProbedShareAllocator.HALF_MILLI_NANOS;
    public static final long GROUP_IDLE_EXPIRY_NANOS = ProbedShareAllocator.GROUP_IDLE_EXPIRY_NANOS;
    public static final long POPULATED_GROWTH_COOLDOWN_NANOS = ProbedShareAllocator.POPULATED_GROWTH_COOLDOWN_NANOS;
    public static final long LOAN_NO_BREACH_NANOS = ProbedShareAllocator.LOAN_NO_BREACH_NANOS;
    public static final long OVERLOAD_VETO_WINDOW_NANOS = ProbedShareAllocator.OVERLOAD_VETO_WINDOW_NANOS;
    public static final long CANDIDATE_AGING_NANOS = ProbedShareAllocator.CANDIDATE_AGING_NANOS;
    public static final int CANDIDATE_MAX_DEFERRALS = ProbedShareAllocator.CANDIDATE_MAX_DEFERRALS;
    public static final double HEADROOM_STEERING_THRESHOLD = ProbedShareAllocator.HEADROOM_STEERING_THRESHOLD;
    public static final double FULL_RECOVERY_SECONDS = ProbedShareAllocator.FULL_RECOVERY_SECONDS;

    /**
     * Per-cell-group telemetry input for a single allocator evaluation (ADR-123 Section 5).
     */
    public record GroupSignal(
            String groupId,
            double interactiveNanosPerTick,
            double backgroundNanosPerTick,
            double recentTwoSecRtpNanosPerTick,
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
            if (Double.isNaN(recentTwoSecRtpNanosPerTick) || recentTwoSecRtpNanosPerTick < 0.0) recentTwoSecRtpNanosPerTick = 0.0;
            if (Double.isNaN(queuedBackgroundNanos) || queuedBackgroundNanos < 0.0) queuedBackgroundNanos = 0.0;
            population = Math.max(0, population);
        }
    }

    /**
     * Computed share, loan, outer thread ceiling, and steering weights for one cell group {@code k}
     * (ADR-123 Sections 6-8).
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
     * Steering weight view for a candidate chunk's cell group (ADR-123 Section 7).
     *
     * @param weight                           headroom weight {@code w_k = (S_k^b + e_k) / S_cfg}
     * @param adjustedWeight                   bystander-adjusted weight {@code w'_k = w_k / (1 + P_k / 4)}
     * @param maxAdjustedWeightWithQueuedWork  {@code max_j w'_j} across groups with queued background work
     */
    public record RegionSteeringWeight(
            double weight,
            double adjustedWeight,
            double maxAdjustedWeightWithQueuedWork
    ) {
        public static final RegionSteeringWeight DEFAULT_UNOWNED =
                new RegionSteeringWeight(0.5, 0.5, 0.5);

        /**
         * Returns {@code true} when a non-aged candidate should be processed immediately:
         * {@code w_k >= 0.5} and {@code w'_k >= 0.5 * max_j w'_j} (ADR-123 Section 7).
         */
        public boolean shouldProcessNow() {
            return weight + 1e-9 >= HEADROOM_STEERING_THRESHOLD
                    && adjustedWeight + 1e-9 >= 0.5 * maxAdjustedWeightWithQueuedWork;
        }
    }

    /**
     * Immutable allocation snapshot published on {@link ComputeBudget} (ADR-123 Sections 6-9).
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
            long breachesNotRtpTotal,
            Map<String, GroupAllocation> groups
    ) {
        public static final AllocationSnapshot EMPTY = new AllocationSnapshot(
                ComputeSignals.DEFAULT_SYNC_NANOS,
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
                    : Collections.unmodifiableMap(groups);
        }

        public RegionSteeringWeight steeringWeightForGroup(String groupId, boolean permitsSaturated) {
            if (groupId != null) {
                GroupAllocation g = groups.get(groupId);
                if (g != null) {
                    return new RegionSteeringWeight(
                            g.weight(),
                            g.adjustedWeight(),
                            maxAdjustedWeightWithQueuedWork
                    );
                }
            }
            // Type C candidate (no existing tick region): w = 0.5, P = 0 when permits are free;
            // deferred (w = 0) when regional permits are saturated (ADR-123 Section 7).
            double w = permitsSaturated ? 0.0 : 0.5;
            return new RegionSteeringWeight(w, w, maxAdjustedWeightWithQueuedWork);
        }
    }

    private final ProbedShareAllocator delegate = new ProbedShareAllocator(ComputeSignals.DEFAULT_SYNC_NANOS);
    private volatile AllocationSnapshot latestSnapshot = AllocationSnapshot.EMPTY;

    /**
     * Evaluates shares, weights, loans, Jain's fairness index {@code J}, and bystander exposure {@code E}
     * at {@code nowNanos} (ADR-123 Sections 6-9).
     *
     * @param configuredSyncNanos     configured {@code syncAllottedTime} ({@code S_cfg}) in nanoseconds
     * @param minShareNanos           one-task floor {@code S_min} in nanoseconds
     * @param distinctThreads         number of distinct tick threads {@code Theta} that ran RTP work in the last 1 s
     * @param anyExternalOverloadNow  {@code true} if any sampled region reported an external overload now
     * @param permitsSaturated        {@code true} if ADR-087 Section 3.2 regional permits are saturated
     * @param signals                 per-group signals
     * @param nowNanos                monotonic timestamp in nanoseconds
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
        ProbedShareAllocator.AllocationSnapshot snap = delegate.evaluate(
                configuredSyncNanos,
                minShareNanos,
                distinctThreads,
                anyExternalOverloadNow,
                permitsSaturated,
                signals,
                RegionShareAllocator::toProbedSignal,
                nowNanos
        );
        AllocationSnapshot adapted = toSnapshot(snap);
        this.latestSnapshot = adapted;
        return adapted;
    }

    /**
     * Records a cell-group split: the new split group starts at {@code min(oldLimit, S_cfg / 2)}
     * (ADR-123 Section 3).
     */
    public void recordSplit(String oldGroupId, String newGroupId, long configuredSyncNanos, long nowNanos) {
        delegate.recordSplit(oldGroupId, newGroupId, configuredSyncNanos, nowNanos);
    }

    /**
     * Records a cell-group merge: the surviving group keeps {@code min(limit_A, limit_B)} and sums
     * their interactive and background ledgers (ADR-123 Section 3).
     */
    public void recordMerge(String survivingGroupId, String absorbedGroupId, long nowNanos) {
        delegate.recordMerge(survivingGroupId, absorbedGroupId, nowNanos);
    }

    /**
     * Returns {@code true} when a deferred background candidate has reached the aging bound
     * (60 s of unpaused time or 8 deferrals, Invariant I9).
     */
    public static boolean isCandidateAged(int deferralCount, long unpausedDeferredNanos) {
        return ProbedShareAllocator.isCandidateAged(deferralCount, unpausedDeferredNanos);
    }

    public void recordCandidateDeferred() {
        delegate.recordCandidateDeferred();
    }

    public void recordCandidateAged() {
        delegate.recordCandidateAged();
    }

    public long deferredTotal() {
        return delegate.deferredTotal();
    }

    public long agedTotal() {
        return delegate.agedTotal();
    }

    public long breachesNotRtpTotal() {
        return delegate.breachesNotAttributedTotal();
    }

    public AllocationSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    public int trackedGroupCount(long nowNanos) {
        return delegate.trackedGroupCount(nowNanos);
    }

    public Double currentGroupLimitNanos(String groupId) {
        return delegate.currentGroupLimitNanos(groupId);
    }

    public void reset() {
        delegate.reset();
        latestSnapshot = AllocationSnapshot.EMPTY;
    }

    private static ProbedShareAllocator.GroupSignal toProbedSignal(GroupSignal sig) {
        if (sig == null) return null;
        return new ProbedShareAllocator.GroupSignal(
                sig.groupId(),
                sig.interactiveNanosPerTick(),
                sig.backgroundNanosPerTick(),
                sig.recentTwoSecRtpNanosPerTick(),
                sig.queuedBackgroundNanos(),
                sig.queuedInteractiveWork(),
                sig.population(),
                sig.breached(),
                sig.exclusiveThreadInLastSecond()
        );
    }

    private static GroupAllocation toGroupAllocation(ProbedShareAllocator.GroupAllocation g) {
        if (g == null) return null;
        return new GroupAllocation(
                g.groupId(),
                g.limitNanos(),
                g.shareNanos(),
                g.backgroundShareNanos(),
                g.loanNanos(),
                g.effectiveGroupBudgetNanos(),
                g.outerThreadCeilingNanos(),
                g.weight(),
                g.adjustedWeight(),
                g.population(),
                g.interactiveNanosPerTick(),
                g.backgroundNanosPerTick(),
                g.exclusiveThread()
        );
    }

    private static AllocationSnapshot toSnapshot(ProbedShareAllocator.AllocationSnapshot snap) {
        Map<String, GroupAllocation> groups = snap.groups().isEmpty()
                ? Collections.emptyMap()
                : new GroupAllocationMapView(snap.groups());
        return new AllocationSnapshot(
                snap.configuredSyncNanos(),
                snap.activeGroups(),
                snap.distinctThreads(),
                snap.poolBudgetNanos(),
                snap.lendablePoolNanos(),
                snap.totalLentNanos(),
                snap.maxAdjustedWeightWithQueuedWork(),
                snap.fairnessIndexJ(),
                snap.bystanderExposureMs(),
                snap.breachesNotAttributedTotal(),
                groups
        );
    }

    private static final class GroupAllocationMapView extends AbstractMap<String, GroupAllocation> {
        private final Map<String, ProbedShareAllocator.GroupAllocation> backing;

        GroupAllocationMapView(Map<String, ProbedShareAllocator.GroupAllocation> backing) {
            this.backing = backing;
        }

        @Override
        public int size() {
            return backing.size();
        }

        @Override
        public boolean isEmpty() {
            return backing.isEmpty();
        }

        @Override
        public boolean containsKey(Object key) {
            return backing.containsKey(key);
        }

        @Override
        public GroupAllocation get(Object key) {
            return toGroupAllocation(backing.get(key));
        }

        @Override
        public Set<Entry<String, GroupAllocation>> entrySet() {
            return new AbstractSet<>() {
                @Override
                public Iterator<Entry<String, GroupAllocation>> iterator() {
                    Iterator<Entry<String, ProbedShareAllocator.GroupAllocation>> it = backing.entrySet().iterator();
                    return new Iterator<>() {
                        @Override
                        public boolean hasNext() {
                            return it.hasNext();
                        }

                        @Override
                        public Entry<String, GroupAllocation> next() {
                            Entry<String, ProbedShareAllocator.GroupAllocation> e = it.next();
                            return new SimpleImmutableEntry<>(e.getKey(), toGroupAllocation(e.getValue()));
                        }
                    };
                }

                @Override
                public int size() {
                    return backing.size();
                }
            };
        }
    }
}
