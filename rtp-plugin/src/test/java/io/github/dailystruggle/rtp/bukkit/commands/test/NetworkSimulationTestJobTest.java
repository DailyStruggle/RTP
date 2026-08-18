package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.database.options.AbstractSQLDatabaseAccessor;
import io.github.dailystruggle.rtp.common.network.NetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused tests for {@link NetworkSimulationTestJob}'s D3-slot resolver and the
 * publish/subscribe/snapshot contract the probe relies on. The full
 * {@code onCommand} async dispatch needs a live {@code RTP.scheduler} and
 * {@code RTP.serverAccessor}, which a unit test cannot stand up; we exercise
 * the resolver and the in-memory transport interactions instead.
 */
class NetworkSimulationTestJobTest {

    /** Minimal SQL-accessor stub: no JDBC, no metadata. Mirrors
     *  {@code ReqRtpNet002NetworkDisabledNoOpTest.StubAccessor}. */
    private static final class StubAccessor extends AbstractSQLDatabaseAccessor {
        @Override public Connection getConnection() throws SQLException {
            throw new SQLException("not needed for this test");
        }
        @Override public String name() { return "stub"; }
        @Override public void startup() { /* no-op */ }
        @Override protected String getInsertStatement() { return ""; }
        @Override @NotNull public Connection connect() {
            throw new UnsupportedOperationException("not needed for this test");
        }
        @Override @NotNull public Optional<Map<String, Object>> read(
                Connection d, String tableName, Map.Entry<String, Object> lookup) {
            return Optional.empty();
        }
        @Override public void write(Connection d, String tableName,
                Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> keyValuePairs) {
            /* no-op */
        }
        @Override public void delete(Connection d, String tableName,
                Map.Entry<String, Object> lookup) { /* no-op */ }
        @Override public void disconnect(Connection d) { /* no-op */ }
    }

    private static final class BindingHolder implements NetworkStateBinding {
        private final NetworkTransport delegate;
        BindingHolder(NetworkTransport delegate) { this.delegate = delegate; }
        @Override public NetworkTransport transport() { return delegate; }
    }

    @Test
    @DisplayName("resolver: null accessor -> null transport (REQ-RTP-NET-002 parity)")
    void resolverNullAccessorReturnsNull() {
        assertNull(NetworkSimulationTestJob.resolveTransportOrNull(null));
    }

    @Test
    @DisplayName("resolver: SQL accessor with no binding -> null transport")
    void resolverNoBindingReturnsNull() {
        StubAccessor a = new StubAccessor();
        assertNull(a.getNetworkStateBinding());
        assertNull(NetworkSimulationTestJob.resolveTransportOrNull(a));
    }

    @Test
    @DisplayName("resolver: SQL accessor with bound transport -> returns it")
    void resolverBoundReturnsTransport() {
        StubAccessor a = new StubAccessor();
        InMemoryNetworkStateBinding live = new InMemoryNetworkStateBinding();
        a.setNetworkStateBinding(new BindingHolder(live));
        NetworkTransport resolved = NetworkSimulationTestJob.resolveTransportOrNull(a);
        assertSame(live, resolved, "resolver shall return the bound transport instance");
    }

    @Test
    @DisplayName("synthetic peers: publish + subscribe + snapshot round-trip on in-memory binding")
    void syntheticPeerRoundTrip() throws Exception {
        InMemoryNetworkStateBinding transport = new InMemoryNetworkStateBinding();
        java.util.Set<String> observed = ConcurrentHashMap.newKeySet();
        Subscription sub = transport.subscribeBackendHeartbeats(row -> {
            if (row != null && row.serverId() != null
                    && row.serverId().startsWith(NetworkSimulationTestJob.SYNTHETIC_PREFIX)) {
                observed.add(row.serverId());
            }
        });
        try {
            // Publish 3 synthetic READY peers - mirrors runProbe() shape.
            for (int i = 0; i < 3; i++) {
                BackendHeartbeat row = new BackendHeartbeat(
                        NetworkSimulationTestJob.SYNTHETIC_PREFIX + i + "-test",
                        1,
                        BackendHeartbeat.PluginState.READY,
                        true,
                        System.currentTimeMillis(),
                        12.5,
                        0, 64,
                        64L * 1024 * 1024,
                        512L * 1024 * 1024,
                        0,
                        Collections.emptyList(),
                        Collections.emptyList(),
                        false);
                transport.publishBackendHeartbeat(row).get(2, TimeUnit.SECONDS);
            }
            Map<String, BackendHeartbeat> snap =
                    transport.readSnapshot().get(2, TimeUnit.SECONDS).backends();
            for (int i = 0; i < 3; i++) {
                String id = NetworkSimulationTestJob.SYNTHETIC_PREFIX + i + "-test";
                assertNotNull(snap.get(id), "snapshot shall contain synthetic peer " + id);
            }
            assertEquals(3, observed.size(), "subscriber shall observe all 3 synthetic peers");
            assertTrue(observed.contains(NetworkSimulationTestJob.SYNTHETIC_PREFIX + "0-test"));
        } finally {
            sub.close();
        }
    }

