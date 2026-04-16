package io.github.dailystruggle.rtp.common.mock;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link MockRTPWorld} that counts the number of currently active
 * force-loaded chunk tickets so that tests can assert the ticket lifecycle.
 *
 * <p>Every call to {@link #setForceLoadedImpl(int, int, boolean)} with
 * {@code forceLoad=true} increments {@link #activeTicketCount}; every call
 * with {@code forceLoad=false} decrements it.  The counter therefore reflects
 * the number of outstanding {@code addPluginChunkTicket} calls that have not
 * yet been matched by a corresponding {@code removePluginChunkTicket} call,
 * mirroring the real Spigot/Paper chunk-ticket API.
 *
 * <p>Requirements covered: REQ-SPIGOT-ARCH-001, REQ-SPIGOT-ARCH-005,
 * REQ-PAPER-ARCH-001, REQ-PAPER-ARCH-005.
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
    protected void setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
        if (forceLoad) {
            activeTicketCount.incrementAndGet();
        } else {
            activeTicketCount.decrementAndGet();
        }
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
     * Resets the active ticket counter to zero.  Useful between test cases
     * that share a single world instance.
     */
    public void resetTicketCount() {
        activeTicketCount.set(0);
    }
}
