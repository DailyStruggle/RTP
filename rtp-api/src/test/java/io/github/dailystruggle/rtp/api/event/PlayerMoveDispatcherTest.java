package io.github.dailystruggle.rtp.api.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Surface guard for ADR-075 per-player {@link PlayerMoveDispatcher}.
 *
 * <p>Verifies watch lifecycle, targeted event fan-out, error isolation, and null safety.
 */
class PlayerMoveDispatcherTest {

    private static PlayerMoveEvent move(UUID id) {
        return new PlayerMoveEvent(id, "world", 0, 64, 0, 1, 64, 0);
    }

    @Test
    void unwatchedPlayerIsNotWatchedAndReceivesNothing() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID id = UUID.randomUUID();
        assertFalse(d.isWatched(id));
        assertFalse(d.hasWatchers());
        // fire for a player nobody watches is a no-op (must not throw).
        d.fire(move(id));
    }

    @Test
    void watchThenFireDeliversToHandler() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID id = UUID.randomUUID();
        List<PlayerMoveEvent> received = new ArrayList<>();
        d.watch(id, received::add);
        assertTrue(d.isWatched(id));
        assertTrue(d.watchedPlayers().contains(id));
        d.fire(move(id));
        assertEquals(1, received.size());
        assertEquals(id, received.get(0).playerId());
    }

    @Test
    void fireOnlyReachesHandlersForTheMovedPlayer() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AtomicInteger aCount = new AtomicInteger();
        AtomicInteger bCount = new AtomicInteger();
        d.watch(a, e -> aCount.incrementAndGet());
        d.watch(b, e -> bCount.incrementAndGet());
        d.fire(move(a));
        assertEquals(1, aCount.get());
        assertEquals(0, bCount.get());
    }

    @Test
    void closingLastHandleStopsWatching() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID id = UUID.randomUUID();
        AtomicInteger count = new AtomicInteger();
        AutoCloseable handle = d.watch(id, e -> count.incrementAndGet());
        try {
            handle.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertFalse(d.isWatched(id));
        d.fire(move(id));
        assertEquals(0, count.get());
    }

    @Test
    void twoHandlersOnSamePlayerAreIndependent() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID id = UUID.randomUUID();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AutoCloseable h1 = d.watch(id, e -> first.incrementAndGet());
        d.watch(id, e -> second.incrementAndGet());
        try {
            h1.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertTrue(d.isWatched(id), "player still watched by the second handler");
        d.fire(move(id));
        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void throwingHandlerDoesNotAbortFanOut() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID id = UUID.randomUUID();
        AtomicInteger good = new AtomicInteger();
        d.watch(id, e -> { throw new RuntimeException("boom"); });
        d.watch(id, e -> good.incrementAndGet());
        d.fire(move(id));
        assertEquals(1, good.get());
    }

    @Test
    void unwatchAllRemovesEveryHandler() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID id = UUID.randomUUID();
        AtomicInteger count = new AtomicInteger();
        d.watch(id, e -> count.incrementAndGet());
        d.watch(id, e -> count.incrementAndGet());
        d.unwatchAll(id);
        assertFalse(d.isWatched(id));
        d.fire(move(id));
        assertEquals(0, count.get());
    }

    @Test
    void nullArgumentsRejected() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        assertThrows(IllegalArgumentException.class, () -> d.watch(null, e -> {}));
        assertThrows(IllegalArgumentException.class, () -> d.watch(UUID.randomUUID(), null));
        assertFalse(d.isWatched(null));
        // null event / null player are tolerated no-ops
        d.fire(null);
        d.unwatchAll(null);
    }

    @Test
    void playerMoveEventRejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> new PlayerMoveEvent(null, "world", 0, 0, 0, 0, 0, 0));
        assertThrows(NullPointerException.class,
                () -> new PlayerMoveEvent(UUID.randomUUID(), null, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void watchAllAndShouldSample() {
        PlayerMoveDispatcher d = new PlayerMoveDispatcher();
        UUID id = UUID.randomUUID();
        assertFalse(d.shouldSample(id));
        assertThrows(IllegalArgumentException.class, () -> d.watchAll(null));

        AtomicInteger globalCount = new AtomicInteger();
        AutoCloseable closeable = d.watchAll(e -> globalCount.incrementAndGet());
        assertTrue(d.shouldSample(id));
        assertTrue(d.hasWatchers());

        d.fire(move(id));
        assertEquals(1, globalCount.get());

        try {
            closeable.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertFalse(d.shouldSample(id));
        d.fire(move(id));
        assertEquals(1, globalCount.get());
    }

    @Test
    void physicalTriggerSpecCoverage() {
        io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec spec =
            new io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec(
                "p1", io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec.TriggerType.PORTAL,
                "world", 10, 20, 30, 0, 10, 20, "arena", -5L);

        assertEquals(0, spec.minX());
        assertEquals(10, spec.maxX());
        assertEquals(10, spec.minY());
        assertEquals(20, spec.maxY());
        assertEquals(20, spec.minZ());
        assertEquals(30, spec.maxZ());
        assertEquals(0L, spec.cooldownSeconds());

        assertTrue(spec.contains("world", 5, 15, 25));
        assertFalse(spec.contains("world", -1, 15, 25));
        assertFalse(spec.contains("world", 15, 15, 25));
        assertFalse(spec.contains("world", 5, 5, 25));
        assertFalse(spec.contains("world", 5, 25, 25));
        assertFalse(spec.contains("world", 5, 15, 10));
        assertFalse(spec.contains("world", 5, 15, 35));
        assertFalse(spec.contains("nether", 5, 15, 25));

        assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec(
            null, io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec.TriggerType.PORTAL, "world", 0, 0, 0, 0, 0, 0, "act", 0));
        assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec(
            "id", null, "world", 0, 0, 0, 0, 0, 0, "act", 0));
        assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec(
            "id", io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec.TriggerType.PORTAL, null, 0, 0, 0, 0, 0, 0, "act", 0));
        assertThrows(NullPointerException.class, () -> new io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec(
            "id", io.github.dailystruggle.rtp.api.trigger.PhysicalTriggerSpec.TriggerType.PORTAL, "world", 0, 0, 0, 0, 0, 0, null, 0));
    }
}
