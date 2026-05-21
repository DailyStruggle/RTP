package io.github.dailystruggle.rtp.bukkit.network;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice C row C8 / C9: {@link NetworkStatusCache} read/refresh semantics
 * via {@link NetworkStatusCache#pollOnce()} (no scheduler dependency).
 */
class NetworkStatusCacheTest {

    private static NetworkStatusCache.QueueStatus row(UUID p,
                                                      NetworkStatusCache.QueueStatus.State s,
                                                      int pos) {
        return new NetworkStatusCache.QueueStatus(
                p, s, pos, Optional.of("peer-1"), Optional.of("safe"), 0L);
    }

    @Test
    void poll_replaces_cache_contents_wholesale() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply = new AtomicReference<>(
                List.of(row(a, NetworkStatusCache.QueueStatus.State.QUEUED, 1),
                        row(b, NetworkStatusCache.QueueStatus.State.RESERVED, 0)));
        NetworkStatusCache cache = new NetworkStatusCache(supply::get);
        cache.pollOnce();
        assertEquals(2, cache.size());
        assertEquals(NetworkStatusCache.QueueStatus.State.QUEUED, cache.get(a).orElseThrow().state());

        // Second poll: 'a' gone, new state for 'b'.
        supply.set(List.of(row(b, NetworkStatusCache.QueueStatus.State.TRANSFERRING, 0)));
        cache.pollOnce();
        assertEquals(1, cache.size());
        assertTrue(cache.get(a).isEmpty(), "stale entry must be evicted");
        assertEquals(NetworkStatusCache.QueueStatus.State.TRANSFERRING, cache.get(b).orElseThrow().state());
    }

    @Test
    void supplier_failure_preserves_last_known_state_per_S004() {
        UUID p = UUID.randomUUID();
        AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply = new AtomicReference<>(
                List.of(row(p, NetworkStatusCache.QueueStatus.State.QUEUED, 3)));
        NetworkStatusCache cache = new NetworkStatusCache(() -> {
            Collection<NetworkStatusCache.QueueStatus> v = supply.get();
            if (v == null) throw new RuntimeException("simulated transport blip");
            return v;
        });
        cache.pollOnce();
        assertEquals(1, cache.size());
        supply.set(null);
        cache.pollOnce(); // throws inside, but caches must remain intact.
        assertEquals(1, cache.size());
        assertEquals(3, cache.get(p).orElseThrow().positionInQueue());
    }

    @Test
    void null_supplier_result_clears_cache() {
        UUID p = UUID.randomUUID();
        AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply = new AtomicReference<>(
                List.of(row(p, NetworkStatusCache.QueueStatus.State.QUEUED, 1)));
        NetworkStatusCache cache = new NetworkStatusCache(supply::get);
        cache.pollOnce();
        assertEquals(1, cache.size());
        // Distinguish from "supplier threw" (test above): explicit null means
        // "no players in queue" and must evict everything.
        supply.set(java.util.Collections.emptyList());
        cache.pollOnce();
        assertEquals(0, cache.size());
    }

    @Test
    void get_null_uuid_returns_empty() {
        NetworkStatusCache cache = new NetworkStatusCache(java.util.Collections::emptyList);
        assertTrue(cache.get(null).isEmpty());
    }

    @Test
    void shutdown_clears_cache_and_is_idempotent() {
        UUID p = UUID.randomUUID();
        NetworkStatusCache cache = new NetworkStatusCache(() ->
                List.of(row(p, NetworkStatusCache.QueueStatus.State.QUEUED, 0)));
        cache.pollOnce();
        assertEquals(1, cache.size());
        cache.shutdown();
        assertEquals(0, cache.size());
        cache.shutdown(); // must not throw
    }
}
