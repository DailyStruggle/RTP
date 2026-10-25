package io.github.dailystruggle.rtp.common.governor;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit and property verification for {@link RegionShareAllocator} and candidate steering/aging
 * (ADR-123 Sections 3, 6-9, 12, Invariants I1 & I9).
 */
@DisplayName("ADR-123: RegionShareAllocator shares, contribution-gated cuts, lending pool (I1), and aging (I9)")
class RegionShareAllocatorTest {

    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long SEC = TimeUnit.SECONDS.toNanos(1);

    private RegionShareAllocator allocator;

    @BeforeEach
    void setUp() {
        allocator = new RegionShareAllocator();
    }

    @Test
    @DisplayName("Invariant I1 property test: pool bound Pi <= S_cfg * Theta and per-thread/loan caps hold across random topologies")
    void invariantI1PoolAndLoanBoundsHoldAcrossRandomTopologies() {
        Random rnd = new Random(12301L);

        for (int trial = 0; trial < 100; trial++) {
            allocator.reset();
            long sCfg = (4L + rnd.nextInt(15)) * MS; // 4..18 ms
            long sMin = 500_000L; // 0.5 ms
            int kCount = 1 + rnd.nextInt(12);
            int theta = 1 + rnd.nextInt(8);
            long now = (trial + 1L) * 20L * SEC;

            // Warm up groups so some reach S_cfg and can request loans
            List<RegionShareAllocator.GroupSignal> warmup = new ArrayList<>();
            for (int k = 0; k < kCount; k++) {
                warmup.add(new RegionShareAllocator.GroupSignal(
                        "g" + k, 0.0, sCfg * 0.5, sCfg * 0.5, sCfg * 2.0, true, 0, false, true));
            }
            allocator.evaluate(sCfg, sMin, theta, false, false, warmup, now);
            allocator.evaluate(sCfg, sMin, theta, false, false, warmup, now + 60 * SEC);

            List<RegionShareAllocator.GroupSignal> signals = new ArrayList<>();
            for (int k = 0; k < kCount; k++) {
                double cI = rnd.nextDouble() * (sCfg * 0.5);
                double cB = rnd.nextDouble() * sCfg;
                double qB = rnd.nextBoolean() ? rnd.nextDouble() * (2.5 * sCfg) : 0.0;
                int pop = rnd.nextInt(20);
                boolean breached = rnd.nextInt(8) == 0;
                boolean exclusive = rnd.nextBoolean();
                signals.add(new RegionShareAllocator.GroupSignal(
                        "g" + k, cI, cB, cI + cB, qB, false, pop, breached, exclusive));
            }

            boolean externalOverload = rnd.nextInt(10) == 0;
            boolean permitsSat = rnd.nextInt(10) == 0;
            RegionShareAllocator.AllocationSnapshot snap = allocator.evaluate(
                    sCfg, sMin, theta, externalOverload, permitsSat, signals, now + 120 * SEC);

            long expectedPi = sCfg * (long) Math.min(kCount, theta);
            assertEquals(expectedPi, snap.poolBudgetNanos());

            long sumSharesAndLoans = 0L;
            long sumBgSharesAndLoans = 0L;
            for (RegionShareAllocator.GroupAllocation ga : snap.groups().values()) {
                sumSharesAndLoans += ga.shareNanos() + ga.loanNanos();
                sumBgSharesAndLoans += ga.backgroundShareNanos() + ga.loanNanos();
                assertTrue(ga.loanNanos() <= sCfg, "Loan e_k must never exceed S_cfg");
                assertTrue(ga.shareNanos() + ga.loanNanos() <= RegionShareAllocator.MAX_BORROWED_TOTAL_NANOS
                                || ga.loanNanos() == 0L,
                        "Borrowed total S_k + e_k must never exceed 25 ms");
                if (ga.loanNanos() > 0L && ga.exclusiveThread()) {
                    long maxAllowedOuter = Math.min(2L * sCfg, RegionShareAllocator.MAX_BORROWED_TOTAL_NANOS);
                    assertTrue(ga.outerThreadCeilingNanos() <= maxAllowedOuter,
                            "Exclusive borrower outer ceiling must stay within min(2 * S_cfg, 25 ms)");
                } else {
                    assertEquals(sCfg, ga.outerThreadCeilingNanos(),
                            "Non-exclusive or un-lent thread ceiling must equal S_cfg");
                }
            }
            assertTrue(sumSharesAndLoans <= expectedPi,
                    "Total shares + loans (" + sumSharesAndLoans + ") must never exceed Pi (" + expectedPi + ")");
            assertTrue(sumBgSharesAndLoans <= expectedPi,
                    "Total background shares + loans must never exceed Pi");
            assertTrue(snap.fairnessIndexJ() >= 0.0 && snap.fairnessIndexJ() <= 1.0);
        }
    }

