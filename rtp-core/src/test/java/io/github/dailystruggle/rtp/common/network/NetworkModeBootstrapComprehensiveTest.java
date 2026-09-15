package io.github.dailystruggle.rtp.common.network;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.network.NetworkCommandHook;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.network.pluginmessage.NetworkBridge;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkModeBootstrapComprehensiveTest {

    @TempDir
    File tempDir;

    private MockRTPScheduler scheduler;
    private MockRTPServerAccessor serverAccessor;

    @BeforeEach
    void setUp() {
        scheduler = new MockRTPScheduler();
        RTP.scheduler = scheduler;
        serverAccessor = new MockRTPServerAccessor(tempDir);
        RTP.serverAccessor = serverAccessor;
    }

    @AfterEach
    void tearDown() {
        if (NetworkModeBootstrap.LIVE != null) {
            NetworkModeBootstrap.LIVE.shutdown();
        }
        RTP.scheduler = null;
        RTP.serverAccessor = null;
        RTP.backendStateSamplerFactory = null;
        RTP.networkBridgeFactory = null;
        RTP.networkCommandHook = NetworkCommandHook.LOCAL_ONLY;
    }

    @Test
    @DisplayName("boot with null or missing file is a no-op")
    void bootNullOrMissingFile() {
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(null);
        assertNull(bootstrap.transport());
        assertNull(NetworkModeBootstrap.LIVE);

        File missing = new File(tempDir, "nonexistent.yml");
        bootstrap.boot(missing);
        assertNull(bootstrap.transport());
        assertNull(NetworkModeBootstrap.LIVE);
    }

    @Test
    @DisplayName("boot with malformed yaml is handled gracefully")
    void bootMalformedYaml() throws IOException {
        File file = new File(tempDir, "malformed.yml");
        Files.writeString(file.toPath(), ": this is : not : valid : yaml");
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);
        assertNull(bootstrap.transport());
        assertNull(NetworkModeBootstrap.LIVE);
    }

    @Test
    @DisplayName("boot with network.enabled=false is a no-op")
    void bootDisabled() throws IOException {
        File file = new File(tempDir, "disabled.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: false\n  serverId: backend1\n");
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);
        assertNull(bootstrap.transport());
        assertNull(NetworkModeBootstrap.LIVE);
    }

    @Test
    @DisplayName("boot with enabled=true but missing serverId aborts")
    void bootMissingServerId() throws IOException {
        File file = new File(tempDir, "noserverid.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: \"\"\n");
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);
        assertNull(bootstrap.transport());
        assertNull(NetworkModeBootstrap.LIVE);
    }

    @Test
    @DisplayName("boot with unknown transport type aborts gracefully")
    void bootUnknownTransport() throws IOException {
        File file = new File(tempDir, "unknowntransport.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: s1\ntransport:\n  type: unsupported-tier\n");
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);
        assertNull(bootstrap.transport());
        assertNull(NetworkModeBootstrap.LIVE);
    }

    @Test
    @DisplayName("boot without backendStateSamplerFactory aborts before starting publisher")
    void bootWithoutSamplerFactory() throws IOException {
        File file = new File(tempDir, "inmemory.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: backend1\ntransport:\n  type: in-memory\n");
        RTP.backendStateSamplerFactory = null;

        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);

        assertNull(bootstrap.publisher());
        assertNull(NetworkModeBootstrap.LIVE);
    }

    @Test
    @DisplayName("boot with valid in-memory config initializes all components and shuts down cleanly")
    void bootInMemoryFullLifecycle() throws IOException {
        File file = new File(tempDir, "network.yml");
        String yaml = "network:\n"
                + "  enabled: true\n"
                + "  serverId: backend-main\n"
                + "  waitlist:\n"
                + "    notifyIntervalSeconds: 3\n"
                + "transport:\n"
                + "  type: in-memory\n"
                + "heartbeat:\n"
                + "  intervalMs: 500\n"
                + "  staleAfterMs: 3000\n"
                + "reservation:\n"
                + "  reapIntervalMs: 5000\n"
                + "routing:\n"
                + "  mode: auto\n"
                + "  lobbyMode: true\n"
                + "queue:\n"
                + "  maxDepth: 25\n"
                + "  flushIntervalMs: 100\n"
                + "  pollIntervalMs: 200\n"
                + "  crossServerRequestsPerSecond: 10\n"
                + "  crossServerRequestsBurst: 15\n"
                + "loadBalancer:\n"
                + "  strategy: most_kept\n";
        Files.writeString(file.toPath(), yaml);

        RTP.backendStateSamplerFactory = lobby -> sid -> new BackendHeartbeat(
                sid, 1, BackendHeartbeat.PluginState.READY, true, System.currentTimeMillis(),
                20.0, 0, 100, 0L, 1L, 0, List.of(), List.of(), false
        );

        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);

        assertNotNull(bootstrap.transport());
        assertNotNull(bootstrap.publisher());
        assertNotNull(bootstrap.joinTriggerSource());
        assertNotNull(bootstrap.router());
        assertNotNull(bootstrap.enrolmentBuffer());
        assertNotNull(bootstrap.statusCache());
        assertNotNull(bootstrap.requestQueue());
        assertNotNull(bootstrap.peerRegionRegistry());
        assertNotNull(bootstrap.commandHook());
        assertSame(bootstrap, NetworkModeBootstrap.LIVE);
        assertSame(bootstrap.commandHook(), RTP.networkCommandHook);

        Predicate<RTPCommandSender> guard = bootstrap.waitlistCommandGuard();
        assertNotNull(guard);

        bootstrap.registerJoinTriggerSource();
        bootstrap.registerJoinTriggerSource(); // idempotent

        bootstrap.registerWaitlistQuitListener();
        bootstrap.registerWaitlistQuitListener(); // idempotent

        // Shutdown
        bootstrap.shutdown();
        assertNull(NetworkModeBootstrap.LIVE);
        assertEquals(NetworkCommandHook.LOCAL_ONLY, RTP.networkCommandHook);
        assertNull(bootstrap.transport());
        assertNull(bootstrap.publisher());

        // Idempotent shutdown
        bootstrap.shutdown();
    }

    @Test
    @DisplayName("readLobbyModeEarly inspects file accurately")
    void testReadLobbyModeEarly() throws IOException {
        assertFalse(NetworkModeBootstrap.readLobbyModeEarly(null));
        assertFalse(NetworkModeBootstrap.readLobbyModeEarly(new File(tempDir, "absent.yml")));

        File fDisabled = new File(tempDir, "fDisabled.yml");
        Files.writeString(fDisabled.toPath(), "network:\n  enabled: false\nrouting:\n  lobbyMode: true\n");
        assertFalse(NetworkModeBootstrap.readLobbyModeEarly(fDisabled));

        File fNotLobby = new File(tempDir, "fNotLobby.yml");
        Files.writeString(fNotLobby.toPath(), "network:\n  enabled: true\nrouting:\n  lobbyMode: false\n");
        assertFalse(NetworkModeBootstrap.readLobbyModeEarly(fNotLobby));

        File fLobby = new File(tempDir, "fLobby.yml");
        Files.writeString(fLobby.toPath(), "network:\n  enabled: true\nrouting:\n  lobbyMode: true\n");
        assertTrue(NetworkModeBootstrap.readLobbyModeEarly(fLobby));

        File fMalformed = new File(tempDir, "fMalformed.yml");
        Files.writeString(fMalformed.toPath(), "network: [invalid");
        assertFalse(NetworkModeBootstrap.readLobbyModeEarly(fMalformed));
    }

    @Test
    @DisplayName("sumLocalKeptCount safely calculates hot queue coordinates")
    void testSumLocalKeptCount() {
        int count = NetworkModeBootstrap.sumLocalKeptCount();
        assertEquals(0, count);
    }

    @Test
    @DisplayName("reconcileNetworkReservations handles empty tokens, non-uuid tokens and releases")
    void testReconcileNetworkReservations() {
        // null transport or empty serverId -> no-op
        NetworkModeBootstrap.reconcileNetworkReservations(null, "s1");
        NetworkModeBootstrap.reconcileNetworkReservations(new InMemoryNetworkStateBinding(), null);
        NetworkModeBootstrap.reconcileNetworkReservations(new InMemoryNetworkStateBinding(), "");

        // Mock transport with tokens
        UUID validUuid = UUID.randomUUID();
        ReservationToken token1 = new ReservationToken("non-uuid-token", "s1", UUID.randomUUID(), System.currentTimeMillis() + 60000, ReservationToken.State.PENDING);
        ReservationToken token2 = new ReservationToken(validUuid.toString(), "s1", UUID.randomUUID(), System.currentTimeMillis() + 60000, ReservationToken.State.PENDING);

        NetworkTransport mockTransport = new NetworkTransport() {
            @Override public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> publishProxyHeartbeat(io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat row) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<NetworkSnapshot> readSnapshot() { return CompletableFuture.completedFuture(new NetworkSnapshot(System.currentTimeMillis(), Map.of())); }
            @Override public io.github.dailystruggle.rtp.proxy.common.spi.Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
                return new io.github.dailystruggle.rtp.proxy.common.spi.Subscription() {
                    @Override public void close() {}
                    @Override public boolean isClosed() { return false; }
                };
            }
            @Override public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, java.time.Duration ttl) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> release(String tokenId, io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason reason) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<List<ReservationToken>> listActiveForServer(String serverId) {
                return CompletableFuture.completedFuture(List.of(token1, token2));
            }
            @Override public void close() {}
        };

        NetworkModeBootstrap.reconcileNetworkReservations(mockTransport, "s1");

        // Error path in listActiveForServer
        NetworkTransport errorTransport = new NetworkTransport() {
            @Override public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> publishProxyHeartbeat(io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat row) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<NetworkSnapshot> readSnapshot() { return CompletableFuture.completedFuture(new NetworkSnapshot(System.currentTimeMillis(), Map.of())); }
            @Override public io.github.dailystruggle.rtp.proxy.common.spi.Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> sink) {
                return new io.github.dailystruggle.rtp.proxy.common.spi.Subscription() {
                    @Override public void close() {}
                    @Override public boolean isClosed() { return false; }
                };
            }
            @Override public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, java.time.Duration ttl) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<Void> release(String tokenId, io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason reason) { return CompletableFuture.completedFuture(null); }
            @Override public CompletableFuture<List<ReservationToken>> listActiveForServer(String serverId) {
                return CompletableFuture.failedFuture(new RuntimeException("simulated list error"));
            }
            @Override public void close() {}
        };
        NetworkModeBootstrap.reconcileNetworkReservations(errorTransport, "s1");
    }

    @Test
    @DisplayName("boot with plugin-message transport fails when bridge is missing")
    void bootPluginMessageMissingBridge() throws IOException {
        File file = new File(tempDir, "pluginmsg.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: s1\ntransport:\n  type: plugin-message\n");

        RTP.networkBridgeFactory = null;
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);

        assertNull(bootstrap.transport());
    }

    @Test
    @DisplayName("boot with proxy-cache transport fails when bridge is missing")
    void bootProxyCacheMissingBridge() throws IOException {
        File file = new File(tempDir, "proxycache.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: s1\ntransport:\n  type: proxy-cache\n");

        RTP.networkBridgeFactory = null;
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);

        assertNull(bootstrap.transport());
    }

    @Test
    @DisplayName("boot with auto transport disables when bridge is missing or passive probe disarmed")
    void bootAutoTransportHandling() throws IOException {
        File file = new File(tempDir, "auto.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: s1\ntransport:\n  type: auto\n");

        RTP.networkBridgeFactory = null;
        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);
        assertNull(bootstrap.transport());

        // Bridge available but disarmed
        NetworkBridge bridgeDisarmed = new NetworkBridge() {
            @Override public boolean isAvailable() { return true; }
            @Override public Optional<UUID> anyOnlinePlayer() { return Optional.empty(); }
            @Override public void broadcastHeartbeat(byte[] payload) {}
            @Override public void connect(UUID player, String targetServerId) {}
            @Override public void registerInbound(Consumer<byte[]> heartbeatSink) {}
            @Override public ProxyProbe passiveProbe() { return ProxyProbe.DISARMED; }
        };
        RTP.networkBridgeFactory = () -> bridgeDisarmed;

        NetworkModeBootstrap bootstrap2 = new NetworkModeBootstrap();
        bootstrap2.boot(file);
        assertNull(bootstrap2.transport());
    }

    @Test
    @DisplayName("boot with proxy-direct fails when proxies list is empty")
    void bootProxyDirectEmptyProxies() throws IOException {
        File file = new File(tempDir, "proxydirect.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: s1\ntransport:\n  type: proxy-direct\n");

        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);
        assertNull(bootstrap.transport());
    }

    @Test
    @DisplayName("boot with sql transport fails when database is not SQL-backed")
    void bootSqlWithoutSqlDatabase() throws IOException {
        File file = new File(tempDir, "sql.yml");
        Files.writeString(file.toPath(), "network:\n  enabled: true\n  serverId: s1\ntransport:\n  type: sql\n");

        NetworkModeBootstrap bootstrap = new NetworkModeBootstrap();
        bootstrap.boot(file);
        assertNull(bootstrap.transport());
    }
}
