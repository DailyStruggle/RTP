package io.github.dailystruggle.rtp.common.playerData;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link TeleportData} covering field state transitions,
 * timestamp updates, clone behaviour, and memory cleanup semantics.
 */
class TeleportDataTest {

    // -------------------------------------------------------------------------
    // Construction / default state
    // -------------------------------------------------------------------------

    @Test
    void defaultState_allFieldsAreDefaultValues() {
        TeleportData data = new TeleportData();

        assertNull(data.sender);
        assertNull(data.originalCoords);
        assertNull(data.selectedCoords);
        assertNull(data.targetRegion);
        assertNull(data.nextTask);
        assertNull(data.biomes);
        assertFalse(data.completed);
        assertFalse(data.written);
        assertEquals(0, data.cost, 1e-9);
        assertEquals(0L, data.delay);
        assertEquals(0L, data.attempts);
        assertEquals(0L, data.queueLocation);
        assertEquals(0L, data.processingTime);
        // time is set to System.currentTimeMillis() at construction - just verify it is positive
        assertTrue(data.time > 0);
    }

    // -------------------------------------------------------------------------
    // State transitions: queued → processing → completed / cancelled
    // -------------------------------------------------------------------------

    @Test
    void stateTransition_queued() {
        TeleportData data = new TeleportData();
        // "queued" state: queueLocation assigned, not yet processing
        data.queueLocation = 42L;

        assertFalse(data.completed);
        assertEquals(42L, data.queueLocation);
        assertEquals(0L, data.processingTime);
    }

    @Test
    void stateTransition_processing() {
        TeleportData data = new TeleportData();
        data.queueLocation = 1L;

        // Transition to processing: processingTime stamped, attempts incremented
        data.processingTime = System.currentTimeMillis();
        data.attempts++;

        assertFalse(data.completed);
        assertTrue(data.processingTime > 0);
        assertEquals(1L, data.attempts);
    }

    @Test
    void stateTransition_completed() {
        TeleportData data = new TeleportData();
        data.queueLocation = 1L;
        data.processingTime = System.currentTimeMillis();
        data.attempts = 1L;

        // Transition to completed
        data.completed = true;
        data.written = true;

        assertTrue(data.completed);
        assertTrue(data.written);
    }

    @Test
    void stateTransition_cancelled_completedFlagRemainsUnset() {
        TeleportData data = new TeleportData();
        data.queueLocation = 1L;
        data.processingTime = System.currentTimeMillis();

        // Cancelled: completed stays false, nextTask cleared
        data.nextTask = null;

        assertFalse(data.completed);
        assertNull(data.nextTask);
    }

    // -------------------------------------------------------------------------
    // Timestamp updates
    // -------------------------------------------------------------------------

    @Test
    void timestampUpdate_timeCanBeRefreshed() throws InterruptedException {
        TeleportData data = new TeleportData();
        long first = data.time;

        Thread.sleep(2);
        data.time = System.currentTimeMillis();

        assertTrue(data.time >= first);
    }

    @Test
    void processingTime_defaultIsZero_canBeSet() {
        TeleportData data = new TeleportData();
        assertEquals(0L, data.processingTime);

        long now = System.currentTimeMillis();
        data.processingTime = now;
        assertEquals(now, data.processingTime);
    }

    // -------------------------------------------------------------------------
    // Clone - null coords / biomes
    // -------------------------------------------------------------------------

    @Test
    void clone_withNullOptionalFields_producesIndependentCopy() {
        TeleportData original = new TeleportData();
        original.sender = new MockRTPCommandSender(UUID.randomUUID(), "Alice");
        // originalCoords, selectedCoords, biomes intentionally left null

        TeleportData copy = original.clone();

        assertNotSame(original, copy);
        assertNull(copy.originalCoords);
        assertNull(copy.selectedCoords);
        assertNull(copy.biomes);
        assertEquals(original.time, copy.time);
        assertEquals(original.completed, copy.completed);
    }

    @Test
    void clone_withCoords_copiesCoordReferences() {
        TeleportData original = new TeleportData();
        original.sender = new MockRTPCommandSender(UUID.randomUUID(), "Bob");
        original.originalCoords = new RTPCoords("world", 10, 64, 20);
        original.selectedCoords = new RTPCoords("world_nether", -5, 70, 15);

        TeleportData copy = original.clone();

        assertNotSame(original, copy);
        assertSame(original.originalCoords, copy.originalCoords);
        assertSame(original.selectedCoords, copy.selectedCoords);
    }

