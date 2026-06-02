package io.github.dailystruggle.rtp.fabric.server;

import io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-049 step 10: covers the Fabric player-lifecycle shim used by the
 * network-mode {@code JoinTriggerSource} / {@code NetworkWaitlistQuitListener}.
 *
 * <p>Verifies reflective UUID extraction from a {@code ServerPlayer}-typed
 * object (here a plain fake exposing {@code getUUID()}), fan-out to
 * subscribers, unsubscribe via the returned {@link AutoCloseable}, and
 * exception isolation between subscribers. No adapter is installed, so
 * extraction falls through to the reflective {@code getMethod("getUUID")}
 * path that production uses on intermediary runtimes.
 */
class FabricPlayerLifecycleHookTest {

    @BeforeEach
    void setUp() {
        FabricVersionAdapterRegistry.clearForTesting();
    }

    @AfterEach
    void tearDown() {
        FabricVersionAdapterRegistry.clearForTesting();
    }

    /** Fake ServerPlayer-typed object: only the reflectively-probed getUUID(). */
    public static final class FakePlayer {
        private final UUID uuid;
        FakePlayer(UUID uuid) { this.uuid = uuid; }
        public UUID getUUID() { return uuid; }
    }

    @Test
    void fireJoinFromPlayerExtractsUuidAndFansOut() {
        FabricPlayerLifecycleHook hook = new FabricPlayerLifecycleHook();
        UUID id = UUID.randomUUID();
        AtomicReference<UUID> seen = new AtomicReference<>();
        hook.onPlayerJoin(seen::set);

        hook.fireJoinFromPlayer(new FakePlayer(id));

        assertSame(id, seen.get(), "join handler must receive the extracted UUID");
    }

    @Test
    void fireQuitFromPlayerExtractsUuidAndFansOut() {
        FabricPlayerLifecycleHook hook = new FabricPlayerLifecycleHook();
        UUID id = UUID.randomUUID();
        AtomicReference<UUID> seen = new AtomicReference<>();
        hook.onPlayerQuit(seen::set);

        hook.fireQuitFromPlayer(new FakePlayer(id));

        assertSame(id, seen.get(), "quit handler must receive the extracted UUID");
    }

    @Test
    void nullPlayerIsNoOp() {
        FabricPlayerLifecycleHook hook = new FabricPlayerLifecycleHook();
        AtomicInteger calls = new AtomicInteger();
        hook.onPlayerJoin(u -> calls.incrementAndGet());
        hook.onPlayerQuit(u -> calls.incrementAndGet());

        hook.fireJoinFromPlayer(null);
        hook.fireQuitFromPlayer(null);

        assertEquals(0, calls.get(), "null player must not fan out");
    }

    @Test
    void unsubscribeStopsDelivery() throws Exception {
        FabricPlayerLifecycleHook hook = new FabricPlayerLifecycleHook();
        AtomicInteger calls = new AtomicInteger();
        AutoCloseable sub = hook.onPlayerJoin(u -> calls.incrementAndGet());

        hook.fireJoinFromPlayer(new FakePlayer(UUID.randomUUID()));
        assertEquals(1, calls.get());

        sub.close();
        assertFalse(hook.hasSubscribers(), "closing the only subscription clears subscribers");

        hook.fireJoinFromPlayer(new FakePlayer(UUID.randomUUID()));
        assertEquals(1, calls.get(), "unsubscribed handler must not fire again");
    }

    @Test
    void faultySubscriberDoesNotAbortFanOut() {
        FabricPlayerLifecycleHook hook = new FabricPlayerLifecycleHook();
        AtomicInteger good = new AtomicInteger();
        hook.onPlayerJoin(u -> { throw new RuntimeException("boom"); });
        hook.onPlayerJoin(u -> good.incrementAndGet());

        hook.fireJoinFromPlayer(new FakePlayer(UUID.randomUUID()));

        assertEquals(1, good.get(), "a throwing subscriber must not block the others");
        assertTrue(hook.hasSubscribers());
    }
}
