package io.github.dailystruggle.rtp.proxy.common.dispatch;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.DispatchOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpDispatcher;
import io.github.dailystruggle.rtp.proxy.common.spi.RtpRequest;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.AlwaysLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkWaitlist;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-NET-015 unit guards for {@link NetworkWaitlistDrainer} (Slice 2).
 *
 * <p>Covers per-pulse outcomes:
 * <ul>
 *   <li>EMPTY when the waitlist is empty.</li>
 *   <li>SKIPPED_NOT_LEADER when the lease denies leadership.</li>
 *   <li>NO_CAPACITY when no backend qualifies (all kill-switched / not
 *       READY / zero {@code regionKeptCounts}).</li>
 *   <li>PROGRESS when at least one drained envelope is routed.</li>
 *   <li>TOTAL_FAILURE refreshes TTLs and pauses; subsequent pulse is
 *       {@code SKIPPED_PAUSED}.</li>
 *   <li>Per-backend caps match {@code sum(regionKeptCounts) -
 *       networkReservedCount}.</li>
 * </ul>
 */
class NetworkWaitlistDrainerTest {

    private InMemoryNetworkWaitlist waitlist;

    @AfterEach
    void tearDown() {
        if (waitlist != null) waitlist.shutdown();
    }

    private static BackendHeartbeat readyBackend(String serverId, Map<String, Integer> regionKept,
                                                 int reserved, boolean killSwitch) {
        return new BackendHeartbeat(
                serverId, 1, PluginState.READY, true,
                System.currentTimeMillis(),
                30.0, 0, 100,
                64L * 1024 * 1024, 512L * 1024 * 1024,
                0,
                List.of(), List.of(),
                killSwitch,
                /* keptCount */ regionKept.values().stream().mapToInt(Integer::intValue).sum(),
                /* networkReservedCount */ reserved,
                regionKept.keySet(),
                regionKept);
    }

    private static NetworkWaitlist.WaitEnvelope envelope(UUID player) {
        return new NetworkWaitlist.WaitEnvelope(
                player, UUID.randomUUID(),
                Optional.empty(), Optional.empty(),
                "proxy-A", System.currentTimeMillis());
    }

    @Test
    void empty_waitlist_resolves_EMPTY() throws Exception {
        waitlist = new InMemoryNetworkWaitlist();
        FakeTransport transport = new FakeTransport(List.of(readyBackend("b1", Map.of("r1", 4), 0, false)));
        RecordingDispatcher dispatcher = new RecordingDispatcher();

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(
                waitlist, transport, dispatcher, new AlwaysLeaderLease());

        assertEquals(NetworkWaitlistDrainer.PulseResult.EMPTY, drainer.runPulse().get());
        assertEquals(0, dispatcher.calls.get());
    }

    @Test
    void not_leader_resolves_SKIPPED_NOT_LEADER_without_touching_waitlist() throws Exception {
        waitlist = new InMemoryNetworkWaitlist();
        waitlist.enrol(envelope(UUID.randomUUID())).get();
        FakeTransport transport = new FakeTransport(List.of(readyBackend("b1", Map.of("r1", 4), 0, false)));
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        WaitlistLeaderLease denying = new WaitlistLeaderLease() {
            @Override public CompletableFuture<Boolean> tryAcquire(Duration holdFor) {
                return CompletableFuture.completedFuture(false);
            }
            @Override public CompletableFuture<Void> release() {
                return CompletableFuture.completedFuture(null);
            }
        };

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(waitlist, transport, dispatcher, denying);
        assertEquals(NetworkWaitlistDrainer.PulseResult.SKIPPED_NOT_LEADER, drainer.runPulse().get());
        assertEquals(0, dispatcher.calls.get());
        assertEquals(1, waitlist.sizeNow(), "Non-leader must not drain");
    }

    @Test
    void no_qualifying_backend_resolves_NO_CAPACITY() throws Exception {
        waitlist = new InMemoryNetworkWaitlist();
        waitlist.enrol(envelope(UUID.randomUUID())).get();
        // Backend exists but is kill-switched -> disqualified.
        FakeTransport transport = new FakeTransport(
                List.of(readyBackend("b1", Map.of("r1", 4), 0, true)));
        RecordingDispatcher dispatcher = new RecordingDispatcher();

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(
                waitlist, transport, dispatcher, new AlwaysLeaderLease());

        assertEquals(NetworkWaitlistDrainer.PulseResult.NO_CAPACITY, drainer.runPulse().get());
        assertEquals(0, dispatcher.calls.get(), "No dispatch when no backend qualifies");
        assertEquals(1, waitlist.sizeNow(), "Envelope must remain parked on NO_CAPACITY");
    }

