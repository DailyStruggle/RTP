package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Backend join/redeem/quit behaviour for {@link JoinTriggerSource}
 * (REQ-RTP-S-004 / REQ-RTP-NET-015). Exercises the reservation-lookup,
 * redeem-outcome branching, local {@code /rtp} dispatch, and disconnect
 * release paths against a fake {@link NetworkTransport} and the mock
 * lifecycle hook / scheduler harness.
 */
@DisplayName("JoinTriggerSource backend join/redeem/quit")
class JoinTriggerSourceTest {

    private static final String SERVER_ID = "backend-a";

    private MockRTPServerAccessor accessor;
    private MockRTPScheduler scheduler;

    @BeforeEach
    void setUp(@TempDir File tempDir) {
        accessor = RTPTestSetup.install(tempDir);
        scheduler = accessor.getMockScheduler();
    }

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** In-memory transport whose lookup/redeem/release behaviour is scriptable. */
    private static final class FakeTransport implements NetworkTransport {
        Optional<ReservationToken> reservation = Optional.empty();
        Throwable findError;
        RedeemOutcome redeemOutcome = RedeemOutcome.REDEEMED;
        Throwable redeemError;
        Throwable releaseError;

        final List<String> redeemedTokens = new ArrayList<>();
        final List<String> releasedTokens = new ArrayList<>();
        final List<ReleaseReason> releaseReasons = new ArrayList<>();

        @Override
        public CompletableFuture<Optional<ReservationToken>> findReservation(UUID playerId) {
            if (findError != null) return CompletableFuture.failedFuture(findError);
            return CompletableFuture.completedFuture(reservation);
        }

        @Override
        public CompletableFuture<RedeemOutcome> redeem(String tokenId, UUID playerId, String expectedServerId) {
            redeemedTokens.add(tokenId);
            if (redeemError != null) return CompletableFuture.failedFuture(redeemError);
            return CompletableFuture.completedFuture(redeemOutcome);
        }

        @Override
        public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
            releasedTokens.add(tokenId);
            releaseReasons.add(reason);
            if (releaseError != null) return CompletableFuture.failedFuture(releaseError);
            return CompletableFuture.completedFuture(null);
        }

        // --- unused abstract members ---------------------------------
        @Override
        public CompletableFuture<NetworkSnapshot> readSnapshot() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
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

