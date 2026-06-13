package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.CancelReason;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.EnrolmentEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueEnvelope;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue.QueueStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2-backed unit tests for {@link SqlNetworkRequestQueue}. Mirrors the
 * {@link SqlNetworkStateBindingH2Test} pattern (in-memory H2, shared via a
 * named URL with {@code DB_CLOSE_DELAY=-1}).
 *
 * <p>Coverage: enrol+dequeue round-trip, batch flush, correlation-id
 * idempotency on replay, pollStatus single + missing, transition to
 * non-terminal + terminal, cancel idempotence, shutdown idempotence,
 * empty-batch ACCEPTED short-circuit, null-envelope NPE.</p>
 */
class SqlNetworkRequestQueueH2Test {

    private static final String JDBC_URL =
            "jdbc:h2:mem:rtp_net_wq_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";

    private DataSource dataSource;
    private SqlNetworkRequestQueue queue;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = new H2DataSource(JDBC_URL);
        try (Connection c = dataSource.getConnection()) {
            try (var st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS rtp_net_wq_ready");
                st.execute("DROP TABLE IF EXISTS rtp_net_wq_status");
            }
        }
        queue = new SqlNetworkRequestQueue(dataSource);
    }

    @AfterEach
    void tearDown() {
        if (queue != null) queue.shutdown();
    }

    @Test
    @DisplayName("enrol + dequeueReady: envelope round-trips and status row reflects QUEUED")
    void enrolAndDequeueRoundtrip() throws Exception {
        UUID player = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        EnrolmentEnvelope env = new EnrolmentEnvelope(
                player, cid, Optional.of("default"), Optional.of("backend-a"),
                System.currentTimeMillis());

        EnrolOutcome out = queue.enrol(env).get(2, TimeUnit.SECONDS);
        assertEquals(EnrolOutcome.ACCEPTED, out);

        // Status row created.
        List<QueueStatus> statuses = queue.pollStatus(List.of(player)).get(2, TimeUnit.SECONDS);
        assertEquals(1, statuses.size());
        assertEquals(QueueState.QUEUED, statuses.get(0).state());
        assertEquals(Optional.of("default"), statuses.get(0).regionKey());

        // Dequeue returns it.
        Optional<QueueEnvelope> popped = queue.dequeueReady(Duration.ofSeconds(2))
                .get(3, TimeUnit.SECONDS);
        assertTrue(popped.isPresent(), "dequeueReady must surface the enqueued envelope");
        assertEquals(player, popped.get().playerId());
        assertEquals(cid, popped.get().correlationId());
        assertEquals(Optional.of("default"), popped.get().regionKey());
        assertEquals(Optional.of("backend-a"), popped.get().serverHint());

        // Ready table is drained.
        Optional<QueueEnvelope> empty = queue.dequeueReady(Duration.ofMillis(100))
                .get(2, TimeUnit.SECONDS);
        assertTrue(empty.isEmpty(), "second dequeueReady must be empty after the row is consumed");
    }

    @Test
    @DisplayName("flushPending: batch of three envelopes lands as three FIFO rows")
    void flushPendingBatch() throws Exception {
        List<EnrolmentEnvelope> batch = List.of(
                envelope("default", "backend-a"),
                envelope("default", "backend-a"),
                envelope("nether", "backend-b"));

        EnrolOutcome out = queue.flushPending(batch).get(2, TimeUnit.SECONDS);
        assertEquals(EnrolOutcome.ACCEPTED, out);

        for (int i = 0; i < 3; i++) {
            Optional<QueueEnvelope> popped = queue.dequeueReady(Duration.ofSeconds(2))
                    .get(3, TimeUnit.SECONDS);
            assertTrue(popped.isPresent(), "row " + i + " must dequeue");
        }
    }

    @Test
    @DisplayName("correlation-id idempotency: replaying the same envelope is a no-op")
    void correlationIdempotency() throws Exception {
        UUID player = UUID.randomUUID();
        UUID cid = UUID.randomUUID();
        EnrolmentEnvelope env = new EnrolmentEnvelope(
                player, cid, Optional.empty(), Optional.empty(),
                System.currentTimeMillis());

        assertEquals(EnrolOutcome.ACCEPTED, queue.enrol(env).get(2, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.ACCEPTED, queue.enrol(env).get(2, TimeUnit.SECONDS));
        assertEquals(EnrolOutcome.ACCEPTED, queue.flushPending(List.of(env)).get(2, TimeUnit.SECONDS));

        // Only one row dequeues.
        Optional<QueueEnvelope> first = queue.dequeueReady(Duration.ofSeconds(2))
                .get(3, TimeUnit.SECONDS);
        assertTrue(first.isPresent());
        Optional<QueueEnvelope> second = queue.dequeueReady(Duration.ofMillis(150))
                .get(2, TimeUnit.SECONDS);
        assertTrue(second.isEmpty(), "duplicate correlationId must not re-enqueue");
    }

    @Test
    @DisplayName("pollStatus: unknown player IDs are absent from the returned list")
    void pollStatusMissingPlayer() throws Exception {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        queue.enrol(new EnrolmentEnvelope(
                known, UUID.randomUUID(), Optional.empty(), Optional.empty(),
                System.currentTimeMillis())).get(2, TimeUnit.SECONDS);

        List<QueueStatus> got = queue.pollStatus(List.of(known, unknown)).get(2, TimeUnit.SECONDS);
        assertEquals(1, got.size(), "unknown player must be skipped");
        assertEquals(known, got.get(0).playerId());
    }

    @Test
    @DisplayName("transition to non-terminal: status row updates state and reason")
    void transitionNonTerminal() throws Exception {
        UUID player = UUID.randomUUID();
        queue.enrol(new EnrolmentEnvelope(
                player, UUID.randomUUID(), Optional.of("default"), Optional.empty(),
                System.currentTimeMillis())).get(2, TimeUnit.SECONDS);

        Optional<QueueStatus> result = queue.transition(
                player, QueueState.ROUTING, Optional.of("routing now"))
                .get(2, TimeUnit.SECONDS);
        assertTrue(result.isPresent());
        assertEquals(QueueState.ROUTING, result.get().state());

        QueueStatus persisted = queue.pollStatus(List.of(player)).get(2, TimeUnit.SECONDS).get(0);
        assertEquals(QueueState.ROUTING, persisted.state());
    }

    @Test
    @DisplayName("transition to terminal: status row + ready row are both deleted")
    void transitionTerminalDeletesRows() throws Exception {
        UUID player = UUID.randomUUID();
        queue.enrol(new EnrolmentEnvelope(
                player, UUID.randomUUID(), Optional.empty(), Optional.empty(),
                System.currentTimeMillis())).get(2, TimeUnit.SECONDS);

        Optional<QueueStatus> result = queue.transition(
                player, QueueState.COMPLETED, Optional.empty()).get(2, TimeUnit.SECONDS);
        assertTrue(result.isPresent());
        assertEquals(QueueState.COMPLETED, result.get().state());

        // Status row gone.
        List<QueueStatus> after = queue.pollStatus(List.of(player)).get(2, TimeUnit.SECONDS);
        assertTrue(after.isEmpty(), "terminal transition must delete the status row");
        // Ready row gone.
        Optional<QueueEnvelope> empty = queue.dequeueReady(Duration.ofMillis(150))
                .get(2, TimeUnit.SECONDS);
        assertTrue(empty.isEmpty(), "terminal transition must also delete the ready row");
    }

    @Test
    @DisplayName("transition: unknown player returns empty Optional (no row to update)")
    void transitionUnknownPlayer() throws Exception {
        Optional<QueueStatus> result = queue.transition(
                UUID.randomUUID(), QueueState.ROUTING, Optional.empty())
                .get(2, TimeUnit.SECONDS);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("cancel: removes both status and ready rows; idempotent on re-cancel")
    void cancelIdempotence() throws Exception {
        UUID player = UUID.randomUUID();
        queue.enrol(new EnrolmentEnvelope(
                player, UUID.randomUUID(), Optional.empty(), Optional.empty(),
                System.currentTimeMillis())).get(2, TimeUnit.SECONDS);

        queue.cancel(player, CancelReason.PLAYER_DISCONNECT).get(2, TimeUnit.SECONDS);
        // Second cancel must not throw.
        queue.cancel(player, CancelReason.PLAYER_DISCONNECT).get(2, TimeUnit.SECONDS);

        assertTrue(queue.pollStatus(List.of(player)).get(2, TimeUnit.SECONDS).isEmpty());
        assertTrue(queue.dequeueReady(Duration.ofMillis(150)).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("flushPending([]) short-circuits to ACCEPTED without touching the DB")
    void emptyBatchShortCircuit() throws Exception {
        assertEquals(EnrolOutcome.ACCEPTED,
                queue.flushPending(List.of()).get(2, TimeUnit.SECONDS));
        // Ready table still empty.
        assertTrue(queue.dequeueReady(Duration.ofMillis(150)).get(2, TimeUnit.SECONDS).isEmpty());
    }

    @Test
    @DisplayName("null envelope throws NPE eagerly (does not silently swallow per S-004)")
    void nullEnvelopeRejected() {
        assertThrows(NullPointerException.class, () -> queue.enrol(null));
    }

    @Test
    @DisplayName("shutdown is idempotent and subsequent calls do not throw")
    void shutdownIdempotent() {
        queue.shutdown();
        queue.shutdown(); // no-op, must not throw
    }

    @Test
    @DisplayName("dequeueReady honors blockFor deadline: returns empty after ~ deadline elapses")
    void dequeueReadyDeadline() throws Exception {
        long start = System.nanoTime();
        Optional<QueueEnvelope> empty = queue.dequeueReady(Duration.ofMillis(100))
                .get(2, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(empty.isEmpty());
        // Allow generous upper bound for CI variance, but require some non-trivial wait.
        assertTrue(elapsedMs >= 50L, "deadline must be honored (>= ~50ms); got " + elapsedMs);
        assertTrue(elapsedMs < 1500L, "deadline must not block beyond reason; got " + elapsedMs);
    }

    @Test
    @DisplayName("dialect detection: H2 in-memory URL surfaces Dialect.H2")
    void dialectDetectedAsH2() {
        assertEquals(SqlNetworkStateBinding.Dialect.H2, queue.dialect());
    }

    @Test
    @DisplayName("ctor rejects null DataSource per SPI contract")
    void ctorNullDataSourceRejected() {
        assertThrows(NullPointerException.class, () -> new SqlNetworkRequestQueue(null));
    }

    // --- helpers ------------------------------------------------------------

    private static EnrolmentEnvelope envelope(String regionKey, String serverHint) {
        return new EnrolmentEnvelope(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Optional.ofNullable(regionKey),
                Optional.ofNullable(serverHint),
                System.currentTimeMillis());
    }

    /** Minimal DataSource wrapper around DriverManager (same pattern as SqlNetworkStateBindingH2Test). */
    private static final class H2DataSource implements DataSource {
        private final String url;
        H2DataSource(String url) { this.url = url; }
        @Override public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url);
        }
        @Override public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
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
