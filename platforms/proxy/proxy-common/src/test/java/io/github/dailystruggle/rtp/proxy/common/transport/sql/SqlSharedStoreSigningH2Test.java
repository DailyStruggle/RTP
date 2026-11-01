package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared-store signing hardening for the SQL tier (rtp-proxy-ADR-010):
 * token HMAC covers regionKey (v2), redeem verifies before consuming, wait-
 * queue envelopes are signed / verified, one pending request per player.
 */
class SqlSharedStoreSigningH2Test {

    private static final String JDBC_URL =
            "jdbc:h2:mem:rtp_net_sql_signing;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private DataSource dataSource;
    private HmacVerifier verifier;
    private SqlNetworkStateBinding binding;
    private SqlNetworkRequestQueue queue;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new H2DataSource(JDBC_URL);
        try (Connection c = dataSource.getConnection(); var st = c.createStatement()) {
            st.execute("DROP TABLE IF EXISTS rtp_network_proxies");
            st.execute("DROP TABLE IF EXISTS rtp_network_backends");
            st.execute("DROP TABLE IF EXISTS rtp_network_tokens");
            st.execute("DROP TABLE IF EXISTS rtp_net_wq_ready");
            st.execute("DROP TABLE IF EXISTS rtp_net_wq_status");
        }
        byte[] secret = new byte[32];
        Arrays.fill(secret, (byte) 7);
        verifier = HmacVerifier.forTesting(secret, 1, 1);
        binding = new SqlNetworkStateBinding(dataSource, 60_000L, verifier, 1);
        queue = new SqlNetworkRequestQueue(dataSource, verifier, 1);
    }

    @AfterEach
    void tearDown() {
        if (binding != null) binding.close();
        if (queue != null) queue.shutdown();
    }

    private void exec(String sql, String... args) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setString(i + 1, args[i]);
            ps.executeUpdate();
        }
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: signed token with regionKey round-trips through findReservation")
    void signedTokenRoundTrips() throws Exception {
        UUID pid = UUID.randomUUID();
        ReservationToken t = binding.claim("srv-a", pid, Duration.ofSeconds(30), Optional.of("east"))
                .get(2, TimeUnit.SECONDS);
        Optional<ReservationToken> found = binding.findReservation(pid).get(2, TimeUnit.SECONDS);
        assertTrue(found.isPresent());
        assertEquals(t.tokenId(), found.get().tokenId());
        assertEquals(Optional.of("east"), found.get().regionKey());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: tampered regionKey fails HMAC verification on find and list")
    void tamperedRegionKeyRejected() throws Exception {
        UUID pid = UUID.randomUUID();
        ReservationToken t = binding.claim("srv-a", pid, Duration.ofSeconds(30), Optional.of("east"))
                .get(2, TimeUnit.SECONDS);
        exec("UPDATE rtp_network_tokens SET region_key = ? WHERE token_id = ?", "nether", t.tokenId());
        assertTrue(binding.findReservation(pid).get(2, TimeUnit.SECONDS).isEmpty());
        assertTrue(binding.listActiveForServer("srv-a").get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-004 / REQ-RTP-PROXY-007: redeem of tampered token returns HMAC_INVALID and leaves it unconsumed")
    void redeemOfTamperedTokenRejected() throws Exception {
        UUID pid = UUID.randomUUID();
        ReservationToken t = binding.claim("srv-a", pid, Duration.ofSeconds(30), Optional.of("east"))
                .get(2, TimeUnit.SECONDS);
        exec("UPDATE rtp_network_tokens SET region_key = ? WHERE token_id = ?", "nether", t.tokenId());
        assertEquals(RedeemOutcome.HMAC_INVALID,
                binding.redeem(t.tokenId(), pid, "srv-a").get(2, TimeUnit.SECONDS));
        // Restore the signed value: the row must still be redeemable (not consumed by the rejected call).
        exec("UPDATE rtp_network_tokens SET region_key = ? WHERE token_id = ?", "east", t.tokenId());
        assertEquals(RedeemOutcome.REDEEMED,
                binding.redeem(t.tokenId(), pid, "srv-a").get(2, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: unsigned (NULL hmac) and pre-v2 forged tokens are not redeemable")
    void unsignedTokenNotRedeemable() throws Exception {
        UUID pid = UUID.randomUUID();
        ReservationToken t = binding.claim("srv-a", pid, Duration.ofSeconds(30), Optional.empty())
                .get(2, TimeUnit.SECONDS);
        exec("UPDATE rtp_network_tokens SET hmac = NULL WHERE token_id = ?", t.tokenId());
        assertEquals(RedeemOutcome.HMAC_INVALID,
                binding.redeem(t.tokenId(), pid, "srv-a").get(2, TimeUnit.SECONDS));
        // v1 layout (no tokenSig / regionKey line) signed with the same secret must not verify.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT expires_at_ms, created_at_ms FROM rtp_network_tokens WHERE token_id = ?")) {
            ps.setString(1, t.tokenId());
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                String v1 = "tokenId=" + t.tokenId() + "\nserverId=srv-a\nplayerId=" + pid
                        + "\nexpiresAtMs=" + rs.getLong(1) + "\ncreatedAtMs=" + rs.getLong(2)
                        + "\nstate=CLAIMED";
                exec("UPDATE rtp_network_tokens SET hmac = ? WHERE token_id = ?",
                        verifier.sign(1, v1), t.tokenId());
            }
        }
        assertEquals(RedeemOutcome.HMAC_INVALID,
                binding.redeem(t.tokenId(), pid, "srv-a").get(2, TimeUnit.SECONDS));
        assertTrue(binding.findReservation(pid).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-PROXY-007: claim with delimiter-injected regionKey is rejected")
    void claimDelimiterInjectionRejected() {
        UUID pid = UUID.randomUUID();
        ExecutionException ex = assertThrows(ExecutionException.class, () ->
                binding.claim("srv-a", pid, Duration.ofSeconds(30), Optional.of("east\nstate=CLAIMED"))
                        .get(2, TimeUnit.SECONDS));
        assertInstanceOf(IllegalArgumentException.class, ex.getCause());
    }

    @Test
    @DisplayName("REQ-RTP-NET-008 / REQ-RTP-PROXY-007: signed queue envelope round-trips")
    void signedEnvelopeRoundTrips() throws Exception {
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        assertEquals(EnrolOutcome.ACCEPTED, queue.enrol(new EnrolmentEnvelope(
                pid, cid, Optional.of("east"), Optional.of("srv-a"), 1234L)).get(2, TimeUnit.SECONDS));
        Optional<QueueEnvelope> got = queue.dequeueReady(Duration.ofMillis(200)).get(2, TimeUnit.SECONDS);
        assertTrue(got.isPresent());
        assertEquals(pid, got.get().playerId());
        assertEquals(Optional.of("srv-a"), got.get().serverHint());
    }

    @Test
    @DisplayName("REQ-RTP-NET-008 / REQ-RTP-PROXY-007: tampered queue envelope is dropped on dequeue")
    void tamperedEnvelopeDropped() throws Exception {
        UUID pid = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        queue.enrol(new EnrolmentEnvelope(pid, cid, Optional.of("east"), Optional.empty(), 1234L))
                .get(2, TimeUnit.SECONDS);
        exec("UPDATE rtp_net_wq_ready SET server_hint = ? WHERE correlation_id = ?", "srv-evil", cid.toString());
        assertTrue(queue.dequeueReady(Duration.ofMillis(200)).get(2, TimeUnit.SECONDS).isEmpty());
        // Dropped row is consumed, not re-picked.
        assertTrue(queue.dequeueReady(Duration.ofMillis(100)).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-NET-008 / REQ-RTP-PROXY-007: unsigned (injected) queue row is dropped on dequeue")
    void unsignedEnvelopeDropped() throws Exception {
        exec("INSERT INTO rtp_net_wq_ready (correlation_id, player_id, region_key, server_hint, "
                        + "enqueued_at_ms, state) VALUES (?, ?, NULL, NULL, 1, 'READY')",
                UUID.randomUUID().toString(), UUID.randomUUID().toString());
        assertTrue(queue.dequeueReady(Duration.ofMillis(200)).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-NET-008: second enrol for a player with a pending request is rejected")
    void duplicateEnrolRejected() throws Exception {
        UUID pid = UUID.randomUUID();
        assertEquals(EnrolOutcome.ACCEPTED, queue.enrol(new EnrolmentEnvelope(
                pid, UUID.randomUUID(), Optional.empty(), Optional.empty(), 1L)).get(2, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.REJECTED, queue.enrol(new EnrolmentEnvelope(
                pid, UUID.randomUUID(), Optional.empty(), Optional.empty(), 2L)).get(2, TimeUnit.SECONDS));
        // Batch path skips the duplicate but still resolves ACCEPTED (backend buffer must not loop).
        assertEquals(EnrolOutcome.ACCEPTED, queue.flushPending(List.of(new EnrolmentEnvelope(
                pid, UUID.randomUUID(), Optional.empty(), Optional.empty(), 3L))).get(2, TimeUnit.SECONDS));
        assertTrue(queue.dequeueReady(Duration.ofMillis(200)).get(2, TimeUnit.SECONDS).isPresent());
        assertTrue(queue.dequeueReady(Duration.ofMillis(100)).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("REQ-RTP-NET-008: envelope with delimiter-injected regionKey is rejected at enrol")
    void envelopeDelimiterInjectionRejected() throws Exception {
        NetworkRequestQueue q = queue;
        assertEquals(EnrolOutcome.REJECTED, q.enrol(new EnrolmentEnvelope(
                UUID.randomUUID(), UUID.randomUUID(), Optional.of("east|x"), Optional.empty(), 1L))
                .get(2, TimeUnit.SECONDS));
        assertTrue(q.dequeueReady(Duration.ofMillis(100)).get(2, TimeUnit.SECONDS).isEmpty());
    }

    /** Minimal DataSource over H2 in-memory (same pattern as SqlNetworkStateBindingH2Test). */
    private static final class H2DataSource implements DataSource {
        private final String url;
        H2DataSource(String url) { this.url = url; }
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        @Override public Connection getConnection(String u, String p) throws SQLException {
            return DriverManager.getConnection(url, u, p);
        }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) { }
        @Override public void setLoginTimeout(int seconds) { }
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { return Logger.getLogger("h2"); }
        @Override public <T> T unwrap(Class<T> iface) { return null; }
        @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    }
}