    @Test
    @DisplayName("probe-mode subcommands registered: heartbeat, tokens, all")
    void probeModeSubcommandsRegistered() {
        // After the grammar-fix refactor (commands-api section 2.2: bare tokens are
        // always subcommands, never default parameter values), the probe-mode
        // selector is a real subcommand tree under `network`, not a hand-parsed
        // positional arg.
        NetworkSimulationTestJob parent = new NetworkSimulationTestJob(null);
        // TreeCommand.addSubCommand() uppercases the registered key for
        // case-insensitive dispatch; CLI lookup remains case-insensitive.
        Map<String, ?> children = parent.getCommandLookup();
        assertNotNull(children.get("HEARTBEAT"), "heartbeat subcommand shall be registered");
        assertNotNull(children.get("TOKENS"), "tokens subcommand shall be registered");
        assertNotNull(children.get("ALL"), "all subcommand shall be registered");
    }

    @Test
    @DisplayName("token round-trip: claim/find/release/findEmpty/reap on in-memory binding")
    void tokenProbeRoundTrip() throws Exception {
        InMemoryNetworkStateBinding transport = new InMemoryNetworkStateBinding();
        try {
            UUID player = new UUID(42L, 99L);
            String serverId = NetworkSimulationTestJob.SYNTHETIC_PREFIX + "tokens-unit";

            // claim
            ReservationToken token = transport.claim(
                    serverId, player, Duration.ofMillis(5_000L))
                    .get(2, TimeUnit.SECONDS);
            assertNotNull(token, "claim shall return a token");
            assertNotNull(token.tokenId(), "token shall have a non-null id");

            // findReservation -> present
            Optional<ReservationToken> found = transport.findReservation(player)
                    .get(2, TimeUnit.SECONDS);
            assertTrue(found.isPresent(),
                    "findReservation shall surface the freshly-claimed token");
            assertEquals(token.tokenId(), found.get().tokenId(),
                    "findReservation shall return the same token id we minted");

            // release with TEST_PROBE
            transport.release(token.tokenId(), ReleaseReason.TEST_PROBE)
                    .get(2, TimeUnit.SECONDS);

            // findReservation -> empty
            Optional<ReservationToken> after = transport.findReservation(player)
                    .get(2, TimeUnit.SECONDS);
            assertTrue(after.isEmpty(),
                    "findReservation after release shall be empty");

            // claim a doomed token with 1ms TTL, sleep, reap
            UUID reapPlayer = new UUID(7L, 11L);
            ReservationToken doomed = transport.claim(
                    serverId, reapPlayer, Duration.ofMillis(1L))
                    .get(2, TimeUnit.SECONDS);
            assertNotNull(doomed);

            Thread.sleep(NetworkSimulationTestJob.MIN_REAP_OBSERVE_MS);

            List<String> reaped = transport.reapExpired(Instant.now())
                    .get(2, TimeUnit.SECONDS);
            assertTrue(reaped.contains(doomed.tokenId()),
                    "reapExpired shall surface the expired doomed token");

            // post-reap: findReservation must be empty for the doomed player.
            Optional<ReservationToken> postReap = transport.findReservation(reapPlayer)
                    .get(2, TimeUnit.SECONDS);
            assertTrue(postReap.isEmpty(),
                    "findReservation after reap shall be empty for the doomed player");
        } finally {
            transport.close();
        }
    }
}
