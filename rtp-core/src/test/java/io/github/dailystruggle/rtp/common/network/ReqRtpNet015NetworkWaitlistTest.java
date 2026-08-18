package io.github.dailystruggle.rtp.common.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-NET-015: lobby-side cross-server waitlist UX tests.
 * Exercises status non-terminal checks, message formatting, and notification deduplication.
 */
@DisplayName("REQ-RTP-NET-015 lobby-side cross-server waitlist UX")
class ReqRtpNet015NetworkWaitlistTest {

    private static NetworkStatusCache.QueueStatus row(UUID p,
                                                     NetworkStatusCache.QueueStatus.State s,
                                                     int pos) {
        return new NetworkStatusCache.QueueStatus(
                p, s, pos, Optional.empty(), Optional.empty(), 0L);
    }

    // ------------------------------------------------------------------
    // 1. nonTerminal() truth table
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("QueueStatus.nonTerminal() truth table")
    class NonTerminal {

        @Test
        void waitlisted_is_non_terminal() {
            UUID u = UUID.randomUUID();
            assertTrue(row(u, NetworkStatusCache.QueueStatus.State.WAITLISTED, 3).nonTerminal());
        }

        @Test
        void queued_is_non_terminal() {
            UUID u = UUID.randomUUID();
            assertTrue(row(u, NetworkStatusCache.QueueStatus.State.QUEUED, 1).nonTerminal());
        }

        @Test
        void in_flight_states_are_non_terminal() {
            UUID u = UUID.randomUUID();
            assertTrue(row(u, NetworkStatusCache.QueueStatus.State.ROUTING, 0).nonTerminal());
            assertTrue(row(u, NetworkStatusCache.QueueStatus.State.RESERVED, 0).nonTerminal());
            assertTrue(row(u, NetworkStatusCache.QueueStatus.State.TRANSFERRING, 0).nonTerminal());
        }

        @Test
        void terminal_states_are_terminal() {
            UUID u = UUID.randomUUID();
            assertFalse(row(u, NetworkStatusCache.QueueStatus.State.COMPLETED, 0).nonTerminal());
            assertFalse(row(u, NetworkStatusCache.QueueStatus.State.FAILED, 0).nonTerminal());
            assertFalse(row(u, NetworkStatusCache.QueueStatus.State.CANCELLED, 0).nonTerminal());
            assertFalse(row(u, NetworkStatusCache.QueueStatus.State.UNKNOWN, 0).nonTerminal());
        }
    }

    // ------------------------------------------------------------------
    // 2. NetworkWaitlistGuard.formatMessage rendering
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("NetworkWaitlistGuard.formatMessage rendering")
    class GuardMessage {

        @Test
        void renders_position_when_known() {
            UUID u = UUID.randomUUID();
            NetworkStatusCache.QueueStatus s =
                    row(u, NetworkStatusCache.QueueStatus.State.WAITLISTED, 4);
            String body = NetworkWaitlistGuard.formatMessage(s);
            assertNotNull(body);
            assertTrue(body.contains("4") && body.contains("position"),
                    "expected rendered position '4' in body, was: " + body);
        }

        @Test
        void renders_position_less_variant_when_pos_zero() {
            UUID u = UUID.randomUUID();
            NetworkStatusCache.QueueStatus s =
                    row(u, NetworkStatusCache.QueueStatus.State.TRANSFERRING, 0);
            String body = NetworkWaitlistGuard.formatMessage(s);
            assertNotNull(body);
            // Position-less variant substitutes [position] with an empty string.
            // The literal "[position]" placeholder must not survive into the
            // rendered output.
            assertFalse(body.contains("[position]"),
                    "position placeholder should be stripped: " + body);
        }
    }

    // ------------------------------------------------------------------
    // 3. NetworkWaitlistNotifier dedup / re-notify / eviction
    // ------------------------------------------------------------------

    /**
     * Notifier subclass that captures emits in a list instead of pushing to
     * Bukkit. {@link NetworkWaitlistNotifier#emit(UUID, String)} returns
     * {@code true} so dedup advances normally.
     */
    private static final class CapturingNotifier extends NetworkWaitlistNotifier {
        final List<String> emitted = new ArrayList<>();
        CapturingNotifier(NetworkStatusCache cache, long reNotifyMs) { super(cache, reNotifyMs); }
        @Override
        boolean emit(UUID uuid, String body) {
            emitted.add(uuid + "|" + body);
            return true;
        }
    }

