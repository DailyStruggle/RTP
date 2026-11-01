package io.github.dailystruggle.rtp.common.mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link MockRTPWorld} tracking active force-loaded chunk tickets for lifecycle assertions.
 *
 * <p>Requirements: REQ-SPIGOT-ARCH-001, REQ-SPIGOT-ARCH-005, REQ-PAPER-ARCH-001, REQ-PAPER-ARCH-005.
 */
public class TrackedMockWorld extends MockRTPWorld {

    /**
     * Thread-safe counter of currently active (unreleased) chunk tickets.
     * Incremented on {@code forceLoad=true}, decremented on {@code forceLoad=false}.
     */
    private final AtomicInteger activeTicketCount = new AtomicInteger(0);

    public TrackedMockWorld(String name) {
        super(name);
    }

    public TrackedMockWorld() {
        this("tracked_mock_world");
    }

    /**
     * Intercepts force-load state changes to maintain the active ticket counter.
     *
     * @param cx        chunk X coordinate
     * @param cz        chunk Z coordinate
     * @param forceLoad {@code true} when a ticket is being added,
     *                  {@code false} when it is being released
     */
    @Override
    protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        if (forceLoad) {
            activeTicketCount.incrementAndGet();
        } else {
            activeTicketCount.decrementAndGet();
        }
        // Synchronous apply in this mock: the counter is updated inline, so the
        // ticket is "applied" by the time this method returns. Subclasses that
        // want to simulate Paper's deferred-apply behaviour should override this
        // to return an incomplete future that is only completed after a test-
        // controlled drain (see ReqRtpS005PaperTicketApplicationRaceTest).
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Returns the number of chunk tickets that have been added but not yet
     * released.  A value of {@code 0} means all tickets are properly paired.
     *
     * @return current active ticket count
     */
    public int getActiveTicketCount() {
        return activeTicketCount.get();
    }

    /**
     * Updates ticket counter for subclasses simulating deferred ticket application.
     *
     * @param forceLoad {@code true} to increment, {@code false} to decrement
     */
    protected final void setForceLoadedImpl_syncCounterForTesting(boolean forceLoad) {
        if (forceLoad) {
            activeTicketCount.incrementAndGet();
        } else {
            activeTicketCount.decrementAndGet();
        }
    }

    /**
     * Resets the active ticket counter to zero.  Useful between test cases
     * that share a single world instance.
     */
    public void resetTicketCount() {
        activeTicketCount.set(0);
    }
}
