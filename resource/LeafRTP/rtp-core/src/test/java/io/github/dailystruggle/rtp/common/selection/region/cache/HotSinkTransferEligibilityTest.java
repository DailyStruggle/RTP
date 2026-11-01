package io.github.dailystruggle.rtp.common.selection.region.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Transfer-eligibility rules 0-2 of {@link HotSink} (ADR-078 phase 1).
 */
class HotSinkTransferEligibilityTest {
    /** Minimal sink stub; only the eligibility-relevant answers vary per test. */
    private static final class StubSink implements HotSink<String> {
        private final CacheStage<String> stage =
                new SimpleCacheStage<>("stub", 4, null, null, null);
        private final CacheStage<?> cold;
        private final Predicate<String> accepts;
        private boolean extrinsic;
        private boolean leased;
        private boolean narrows;
        private int cost = 1;
        private boolean acceptsCalled;

        StubSink(CacheStage<?> cold, Predicate<String> accepts) {
            this.cold = cold;
            this.accepts = accepts;
        }

        @Override public String name() { return "stub"; }
        @Override public CacheStage<String> stage() { return stage; }
        @Override public CacheStage<?> coldSource() { return cold; }
        @Override public boolean accepts(String entry) {
            acceptsCalled = true;
            return accepts.test(entry);
        }
        @Override public boolean hasExtrinsicVerifier() { return extrinsic; }
        @Override public boolean isExternallyLeased() { return leased; }
        @Override public boolean narrowsBeyondColdSource() { return narrows; }
        @Override public int chunkCostPerEntry() { return cost; }
        @Override public long demandWeight() { return 0L; }
    }

    private static CacheStage<String> cold() {
        return new SimpleCacheStage<>("cold", 8, null, null, null);
    }

    @Test
    @DisplayName("rule 1: common provenance certifies without re-running any check")
    void commonProvenanceCertifies() {
        CacheStage<String> cold = cold();
        StubSink from = new StubSink(cold, e -> false);
        StubSink to = new StubSink(cold, e -> false);
        assertTrue(HotSink.transferEligible(from, to, "entry"));
        assertFalse(to.acceptsCalled, "provenance must not need the recheck");
    }

    @Test
    @DisplayName("rule 1 is insufficient when the destination narrows further; rule 2 decides")
    void narrowingSinkFallsThroughToRecheck() {
        CacheStage<String> cold = cold();
        StubSink from = new StubSink(cold, e -> true);
        StubSink rejecting = new StubSink(cold, e -> false);
        rejecting.narrows = true;
        assertFalse(HotSink.transferEligible(from, rejecting, "entry"));
        assertTrue(rejecting.acceptsCalled);

        StubSink accepting = new StubSink(cold, e -> true);
        accepting.narrows = true;
        assertTrue(HotSink.transferEligible(from, accepting, "entry"));
    }

    @Test
    @DisplayName("rule 0: an externally leased sink is never source or destination")
    void leasedSinkNeverEligible() {
        CacheStage<String> cold = cold();
        StubSink leasedSource = new StubSink(cold, e -> true);
        leasedSource.leased = true;
        StubSink plain = new StubSink(cold, e -> true);
        assertFalse(HotSink.transferEligible(leasedSource, plain, "entry"));
        assertFalse(HotSink.transferEligible(plain, leasedSource, "entry"));
    }

    @Test
    @DisplayName("rule 0: subsumption permits N -> k but never a smaller footprint upward")
    void footprintRelationEnforced() {
        StubSink bigger = new StubSink(cold(), e -> true);
        bigger.cost = 4;
        StubSink smaller = new StubSink(cold(), e -> true);
        smaller.cost = 1;
        assertTrue(HotSink.transferEligible(bigger, smaller, "entry"));
        assertFalse(HotSink.transferEligible(smaller, bigger, "entry"));
    }

    @Test
    @DisplayName("REQ-RTP-S-003: an extrinsic verifier is eligible only under common provenance")
    void extrinsicVerifierExcludedFromRecheck() {
        StubSink from = new StubSink(cold(), e -> true);
        StubSink extrinsicDestination = new StubSink(cold(), e -> true);
        extrinsicDestination.extrinsic = true;
        assertFalse(HotSink.transferEligible(from, extrinsicDestination, "entry"));
        assertFalse(extrinsicDestination.acceptsCalled, "extrinsic checks must not be re-run per transfer");

        CacheStage<String> shared = cold();
        StubSink sharedFrom = new StubSink(shared, e -> true);
        StubSink sharedExtrinsic = new StubSink(shared, e -> true);
        sharedExtrinsic.extrinsic = true;
        assertTrue(HotSink.transferEligible(sharedFrom, sharedExtrinsic, "entry"));
    }

    @Test
    @DisplayName("rule 2: distinct cold sources require the destination's own acceptance check")
    void distinctColdSourcesRequireRecheck() {
        StubSink from = new StubSink(cold(), e -> true);
        StubSink to = new StubSink(cold(), "ok"::equals);
        assertTrue(HotSink.transferEligible(from, to, "ok"));
        assertFalse(HotSink.transferEligible(from, to, "no"));
    }

    @Test
    @DisplayName("a sink is never a transfer partner with itself and nulls are declined")
    void degenerateInputsDeclined() {
        StubSink sink = new StubSink(cold(), e -> true);
        assertFalse(HotSink.transferEligible(sink, sink, "entry"));
        assertFalse(HotSink.transferEligible(sink, null, "entry"));
        assertFalse(HotSink.transferEligible(sink, new StubSink(cold(), e -> true), null));
    }
}
