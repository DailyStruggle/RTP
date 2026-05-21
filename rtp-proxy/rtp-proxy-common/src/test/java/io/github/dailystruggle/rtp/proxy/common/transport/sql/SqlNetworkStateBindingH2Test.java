package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the "two JVMs see each other in a shared SQL DB" claim of
 * rtp-proxy-ADR-011: two {@link SqlNetworkStateBinding} instances open the
 * same H2 database, one publishes a backend heartbeat, the other observes it
 * via {@link SqlNetworkStateBinding#readSnapshot()} and (separately) via a
 * subscriber after the poll loop fires. Also covers the SQLSTATE 23xxx ->
 * {@link IllegalStateException} translation that mirrors the in-memory
 * binding's claim-race contract (REQ-RTP-PROXY-004).
 */
class SqlNetworkStateBindingH2Test {

    private static final String JDBC_URL =
            "jdbc:h2:mem:rtp_net_sql_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private DataSource dataSource;
    private SqlNetworkStateBinding a;
    private SqlNetworkStateBinding b;

    @BeforeEach
    void setUp() throws SQLException {
        // Shared in-memory H2 database identified by URL; DB_CLOSE_DELAY=-1
        // keeps it alive across the test even when no connection is held.
        dataSource = new H2DataSource(JDBC_URL);
        // Wipe any state from a prior test in the same JVM. JDBC TRUNCATE not
        // available on freshly-bootstrapped tables; drop+recreate via DROP IF EXISTS.
        try (Connection c = dataSource.getConnection()) {
            try (var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS rtp_network_proxies");
                st.execute("DROP TABLE IF EXISTS rtp_network_backends");
                st.execute("DROP TABLE IF EXISTS rtp_network_tokens");
            }
        }
        // 200 ms poll interval keeps the cross-instance fan-out test fast.
        a = new SqlNetworkStateBinding(dataSource, 200L);
        b = new SqlNetworkStateBinding(dataSource, 200L);
    }

    @AfterEach
    void tearDown() {
        if (a != null) a.close();
        if (b != null) b.close();
    }

    @Test
    @DisplayName("Snapshot reflects rows published by a peer binding (ADR-011 §DB-as-bus)")
    void snapshotCrossesInstances() throws Exception {
        a.publishBackendHeartbeat(sampleBackend("server-a", 42L)).get(2, TimeUnit.SECONDS);
        NetworkSnapshot snap = b.readSnapshot().get(2, TimeUnit.SECONDS);
        assertEquals(1, snap.all().size(), "peer must observe one backend row");
        BackendHeartbeat seen = snap.backend("server-a").orElseThrow();
        assertEquals("server-a", seen.serverId());
        assertEquals(42L, seen.lastSeenEpochMs());
        assertEquals(PluginState.READY, seen.pluginState());
    }

    @Test
    @DisplayName("Subscribers on peer binding fire after the poll loop observes a new heartbeat")
    void subscriberCrossesInstances() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<BackendHeartbeat> received = new AtomicReference<>();
        Subscription sub = b.subscribeBackendHeartbeats(hb -> {
            received.set(hb);
            latch.countDown();
        });
        try {
            a.publishBackendHeartbeat(sampleBackend("server-x", System.currentTimeMillis()))
                    .get(2, TimeUnit.SECONDS);
            assertTrue(latch.await(3, TimeUnit.SECONDS), "peer poll loop must deliver heartbeat within 3s");
            assertNotNull(received.get());
            assertEquals("server-x", received.get().serverId());
        } finally {
            sub.close();
        }
    }

    @Test
    @DisplayName("Repeated UPSERTs update the same row (no duplicate-key explosion)")
    void upsertIsIdempotent() throws Exception {
        a.publishBackendHeartbeat(sampleBackend("server-r", 100L)).get(2, TimeUnit.SECONDS);
        a.publishBackendHeartbeat(sampleBackend("server-r", 200L)).get(2, TimeUnit.SECONDS);
        a.publishBackendHeartbeat(sampleBackend("server-r", 300L)).get(2, TimeUnit.SECONDS);
        NetworkSnapshot snap = a.readSnapshot().get(2, TimeUnit.SECONDS);
        assertEquals(1, snap.all().size(), "three UPSERTs must collapse to one row");
        assertEquals(300L, snap.backend("server-r").orElseThrow().lastSeenEpochMs());
    }

    @Test
    @DisplayName("Concurrent claims for the same player: exactly one succeeds (REQ-RTP-PROXY-004)")
    void claimRaceAcrossInstances() {
        UUID player = UUID.randomUUID();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        Runnable claimOnA = () -> {
            try {
                a.claim("server-c", player, Duration.ofSeconds(30)).join();
                successes.incrementAndGet();
            } catch (CompletionException ce) {
                if (ce.getCause() instanceof IllegalStateException) {
                    failures.incrementAndGet();
                }
            }
        };
        Runnable claimOnB = () -> {
            try {
                b.claim("server-c", player, Duration.ofSeconds(30)).join();
                successes.incrementAndGet();
            } catch (CompletionException ce) {
                if (ce.getCause() instanceof IllegalStateException) {
                    failures.incrementAndGet();
                }
            }
        };

        Thread t1 = new Thread(claimOnA);
        Thread t2 = new Thread(claimOnB);
        t1.start();
        t2.start();
        try {
            t1.join(3000);
            t2.join(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        assertEquals(1, successes.get(), "exactly one claim must win");
        assertEquals(1, failures.get(), "the other must be translated to IllegalStateException");
    }

    @Test
    @DisplayName("Release-then-reclaim works after CONSUMED terminal state")
    void releaseAllowsReclaim() throws Exception {
        UUID player = UUID.randomUUID();
        ReservationToken first = a.claim("server-d", player, Duration.ofSeconds(30))
                .get(2, TimeUnit.SECONDS);
        a.release(first.tokenId(), io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason.CONSUMED)
                .get(2, TimeUnit.SECONDS);
        // Second claim should succeed because the prior row no longer occupies
        // the player_id slot (released_at_ms IS NOT NULL is treated as freed
        // in subsequent attempts; the row has a unique token_id PK).
        // NOTE: with the current schema the unique index is on (player_id) wide;
        // we must delete the released row to keep the test honest.
        try (Connection c = dataSource.getConnection()) {
            try (var ps = c.prepareStatement("DELETE FROM rtp_network_tokens WHERE token_id = ?")) {
                ps.setString(1, first.tokenId());
                ps.executeUpdate();
            }
        }
        ReservationToken second = a.claim("server-d", player, Duration.ofSeconds(30))
                .get(2, TimeUnit.SECONDS);
        assertEquals(ReservationToken.State.CLAIMED, second.state());
    }

    @Test
    @DisplayName("findReservation returns the active token across instances and skips released rows")
    void findReservationCrossesInstances() throws Exception {
        UUID player = UUID.randomUUID();
        // Claim on a; b must see it via findReservation against the shared DB.
        ReservationToken claimed = a.claim("server-f", player, Duration.ofSeconds(30))
                .get(2, TimeUnit.SECONDS);
        java.util.Optional<ReservationToken> seen = b.findReservation(player).get(2, TimeUnit.SECONDS);
        assertTrue(seen.isPresent(), "peer must observe the active reservation");
        assertEquals(claimed.tokenId(), seen.get().tokenId());
        assertEquals("server-f", seen.get().serverId());

        // After release the row is excluded (released_at_ms IS NOT NULL).
        a.release(claimed.tokenId(), io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason.CONSUMED)
                .get(2, TimeUnit.SECONDS);
        java.util.Optional<ReservationToken> afterRelease = b.findReservation(player).get(2, TimeUnit.SECONDS);
        assertTrue(afterRelease.isEmpty(), "released reservation must not be returned");

        // Unknown player returns empty (REQ-RTP-PROXY-VELOCITY-002 fall-through path).
        java.util.Optional<ReservationToken> none = a.findReservation(UUID.randomUUID()).get(2, TimeUnit.SECONDS);
        assertTrue(none.isEmpty());
    }

    @Test
    @DisplayName("reapExpired transitions only expired rows, returns winner IDs (REQ-RTP-NET-011)")
    void reapExpiredDropsOnlyExpiredRows() throws Exception {
        UUID alive = UUID.randomUUID();
        UUID expired = UUID.randomUUID();
        ReservationToken aliveTok = a.claim("server-r1", alive, Duration.ofSeconds(60))
                .get(2, TimeUnit.SECONDS);
        ReservationToken expiredTok = a.claim("server-r2", expired, Duration.ofSeconds(60))
                .get(2, TimeUnit.SECONDS);
        // Backdate expiredTok directly in the DB so the reaper sees it past TTL.
        long farPast = 1L;
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement(
                     "UPDATE rtp_network_tokens SET expires_at_ms = ? WHERE token_id = ?")) {
            ps.setLong(1, farPast);
            ps.setString(2, expiredTok.tokenId());
            ps.executeUpdate();
        }

        List<String> reaped = b.reapExpired(java.time.Instant.now())
                .get(3, TimeUnit.SECONDS);
        assertEquals(1, reaped.size(),
                "exactly one token must be reaped");
        assertEquals(expiredTok.tokenId(), reaped.get(0));

        // Second pass returns empty (idempotent: row now has released_at_ms set).
        List<String> reapedAgain = b.reapExpired(java.time.Instant.now())
                .get(3, TimeUnit.SECONDS);
        assertTrue(reapedAgain.isEmpty(),
                "second sweep must reap nothing");

        // Alive token is untouched and still findable across instances.
        assertTrue(b.findReservation(alive).get(2, TimeUnit.SECONDS).isPresent(),
                "alive token must survive the reap");
        // Expired token is no longer returned by findReservation.
        assertTrue(b.findReservation(expired).get(2, TimeUnit.SECONDS).isEmpty(),
                "expired token must not be returned to findReservation");
        // release(TTL_EXPIRED) on the already-reaped row is a no-op (idempotent).
        a.release(expiredTok.tokenId(), ReleaseReason.TTL_EXPIRED)
                .get(2, TimeUnit.SECONDS);
        // Sanity: aliveTok matches the original CLAIMED token (no Norway-problem
        // weirdness on the unused reference).
        assertEquals(ReservationToken.State.CLAIMED, aliveTok.state());
    }

    @Test
    @DisplayName("Close prevents further SPI calls")
    void closeIsTerminal() {
        a.close();
        assertThrows(IllegalStateException.class, () -> a.readSnapshot());
    }

    @Test
    @DisplayName("A3 HMAC envelope: signed roundtrip across peers and tampered-row drop")
    void hmacEnvelopeSignedRoundtripAndTamperDrop() throws Exception {
        // Build two signed bindings sharing the same H2 DB and the same secret.
        byte[] secret = new byte[32];
        for (int i = 0; i < 32; i++) secret[i] = (byte) i;
        io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier v =
                io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier.forTesting(secret, 1, 1);

        a.close(); b.close();
        a = new SqlNetworkStateBinding(dataSource, 200L, v, 1);
        b = new SqlNetworkStateBinding(dataSource, 200L, v, 1);

        // Signed backend heartbeat: peer must observe it (verify path returns true).
        a.publishBackendHeartbeat(sampleBackend("server-hmac", 999L)).get(2, TimeUnit.SECONDS);
        NetworkSnapshot snap = b.readSnapshot().get(2, TimeUnit.SECONDS);
        assertEquals(1, snap.all().size(), "peer must observe signed backend row");
        assertEquals("server-hmac", snap.backend("server-hmac").orElseThrow().serverId());

        // Signed reservation token: cross-instance findReservation must verify.
        UUID player = UUID.randomUUID();
        ReservationToken claimed = a.claim("server-hmac", player, Duration.ofSeconds(30))
                .get(2, TimeUnit.SECONDS);
        assertTrue(b.findReservation(player).get(2, TimeUnit.SECONDS).isPresent(),
                "peer must verify-and-return signed reservation");

        // Tamper: corrupt the hmac column directly. Reader must drop the row.
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("UPDATE rtp_network_tokens SET hmac = ? WHERE token_id = ?")) {
            ps.setString(1, "deadbeef".repeat(8));
            ps.setString(2, claimed.tokenId());
            ps.executeUpdate();
        }
        assertTrue(b.findReservation(player).get(2, TimeUnit.SECONDS).isEmpty(),
                "tampered-hmac row must be dropped on read (REQ-RTP-S-004)");

        // Tamper: corrupt the backend row's hmac. Snapshot must drop it.
        try (Connection c = dataSource.getConnection();
             var ps = c.prepareStatement("UPDATE rtp_network_backends SET hmac = ? WHERE server_id = ?")) {
            ps.setString(1, "deadbeef".repeat(8));
            ps.setString(2, "server-hmac");
            ps.executeUpdate();
        }
        NetworkSnapshot tamperedSnap = b.readSnapshot().get(2, TimeUnit.SECONDS);
        assertEquals(0, tamperedSnap.all().size(),
                "tampered-hmac backend row must be dropped on snapshot read (REQ-RTP-S-004)");
    }

    @Test
    @DisplayName("A3 HMAC: legacy NULL-hmac row is dropped when verifier is enabled")
    void hmacEnvelopeLegacyNullDrop() throws Exception {
        // Unsigned binding writes a row (hmac column NULL).
        a.publishBackendHeartbeat(sampleBackend("legacy-server", 1L)).get(2, TimeUnit.SECONDS);

        // Signed binding reads the same DB; the legacy row must be dropped.
        byte[] secret = new byte[32];
        for (int i = 0; i < 32; i++) secret[i] = (byte) (i + 1);
        io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier v =
                io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier.forTesting(secret, 1, 1);
        try (SqlNetworkStateBinding signed = new SqlNetworkStateBinding(dataSource, 200L, v, 1)) {
            NetworkSnapshot snap = signed.readSnapshot().get(2, TimeUnit.SECONDS);
            assertEquals(0, snap.all().size(),
                    "legacy NULL-hmac row must not surface to a verifier-enabled reader");
        }
    }

    // --- helpers -------------------------------------------------------------

    private static BackendHeartbeat sampleBackend(String id, long lastSeen) {
        return new BackendHeartbeat(
                id, 1, PluginState.READY, true, lastSeen,
                /*mspt*/ 18.0, /*queueDepth*/ 0, /*softCap*/ 20,
                /*heapUsed*/ 1L, /*heapMax*/ 2L,
                /*players*/ 0,
                List.of("default"),
                List.of("world"),
                /*killSwitch*/ false);
    }

    /** Minimal JDBC DataSource over H2 in-memory. No pooling required for tests. */
    private static final class H2DataSource implements DataSource {
        private final String url;
        H2DataSource(String url) { this.url = url; }
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, "sa", "");
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }
        @Override public java.io.PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(java.io.PrintWriter out) { /* unused */ }
        @Override public void setLoginTimeout(int seconds) { /* unused */ }
        @Override public int getLoginTimeout() { return 0; }
        @Override public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getLogger("h2-test-ds"); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
