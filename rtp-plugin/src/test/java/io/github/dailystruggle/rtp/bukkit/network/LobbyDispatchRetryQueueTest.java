package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Post-Slice-4 lobby auto-retry queue: verifies the four advance paths
 * (transient -> stay parked, transient cleared -> enrol + drop,
 * REGION_UNAVAILABLE -> terminate, TTL exhausted -> terminate).
 *
 * <p>Design choice: the test drives {@link LobbyDispatchRetryQueue#pulse()}
 * directly so it never touches {@code RTP.scheduler}. The router is built
 * with a mutable snapshot reference so a single test can simulate the
 * "first attempt transient, second attempt CrossServer" sequence that
 * is the whole point of the queue.</p>
 */
class LobbyDispatchRetryQueueTest {

    private static BackendHeartbeat hb(String id, Set<String> regions, boolean kill) {
        return new BackendHeartbeat(
                id, 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of(), List.of(),
                kill, 0, 0, regions, Map.of());
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(System.currentTimeMillis(), m);
    }

    /** Build a router whose snapshot can be swapped between pulses. */
    private static NetworkRouter routerWithSwappableSnapshot(AtomicReference<NetworkSnapshot> ref) {
        return new NetworkRouter(
                "local-1", NetworkRouter.Mode.CROSS_SERVER,
                ref::get,
                () -> 0, () -> 0, 50,
                100, 100,
                System::currentTimeMillis);
    }

    private static NetworkEnrolmentBuffer freshBuffer() {
        return new NetworkEnrolmentBuffer(batch -> { /* test sink: drop */ }, 100);
    }

    // ── 1. Transient first, then cleared: parked -> enrolled on next pulse ───

    @Test
    void transientThenCleared_parksThenEnrolsOnNextPulse() {
        // Null snapshot trips Gate 1 (NETWORK_DISABLED, transient). Once
        // we set a snapshot with a live peer that hosts the requested
        // region, the router returns CrossServer and the queue enrols.
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        NetworkEnrolmentBuffer buffer = freshBuffer();
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, buffer,
                /* pulseIntervalMs */ 50L,
                /* maxAttempts */    20,
                /* maxTotalMs */     30_000L);

        UUID player = UUID.randomUUID();
        queue.enqueue(player,
                Map.of("region", List.of("backend-a:default")),
                RoutingDecision.FallbackReason.NO_LIVE_PEER,
                null);

        assertTrue(queue.isParked(player), "newly enqueued player must be parked");
        assertEquals(0, buffer.pendingDepth(), "no enrolment yet");

        // First pulse: snapshot still empty, transient persists.
        queue.pulse();
        assertTrue(queue.isParked(player),
                "transient fallback must KEEP the entry parked for the next pulse");
        assertEquals(0, buffer.pendingDepth());

        // Peer comes online; next pulse should enrol and drop the entry.
        snapRef.set(snap(hb("backend-a", Set.of("default"), false)));
        queue.pulse();

        assertFalse(queue.isParked(player),
                "successful CrossServer must drop the entry from the parked map");
        assertEquals(1, buffer.pendingDepth(),
                "successful enrolment must offer exactly one EnrolmentRecord");
    }

    // ── 1b. onTerminalFailure re-parks a recorded enrolment ──────────────────

    @Test
    void onTerminalFailure_reparksRecordedEnrolment_andReturnsTrue() {
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        NetworkEnrolmentBuffer buffer = freshBuffer();
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, buffer, 50L, 20, 30_000L);

        UUID player = UUID.randomUUID();
        // Simulate the hook's "real CrossServer" path: it recorded the
        // enrolment so we could recover from a proxy that never responds.
        queue.recordEnrolment(player,
                Map.of("region", List.of("backend-a:default")),
                null);

        assertFalse(queue.isParked(player), "recordEnrolment does not park");

        // Sticky-TTL FAILED arrives from the status cache.
        boolean reparked = queue.onTerminalFailure(player);
        assertTrue(reparked, "onTerminalFailure must return true when an enrolment was recorded");
        assertTrue(queue.isParked(player),
                "onTerminalFailure must move the player into the parked map for a fresh retry");

        // Peer is live now -> next pulse enrols.
        snapRef.set(snap(hb("backend-a", Set.of("default"), false)));
        queue.pulse();
        assertFalse(queue.isParked(player),
                "re-parked entry must enrol on the next pulse when routing succeeds");
        assertEquals(1, buffer.pendingDepth());
    }

    @Test
    void onTerminalFailure_returnsFalse_whenNoEnrolmentRecorded() {
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, freshBuffer(), 50L, 20, 30_000L);

        assertFalse(queue.onTerminalFailure(UUID.randomUUID()),
                "no recorded enrolment -> caller should release the lock");
    }

    // ── 2. REGION_UNAVAILABLE is terminal, not retried ───────────────────────

    @Test
    void regionUnavailable_isTerminal_dropsEntryImmediatelyOnFirstPulse() {
        // Snapshot has a live peer but it does NOT advertise the requested
        // region. Router returns LocalFallback(REGION_UNAVAILABLE).
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(
                snap(hb("backend-a", Set.of("nether"), false)));
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        NetworkEnrolmentBuffer buffer = freshBuffer();
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, buffer, 50L, 20, 30_000L);

        UUID player = UUID.randomUUID();
        // The hook would have enqueued this with reason=NO_LIVE_PEER on the
        // first attempt (i.e. before the snapshot had any peers). We feed
        // it the same args; on the first pulse the router now returns
        // REGION_UNAVAILABLE which is terminal.
        queue.enqueue(player,
                Map.of("region", List.of("backend-a:default")),
                RoutingDecision.FallbackReason.NO_LIVE_PEER,
                null);

        queue.pulse();

        assertFalse(queue.isParked(player),
                "REGION_UNAVAILABLE is terminal; the entry must NOT stay parked");
        assertEquals(0, buffer.pendingDepth(),
                "terminal fallback must NOT enrol the player");
    }

    // ── 3. TTL exhaustion drops the entry ────────────────────────────────────

    @Test
    void ttlExhausted_dropsEntry() {
        // Null snapshot stays so every pulse re-trips NETWORK_DISABLED.
        // A 1s TTL guarantees the next pulse sees elapsed > TTL.
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        NetworkEnrolmentBuffer buffer = freshBuffer();
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, buffer, 50L, 20, /* maxTotalMs */ 1_000L);
        // maxTotalMs is clamped to a min of 1_000ms by the ctor; we'll
        // instead burn down attempts: maxAttempts is clamped to >= 1.

        UUID player = UUID.randomUUID();
        queue.enqueue(player,
                Map.of("region", List.of("backend-a:default")),
                RoutingDecision.FallbackReason.NO_LIVE_PEER,
                null);

        // Wait past the 1s TTL window so the next pulse times out.
        try { Thread.sleep(1_100L); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        queue.pulse();

        assertFalse(queue.isParked(player),
                "TTL exhaustion must drop the entry");
        assertEquals(0, buffer.pendingDepth(),
                "TTL exhaustion is terminal; no enrolment");
    }

    // ── 4. Attempt cap drops the entry once attempts > maxAttempts ───────────

    @Test
    void attemptCapExceeded_dropsEntry() {
        // Null snapshot => every pulse trips NETWORK_DISABLED (transient).
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        NetworkEnrolmentBuffer buffer = freshBuffer();
        // maxAttempts=1 means the second pulse (attempts goes to 2) is
        // beyond the cap and terminates.
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, buffer, 50L, /* maxAttempts */ 1, 30_000L);

        UUID player = UUID.randomUUID();
        queue.enqueue(player,
                Map.of("region", List.of("backend-a:default")),
                RoutingDecision.FallbackReason.NO_LIVE_PEER,
                null);

        // First pulse: attempts=1, transient persists, entry stays parked.
        queue.pulse();
        assertTrue(queue.isParked(player), "attempt 1 still under cap");

        // Second pulse: attempts=2 > cap=1, terminate.
        queue.pulse();
        assertFalse(queue.isParked(player),
                "attempts > maxAttempts must terminate the entry");
        assertEquals(0, buffer.pendingDepth());
    }

    // ── 5. Duplicate enqueue is a no-op ──────────────────────────────────────

    @Test
    void duplicateEnqueue_isNoOp() {
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, freshBuffer(), 50L, 20, 30_000L);

        UUID player = UUID.randomUUID();
        queue.enqueue(player,
                Map.of("region", List.of("backend-a:default")),
                RoutingDecision.FallbackReason.NO_LIVE_PEER,
                null);
        queue.enqueue(player,
                Map.of("region", List.of("backend-a:default")),
                RoutingDecision.FallbackReason.QUEUE_FULL,
                null);

        assertEquals(1, queue.size(), "duplicate enqueue must be a no-op");
    }

    // ── 6. cancel() removes a parked entry ──────────────────────────────────

    @Test
    void cancel_removesParkedEntry() {
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        LobbyDispatchRetryQueue queue = new LobbyDispatchRetryQueue(
                router, freshBuffer(), 50L, 20, 30_000L);

        UUID player = UUID.randomUUID();
        queue.enqueue(player,
                Map.of("region", List.of("backend-a:default")),
                RoutingDecision.FallbackReason.NO_LIVE_PEER,
                null);
        assertTrue(queue.isParked(player));

        // Disconnect path: messageKey=null so no chat is sent.
        queue.cancel(player, null);

        assertFalse(queue.isParked(player),
                "cancel(uuid,null) must drop the entry");
    }

    // ── 7. Hook integration: lobby + transient -> synthetic CrossServer + park

    @Test
    void hookIntegration_lobbyMode_transientFallback_parksAndReturnsSyntheticCrossServer() {
        // Null snapshot => NETWORK_DISABLED (transient). We provide an
        // explicit region= arg to exercise the non-lobby-noargs path
        // (which on a lobby would have synthesised via pickMostKept).
        AtomicReference<NetworkSnapshot> snapRef = new AtomicReference<>(null);
        NetworkRouter router = routerWithSwappableSnapshot(snapRef);
        NetworkEnrolmentBuffer buffer = freshBuffer();
        LobbyDispatchRetryQueue retry = new LobbyDispatchRetryQueue(router, buffer);
        PeerRegionRegistry reg = new PeerRegionRegistry(snapRef::get, "local-1");

        BukkitNetworkCommandHook hook = new BukkitNetworkCommandHook(
                router, buffer, reg, /* lobbyMode */ true,
                /* backendStatePublisher */ null,
                /* lobbyRetryQueue */ retry);

        UUID player = UUID.randomUUID();
        var result = hook.route(player, Map.of("region", List.of("backend-a:default")));

        // The hook must NOT have returned Local; on a lobby that would be
        // a dead path. Synthetic CrossServer is the contract.
        assertTrue(result instanceof
                        io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult.CrossServer,
                "lobby + transient fallback must return synthetic CrossServer; got "
                        + result.getClass().getSimpleName());

        // Side effect: the player must be parked on the retry queue and the
        // enrolment buffer must NOT have a record yet (the retry pulse
        // will offer one when the transient clears).
        assertTrue(retry.isParked(player),
                "lobby + transient fallback must park the player on the retry queue");
        assertEquals(0, buffer.pendingDepth(),
                "synthetic CrossServer must NOT pre-enrol on the buffer; the queue's "
                        + "next successful pulse is what enrols.");
    }
}
