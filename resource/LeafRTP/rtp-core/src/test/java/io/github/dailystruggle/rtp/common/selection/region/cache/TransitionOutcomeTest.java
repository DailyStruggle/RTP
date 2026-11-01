package io.github.dailystruggle.rtp.common.selection.region.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TransitionOutcome} and {@link StageTransition} contract (ADR-078 phase 1).
 */
class TransitionOutcomeTest {
    @Test
    @DisplayName("REQ-RTP-S-004: a rejection always carries a populated reason")
    void rejectionCarriesReason() {
        TransitionOutcome<String> outcome =
                TransitionOutcome.rejected(RejectionReason.UNSAFE_BLOCK, "solid ceiling");
        assertFalse(outcome.isPromoted());
        assertEquals(Optional.empty(), outcome.value());
        assertEquals(RejectionReason.UNSAFE_BLOCK,
                ((TransitionOutcome.Rejected<String>) outcome).reason());
        assertEquals("solid ceiling", ((TransitionOutcome.Rejected<String>) outcome).detail());
    }

    @Test
    @DisplayName("REQ-RTP-S-004: a reasonless rejection cannot be constructed")
    void reasonIsMandatory() {
        assertThrows(IllegalArgumentException.class,
                () -> new TransitionOutcome.Rejected<String>(null, "no reason"));
    }

    @Test
    @DisplayName("a null detail is normalized to empty rather than propagated")
    void nullDetailNormalized() {
        assertEquals("", new TransitionOutcome.Rejected<String>(RejectionReason.ERROR, null).detail());
    }

    @Test
    @DisplayName("a promotion carries a non-null entry")
    void promotionCarriesEntry() {
        TransitionOutcome<String> outcome = TransitionOutcome.promoted("entry");
        assertTrue(outcome.isPromoted());
        assertEquals(Optional.of("entry"), outcome.value());
        assertThrows(IllegalArgumentException.class, () -> new TransitionOutcome.Promoted<String>(null));
    }

    @Test
    @DisplayName("REQ-RTP-S-004: an exceptional transition surfaces rather than resolving empty")
    void exceptionalTransitionSurfaces() {
        StageTransition<String, String> failing =
                source -> CompletableFuture.failedFuture(new IllegalStateException("boom"));
        CompletableFuture<TransitionOutcome<String>> future = failing.promote("coordinate");
        assertTrue(future.isCompletedExceptionally());
        assertThrows(ExecutionException.class, future::get);
    }

    @Test
    @DisplayName("a transition may map its own failure onto the ERROR rejection")
    void transitionMapsErrorToRejection() throws Exception {
        StageTransition<String, String> mapping = source -> CompletableFuture
                .<TransitionOutcome<String>>failedFuture(new IllegalStateException("boom"))
                .exceptionally(t -> TransitionOutcome.rejected(RejectionReason.ERROR, t.getMessage()));
        TransitionOutcome<String> outcome = mapping.promote("coordinate").get();
        assertFalse(outcome.isPromoted());
        assertEquals(RejectionReason.ERROR, ((TransitionOutcome.Rejected<String>) outcome).reason());
    }
}