    @Test
    @DisplayName("ADR-123 Section 6: Breach cuts only when RTP contributed >= max(0.5 ms, S_cfg / 10); populated regions wait 60 s and grow at 1/4 rate")
    void contributionGatedBreachCutsAndPopulationAwareGrowth() {
        long sCfg = 10 * MS;
        long sMin = MS;

        // 1. New group starts at S_cfg / 2 = 5 ms
        RegionShareAllocator.AllocationSnapshot s0 = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("pop", 0, 0, 0.2 * MS, 0, false, 8, false, true),
                        new RegionShareAllocator.GroupSignal("empty", 0, 0, 0.2 * MS, 0, false, 0, false, true)
                ),
                10 * SEC);
        assertEquals(5 * MS, s0.groups().get("pop").shareNanos());
        assertEquals(5 * MS, s0.groups().get("empty").shareNanos());

        // 2. Breach on "pop" with RTP contribution 0.2 ms < max(0.5 ms, 1.0 ms) = 1.0 ms -> NOT cut, breachesNotRtpTotal++
        RegionShareAllocator.AllocationSnapshot sNonRtpBreach = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("pop", 0, 0, 0.2 * MS, 0, false, 8, true, true),
                        new RegionShareAllocator.GroupSignal("empty", 0, 0, 0.2 * MS, 0, false, 0, false, true)
                ),
                12 * SEC);
        assertEquals(5 * MS, sNonRtpBreach.groups().get("pop").shareNanos(),
                "Breach where RTP charged < S_cfg/10 must not cut limit_k");
        assertEquals(1L, sNonRtpBreach.breachesNotRtpTotal());

        // 3. Breach on both "pop" and "empty" with RTP contribution 2.0 ms >= 1.0 ms -> halves both from 5 ms to 2.5 ms
        RegionShareAllocator.AllocationSnapshot sRtpBreach = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("pop", 0, 2 * MS, 2 * MS, 0, false, 8, true, true),
                        new RegionShareAllocator.GroupSignal("empty", 0, 2 * MS, 2 * MS, 0, false, 0, true, true)
                ),
                20 * SEC);
        long popCut = sRtpBreach.groups().get("pop").shareNanos();
        long emptyCut = sRtpBreach.groups().get("empty").shareNanos();
        assertEquals(2_500_000L, popCut);
        assertTrue(emptyCut < 3_000_000L);

        // 4. 30 s after breach (< 60 s cooldown): populated region (P=8) does NOT grow, while empty region (P=0) grows
        RegionShareAllocator.AllocationSnapshot sAfter30s = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("pop", 0, 0, 0, 0, false, 8, false, true),
                        new RegionShareAllocator.GroupSignal("empty", 0, 0, 0, 0, false, 0, false, true)
                ),
                50 * SEC);
        assertEquals(popCut, sAfter30s.groups().get("pop").shareNanos(),
                "Populated region (P_k > 1) must not grow within 60 s of a breach");
        assertTrue(sAfter30s.groups().get("empty").shareNanos() > emptyCut,
                "Empty region (P_k <= 1) grows immediately after breach clears");

        // 5. At t = 80 s (60 s after breach at 20 s) and t = 90 s (+10 s): populated region grows at 1/4 standard rate
        allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(new RegionShareAllocator.GroupSignal("pop", 0, 0, 0, 0, false, 8, false, true)),
                80 * SEC);
        RegionShareAllocator.AllocationSnapshot sAfter90s = allocator.evaluate(
                sCfg, sMin, 4, false, false,
                List.of(new RegionShareAllocator.GroupSignal("pop", 0, 0, 0, 0, false, 8, false, true)),
                90 * SEC);
        // Standard rate = (10 ms - 1 ms) / 50 s = 0.18 ms/s -> over 10 s at 1/4 rate = +0.45 ms -> 2.95 ms
        assertEquals(2_950_000L, sAfter90s.groups().get("pop").shareNanos(), 10_000L);
    }

    @Test
    @DisplayName("ADR-123 Section 3 & 8: Split/merge rules, 60 s group expiry, lending eligibility, and 5 s overload veto")
    void splitMergeExpiryAndLendingVeto() {
        long sCfg = 10 * MS;
        long sMin = MS;

        // Grow group A to 10 ms and keep group B at 5 ms
        allocator.evaluate(sCfg, sMin, 2, false, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("A", 0, 0, 0, 0, true, 0, false, true),
                        new RegionShareAllocator.GroupSignal("B", 0, 0, 0, 0, false, 0, false, true)
                ),
                10 * SEC);
        allocator.evaluate(sCfg, sMin, 2, false, false,
                List.of(new RegionShareAllocator.GroupSignal("A", 0, 0, 0, 0, true, 0, false, true)),
                70 * SEC);
        assertEquals((double) sCfg, allocator.currentGroupLimitNanos("A"), 1.0);

        // Split from A -> child C starts at min(limit_A, S_cfg / 2) = 5 ms
        allocator.recordSplit("A", "C", sCfg, 70 * SEC);
        assertEquals(5.0 * MS, allocator.currentGroupLimitNanos("C"), 1.0);

        // Merge A (10 ms) and C (5 ms) -> surviving A keeps min(10 ms, 5 ms) = 5 ms
        allocator.recordMerge("A", "C", 70 * SEC);
        assertEquals(5.0 * MS, allocator.currentGroupLimitNanos("A"), 1.0);

        // Grow A back to 10 ms at t = 130 s, while D sits at 5 ms -> Pi = 20 ms, sum(S) = 15 ms, L = 5 ms
        allocator.evaluate(sCfg, sMin, 2, false, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("A", 0, 0, 0, 20 * MS, true, 0, false, true),
                        new RegionShareAllocator.GroupSignal("D", 0, 0, 0, 0, false, 4, false, true)
                ),
                130 * SEC);
        RegionShareAllocator.AllocationSnapshot withLoan = allocator.evaluate(sCfg, sMin, 2, false, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("A", 0, 5 * MS, 5 * MS, 20 * MS, true, 0, false, true),
                        new RegionShareAllocator.GroupSignal("D", 0, 2 * MS, 2 * MS, 0, false, 4, false, true)
                ),
                145 * SEC);
        assertTrue(withLoan.groups().get("A").loanNanos() > 0L,
                "Eligible group A at ceiling with queued work and exclusive thread must receive a loan from L");

        // External overload drops loan to 0 immediately and vetoes lending for 5 s
        RegionShareAllocator.AllocationSnapshot vetoed = allocator.evaluate(sCfg, sMin, 2, true, false,
                List.of(
                        new RegionShareAllocator.GroupSignal("A", 0, 5 * MS, 5 * MS, 20 * MS, true, 0, false, true),
                        new RegionShareAllocator.GroupSignal("D", 0, 2 * MS, 2 * MS, 0, false, 4, false, true)
                ),
                146 * SEC);
        assertEquals(0L, vetoed.groups().get("A").loanNanos(),
                "Overload on any sampled region must immediately veto lending");

        // After 61 s idle, groups expire
        assertEquals(0, allocator.trackedGroupCount(210 * SEC));
    }

    @Test
    @DisplayName("Invariant I9: Candidate steering defers at most 8 times or 60 s of unpaused time and ignores paused time")
    void invariantI9CandidateSteeringAndAging(@TempDir Path tempDir) {
        RTPTestSetup.install(tempDir.toFile());
        Region region = new Region("default", new RegionSettings(
                "default", null, null, null, true, false,
                10L, 10L, 0L, 4, 1.0, 64L, "default", false));
        assertNotNull(region);

        RegionShareAllocator.RegionSteeringWeight lowWeight =
                new RegionShareAllocator.RegionSteeringWeight(0.2, 0.2, 0.8);
        RegionShareAllocator.RegionSteeringWeight healthyWeight =
                new RegionShareAllocator.RegionSteeringWeight(0.8, 0.8, 0.8);

        // 1. Healthy weight is never deferred
        assertFalse(region.shouldDeferBackgroundCandidate(10, 10, healthyWeight, false, SEC));

        // 2. Low-weight candidate at (20, 20) deferred 8 times at 1 s intervals -> ages on 8th deferral
        for (int i = 1; i <= RegionShareAllocator.CANDIDATE_MAX_DEFERRALS; i++) {
            assertTrue(region.shouldDeferBackgroundCandidate(20, 20, lowWeight, false, i * SEC));
        }
        assertTrue(region.isCandidateAged(20, 20, 8 * SEC));
        assertFalse(region.shouldDeferBackgroundCandidate(20, 20, lowWeight, false, 9 * SEC),
                "Aged candidate must not be deferred on its next turn (Invariant I9)");
        region.clearCandidateDeferral(20, 20);

        // 3. Time-based aging (60 s unpaused time), verifying paused/zero-quota intervals do not count
        assertTrue(region.shouldDeferBackgroundCandidate(30, 30, lowWeight, false, 10 * SEC)); // deferral 1 at 10 s
        // Paused for 100 s (10 s -> 110 s): unpaused time remains 0
        assertTrue(region.shouldDeferBackgroundCandidate(30, 30, lowWeight, true, 110 * SEC));
        assertFalse(region.isCandidateAged(30, 30, 110 * SEC),
                "Paused or zero-quota time must not advance candidate aging (Invariant I9)");
        // Unpaused for 30 s (110 s -> 140 s): still < 60 s and 2 deferrals
        assertTrue(region.shouldDeferBackgroundCandidate(30, 30, lowWeight, false, 140 * SEC));
        assertFalse(region.isCandidateAged(30, 30, 140 * SEC));
        // Unpaused for another 31 s (140 s -> 171 s, total 61 s unpaused): now aged!
        assertTrue(region.isCandidateAged(30, 30, 171 * SEC));
        assertFalse(region.shouldDeferBackgroundCandidate(30, 30, lowWeight, false, 171 * SEC),
                "Candidate deferred for >= 60 s of unpaused time must be taken without further deferral");
    }
}