    @Nested
    @DisplayName("NetworkWaitlistNotifier dedup / re-notify / eviction")
    class NotifierBehavior {

        @Test
        void emits_once_on_first_pulse_for_waitlisted_player() {
            UUID p = UUID.randomUUID();
            AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply =
                    new AtomicReference<>(List.of(
                            row(p, NetworkStatusCache.QueueStatus.State.WAITLISTED, 1)));
            NetworkStatusCache cache = new NetworkStatusCache(supply::get);
            cache.pollOnce();
            CapturingNotifier n = new CapturingNotifier(cache, 5000L);

            n.doPulse(1000L);

            assertEquals(1, n.emitted.size());
            assertTrue(n.emitted.get(0).contains("1"),
                    "expected emitted body to mention position '1', was: " + n.emitted.get(0));
        }

        @Test
        void does_not_re_emit_identical_body_within_interval() {
            UUID p = UUID.randomUUID();
            AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply =
                    new AtomicReference<>(List.of(
                            row(p, NetworkStatusCache.QueueStatus.State.WAITLISTED, 2)));
            NetworkStatusCache cache = new NetworkStatusCache(supply::get);
            cache.pollOnce();
            CapturingNotifier n = new CapturingNotifier(cache, 5000L);

            n.doPulse(1000L);
            n.doPulse(2000L); // 1s later, interval = 5s, body unchanged

            assertEquals(1, n.emitted.size());
        }

        @Test
        void re_emits_after_interval_elapsed() {
            UUID p = UUID.randomUUID();
            AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply =
                    new AtomicReference<>(List.of(
                            row(p, NetworkStatusCache.QueueStatus.State.WAITLISTED, 2)));
            NetworkStatusCache cache = new NetworkStatusCache(supply::get);
            cache.pollOnce();
            CapturingNotifier n = new CapturingNotifier(cache, 5000L);

            n.doPulse(1000L);
            n.doPulse(6000L); // 5s elapsed, re-notify

            assertEquals(2, n.emitted.size());
        }

        @Test
        void re_emits_immediately_on_position_change() {
            UUID p = UUID.randomUUID();
            AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply =
                    new AtomicReference<>(List.of(
                            row(p, NetworkStatusCache.QueueStatus.State.WAITLISTED, 5)));
            NetworkStatusCache cache = new NetworkStatusCache(supply::get);
            cache.pollOnce();
            CapturingNotifier n = new CapturingNotifier(cache, 60_000L);

            n.doPulse(1000L);
            // position moves up to #2 within the dedup interval - body changed
            supply.set(List.of(row(p, NetworkStatusCache.QueueStatus.State.WAITLISTED, 2)));
            cache.pollOnce();
            n.doPulse(2000L);

            assertEquals(2, n.emitted.size());
            assertTrue(n.emitted.get(0).contains("5"),
                    "first emit should mention position 5, was: " + n.emitted.get(0));
            assertTrue(n.emitted.get(1).contains("2"),
                    "second emit should mention position 2, was: " + n.emitted.get(1));
        }

        @Test
        void does_not_emit_for_non_waitlisted_states() {
            UUID p = UUID.randomUUID();
            AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply =
                    new AtomicReference<>(List.of(
                            row(p, NetworkStatusCache.QueueStatus.State.TRANSFERRING, 0)));
            NetworkStatusCache cache = new NetworkStatusCache(supply::get);
            cache.pollOnce();
            CapturingNotifier n = new CapturingNotifier(cache, 5000L);

            n.doPulse(1000L);

            assertEquals(0, n.emitted.size());
        }

        @Test
        void evicts_dedup_when_player_falls_out_of_cache() {
            UUID p = UUID.randomUUID();
            AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply =
                    new AtomicReference<>(List.of(
                            row(p, NetworkStatusCache.QueueStatus.State.WAITLISTED, 3)));
            NetworkStatusCache cache = new NetworkStatusCache(supply::get);
            cache.pollOnce();
            CapturingNotifier n = new CapturingNotifier(cache, 5000L);

            n.doPulse(1000L);
            assertEquals(1, n.trackedCount());

            // Cache evicts the player (terminal / completed).
            supply.set(java.util.Collections.emptyList());
            cache.pollOnce();
            n.doPulse(2000L);

            assertEquals(0, n.trackedCount());
        }

