package io.github.dailystruggle.rtp.proxy.common.transport.memory;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist.WaitEnvelope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link InMemoryNetworkWaitlist} - lifecycle, idempotency,
 * FIFO discipline, point-removal, position lookup, TTL reap, and
 * per-backend dynamic batch sizing for the reference
 * {@link InMemoryNetworkWaitlist}.
 *
 * <p>REQ traceability: REQ-RTP-NET-015 (shared network waitlist for
 * cross-server {@code /rtp}).</p>
 */
final class InMemoryNetworkWaitlistTest {

    private InMemoryNetworkWaitlist w;

    @BeforeEach
    void setUp() {
        w = new InMemoryNetworkWaitlist(8);
    }

    @AfterEach
    void tearDown() {
        w.shutdown();
    }

    private static WaitEnvelope env(UUID player) {
        return env(player, "overworld", "lobby-a");
    }

    private static WaitEnvelope env(UUID player, String region, String origin) {
        return new WaitEnvelope(
                player, UUID.randomUUID(),
                Optional.ofNullable(region), Optional.empty(),
                origin, System.currentTimeMillis());
    }

    @Test
    void constructor_rejects_non_positive_max_size() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryNetworkWaitlist(0));
        assertThrows(IllegalArgumentException.class, () -> new InMemoryNetworkWaitlist(-1));
    }

    @Test
    void enrol_parks_envelope_and_position_is_one_indexed() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertEquals(EnrolOutcome.ACCEPTED, w.enrol(env(a)).get(2, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.ACCEPTED, w.enrol(env(b)).get(2, TimeUnit.SECONDS));
        assertEquals(2, w.size().get(2, TimeUnit.SECONDS).intValue());
        assertEquals(Optional.of(1), w.position(a).get(2, TimeUnit.SECONDS));
        assertEquals(Optional.of(2), w.position(b).get(2, TimeUnit.SECONDS));
    }

    @Test
    void enrol_is_idempotent_per_correlation_id() throws Exception {
        UUID p = UUID.randomUUID();
        WaitEnvelope e = env(p);
        assertEquals(EnrolOutcome.ACCEPTED, w.enrol(e).get(2, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.ACCEPTED, w.enrol(e).get(2, TimeUnit.SECONDS));
        assertEquals(1, w.sizeNow());
    }

    @Test
    void enrol_rejects_duplicate_player_id_with_different_correlation() throws Exception {
        UUID p = UUID.randomUUID();
        assertEquals(EnrolOutcome.ACCEPTED, w.enrol(env(p)).get(2, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.REJECTED_DUPLICATE, w.enrol(env(p)).get(2, TimeUnit.SECONDS));
        assertEquals(1, w.sizeNow());
    }

    @Test
    void enrol_rejects_when_at_capacity() throws Exception {
        InMemoryNetworkWaitlist tiny = new InMemoryNetworkWaitlist(2);
        try {
            assertEquals(EnrolOutcome.ACCEPTED, tiny.enrol(env(UUID.randomUUID())).get(2, TimeUnit.SECONDS));
            assertEquals(EnrolOutcome.ACCEPTED, tiny.enrol(env(UUID.randomUUID())).get(2, TimeUnit.SECONDS));
            assertEquals(EnrolOutcome.REJECTED_FULL, tiny.enrol(env(UUID.randomUUID())).get(2, TimeUnit.SECONDS));
        } finally {
            tiny.shutdown();
        }
    }

    @Test
    void enrol_after_shutdown_is_rejected_closed() throws Exception {
        w.shutdown();
        // The executor is shut down; submit will throw inside runAsync and the
        // future completes exceptionally. The S-004 contract is "never silent".
        assertThrows(Exception.class,
                () -> w.enrol(env(UUID.randomUUID())).get(2, TimeUnit.SECONDS));
    }

    @Test
    void position_returns_empty_when_player_not_on_list() throws Exception {
        assertEquals(Optional.empty(), w.position(UUID.randomUUID()).get(2, TimeUnit.SECONDS));
    }

    @Test
    void remove_evicts_entry_and_subsequent_position_returns_empty() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        w.enrol(env(a)).get(2, TimeUnit.SECONDS);
        w.enrol(env(b)).get(2, TimeUnit.SECONDS);
        assertTrue(w.remove(a, CancelReason.PLAYER_DISCONNECT).get(2, TimeUnit.SECONDS));
        assertEquals(Optional.empty(), w.position(a).get(2, TimeUnit.SECONDS));
        // b moves to position 1.
        assertEquals(Optional.of(1), w.position(b).get(2, TimeUnit.SECONDS));
    }

    @Test
    void remove_returns_false_when_player_not_on_list() throws Exception {
        assertFalse(w.remove(UUID.randomUUID(), CancelReason.PLAYER_DISCONNECT)
                .get(2, TimeUnit.SECONDS));
    }

    @Test
    void drainBatch_respects_per_backend_cap_and_preserves_fifo() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        w.enrol(env(a)).get(2, TimeUnit.SECONDS);
        w.enrol(env(b)).get(2, TimeUnit.SECONDS);
        w.enrol(env(c)).get(2, TimeUnit.SECONDS);
        w.enrol(env(d)).get(2, TimeUnit.SECONDS);

        Map<String, List<WaitEnvelope>> drained = w.drainBatch(
                Map.of("backend-a", 2, "backend-b", 1), 10).get(2, TimeUnit.SECONDS);

        // Per pickBackend: smallest-keyed serverId with cap > 0 is chosen each
        // round, so allocation is: a -> backend-a, b -> backend-a (cap exhausted),
        // c -> backend-b (cap exhausted), d stays on the waitlist.
        assertEquals(List.of(a, b),
                drained.get("backend-a").stream().map(WaitEnvelope::playerId).toList());
        assertEquals(List.of(c),
                drained.get("backend-b").stream().map(WaitEnvelope::playerId).toList());
        assertEquals(1, w.sizeNow());
        assertEquals(Optional.of(1), w.position(d).get(2, TimeUnit.SECONDS));
    }

    @Test
    void drainBatch_with_global_cap_below_sum_of_per_backend_caps_is_honored() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        w.enrol(env(a)).get(2, TimeUnit.SECONDS);
        w.enrol(env(b)).get(2, TimeUnit.SECONDS);
        w.enrol(env(c)).get(2, TimeUnit.SECONDS);

        Map<String, List<WaitEnvelope>> drained = w.drainBatch(
                Map.of("backend-a", 10, "backend-b", 10), 2).get(2, TimeUnit.SECONDS);

        int total = drained.values().stream().mapToInt(List::size).sum();
        assertEquals(2, total);
        assertEquals(1, w.sizeNow());
    }

    @Test
    void drainBatch_with_empty_caps_is_a_noop() throws Exception {
        w.enrol(env(UUID.randomUUID())).get(2, TimeUnit.SECONDS);
        Map<String, List<WaitEnvelope>> drained = w.drainBatch(Map.of(), 10).get(2, TimeUnit.SECONDS);
        assertTrue(drained.isEmpty());
        assertEquals(1, w.sizeNow());
    }

    @Test
    void drainBatch_skips_zero_cap_backends() throws Exception {
        UUID p = UUID.randomUUID();
        w.enrol(env(p)).get(2, TimeUnit.SECONDS);
        Map<String, List<WaitEnvelope>> drained = w.drainBatch(
                Map.of("backend-a", 0), 10).get(2, TimeUnit.SECONDS);
        assertTrue(drained.isEmpty());
        assertEquals(1, w.sizeNow());
    }

    @Test
    void reap_drops_entries_older_than_max_age() throws Exception {
        // Manually backdate one entry by constructing it with a small enrolledAtMs.
        UUID old = UUID.randomUUID();
        UUID fresh = UUID.randomUUID();
        WaitEnvelope oldEnv = new WaitEnvelope(
                old, UUID.randomUUID(), Optional.empty(), Optional.empty(),
                "lobby-a", System.currentTimeMillis() - 60_000L);
        // Direct enrol won't backdate; use the executor to bypass via a sibling
        // call - simplest path is to enrol, then reap with a tiny duration after
        // waiting briefly.
        // Instead: just enrol oldEnv first (its enrolledAtMs is backdated), then a fresh one.
        w.enrol(oldEnv).get(2, TimeUnit.SECONDS);
        w.enrol(env(fresh)).get(2, TimeUnit.SECONDS);
        assertEquals(2, w.sizeNow());

        int reaped = w.reap(Duration.ofSeconds(30)).get(2, TimeUnit.SECONDS);
        assertEquals(1, reaped);
        assertEquals(Optional.empty(), w.position(old).get(2, TimeUnit.SECONDS));
        assertEquals(Optional.of(1), w.position(fresh).get(2, TimeUnit.SECONDS));
    }

    @Test
    void reap_with_zero_or_negative_duration_is_a_noop() throws Exception {
        w.enrol(env(UUID.randomUUID())).get(2, TimeUnit.SECONDS);
        assertEquals(0, w.reap(Duration.ZERO).get(2, TimeUnit.SECONDS).intValue());
        assertEquals(0, w.reap(Duration.ofSeconds(-1)).get(2, TimeUnit.SECONDS).intValue());
        assertEquals(1, w.sizeNow());
    }
}