        @Override
        public void close() { }
    }

    /** Lifecycle hook that captures registered handlers and exposes fire helpers. */
    private static final class FakeLifecycleHook implements PlayerLifecycleHook {
        Consumer<UUID> joinHandler;
        Consumer<UUID> quitHandler;
        int joinSubscriptions;
        int quitSubscriptions;
        int joinClosed;
        int quitClosed;

        @Override
        public AutoCloseable onPlayerJoin(Consumer<UUID> handler) {
            this.joinHandler = handler;
            joinSubscriptions++;
            return () -> joinClosed++;
        }

        @Override
        public AutoCloseable onPlayerQuit(Consumer<UUID> handler) {
            this.quitHandler = handler;
            quitSubscriptions++;
            return () -> quitClosed++;
        }
    }

    private static ReservationToken token(String tokenId, String serverId, UUID player, String regionKey) {
        return new ReservationToken(
                tokenId, serverId, player,
                Instant.now().plusSeconds(60).toEpochMilli(),
                ReservationToken.State.CLAIMED, regionKey);
    }

    private MockRTPPlayer onlinePlayer(UUID id) {
        MockRTPPlayer p = new MockRTPPlayer(id, "P-" + id, null);
        p.setOnline(true);
        accessor.addPlayer(p);
        return p;
    }

    // ------------------------------------------------------------------
    // Constructor contract
    // ------------------------------------------------------------------

    @Test
    @DisplayName("constructor rejects null transport and null/empty serverId")
    void constructorValidation() {
        FakeTransport t = new FakeTransport();
        assertThrows(IllegalArgumentException.class, () -> new JoinTriggerSource(null, SERVER_ID));
        assertThrows(IllegalArgumentException.class, () -> new JoinTriggerSource(t, null));
        assertThrows(IllegalArgumentException.class, () -> new JoinTriggerSource(t, ""));

        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);
        assertEquals(SERVER_ID, src.serverId());
    }

    // ------------------------------------------------------------------
    // register / unregister
    // ------------------------------------------------------------------

    @Test
    @DisplayName("register is idempotent and null-tolerant; unregister closes both subscriptions")
    void registerUnregister() {
        FakeTransport t = new FakeTransport();
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        src.register(null); // null hook tolerated

        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);
        src.register(hook); // second call must not re-subscribe
        assertEquals(1, hook.joinSubscriptions);
        assertEquals(1, hook.quitSubscriptions);
        assertNotNull(hook.joinHandler);
        assertNotNull(hook.quitHandler);

        src.unregister();
        assertEquals(1, hook.joinClosed);
        assertEquals(1, hook.quitClosed);
        src.unregister(); // idempotent - no further closes
        assertEquals(1, hook.joinClosed);
    }

    // ------------------------------------------------------------------
    // onJoin: reservation lookup branches
    // ------------------------------------------------------------------

    @Test
    @DisplayName("no reservation -> no redeem and no /rtp dispatch")
    void joinWithoutReservation() {
        FakeTransport t = new FakeTransport();
        t.reservation = Optional.empty();
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        UUID id = UUID.randomUUID();
        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        scheduler.tick(5);

        assertTrue(t.redeemedTokens.isEmpty());
        assertTrue(p.performedCommands.isEmpty());
    }

    @Test
    @DisplayName("reservation for a different backend -> no redeem")
    void joinReservationForOtherServer() {
        FakeTransport t = new FakeTransport();
        UUID id = UUID.randomUUID();
        t.reservation = Optional.of(token(UUID.randomUUID().toString(), "backend-z", id, null));
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        scheduler.tick(5);

        assertTrue(t.redeemedTokens.isEmpty(), "token for another backend must not be redeemed");
        assertTrue(p.performedCommands.isEmpty());
    }

    @Test
    @DisplayName("findReservation failure surfaces WARNING and dispatches nothing")
    void joinLookupFailure() {
        FakeTransport t = new FakeTransport();
        t.findError = new RuntimeException("transport blip");
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        UUID id = UUID.randomUUID();
        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        scheduler.tick(5);

        assertTrue(t.redeemedTokens.isEmpty());
        assertTrue(p.performedCommands.isEmpty());
        assertTrue(accessor.logMessages.stream().anyMatch(m -> m.contains("findReservation failed")));
    }

    // ------------------------------------------------------------------
    // onJoin: redeem outcome branches
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REDEEMED with regionless token -> dispatches bare 'rtp'")
    void redeemedRegionlessDispatchesRtp() {
        FakeTransport t = new FakeTransport();
        UUID id = UUID.randomUUID();
        String tokenId = UUID.randomUUID().toString();
        t.reservation = Optional.of(token(tokenId, SERVER_ID, id, null));
        t.redeemOutcome = RedeemOutcome.REDEEMED;
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        scheduler.tick(5);

        assertEquals(List.of(tokenId), t.redeemedTokens);
        assertEquals(List.of("rtp"), p.performedCommands);
    }

    @Test
    @DisplayName("REDEEMED with region token -> dispatches 'rtp region=<key>'")
    void redeemedRegionDispatchesRtpRegion() {
        FakeTransport t = new FakeTransport();
        UUID id = UUID.randomUUID();
        String tokenId = UUID.randomUUID().toString();
        t.reservation = Optional.of(token(tokenId, SERVER_ID, id, "backend-a:nether"));
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        scheduler.tick(5);

        assertEquals(List.of("rtp region=backend-a:nether"), p.performedCommands);
    }

    @Test
    @DisplayName("REDEEMED evicts a non-terminal status-cache row so the arrival /rtp is not blocked")
    void redeemedEvictsStatusCache() {
        FakeTransport t = new FakeTransport();
        UUID id = UUID.randomUUID();
        String tokenId = UUID.randomUUID().toString();
        t.reservation = Optional.of(token(tokenId, SERVER_ID, id, null));

        NetworkStatusCache cache = new NetworkStatusCache(java.util.Collections::emptyList);
        cache.seedLocal(id);
        assertTrue(cache.get(id).isPresent());

        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID, cache);
        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        scheduler.tick(5);

        assertTrue(cache.get(id).isEmpty(), "REDEEMED must evict the local status row");
        assertEquals(List.of("rtp"), p.performedCommands);
    }

    @Test
    @DisplayName("ALREADY_CONSUMED / WRONG_SERVER are silent no-ops (no dispatch)")
    void redeemedRaceStaysSilent() {
        for (RedeemOutcome outcome : List.of(RedeemOutcome.ALREADY_CONSUMED, RedeemOutcome.WRONG_SERVER)) {
            FakeTransport t = new FakeTransport();
            UUID id = UUID.randomUUID();
            t.reservation = Optional.of(token(UUID.randomUUID().toString(), SERVER_ID, id, null));
            t.redeemOutcome = outcome;
            JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

            MockRTPPlayer p = onlinePlayer(id);
            FakeLifecycleHook hook = new FakeLifecycleHook();
            src.register(hook);

            hook.joinHandler.accept(id);
            scheduler.tick(5);

            assertTrue(p.performedCommands.isEmpty(), outcome + " must not dispatch /rtp");
        }
    }

    @Test
    @DisplayName("EXPIRED / BAD_STATE surface WARNING and do not dispatch")
    void redeemedFailureOutcomesWarn() {
        for (RedeemOutcome outcome : List.of(RedeemOutcome.EXPIRED, RedeemOutcome.BAD_STATE)) {
            MockRTPServerAccessor local = accessor;
            FakeTransport t = new FakeTransport();
            UUID id = UUID.randomUUID();
            t.reservation = Optional.of(token(UUID.randomUUID().toString(), SERVER_ID, id, null));
            t.redeemOutcome = outcome;
            JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

            MockRTPPlayer p = onlinePlayer(id);
            FakeLifecycleHook hook = new FakeLifecycleHook();
            src.register(hook);

            hook.joinHandler.accept(id);
            scheduler.tick(5);

            assertTrue(p.performedCommands.isEmpty(), outcome + " must not dispatch /rtp");
            assertTrue(local.logMessages.stream().anyMatch(m -> m.contains(outcome.name())),
                    "expected a log line mentioning " + outcome);
        }
    }

    @Test
    @DisplayName("redeem dispatch failure surfaces WARNING and dispatches nothing")
    void redeemDispatchFailure() {
        FakeTransport t = new FakeTransport();
        UUID id = UUID.randomUUID();
        t.reservation = Optional.of(token(UUID.randomUUID().toString(), SERVER_ID, id, null));
        t.redeemError = new RuntimeException("redeem boom");
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        scheduler.tick(5);

        assertTrue(p.performedCommands.isEmpty());
        assertTrue(accessor.logMessages.stream().anyMatch(m -> m.contains("redeem dispatch failed")));
    }

    @Test
    @DisplayName("player offline at hop time -> /rtp is not dispatched")
    void offlineAtHopSkipsDispatch() {
        FakeTransport t = new FakeTransport();
        UUID id = UUID.randomUUID();
        t.reservation = Optional.of(token(UUID.randomUUID().toString(), SERVER_ID, id, null));
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);

        MockRTPPlayer p = onlinePlayer(id);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.joinHandler.accept(id);
        p.setOnline(false); // disconnect between join and hop
        scheduler.tick(5);

        assertTrue(p.performedCommands.isEmpty());
    }

    // ------------------------------------------------------------------
    // onQuit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quit with no active reservation is a no-op (no release call)")
    void quitWithoutActiveReservation() {
        FakeTransport t = new FakeTransport();
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        hook.quitHandler.accept(UUID.randomUUID());

        assertTrue(t.releasedTokens.isEmpty(), "no bound reservation -> nothing to release");
        assertTrue(src.activeReservationsForTesting().isEmpty());
    }

    @Test
    @DisplayName("active reservation onQuit releases locally and proxy-side")
    void quitWithActiveReservation() {
        FakeTransport t = new FakeTransport();
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        UUID playerId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        // Inject active reservation into trigger source
        src.activeReservationsForTesting(); // read-only check
        // We can simulate an active reservation by triggering onQuit when activeReservations contains the token
        // In order to put into activeReservations, let's call onRedeemed or use reflection / mock region
        // JoinTriggerSource has a package-private onQuit
        try {
            java.lang.reflect.Field field = JoinTriggerSource.class.getDeclaredField("activeReservations");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, UUID> map =
                    (java.util.concurrent.ConcurrentHashMap<UUID, UUID>) field.get(src);
            map.put(playerId, tokenId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        hook.quitHandler.accept(playerId);

        assertEquals(List.of(tokenId.toString()), t.releasedTokens);
        assertEquals(List.of(ReleaseReason.PLAYER_DISCONNECTED), t.releaseReasons);
        assertTrue(src.activeReservationsForTesting().isEmpty());
    }

    @Test
    @DisplayName("transport release error on quit is logged safely")
    void quitWithActiveReservationTransportFails() {
        FakeTransport t = new FakeTransport();
        JoinTriggerSource src = new JoinTriggerSource(t, SERVER_ID);
        FakeLifecycleHook hook = new FakeLifecycleHook();
        src.register(hook);

        UUID playerId = UUID.randomUUID();
        UUID tokenId = UUID.randomUUID();

        try {
            java.lang.reflect.Field field = JoinTriggerSource.class.getDeclaredField("activeReservations");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, UUID> map =
                    (java.util.concurrent.ConcurrentHashMap<UUID, UUID>) field.get(src);
            map.put(playerId, tokenId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        FakeTransport throwingTransport = new FakeTransport();
        throwingTransport.releaseError = new RuntimeException("release failed");
        JoinTriggerSource failSrc = new JoinTriggerSource(throwingTransport, SERVER_ID);
        try {
            java.lang.reflect.Field field = JoinTriggerSource.class.getDeclaredField("activeReservations");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.ConcurrentHashMap<UUID, UUID> map =
                    (java.util.concurrent.ConcurrentHashMap<UUID, UUID>) field.get(failSrc);
            map.put(playerId, tokenId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        failSrc.onQuit(playerId);
        assertTrue(accessor.logMessages.stream().anyMatch(m -> m.contains("transport.release failed")));
    }
}
