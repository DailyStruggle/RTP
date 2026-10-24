package io.github.dailystruggle.rtp.common.tools;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("GenerationBudget: background fill waits while the chunk system is busy (ADR-087)")
class GenerationBudgetTest {

    @AfterEach
    void tearDown() {
        ServerHealthGate.resetForTesting();
        GenerationBudget.server().resetForTesting();
        GenerationBudget.configureServerAdmission(true);
        GenerationBudget.configureServerCap(null);
    }

    @Test
    @DisplayName("saturates at the cap and drains below it")
    void saturatesAtCap() {
        GenerationBudget budget = new GenerationBudget(2);
        Runnable a = budget.begin();
        assertFalse(budget.saturated());
        Runnable b = budget.begin();
        assertTrue(budget.saturated(), "2 of 2 in flight");
        a.run();
        assertFalse(budget.saturated());
        assertEquals(1, budget.inFlight());
        b.run();
        assertEquals(0, budget.inFlight());
    }

    @Test
    @DisplayName("release is idempotent")
    void releaseIsIdempotent() {
        GenerationBudget budget = new GenerationBudget(2);
        Runnable a = budget.begin();
        budget.begin();
        a.run();
        a.run();
        assertEquals(1, budget.inFlight(), "a second release of the same load must not free another slot");
    }

    @Test
    @DisplayName("REQ-CORE-F-002: reservation counts at admission and begin consumes it without double counting")
    void reservationIsConsumedByBegin() {
        GenerationBudget budget = new GenerationBudget(2);
        GenerationBudget.Reservation reservation = budget.reserve();

        assertNotNull(reservation);
        assertEquals(1, budget.inFlight());
        Runnable release = budget.begin(reservation);
        assertEquals(1, budget.inFlight());

        reservation.close();
        assertEquals(1, budget.inFlight(), "closing a consumed reservation must not release its load");
        release.run();
        release.run();
        assertEquals(0, budget.inFlight());
    }

    @Test
    @DisplayName("REQ-CORE-F-002: unconsumed reservations release idempotently and expire after their TTL")
    void reservationReleaseAndExpiry() {
        GenerationBudget budget = new GenerationBudget(1);
        GenerationBudget.Reservation released = budget.reserve();
        assertNotNull(released);
        released.close();
        released.close();
        assertEquals(0, budget.inFlight());

        AtomicLong now = new AtomicLong(0L);
        GenerationBudget shortLived = new GenerationBudget(() -> 1, now::get, 10L);
        GenerationBudget.Reservation expired = shortLived.reserve();
        assertNotNull(expired);
        now.set(11L);
        assertFalse(shortLived.saturated(), "expired admission must not hold the cap");
        assertEquals(0, shortLived.inFlight());

        GenerationBudget.Reservation replacement = shortLived.reserve();
        assertNotNull(replacement);
        expired.close();
        assertEquals(1, shortLived.inFlight(), "an expired token must not release a later reservation");
        replacement.close();
        assertEquals(0, shortLived.inFlight());
    }

    @Test
    @DisplayName("REQ-CORE-F-002: Paper/Folia disable admission reservations but count native loads")
    void disabledServerAdmissionDoesNotReserve() {
        GenerationBudget budget = GenerationBudget.server();
        GenerationBudget.configureServerAdmission(false);
        GenerationBudget.Reservation reservation = budget.reserve();

        assertNotNull(reservation);
        assertEquals(0, budget.inFlight());
        Runnable release = budget.begin(reservation);
        assertEquals(1, budget.inFlight(), "a native load still counts on hosts that do not gate fill");
        reservation.close();
        release.run();
        assertEquals(0, budget.inFlight());
    }

    @Test
    @DisplayName("a load stays counted after RTP's deadline fires, until the server's future settles")
    void loadStaysCountedPastRtpDeadline() {
        GenerationBudget budget = new GenerationBudget(1);
        CompletableFuture<Object> serverFuture = new CompletableFuture<>();
        GenerationBudget.releaseOn(serverFuture, budget.begin());
        CompletableFuture<Object> rtpView = serverFuture.thenApply(x -> x).orTimeout(20, TimeUnit.MILLISECONDS);

        CompletionException ex = assertThrows(CompletionException.class, rtpView::join);
        assertInstanceOf(TimeoutException.class, ex.getCause());
        assertTrue(budget.saturated(), "vanilla is still generating; the slot must stay taken");

        serverFuture.complete(new Object());
        assertFalse(budget.saturated());
        assertEquals(0, budget.inFlight());
    }

    @Test
    @DisplayName("an exceptional server future and a null (never submitted) future both release")
    void failedAndMissingFuturesRelease() {
        GenerationBudget budget = new GenerationBudget(2);
        CompletableFuture<Object> failing = new CompletableFuture<>();
        GenerationBudget.releaseOn(failing, budget.begin());
        GenerationBudget.releaseOn(null, budget.begin());
        assertEquals(1, budget.inFlight());
        failing.completeExceptionally(new IllegalStateException("unloaded"));
        assertEquals(0, budget.inFlight());
    }

    @Test
    @DisplayName("cap: 26.x gets the modern cap, 1.20 / 1.21 / unknown the vanilla cap; <= 0 never saturates")
    void capByVersion() {
        assertEquals(GenerationBudget.MODERN_CAP, GenerationBudget.capFor("26.1"));
        assertEquals(GenerationBudget.MODERN_CAP, GenerationBudget.capFor("26.1.2-R0.1-SNAPSHOT"));
        assertEquals(GenerationBudget.VANILLA_CAP, GenerationBudget.capFor("1.21.1"));
        assertEquals(GenerationBudget.VANILLA_CAP, GenerationBudget.capFor("1.20.1-R0.1-SNAPSHOT"));
        assertEquals(GenerationBudget.VANILLA_CAP, GenerationBudget.capFor(null));
        GenerationBudget off = new GenerationBudget(0);
        off.begin();
        off.begin();
        assertFalse(off.saturated());
    }

    @Test
    @DisplayName("stalled tick: a tick running past STALL_MS pauses; finished or idle ticks do not")
    void stalledTickPauses() {
        ServerHealthGate.resetForTesting();
        ServerHealthGate.setReadingsForTesting(() -> 20.0, () -> 10.0);
        assertFalse(ServerHealthGate.shouldPause(), "never marked: fail open");

        ServerHealthGate.tickStarted();
        assertFalse(ServerHealthGate.shouldPause(), "a tick that just started is not stalled");

        ServerHealthGate.setTickStartNanosForTesting(
                System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(ServerHealthGate.STALL_MS + 500));
        assertTrue(ServerHealthGate.tickStalled(System.nanoTime()));
        assertTrue(ServerHealthGate.shouldPause(), "TPS 20 is a frozen reading; the stuck tick wins");

        ServerHealthGate.tickFinished();
        assertFalse(ServerHealthGate.shouldPause(), "a paused-between-ticks server is not stalled");
    }
}
