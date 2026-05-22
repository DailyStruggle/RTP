package io.github.dailystruggle.rtp.bukkit.network;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.network.NetworkCommandHook.RoutingResult;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L6 Slice I (lobby mode) coverage. Targets:
 *
 * <ol>
 *   <li>{@link PeerRegionRegistry#pickMostKept()} returns the peer+region
 *       with the largest {@code regionKeptCounts} value.</li>
 *   <li>{@link BukkitNetworkCommandHook} in lobby mode synthesises a
 *       cross-server enrolment from a bare no-arg {@code /rtp}.</li>
 *   <li>Empty / kill-switch / self-only snapshots in lobby mode reject
 *       visibly via {@code networkRegionUnavailable} rather than
 *       silently falling through.</li>
 *   <li>Lobby mode does not break explicit
 *       {@code rtp region=server:region} dispatch.</li>
 *   <li>Non-lobby (legacy 2-arg ctor) behaviour preserved.</li>
 * </ol>
 */
class LobbyModeTest {

    private static BackendHeartbeat hb(String id,
                                       Set<String> regions,
                                       Map<String, Integer> kept,
                                       boolean kill) {
        return new BackendHeartbeat(
                id, 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of(), List.of(),
                kill, 0, 0, regions, kept);
    }

    private static NetworkSnapshot snap(BackendHeartbeat... rows) {
        LinkedHashMap<String, BackendHeartbeat> m = new LinkedHashMap<>();
        for (BackendHeartbeat r : rows) m.put(r.serverId(), r);
        return new NetworkSnapshot(System.currentTimeMillis(), m);
    }

    private static NetworkRouter routerWith(NetworkSnapshot s) {
        return new NetworkRouter(
                "lobby-1",
                NetworkRouter.Mode.CROSS_SERVER,
                () -> s,
                () -> 0,
                () -> 0,
                50, 100, 100,
                System::currentTimeMillis);
    }

    // ----- PeerRegionRegistry.pickMostKept ------------------------------

    @Test
    void pickMostKept_chooses_largest_count() {
        PeerRegionRegistry reg = new PeerRegionRegistry(
                () -> snap(
                        hb("backend-a", Set.of("default"), Map.of("default", 3), false),
                        hb("backend-b", Set.of("default", "east"),
                                Map.of("default", 1, "east", 9), false)),
                "lobby-1");
        Optional<PeerRegionRegistry.ServerRegion> pick = reg.pickMostKept();
        assertTrue(pick.isPresent());
        assertEquals("backend-b", pick.get().serverId());
        assertEquals("east", pick.get().regionKey());
    }

    @Test
    void pickMostKept_excludes_self_server() {
        PeerRegionRegistry reg = new PeerRegionRegistry(
                () -> snap(
                        hb("lobby-1", Set.of("default"), Map.of("default", 99), false),
                        hb("backend-a", Set.of("default"), Map.of("default", 2), false)),
                "lobby-1");
        Optional<PeerRegionRegistry.ServerRegion> pick = reg.pickMostKept();
        assertTrue(pick.isPresent());
        assertEquals("backend-a", pick.get().serverId());
    }

    @Test
    void pickMostKept_excludes_killSwitch_peers() {
        PeerRegionRegistry reg = new PeerRegionRegistry(
                () -> snap(
                        hb("backend-a", Set.of("default"), Map.of("default", 99), /*kill=*/true),
                        hb("backend-b", Set.of("default"), Map.of("default", 1), false)),
                "lobby-1");
        Optional<PeerRegionRegistry.ServerRegion> pick = reg.pickMostKept();
        assertTrue(pick.isPresent());
        assertEquals("backend-b", pick.get().serverId());
    }

    @Test
    void pickMostKept_empty_when_no_peers() {
        PeerRegionRegistry reg = new PeerRegionRegistry(
                () -> snap(),
                "lobby-1");
        assertTrue(reg.pickMostKept().isEmpty());
    }

    @Test
    void pickMostKept_empty_when_snapshot_null() {
        PeerRegionRegistry reg = new PeerRegionRegistry(() -> null, "lobby-1");
        assertTrue(reg.pickMostKept().isEmpty());
    }

    @Test
    void pickMostKept_tiebreak_is_deterministic_by_serverId_then_region() {
        // Two peers both kept=5 on "default"; lexicographic serverId wins.
        PeerRegionRegistry reg = new PeerRegionRegistry(
                () -> snap(
                        hb("backend-z", Set.of("default"), Map.of("default", 5), false),
                        hb("backend-a", Set.of("default"), Map.of("default", 5), false)),
                "lobby-1");
        Optional<PeerRegionRegistry.ServerRegion> pick = reg.pickMostKept();
        assertTrue(pick.isPresent());
        assertEquals("backend-a", pick.get().serverId());
    }

    @Test
    void pickMostKept_legacy_peer_with_regionsAvailable_falls_through() {
        // Pre-L6 peer: regions Set empty, regionsAvailable list populated,
        // regionKeptCounts empty. Should still be considered (count=0).
        BackendHeartbeat legacy = new BackendHeartbeat(
                "legacy-1", 1, BackendHeartbeat.PluginState.READY, true,
                System.currentTimeMillis(), 5.0, 0, 100, 0L, 1L, 0,
                List.of("default"), List.of(),
                false, 0, 0, Set.of(), Map.of());
        PeerRegionRegistry reg = new PeerRegionRegistry(() -> snap(legacy), "lobby-1");
        Optional<PeerRegionRegistry.ServerRegion> pick = reg.pickMostKept();
        assertTrue(pick.isPresent());
        assertEquals("legacy-1", pick.get().serverId());
        assertEquals("default", pick.get().regionKey());
    }

    // ----- BukkitNetworkCommandHook lobby-mode dispatch ----------------

    @Test
    void lobbyMode_noArg_synthesises_crossServer_to_mostKept() {
        NetworkSnapshot s = snap(
                hb("backend-a", Set.of("default"), Map.of("default", 7), false),
                hb("backend-b", Set.of("default"), Map.of("default", 2), false));
        NetworkRouter router = routerWith(s);
        NetworkEnrolmentBuffer buffer = new NetworkEnrolmentBuffer(
                batch -> {}, 100);
        PeerRegionRegistry reg = new PeerRegionRegistry(() -> s, "lobby-1");
        BukkitNetworkCommandHook hook = new BukkitNetworkCommandHook(
                router, buffer, reg, /*lobbyMode=*/true);

        RoutingResult r = hook.route(UUID.randomUUID(), Map.of());

        var cs = assertInstanceOf(RoutingResult.CrossServer.class, r);
        assertEquals("default", cs.regionKey().orElseThrow());
        assertEquals("backend-a", cs.serverHint().orElseThrow());
    }

    @Test
    void lobbyMode_noArg_with_no_peers_rejects_networkRegionUnavailable() {
        NetworkSnapshot s = snap(); // empty
        NetworkRouter router = routerWith(s);
        NetworkEnrolmentBuffer buffer = new NetworkEnrolmentBuffer(
                batch -> {}, 100);
        PeerRegionRegistry reg = new PeerRegionRegistry(() -> s, "lobby-1");
        BukkitNetworkCommandHook hook = new BukkitNetworkCommandHook(
                router, buffer, reg, true);

        RoutingResult r = hook.route(UUID.randomUUID(), Map.of());

        var rej = assertInstanceOf(RoutingResult.Reject.class, r);
        assertEquals(MessagesKeys.networkRegionUnavailable.name(), rej.messageKey());
    }

    @Test
    void lobbyMode_explicit_region_still_routes_via_parseRegionArgQualified() {
        NetworkSnapshot s = snap(
                hb("backend-a", Set.of("default"), Map.of("default", 1), false),
                hb("backend-b", Set.of("default"), Map.of("default", 9), false));
        NetworkRouter router = routerWith(s);
        NetworkEnrolmentBuffer buffer = new NetworkEnrolmentBuffer(
                batch -> {}, 100);
        PeerRegionRegistry reg = new PeerRegionRegistry(() -> s, "lobby-1");
        BukkitNetworkCommandHook hook = new BukkitNetworkCommandHook(
                router, buffer, reg, true);

        // Player typed `rtp region=backend-a:default` -> the explicit pick
        // wins over pickMostKept's preference for backend-b.
        RoutingResult r = hook.route(
                UUID.randomUUID(),
                Map.of("region", List.of("backend-a:default")));

        var cs = assertInstanceOf(RoutingResult.CrossServer.class, r);
        assertEquals("default", cs.regionKey().orElseThrow());
        assertEquals("backend-a", cs.serverHint().orElseThrow());
    }

    @Test
    void nonLobby_legacy_ctor_does_not_synthesise_serverHint() {
        // Legacy 2-arg ctor: a no-arg /rtp on a non-lobby backend goes
        // through parseRegionArgQualified(null), which yields a
        // ParsedRegion with null regionKey AND null serverHint - the
        // router then decides freely. The contract this test pins is
        // "pickMostKept is NOT called" - i.e. the hook does not invent
        // a serverHint from the snapshot. If the router happens to
        // produce a CrossServer (because mode=CROSS_SERVER + any
        // available peer), its serverHint MUST be empty (router-chosen,
        // not hook-synthesised). Under lobby mode the equivalent call
        // would synthesise a non-empty serverHint via pickMostKept.
        NetworkSnapshot s = snap(
                hb("backend-a", Set.of("default"), Map.of("default", 7), false));
        NetworkRouter router = routerWith(s);
        NetworkEnrolmentBuffer buffer = new NetworkEnrolmentBuffer(
                batch -> {}, 100);
        BukkitNetworkCommandHook hook = new BukkitNetworkCommandHook(router, buffer);

        RoutingResult r = hook.route(UUID.randomUUID(), Map.of());

        if (r instanceof RoutingResult.CrossServer cs) {
            // Router-derived hint must be empty - the hook didn't pre-pin.
            assertTrue(cs.serverHint().isEmpty(),
                    "non-lobby hook must not synthesise serverHint; got "
                            + cs.serverHint().orElse("?"));
        } else {
            // Local or Reject are both acceptable - the invariant is
            // just "no hook-synthesised hard pin".
            assertTrue(r instanceof RoutingResult.Local
                            || r instanceof RoutingResult.Reject,
                    "unexpected RoutingResult: " + r.getClass().getName());
        }
    }

    @Test
    void lobbyMode_ctor_rejects_null_registry() {
        NetworkRouter router = routerWith(snap());
        NetworkEnrolmentBuffer buffer = new NetworkEnrolmentBuffer(
                batch -> {}, 100);
        assertThrows(IllegalArgumentException.class, () ->
                new BukkitNetworkCommandHook(router, buffer, null, true));
    }

    // ----- BukkitBackendStateSampler lobby suppression -----------------
    // Note: full sampler tests live elsewhere (rtp-bukkit-common test set);
    // we cover the flag-toggle invariant here so the bootstrap wiring is
    // exercised in this module.

    @Test
    void sampler_lobbyMode_default_off() {
        io.github.dailystruggle.rtp.bukkitplatform.network.BukkitBackendStateSampler s =
                new io.github.dailystruggle.rtp.bukkitplatform.network.BukkitBackendStateSampler();
        assertSame(false, s.isLobbyMode());
    }

    @Test
    void sampler_lobbyMode_via_ctor_then_setter() {
        io.github.dailystruggle.rtp.bukkitplatform.network.BukkitBackendStateSampler s =
                new io.github.dailystruggle.rtp.bukkitplatform.network.BukkitBackendStateSampler(true);
        assertSame(true, s.isLobbyMode());
        s.setLobbyMode(false);
        assertSame(false, s.isLobbyMode());
    }

    // ----- Slice I follow-up: acceptingRequests filter -----------------

    /**
     * Build a heartbeat with explicit {@code acceptingRequests} and an
     * explicit {@code lastSeenEpochMs} so the decrement-clearing test can
     * pin a deterministic before/after relationship without relying on
     * wall-clock progression.
     */
    private static BackendHeartbeat hbFull(String id,
                                           Set<String> regions,
                                           Map<String, Integer> kept,
                                           boolean accepting,
                                           long lastSeenMs) {
        return new BackendHeartbeat(
                id, 1, BackendHeartbeat.PluginState.READY, accepting,
                lastSeenMs, 5.0, 0, 100, 0L, 1L, 0,
                List.of(), List.of(),
                /*kill*/ false, 0, 0, regions, kept);
    }

    @Test
    void pickMostKept_excludes_peers_not_accepting_requests() {
        // backend-a has the larger count BUT is not accepting requests
        // (e.g. still warming caches or draining). pickMostKept must skip
        // it and fall back to ready backend-b. Without this filter, the
        // earlier lex tiebreak forces backend-a to win when both peers
        // tie at count=0 (which is the default for the 13-arg ctor); this
        // test ALSO covers the higher-count case so the filter is not
        // accidentally weakened to "only tiebreak time".
        PeerRegionRegistry reg = new PeerRegionRegistry(
                () -> snap(
                        hbFull("backend-a", Set.of("default"),
                                Map.of("default", 99), /*accepting=*/false, 1L),
                        hbFull("backend-b", Set.of("default"),
                                Map.of("default", 1), /*accepting=*/true, 1L)),
                "lobby-1");
        Optional<PeerRegionRegistry.ServerRegion> pick = reg.pickMostKept();
        assertTrue(pick.isPresent());
        assertEquals("backend-b", pick.get().serverId(),
                "unready peer must be excluded even when its kept count is higher");
    }

    @Test
    void pickMostKept_zero_vs_zero_tiebreak_does_not_pick_unready_backend_a() {
        // Regression: the user-reported "backend-a unready, never falls
        // through to backend-b" symptom. Both peers tie at 0 kept; the
        // deterministic lex tiebreak (backend-a < backend-b) would force
        // backend-a to win - except that the new acceptingRequests filter
        // excludes it from candidate set entirely.
        PeerRegionRegistry reg = new PeerRegionRegistry(
                () -> snap(
                        hbFull("backend-a", Set.of("default"),
                                Map.of(), /*accepting=*/false, 1L),
                        hbFull("backend-b", Set.of("default"),
                                Map.of(), /*accepting=*/true, 1L)),
                "lobby-1");
        Optional<PeerRegionRegistry.ServerRegion> pick = reg.pickMostKept();
        assertTrue(pick.isPresent());
        assertEquals("backend-b", pick.get().serverId());
    }

    // ----- Slice I follow-up: on-send local decrement ------------------

    @Test
    void pickMostKept_applies_local_decrement_after_recordDispatch() {
        // Anchor heartbeats with deterministic lastSeenMs=1L so the
        // decrement is recorded against that anchor and is NOT cleared
        // on read (the snapshot's lastSeenMs is == anchor, not >).
        NetworkSnapshot s = snap(
                hbFull("backend-a", Set.of("default"),
                        Map.of("default", 5), true, 1L),
                hbFull("backend-b", Set.of("default"),
                        Map.of("default", 4), true, 1L));
        PeerRegionRegistry reg = new PeerRegionRegistry(() -> s, "lobby-1");

        // First pick: backend-a (5 > 4).
        assertEquals("backend-a", reg.pickMostKept().orElseThrow().serverId());

        // Record two dispatches against backend-a; effective count -> 3.
        reg.recordDispatch("backend-a", "default");
        reg.recordDispatch("backend-a", "default");
        assertEquals(2, reg.pendingDecrementFor("backend-a", "default"));

        // Now backend-b (4) outscores backend-a (5-2 = 3); pickMostKept
        // must switch.
        assertEquals("backend-b", reg.pickMostKept().orElseThrow().serverId(),
                "after two dispatches, backend-a's effective count drops "
                        + "below backend-b's and the picker must spread the load");
    }

    @Test
    void decrement_cleared_on_strictly_newer_heartbeat() {
        // Mutable holder so we can flip the snapshot's lastSeenEpochMs
        // mid-test, simulating a fresh heartbeat arriving from the peer.
        long[] lastSeen = { 1L };
        java.util.function.Supplier<NetworkSnapshot> supplier = () -> snap(
                hbFull("backend-a", Set.of("default"),
                        Map.of("default", 5), true, lastSeen[0]),
                hbFull("backend-b", Set.of("default"),
                        Map.of("default", 4), true, lastSeen[0]));
        PeerRegionRegistry reg = new PeerRegionRegistry(supplier, "lobby-1");

        // Record a dispatch against anchor=1; effective count for
        // backend-a falls to 4, tying with backend-b. Lex tiebreak then
        // hands the next pick back to backend-a (alphabetically first).
        reg.recordDispatch("backend-a", "default");
        assertEquals(1, reg.pendingDecrementFor("backend-a", "default"));

        // A fresh heartbeat arrives (lastSeen advances). The decrement
        // anchor (1L) is strictly older than the new snapshot's
        // lastSeenEpochMs (2L), so the decrement must be cleared on the
        // next pickMostKept pass and the registry's pending counter must
        // report 0.
        lastSeen[0] = 2L;
        assertEquals(0, reg.pendingDecrementFor("backend-a", "default"));
        // Running pickMostKept also evicts the entry as a side effect.
        reg.pickMostKept();
        assertEquals(0, reg.pendingDecrementFor("backend-a", "default"));
    }
}
