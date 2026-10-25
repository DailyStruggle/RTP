package io.github.dailystruggle.rtp.common.predict;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and property verification for {@link ProbedShareAllocator}.
 *
 * @governedBy ADR-087
 * @governedBy ADR-115
 * @governedBy ADR-123
 */
@DisplayName("ADR-123: ProbedShareAllocator Probed Shares, Pool Lending, Steering Weights, and Aging")
class ProbedShareAllocatorTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);

    private ProbedShareAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new ProbedShareAllocator();
    }

    @Test
    @DisplayName("Pool bound Pi <= S_cfg * Theta and per-thread/loan caps hold across random topologies")
    void poolAndLoanBoundsHoldAcrossRandomTopologies() {
        Random rnd = new Random(12301L);

        for (int trial = 0; trial < 100; trial++) {
            allocator.reset();
            long sCfg = (4L + rnd.nextInt(15)) * MS;
            long sMin = 500_000L;
            int kCount = 1 + rnd.nextInt(12);
            int theta = 1 + rnd.nextInt(8);
            long now = (trial + 1L) * 20L * SEC;

            List<ProbedShareAllocator.GroupSignal> warmup = new ArrayList<>();
            for (int k = 0; k < kCount; k++) {
                warmup.add(new ProbedShareAllocator.GroupSignal(
                        "g" + k, 0.0, sCfg * 0.5, sCfg * 0.5, sCfg * 2.0, true, 0, false, true));
            }
            allocator.evaluate(sCfg, sMin, theta, false, false, warmup, now);
            allocator.evaluate(sCfg, sMin, theta, false, false, warmup, now + 60 * SEC);

            List<ProbedShareAllocator.GroupSignal> signals = new ArrayList<>();
            for (int k = 0; k < kCount; k++) {
                double cI = rnd.nextDouble() * (sCfg * 0.5);
                double cB = rnd.nextDouble() * sCfg;
                double qB = rnd.nextBoolean() ? rnd.nextDouble() * (2.5 * sCfg) : 0.0;
                int pop = rnd.nextInt(20);
                boolean breached = rnd.nextInt(8) == 0;
                boolean exclusive = rnd.nextBoolean();
                signals.add(new ProbedShareAllocator.GroupSignal(
                        "g" + k, cI, cB, cI + cB, qB, false, pop, breached, exclusive));
            }

            boolean externalOverload = rnd.nextInt(10) == 0;
            boolean permitsSat = rnd.nextInt(10) == 0;
            ProbedShareAllocator.AllocationSnapshot snap = allocator.evaluate(
                    sCfg, sMin, theta, externalOverload, permitsSat, signals, now + 120 * SEC);

            long expectedPi = sCfg * (long) Math.min(kCount, theta);
            assertEquals(expectedPi, snap.poolBudgetNanos());

            long sumSharesAndLoans = 0L;
            long sumBgSharesAndLoans = 0L;
            for (ProbedShareAllocator.GroupAllocation ga : snap.groups().values()) {
                sumSharesAndLoans += ga.shareNanos() + ga.loanNanos();
                sumBgSharesAndLoans += ga.backgroundShareNanos() + ga.loanNanos();
                assertTrue(ga.loanNanos() <= sCfg);
                assertTrue(ga.shareNanos() + ga.loanNanos() <= ProbedShareAllocator.MAX_BORROWED_TOTAL_NANOS
                        || ga.loanNanos() == 0L);
                if (ga.loanNanos() > 0L && ga.exclusiveThread()) {
                    long maxAllowedOuter = Math.min(2L * sCfg, ProbedShareAllocator.MAX_BORROWED_TOTAL_NANOS);
                    assertTrue(ga.outerThreadCeilingNanos() <= maxAllowedOuter);
                } else {
                    assertEquals(sCfg, ga.outerThreadCeilingNanos());
                }
            }
            assertTrue(sumSharesAndLoans <= expectedPi);
            assertTrue(sumBgSharesAndLoans <= expectedPi);
            assertTrue(snap.fairnessIndexJ() >= 0.0 && snap.fairnessIndexJ() <= 1.0);
        }
    }

    @Test
    @DisplayName("Contribution-gated breach cuts, populated 60 s cooldown, and 1/4 growth rate")
    void contributionGatedBreachCutsAndPopulationAwareGrowth() {
        long sCfg = 10 * MS;
        long sMin = MS;

        ProbedShareAllocator.AllocationSnapshot s0 = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new ProbedShareAllocator.GroupSignal("pop", 0, 0, 0.2 * MS, 0, false, 8, false, true),
                        new ProbedShareAllocator.GroupSignal("empty", 0, 0, 0.2 * MS, 0, false, 0, false, true)
                ),
                10 * SEC);
        assertEquals(5 * MS, s0.groups().get("pop").shareNanos());
        assertEquals(5 * MS, s0.groups().get("empty").shareNanos());

        // Non-attributed breach (< S_cfg / 10) does not cut limit_k and increments breachesNotAttributedTotal
        ProbedShareAllocator.AllocationSnapshot sNonAttr = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new ProbedShareAllocator.GroupSignal("pop", 0, 0, 0.2 * MS, 0, false, 8, true, true),
                        new ProbedShareAllocator.GroupSignal("empty", 0, 0, 0.2 * MS, 0, false, 0, false, true)
                ),
                12 * SEC);
        assertEquals(5 * MS, sNonAttr.groups().get("pop").shareNanos());
        assertEquals(1L, sNonAttr.breachesNotAttributedTotal());
        assertEquals(1L, allocator.breachesNotAttributedTotal());

        // Attributed breach halves both to 2.5 ms
        ProbedShareAllocator.AllocationSnapshot sBreach = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new ProbedShareAllocator.GroupSignal("pop", 0, 2 * MS, 2 * MS, 0, false, 8, true, true),
                        new ProbedShareAllocator.GroupSignal("empty", 0, 2 * MS, 2 * MS, 0, false, 0, true, true)
                ),
                20 * SEC);
        assertEquals(2_500_000L, sBreach.groups().get("pop").shareNanos());

        // 30 s after breach (< 60 s cooldown): populated group holds while empty group grows
        ProbedShareAllocator.AllocationSnapshot sAfter30s = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new ProbedShareAllocator.GroupSignal("pop", 0, 0, 0, 0, false, 8, false, true),
                        new ProbedShareAllocator.GroupSignal("empty", 0, 0, 0, 0, false, 0, false, true)
                ),
                50 * SEC);
        assertEquals(2_500_000L, sAfter30s.groups().get("pop").shareNanos());
        assertTrue(sAfter30s.groups().get("empty").shareNanos() > 2_500_000L);
    }

    @Test
    @DisplayName("Split/merge state transfer, steering weights, candidate aging, and counters")
    void splitMergeSteeringAndCandidateAging() {
        long sCfg = 10 * MS;
        long sMin = MS;

        allocator.evaluate(sCfg, sMin, 2, false, false,
                List.of(new ProbedShareAllocator.GroupSignal("A", 0, 0, 0, 10 * MS, true, 0, false, true)),
                10 * SEC);
        allocator.evaluate(sCfg, sMin, 2, false, false,
                List.of(new ProbedShareAllocator.GroupSignal("A", 0, 0, 0, 10 * MS, true, 0, false, true)),
                70 * SEC);
        assertEquals((double) sCfg, allocator.currentGroupLimitNanos("A"), 1.0);

        allocator.recordSplit("A", "C", sCfg, 70 * SEC);
        assertEquals(5.0 * MS, allocator.currentGroupLimitNanos("C"), 1.0);

        allocator.recordMerge("A", "C", 70 * SEC);
        assertEquals(5.0 * MS, allocator.currentGroupLimitNanos("A"), 1.0);

        ProbedShareAllocator.AllocationSnapshot snap = allocator.latestSnapshot();
        assertTrue(snap.steeringWeightForGroup("A", false).shouldProcessNow());
        assertFalse(snap.steeringWeightForGroup("unowned", true).shouldProcessNow());
        assertTrue(snap.steeringWeightForGroup("unowned", false).shouldProcessNow());

        assertFalse(ProbedShareAllocator.isCandidateAged(7, 59 * SEC));
        assertTrue(ProbedShareAllocator.isCandidateAged(8, 10 * SEC));
        assertTrue(ProbedShareAllocator.isCandidateAged(1, 60 * SEC));

        allocator.recordCandidateDeferred();
        allocator.recordCandidateAged();
        assertEquals(1L, allocator.deferredTotal());
        assertEquals(1L, allocator.agedTotal());

        assertEquals(0, allocator.trackedGroupCount(200 * SEC));
        allocator.reset();
        assertEquals(0L, allocator.deferredTotal());
        assertEquals(0L, allocator.agedTotal());
    }
}