    @Test
    void per_backend_cap_equals_kept_minus_reserved() {
        NetworkSnapshot snap = snapshotOf(
                readyBackend("b1", Map.of("r1", 5, "r2", 3), /* reserved */ 2, false),
                readyBackend("b2", Map.of("r1", 4), 4, false), // fully reserved
                readyBackend("b3", Map.of("r1", 2), 0, true)); // kill-switched
        Map<String, Integer> caps = NetworkWaitlistDrainer.computePerBackendCaps(snap);
        assertEquals(Map.of("b1", 6), caps,
                "Cap = sum(regionKeptCounts) - networkReservedCount; "
                        + "fully reserved and kill-switched backends are excluded");
    }

    @Test
    void progress_when_some_envelopes_route() throws Exception {
        waitlist = new InMemoryNetworkWaitlist();
        waitlist.enrol(envelope(UUID.randomUUID())).get();
        waitlist.enrol(envelope(UUID.randomUUID())).get();
        FakeTransport transport = new FakeTransport(
                List.of(readyBackend("b1", Map.of("r1", 4), 0, false)));
        // Dispatcher routes everything successfully.
        RecordingDispatcher dispatcher = new RecordingDispatcher(req ->
                CompletableFuture.completedFuture(
                        new DispatchOutcome.Routed("b1", "token-" + req.playerId())));

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(
                waitlist, transport, dispatcher, new AlwaysLeaderLease());

        assertEquals(NetworkWaitlistDrainer.PulseResult.PROGRESS, drainer.runPulse().get());
        assertEquals(2, dispatcher.calls.get());
        assertEquals(0, waitlist.sizeNow(), "Routed envelopes must leave the waitlist");
    }

    @Test
    void total_failure_refreshes_ttls_and_pauses_next_pulse() throws Exception {
        waitlist = new InMemoryNetworkWaitlist();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        long old = System.currentTimeMillis() - 60_000;
        waitlist.enrol(new NetworkWaitlist.WaitEnvelope(
                p1, UUID.randomUUID(), Optional.empty(), Optional.empty(), "proxy-A", old)).get();
        waitlist.enrol(new NetworkWaitlist.WaitEnvelope(
                p2, UUID.randomUUID(), Optional.empty(), Optional.empty(), "proxy-A", old)).get();
        FakeTransport transport = new FakeTransport(
                List.of(readyBackend("b1", Map.of("r1", 4), 0, false)));
        // Dispatcher fails every request.
        RecordingDispatcher dispatcher = new RecordingDispatcher(req ->
                CompletableFuture.completedFuture(new DispatchOutcome.Failed(
                        DispatchOutcome.Failed.Reason.INTERNAL, "rtp.network.internal-error")));

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(
                waitlist, transport, dispatcher, new AlwaysLeaderLease(),
                Duration.ofSeconds(2), Duration.ofMillis(500));

        NetworkWaitlistDrainer.PulseResult first = drainer.runPulse().get();
        assertEquals(NetworkWaitlistDrainer.PulseResult.TOTAL_FAILURE, first);
        // Envelopes were drained out of the waitlist's FIFO into the dispatch
        // attempts. The waitlist therefore drops to zero entries; the
        // refresh-and-pause path protects whatever is left in the waitlist
        // (here: nothing), and crucially pauses the next pulse so the
        // drainer does not hammer a known-dead network.
        assertTrue(drainer.pausedUntilEpochMs() > System.currentTimeMillis(),
                "Drainer must be paused after a total-failure pulse");

        // Re-enrol a fresh envelope; the next pulse should SKIP_PAUSED.
        waitlist.enrol(envelope(UUID.randomUUID())).get();
        assertEquals(NetworkWaitlistDrainer.PulseResult.SKIPPED_PAUSED, drainer.runPulse().get(),
                "Pulse during the pause window must short-circuit to SKIPPED_PAUSED");

        // resumeNow() unblocks; with the dispatcher still failing, we get
        // another TOTAL_FAILURE.
        drainer.resumeNow();
        assertEquals(NetworkWaitlistDrainer.PulseResult.TOTAL_FAILURE, drainer.runPulse().get());
    }