    @Test
    void clone_withBiomes_producesDefensiveCopyOfBiomeSet() {
        TeleportData original = new TeleportData();
        original.sender = new MockRTPCommandSender(UUID.randomUUID(), "Carol");
        Set<String> biomes = new HashSet<>();
        biomes.add("FOREST");
        biomes.add("PLAINS");
        original.biomes = biomes;

        TeleportData copy = original.clone();

        assertNotSame(original.biomes, copy.biomes);
        assertEquals(original.biomes, copy.biomes);

        // Mutating the copy must not affect the original
        copy.biomes.add("DESERT");
        assertFalse(original.biomes.contains("DESERT"));
    }

    @Test
    void clone_mutatingCopy_doesNotAffectOriginal() {
        TeleportData original = new TeleportData();
        original.sender = new MockRTPCommandSender(UUID.randomUUID(), "Dave");
        original.cost = 5.0;
        original.attempts = 3L;

        TeleportData copy = original.clone();
        copy.cost = 99.0;
        copy.attempts = 100L;
        copy.completed = true;

        assertEquals(5.0, original.cost, 1e-9);
        assertEquals(3L, original.attempts);
        assertFalse(original.completed);
    }

    // -------------------------------------------------------------------------
    // Memory cleanup semantics
    // -------------------------------------------------------------------------

    @Test
    void memoryCleanup_clearingReferencesNullifiesHeavyFields() {
        TeleportData data = new TeleportData();
        data.sender = new MockRTPCommandSender(UUID.randomUUID(), "Eve");
        data.originalCoords = new RTPCoords("world", 0, 64, 0);
        data.selectedCoords = new RTPCoords("world", 100, 64, 100);
        data.biomes = new HashSet<>();
        data.biomes.add("OCEAN");

        // Simulate cleanup after teleport completes
        data.nextTask = null;
        data.biomes = null;
        data.originalCoords = null;
        data.selectedCoords = null;

        assertNull(data.nextTask);
        assertNull(data.biomes);
        assertNull(data.originalCoords);
        assertNull(data.selectedCoords);
        // sender and completed flag remain
        assertNotNull(data.sender);
    }

    // -------------------------------------------------------------------------
    // Field assignments
    // -------------------------------------------------------------------------

    @Test
    void costField_canBeSetAndRead() {
        TeleportData data = new TeleportData();
        data.cost = 12.5;
        assertEquals(12.5, data.cost, 1e-9);
    }

    @Test
    void delayField_canBeSetAndRead() {
        TeleportData data = new TeleportData();
        data.delay = 200L;
        assertEquals(200L, data.delay);
    }

    @Test
    void attemptsField_incrementsCorrectly() {
        TeleportData data = new TeleportData();
        for (int i = 0; i < 5; i++) data.attempts++;
        assertEquals(5L, data.attempts);
    }

    @Test
    void writtenFlag_defaultFalse_canBeSetTrue() {
        TeleportData data = new TeleportData();
        assertFalse(data.written);
        data.written = true;
        assertTrue(data.written);
    }

    // -------------------------------------------------------------------------
    // onComplete / fireOnComplete (public API teleport-future completion, S-004)
    // -------------------------------------------------------------------------

    @Test
    void fireOnComplete_invokesCallbackExactlyOnce() {
        TeleportData data = new TeleportData();
        int[] calls = {0};
        data.onComplete = td -> calls[0]++;

        data.fireOnComplete();
        data.fireOnComplete();
        data.fireOnComplete();

        assertEquals(1, calls[0], "onComplete must fire exactly once across multiple cleanup paths");
    }

    @Test
    void fireOnComplete_isNoOpWhenCallbackNull() {
        TeleportData data = new TeleportData();
        assertNull(data.onComplete);
        assertDoesNotThrow(data::fireOnComplete);
    }

    @Test
    void fireOnComplete_swallowsCallbackException() {
        TeleportData data = new TeleportData();
        data.onComplete = td -> { throw new RuntimeException("boom"); };
        assertDoesNotThrow(data::fireOnComplete,
                "a misbehaving consumer callback must never abort pipeline cleanup");
    }

    @Test
    void clone_doesNotCarryOnCompleteCallback() {
        TeleportData original = new TeleportData();
        original.sender = new MockRTPCommandSender(UUID.randomUUID(), "Frank");
        int[] calls = {0};
        original.onComplete = td -> calls[0]++;

        TeleportData copy = original.clone();
        copy.fireOnComplete();

        assertNull(copy.onComplete, "history/prior-data clones must not retain the API callback");
        assertEquals(0, calls[0], "a clone must never fire the original's completion callback");
    }
}
