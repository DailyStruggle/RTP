package io.github.dailystruggle.rtp.common.network;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link NetworkStatusCache} terminal-listener + local-seed
 * machinery introduced to fix the "queued then alreadyTeleporting forever"
 * symptom: the lobby releases the local {@code processingPlayers} lock
 * once the proxy reports a terminal state, evicts the row, or fails to
 * report at all within the sticky TTL.
 */
final class NetworkStatusCacheTerminalListenerTest {

    @Test
    void seededLocal_isStickyUntilSupplierConfirms_thenFollowsRealState() {
        AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supplied =
                new AtomicReference<>(Collections.emptyList());
        NetworkStatusCache cache = new NetworkStatusCache(supplied::get);
        UUID p = UUID.randomUUID();
        List<UUID> terminalFires = new ArrayList<>();
        cache.setTerminalListener((u, s) -> terminalFires.add(u));

        cache.seedLocal(p);
        // Immediately visible as non-terminal (anti-spam guards rely on this).
        assertTrue(cache.get(p).isPresent());
        assertTrue(cache.get(p).get().nonTerminal());

        // Supplier still empty for many pulses: sticky entry survives.
        for (int i = 0; i < 5; i++) cache.pollOnce();
        assertTrue(cache.get(p).isPresent());
        assertTrue(terminalFires.isEmpty(),
                "sticky-seeded row must not fire terminal within TTL");

        // Supplier now confirms the row as QUEUED. Sticky seed is dropped,
        // the row continues to be non-terminal.
        supplied.set(List.of(new NetworkStatusCache.QueueStatus(
                p, NetworkStatusCache.QueueStatus.State.QUEUED, 0,
                Optional.empty(), Optional.empty(), System.currentTimeMillis())));
        cache.pollOnce();
        assertTrue(cache.get(p).isPresent());
        assertTrue(terminalFires.isEmpty(),
                "supplier-confirmed non-terminal must not fire terminal");

        // Supplier reports COMPLETED: terminal listener fires once.
        supplied.set(List.of(new NetworkStatusCache.QueueStatus(
                p, NetworkStatusCache.QueueStatus.State.COMPLETED, 0,
                Optional.empty(), Optional.empty(), System.currentTimeMillis())));
        cache.pollOnce();
        assertEquals(1, terminalFires.size(),
                "transition to terminal must fire listener exactly once");
        assertEquals(p, terminalFires.get(0));
    }

    @Test
    void evictionOfPreviouslyNonTerminal_firesTerminal_withNullState() {
        AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supplied =
                new AtomicReference<>(List.of(new NetworkStatusCache.QueueStatus(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"),
                        NetworkStatusCache.QueueStatus.State.QUEUED, 0,
                        Optional.empty(), Optional.empty(), System.currentTimeMillis())));
        NetworkStatusCache cache = new NetworkStatusCache(supplied::get);
        List<NetworkStatusCache.QueueStatus.State> fires = new ArrayList<>();
        cache.setTerminalListener((u, s) -> fires.add(s));

        cache.pollOnce(); // installs the non-terminal row
        supplied.set(Collections.emptyList());
        cache.pollOnce(); // proxy dropped the row -> eviction -> terminal

        assertEquals(1, fires.size());
        assertNull(fires.get(0),
                "supplier-eviction terminal carries null state per contract");
    }

    @Test
    void stickyTtlExpiry_firesTerminalWithFAILED_andEvictsRow() {
        AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supplied =
                new AtomicReference<>(Collections.emptyList());
        NetworkStatusCache cache = new NetworkStatusCache(supplied::get);
        cache.setSeededTimeoutMs(0L); // expire immediately
        UUID p = UUID.randomUUID();
        List<NetworkStatusCache.QueueStatus.State> fires = new ArrayList<>();
        cache.setTerminalListener((u, s) -> fires.add(s));

        cache.seedLocal(p);
        // Force the deadline to be in the past so the next pollOnce treats it as expired.
        try { Thread.sleep(5L); } catch (InterruptedException ignored) {}
        cache.pollOnce();

        assertEquals(1, fires.size(),
                "TTL-expired sticky seed must fire terminal listener once");
        assertEquals(NetworkStatusCache.QueueStatus.State.FAILED, fires.get(0));
        assertTrue(cache.get(p).isEmpty(),
                "TTL-expired sticky seed must be evicted from the cache");
    }

}