    @Test
    void total_failure_refreshes_ttls_of_entries_left_in_waitlist() throws Exception {
        // This scenario: drain takes ONE envelope (cap=1), it fails; the
        // OTHER parked envelope (still on the waitlist) gets its TTL refreshed.
        waitlist = new InMemoryNetworkWaitlist();
        UUID drained = UUID.randomUUID();
        UUID parked = UUID.randomUUID();
        long stale = System.currentTimeMillis() - 60_000;
        waitlist.enrol(new NetworkWaitlist.WaitEnvelope(
                drained, UUID.randomUUID(), Optional.empty(), Optional.empty(), "proxy-A", stale)).get();
        waitlist.enrol(new NetworkWaitlist.WaitEnvelope(
                parked, UUID.randomUUID(), Optional.empty(), Optional.empty(), "proxy-A", stale)).get();

        FakeTransport transport = new FakeTransport(
                // cap=1: only one envelope drained per pulse.
                List.of(readyBackend("b1", Map.of("r1", 1), 0, false)));
        RecordingDispatcher dispatcher = new RecordingDispatcher(req ->
                CompletableFuture.completedFuture(new DispatchOutcome.Failed(
                        DispatchOutcome.Failed.Reason.INTERNAL, "rtp.network.internal-error")));

        NetworkWaitlistDrainer drainer = new NetworkWaitlistDrainer(
                waitlist, transport, dispatcher, new AlwaysLeaderLease(),
                Duration.ofSeconds(2), Duration.ofMillis(500));

        long before = System.currentTimeMillis();
        assertEquals(NetworkWaitlistDrainer.PulseResult.TOTAL_FAILURE, drainer.runPulse().get());
        // The drained envelope was consumed by drainBatch; only `parked`
        // remains. Its enrolledAtMs must have been refreshed.
        assertEquals(1, waitlist.sizeNow());
        // The reap-with-very-short-maxAge proves the entry is no longer stale:
        // if refreshAllTtl ran, enrolledAtMs >= before; reap(maxAge=10s) keeps it.
        int reaped = waitlist.reap(Duration.ofSeconds(10)).get();
        assertEquals(0, reaped, "Parked entry must have been TTL-refreshed past the reap cutoff");
    }

    // ---- fakes ----

    private static NetworkSnapshot snapshotOf(BackendHeartbeat... backends) {
        LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat b : backends) m.put(b.serverId(), b);
        return new NetworkSnapshot(Instant.now().toEpochMilli(), m);
    }

    private static final class RecordingDispatcher implements RtpDispatcher {
        final AtomicInteger calls = new AtomicInteger(0);
        final ConcurrentLinkedQueue<RtpRequest> received = new ConcurrentLinkedQueue<>();
        private final java.util.function.Function<RtpRequest, CompletableFuture<DispatchOutcome>> fn;

        RecordingDispatcher() {
            this(req -> CompletableFuture.completedFuture(new DispatchOutcome.Queued(0)));
        }

        RecordingDispatcher(java.util.function.Function<RtpRequest, CompletableFuture<DispatchOutcome>> fn) {
            this.fn = fn;
        }

        @Override
        public CompletableFuture<DispatchOutcome> dispatch(RtpRequest request) {
            calls.incrementAndGet();
            received.add(request);
            return fn.apply(request);
        }
    }

    private static final class FakeTransport implements NetworkTransport {
        final NetworkSnapshot snapshot;

        FakeTransport(List<BackendHeartbeat> backends) {
            LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
            for (BackendHeartbeat b : backends) m.put(b.serverId(), b);
            this.snapshot = new NetworkSnapshot(Instant.now().toEpochMilli(), m);
        }

        @Override
        public CompletableFuture<NetworkSnapshot> readSnapshot() {
            return CompletableFuture.completedFuture(snapshot);
        }

        @Override
        public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
            return new Subscription() {
                @Override public void close() { }
                @Override public boolean isClosed() { return false; }
            };
        }

        @Override public void close() { }
    }
}