        @Test
        void resets_dedup_when_player_leaves_waitlisted_state() {
            UUID p = UUID.randomUUID();
            AtomicReference<Collection<NetworkStatusCache.QueueStatus>> supply =
                    new AtomicReference<>(List.of(
                            row(p, NetworkStatusCache.QueueStatus.State.WAITLISTED, 1)));
            NetworkStatusCache cache = new NetworkStatusCache(supply::get);
            cache.pollOnce();
            CapturingNotifier n = new CapturingNotifier(cache, 5000L);

            n.doPulse(1000L);
            assertEquals(1, n.trackedCount());

            // Player moves to ROUTING (still in cache, no longer WAITLISTED).
            supply.set(List.of(row(p, NetworkStatusCache.QueueStatus.State.ROUTING, 0)));
            cache.pollOnce();
            n.doPulse(2000L);

            assertEquals(0, n.trackedCount());
            assertEquals(1, n.emitted.size(), "ROUTING should not emit a queued message");
        }
    }

    // ------------------------------------------------------------------
    // 4. Quit listener constructor contract (defensive: NPE on null queue)
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("NetworkWaitlistQuitListener constructor contract")
    class QuitListenerCtor {

        @Test
        void rejects_null_request_queue() {
            try {
                new NetworkWaitlistQuitListener(null);
            } catch (NullPointerException expected) {
                return;
            }
            org.junit.jupiter.api.Assertions.fail("expected NPE on null requestQueue");
        }

        @Test
        void accepts_non_null_request_queue() {
            // Build a trivial mock - the listener doesn't call into the queue
            // until an actual quit event lands, so a passthrough is enough.
            io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue rq =
                    new io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue() {
                        @Override public java.util.concurrent.CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope e) { return java.util.concurrent.CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED); }
                        @Override public java.util.concurrent.CompletableFuture<EnrolOutcome> flushPending(java.util.List<EnrolmentEnvelope> batch) { return java.util.concurrent.CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED); }
                        @Override public java.util.concurrent.CompletableFuture<java.util.List<QueueStatus>> pollStatus(java.util.List<UUID> ids) { return java.util.concurrent.CompletableFuture.completedFuture(java.util.Collections.emptyList()); }
                        @Override public java.util.concurrent.CompletableFuture<Optional<QueueEnvelope>> dequeueReady(java.time.Duration blockFor) { return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()); }
                        @Override public java.util.concurrent.CompletableFuture<Optional<QueueStatus>> transition(UUID p, QueueState n, Optional<String> r) { return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty()); }
                        @Override public java.util.concurrent.CompletableFuture<Void> cancel(UUID p, CancelReason r) { return java.util.concurrent.CompletableFuture.completedFuture(null); }
                    };
            NetworkWaitlistQuitListener l = new NetworkWaitlistQuitListener(rq);
            assertNotNull(l);
        }
    }

    // ------------------------------------------------------------------
    // Sanity: the proxy QueueState.WAITLISTED maps to the lobby
    // QueueStatus.State.WAITLISTED via name() (the bootstrap-side adapter
    // uses State.valueOf(s.state().name())). Pin this here so a future
    // rename on either side trips a test rather than silently dropping
    // proxy WAITLISTED rows.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("proxy QueueState.WAITLISTED and lobby State.WAITLISTED share a name() mapping")
    void state_enum_name_mapping_is_pinned() {
        // The mapping in NetworkModeBootstrap is:
        //     State.valueOf(proxyStatus.state().name())
        // If either enum loses WAITLISTED or renames it, this throws.
        NetworkStatusCache.QueueStatus.State mapped =
                NetworkStatusCache.QueueStatus.State.valueOf(
                        io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue
                                .QueueState.WAITLISTED.name());
        assertEquals(NetworkStatusCache.QueueStatus.State.WAITLISTED, mapped);

        // And the lobby enum value still flags as non-terminal.
        UUID u = UUID.randomUUID();
        assertTrue(row(u, mapped, 1).nonTerminal());
        assertNull(null, "structural guard"); // keep the import clean
    }
}
