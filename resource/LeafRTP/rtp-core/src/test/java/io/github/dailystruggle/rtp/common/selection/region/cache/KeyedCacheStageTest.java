package io.github.dailystruggle.rtp.common.selection.region.cache;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link KeyedCacheStage} partition lifecycle (ADR-078 phase 1).
 */
class KeyedCacheStageTest {
    private final List<String> disposed = new ArrayList<>();

    private KeyedCacheStage<UUID, String> keyed() {
        return new KeyedCacheStage<>("personal",
                (key, capacity) -> new SimpleCacheStage<>(key.toString(), capacity, null, null, disposed::add));
    }

    @Test
    @DisplayName("open is idempotent and does not replace a live partition")
    void openIsIdempotent() {
        KeyedCacheStage<UUID, String> keyed = keyed();
        UUID key = UUID.randomUUID();
        CacheStage<String> first = keyed.open(key, 2);
        first.offer("a");
        assertSame(first, keyed.open(key, 99));
        assertEquals(1, keyed.partitionCount());
        assertEquals(1, keyed.size());
        assertTrue(disposed.isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-S-002: closeKey drains that partition through disposal")
    void closeKeyDisposesPartition() {
        KeyedCacheStage<UUID, String> keyed = keyed();
        UUID key = UUID.randomUUID();
        keyed.open(key, 4).offer("a");
        keyed.closeKey(key);
        assertEquals(List.of("a"), disposed);
        assertEquals(0, keyed.partitionCount());
        assertEquals(Optional.empty(), keyed.poll(key));
    }

    @Test
    @DisplayName("an unopened key neither accepts entries nor is created implicitly")
    void unopenedKeyRejects() {
        KeyedCacheStage<UUID, String> keyed = keyed();
        assertFalse(keyed.offer(UUID.randomUUID(), "a"));
        assertEquals(0, keyed.partitionCount());
        assertTrue(disposed.isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-S-002: close drains every partition")
    void closeDrainsAllPartitions() {
        KeyedCacheStage<UUID, String> keyed = keyed();
        keyed.open(UUID.randomUUID(), 4).offer("a");
        keyed.open(UUID.randomUUID(), 4).offer("b");
        keyed.close();
        assertEquals(2, disposed.size());
        assertEquals(0, keyed.partitionCount());
    }
}
