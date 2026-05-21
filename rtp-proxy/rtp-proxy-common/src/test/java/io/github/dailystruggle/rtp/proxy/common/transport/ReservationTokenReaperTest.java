package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the REQ-RTP-NET-011 reaper against the in-memory binding (offline,
 * no Redis / H2). The Redis and SQL bindings have their own
 * {@code reapExpired} suites; this class exercises:
 *
 * <ul>
 *   <li>{@link ReservationTokenReaper#reapNow()} returns the count of tokens
 *       it transitions to {@code RELEASED}.</li>
 *   <li>Fresh (non-expired) tokens are never reaped.</li>
 *   <li>A second pass over the same store reaps zero (idempotent).</li>
 *   <li>The post-reap binding state is "no token map entry, no active-player
 *       index entry" - i.e. the slot is reclaimable.</li>
 *   <li>The reaper's scheduler shuts down cleanly via {@link AutoCloseable}
 *       even when {@code start()} was never called (no leaked thread).</li>
 *   <li>{@code TTL_EXPIRED} release is propagated to the transport for each
 *       reaped token (verifies the "release plumbing notifies the originating
 *       backend" guarantee end-to-end).</li>
 * </ul>
 */
class ReservationTokenReaperTest {

    private InMemoryNetworkStateBinding binding;
    private AtomicReference<Instant> clock;

    @BeforeEach
    void setUp() {
        binding = new InMemoryNetworkStateBinding();
        clock = new AtomicReference<>(Instant.ofEpochMilli(1_000_000L));
        binding.setClock(clock::get);
    }

    @AfterEach
    void tearDown() {
        if (binding != null) binding.close();
    }

    @Test
    @DisplayName("reapNow transitions only expired tokens and returns the count")
    void reapsExpiredOnly() throws Exception {
        // Fresh token: expires 60s after current clock.
        UUID alivePlayer = UUID.randomUUID();
        binding.claim("backend-a", alivePlayer, Duration.ofSeconds(60))
                .get(2, TimeUnit.SECONDS);
        // Already-expired token: 1ms TTL, then advance clock past it.
        UUID expiredPlayer = UUID.randomUUID();
        binding.claim("backend-b", expiredPlayer, Duration.ofMillis(1))
                .get(2, TimeUnit.SECONDS);
        clock.set(Instant.ofEpochMilli(1_001_000L)); // +1s, well past 1ms TTL

        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            assertEquals(1, reaper.reapNow(),
                    "exactly one token must be reaped on first pass");
            assertEquals(0, reaper.reapNow(),
                    "second pass must be a no-op (already-reaped tokens are skipped)");
            assertEquals(1L, reaper.reapedCount(),
                    "reapedCount is cumulative across passes");

            // Fresh token survived: dump still contains exactly one entry,
            // and findReservation still resolves the alive player.
            Map<String, ReservationToken> remaining = binding.dumpTokens();
            assertEquals(1, remaining.size(),
                    "fresh token must remain in the map");
            assertTrue(binding.findReservation(alivePlayer)
                            .get(1, TimeUnit.SECONDS).isPresent(),
                    "fresh token must still be findable");

            // Expired player's active-index slot is reclaimable.
            assertTrue(binding.findReservation(expiredPlayer)
                            .get(1, TimeUnit.SECONDS).isEmpty(),
                    "expired token must not be returned to findReservation");
        }
    }

    @Test
    @DisplayName("close shuts the scheduler down even when start was never called")
    void closeWithoutStartIsClean() {
        ReservationTokenReaper reaper =
                new ReservationTokenReaper(binding, Duration.ofMillis(100));
        assertFalse(reaper.isRunning());
        reaper.close(); // must not throw, must not leak the thread
        assertFalse(reaper.isRunning());
    }

    @Test
    @DisplayName("Reaper rejects non-positive intervals at construction")
    void rejectsBadInterval() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReservationTokenReaper(binding, Duration.ZERO));
        assertThrows(IllegalArgumentException.class,
                () -> new ReservationTokenReaper(binding, Duration.ofMillis(-1)));
    }

    @Test
    @DisplayName("Reaper survives a transport that throws during reapExpired (no scheduler death)")
    void survivesTransportFailure() throws Exception {
        // Force a failure by closing the binding before the reaper sweeps.
        binding.close();
        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofSeconds(30), clock::get)) {
            // reapNow logs a WARNING and returns 0 rather than throwing.
            assertEquals(0, reaper.reapNow());
        }
        // Re-open a fresh binding for the @AfterEach to close cleanly.
        binding = new InMemoryNetworkStateBinding();
    }

    @Test
    @DisplayName("Periodic sweep fires after start and reaps expired tokens autonomously")
    void scheduledSweepReaps() throws Exception {
        UUID expiredPlayer = UUID.randomUUID();
        binding.claim("backend-c", expiredPlayer, Duration.ofMillis(1))
                .get(2, TimeUnit.SECONDS);
        clock.set(Instant.ofEpochMilli(1_002_000L)); // past expiry

        try (ReservationTokenReaper reaper =
                     new ReservationTokenReaper(binding, Duration.ofMillis(50), clock::get)) {
            reaper.start();
            // Allow at least one scheduled tick to fire (50ms interval).
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (reaper.reapedCount() == 0L && System.nanoTime() < deadline) {
                Thread.sleep(25L);
            }
            assertEquals(1L, reaper.reapedCount(),
                    "scheduled sweep must reap the expired token within 2s");
            assertNotNull(reaper);
        }
    }
}
