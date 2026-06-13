package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQL-backed {@link NetworkRequestQueue} implementation.
 *
 * <p>Reuses the shared {@link DataSource} owned by the host's
 * {@code AbstractSQLDatabaseAccessor} (rtp-proxy-ADR-011 §HikariCP Pool
 * Sharing). Schema lives in {@link SqlNetworkStateSchema} as two tables:
 * <ul>
 *   <li>{@code rtp_net_wq_ready} - FIFO of envelopes awaiting proxy
 *       dequeue. {@code state = 'READY'} for waiting rows; dequeue flips to
 *       {@code 'CLAIMED'} atomically via UPDATE..WHERE..LIMIT-equivalent.</li>
 *   <li>{@code rtp_net_wq_status} - per-player live status row, UPSERTed
 *       on enrolment and transition, deleted on terminal state.</li>
 * </ul>
 *
 * <p><strong>Threading.</strong> All public methods return on the impl's
 * private single-thread executor (mirrors {@link
 * io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue}
 * and {@link SqlNetworkStateBinding}). Per-method JDBC work runs off the
 * caller's thread.</p>
 *
 * <p><strong>Atomicity / idempotency.</strong> {@link #enrol} and
 * {@link #flushPending} use {@code INSERT INTO ... ready} with primary-key
 * uniqueness on {@code correlation_id}; SQLSTATE 23xxx duplicate-key on
 * replay is swallowed (idempotent, matches the Redis {@code SADD seen}
 * guard). {@link #dequeueReady} runs a portable transactional pattern:
 * SELECT one row WHERE state='READY' ORDER BY enqueued_at_ms,correlation_id,
 * then UPDATE..WHERE correlation_id=? AND state='READY'; if the UPDATE
 * row count is zero a peer claimed it first and the impl loops.</p>
 *
 * <p><strong>S-004 contract.</strong> Every failure path completes the
 * returned future exceptionally or with a typed enum; nothing is
 * silently swallowed.</p>
 */
public final class SqlNetworkRequestQueue implements NetworkRequestQueue {

    private static final Logger LOG = Logger.getLogger(SqlNetworkRequestQueue.class.getName());

    /** SQLSTATE class 23 - integrity constraint violation (duplicate-key on PK). */
    private static final String SQLSTATE_INTEGRITY_PREFIX = "23";

    private final DataSource dataSource;
    private final SqlNetworkStateBinding.Dialect dialect;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger threadCounter = new AtomicInteger();

    /**
     * Open the queue against the supplied {@code DataSource}. Schema
     * bootstrap is delegated to {@link SqlNetworkStateSchema#bootstrap} so
     * the two wait-queue tables are created idempotently on first open.
     */
    public SqlNetworkRequestQueue(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        SqlNetworkStateBinding.Dialect detected;
        try (Connection c = dataSource.getConnection()) {
            detected = SqlNetworkStateBinding.dialectOf(c);
            SqlNetworkStateSchema.bootstrap(c);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "SqlNetworkRequestQueue.open: schema bootstrap failed", e);
        }
        this.dialect = detected;
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rtp-sql-network-request-queue-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(tf);
    }

    // --- NetworkRequestQueue -------------------------------------------------

    @Override
    public CompletableFuture<EnrolOutcome> enrol(EnrolmentEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        return runAsync(() -> {
            if (closed.get()) return EnrolOutcome.REJECTED;
            insertOne(envelope);
            return EnrolOutcome.ACCEPTED;
        });
    }

    @Override
    public CompletableFuture<EnrolOutcome> flushPending(List<EnrolmentEnvelope> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) return CompletableFuture.completedFuture(EnrolOutcome.ACCEPTED);
        return runAsync(() -> {
            if (closed.get()) return EnrolOutcome.REJECTED;
            try (Connection c = dataSource.getConnection()) {
                boolean prevAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    for (EnrolmentEnvelope env : batch) {
                        if (env == null) continue;
                        insertOne(c, env);
                    }
                    c.commit();
                } catch (SQLException ex) {
                    safeRollback(c);
                    throw ex;
                } finally {
                    safeRestoreAuto(c, prevAuto);
                }
            }
            return EnrolOutcome.ACCEPTED;
        });
    }

    @Override
    public CompletableFuture<List<QueueStatus>> pollStatus(List<UUID> playerIds) {
        Objects.requireNonNull(playerIds, "playerIds");
        return runAsync(() -> {
            if (playerIds.isEmpty() || closed.get()) {
                return Collections.<QueueStatus>emptyList();
            }
            List<QueueStatus> out = new ArrayList<>(playerIds.size());
            String sql = "SELECT player_id, state, position_in_queue, server_id, "
                    + "region_key, updated_at_ms FROM rtp_net_wq_status WHERE player_id = ?";
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement(sql)) {
                for (UUID id : playerIds) {
                    if (id == null) continue;
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            out.add(rowToStatus(rs));
                        }
                    }
                }
            }
            return Collections.unmodifiableList(out);
        });
    }

    @Override
    public CompletableFuture<Optional<QueueEnvelope>> dequeueReady(Duration blockFor) {
        Objects.requireNonNull(blockFor, "blockFor");
        return CompletableFuture.supplyAsync(() -> {
            long deadlineNs = System.nanoTime() + Math.max(0L, blockFor.toNanos());
            while (true) {
                if (closed.get()) return Optional.<QueueEnvelope>empty();
                try {
                    Optional<QueueEnvelope> popped = tryClaimOne();
                    if (popped.isPresent()) return popped;
                } catch (SQLException ex) {
                    throw new RuntimeException("SqlNetworkRequestQueue.dequeueReady: claim failed", ex);
                }
                long remaining = deadlineNs - System.nanoTime();
                if (remaining <= 0L) return Optional.<QueueEnvelope>empty();
                try {
                    Thread.sleep(Math.min(50L, Math.max(5L, remaining / 1_000_000L)));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.<QueueEnvelope>empty();
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Optional<QueueStatus>> transition(
            UUID playerId, QueueState next, Optional<String> reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(reason, "reason");
        return runAsync(() -> {
            if (closed.get()) return Optional.<QueueStatus>empty();
            try (Connection c = dataSource.getConnection()) {
                Optional<QueueStatus> prev = selectStatus(c, playerId);
                if (prev.isEmpty()) return Optional.<QueueStatus>empty();
                if (isTerminal(next)) {
                    deleteStatus(c, playerId);
                    deleteReadyByPlayer(c, playerId);
                    QueueStatus updated = new QueueStatus(
                            prev.get().playerId(),
                            next,
                            prev.get().positionInQueue(),
                            prev.get().serverId(),
                            prev.get().regionKey(),
                            System.currentTimeMillis());
                    return Optional.of(updated);
                }
                long now = System.currentTimeMillis();
                String sql = "UPDATE rtp_net_wq_status SET state = ?, reason = ?, "
                        + "updated_at_ms = ? WHERE player_id = ?";
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    ps.setString(1, next.name());
                    ps.setString(2, reason.orElse(null));
                    ps.setLong(3, now);
                    ps.setString(4, playerId.toString());
                    ps.executeUpdate();
                }
                QueueStatus updated = new QueueStatus(
                        prev.get().playerId(),
                        next,
                        prev.get().positionInQueue(),
                        prev.get().serverId(),
                        prev.get().regionKey(),
                        now);
                return Optional.of(updated);
            }
        });
    }

    @Override
    public CompletableFuture<Void> cancel(UUID playerId, CancelReason reason) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(reason, "reason");
        return runAsync(() -> {
            if (closed.get()) return null;
            try (Connection c = dataSource.getConnection()) {
                boolean prevAuto = c.getAutoCommit();
                c.setAutoCommit(false);
                try {
                    deleteStatus(c, playerId);
                    deleteReadyByPlayer(c, playerId);
                    c.commit();
                } catch (SQLException ex) {
                    safeRollback(c);
                    throw ex;
                } finally {
                    safeRestoreAuto(c, prevAuto);
                }
            }
            return null;
        });
    }

    /** Idempotent; subsequent calls are no-ops. */
    public void shutdown() {
        if (!closed.compareAndSet(false, true)) return;
        executor.shutdownNow();
    }

    /** Test visibility: which dialect was detected at open. */
    public SqlNetworkStateBinding.Dialect dialect() { return dialect; }

    // --- internals -----------------------------------------------------------

    private void insertOne(EnrolmentEnvelope env) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            insertOne(c, env);
        }
    }

    /**
     * Insert an envelope into both the ready FIFO and the status row, with
     * correlation-id-as-PK idempotency. Duplicate-key (SQLSTATE 23xxx) is
     * swallowed as "already enqueued, no-op".
     */
    private void insertOne(Connection c, EnrolmentEnvelope env) throws SQLException {
        long now = System.currentTimeMillis();
        // 1. Insert into ready FIFO. PK = correlation_id; duplicate key = idempotent replay.
        String insReady = "INSERT INTO rtp_net_wq_ready "
                + "(correlation_id, player_id, region_key, server_hint, enqueued_at_ms, state) "
                + "VALUES (?, ?, ?, ?, ?, 'READY')";
        try (PreparedStatement ps = c.prepareStatement(insReady)) {
            ps.setString(1, env.correlationId().toString());
            ps.setString(2, env.playerId().toString());
            ps.setString(3, env.regionKey().orElse(null));
            ps.setString(4, env.serverHint().orElse(null));
            ps.setLong(5, env.createdAtMs() > 0 ? env.createdAtMs() : now);
            ps.executeUpdate();
        } catch (SQLException ex) {
            if (isIntegrityConstraintViolation(ex)) return; // idempotent replay
            throw ex;
        }
        // 2. UPSERT the status row to QUEUED. Dialect-aware MERGE / ON CONFLICT.
        upsertStatusQueued(c, env, now);
    }

    private void upsertStatusQueued(Connection c, EnrolmentEnvelope env, long now) throws SQLException {
        String sql = upsertStatusSql();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, env.playerId().toString());
            ps.setString(2, env.correlationId().toString());
            ps.setString(3, QueueState.QUEUED.name());
            ps.setInt(4, 0);
            ps.setString(5, null);
            ps.setString(6, env.regionKey().orElse(null));
            ps.setString(7, null);
            ps.setLong(8, now);
            ps.executeUpdate();
        }
    }

    private String upsertStatusSql() {
        return switch (dialect) {
            case MYSQL -> """
                INSERT INTO rtp_net_wq_status
                    (player_id, correlation_id, state, position_in_queue, server_id, region_key, reason, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    correlation_id = VALUES(correlation_id),
                    state = VALUES(state),
                    position_in_queue = VALUES(position_in_queue),
                    server_id = VALUES(server_id),
                    region_key = VALUES(region_key),
                    reason = VALUES(reason),
                    updated_at_ms = VALUES(updated_at_ms)
                """;
            case POSTGRES, SQLITE -> """
                INSERT INTO rtp_net_wq_status
                    (player_id, correlation_id, state, position_in_queue, server_id, region_key, reason, updated_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (player_id) DO UPDATE SET
                    correlation_id = EXCLUDED.correlation_id,
                    state = EXCLUDED.state,
                    position_in_queue = EXCLUDED.position_in_queue,
                    server_id = EXCLUDED.server_id,
                    region_key = EXCLUDED.region_key,
                    reason = EXCLUDED.reason,
                    updated_at_ms = EXCLUDED.updated_at_ms
                """;
            case H2, UNKNOWN -> """
                MERGE INTO rtp_net_wq_status
                    (player_id, correlation_id, state, position_in_queue, server_id, region_key, reason, updated_at_ms)
                KEY (player_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        };
    }

    /**
     * Try to atomically claim one READY envelope. Returns empty if no
     * claimable row was found in this iteration. The caller's
     * {@link #dequeueReady} loop will re-poll until {@code blockFor}
     * elapses.
     */
    private Optional<QueueEnvelope> tryClaimOne() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            // Step 1: pick a candidate. We do this in a short autocommit
            // SELECT so we don't hold a transaction across the network.
            // MySQL/SQLite use LIMIT 1; H2/Postgres support FETCH FIRST.
            String pick;
            if (dialect == SqlNetworkStateBinding.Dialect.MYSQL
                    || dialect == SqlNetworkStateBinding.Dialect.SQLITE) {
                pick = "SELECT correlation_id, player_id, region_key, server_hint, enqueued_at_ms "
                        + "FROM rtp_net_wq_ready WHERE state = 'READY' "
                        + "ORDER BY enqueued_at_ms, correlation_id LIMIT 1";
            } else {
                pick = "SELECT correlation_id, player_id, region_key, server_hint, enqueued_at_ms "
                        + "FROM rtp_net_wq_ready WHERE state = 'READY' "
                        + "ORDER BY enqueued_at_ms, correlation_id FETCH FIRST 1 ROWS ONLY";
            }
            String cid;
            String pid;
            String regionKey;
            String serverHint;
            long enqueuedAt;
            try (PreparedStatement ps = c.prepareStatement(pick);
                 ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                cid = rs.getString(1);
                pid = rs.getString(2);
                regionKey = rs.getString(3);
                serverHint = rs.getString(4);
                enqueuedAt = rs.getLong(5);
            }
            // Step 2: try to claim it. UPDATE returns row count; 0 means a
            // peer beat us (no exception, just retry the picker).
            long now = System.currentTimeMillis();
            String upd = "UPDATE rtp_net_wq_ready SET state = 'CLAIMED', claimed_at_ms = ? "
                    + "WHERE correlation_id = ? AND state = 'READY'";
            int claimed;
            try (PreparedStatement ps = c.prepareStatement(upd)) {
                ps.setLong(1, now);
                ps.setString(2, cid);
                claimed = ps.executeUpdate();
            }
            if (claimed == 0) return Optional.empty();
            // Step 3: terminal-state convention - once an envelope is
            // claimed it is no longer subject to re-dequeue, so we delete
            // the ready row in the same connection to avoid CLAIMED rows
            // accumulating. The status row stays (transitions handle it).
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM rtp_net_wq_ready WHERE correlation_id = ?")) {
                ps.setString(1, cid);
                ps.executeUpdate();
            }
            return Optional.of(new QueueEnvelope(
                    UUID.fromString(pid),
                    UUID.fromString(cid),
                    regionKey == null ? Optional.empty() : Optional.of(regionKey),
                    serverHint == null ? Optional.empty() : Optional.of(serverHint),
                    enqueuedAt,
                    now));
        }
    }

    private Optional<QueueStatus> selectStatus(Connection c, UUID playerId) throws SQLException {
        String sql = "SELECT player_id, state, position_in_queue, server_id, region_key, updated_at_ms "
                + "FROM rtp_net_wq_status WHERE player_id = ?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(rowToStatus(rs));
            }
        }
    }

    private void deleteStatus(Connection c, UUID playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM rtp_net_wq_status WHERE player_id = ?")) {
            ps.setString(1, playerId.toString());
            ps.executeUpdate();
        }
    }

    private void deleteReadyByPlayer(Connection c, UUID playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM rtp_net_wq_ready WHERE player_id = ?")) {
            ps.setString(1, playerId.toString());
            ps.executeUpdate();
        }
    }

    private static QueueStatus rowToStatus(ResultSet rs) throws SQLException {
        String pid = rs.getString(1);
        String state = rs.getString(2);
        int pos = rs.getInt(3);
        String serverId = rs.getString(4);
        String regionKey = rs.getString(5);
        long updatedAt = rs.getLong(6);
        QueueState qs;
        try {
            qs = QueueState.valueOf(state);
        } catch (IllegalArgumentException ex) {
            qs = QueueState.UNKNOWN;
        }
        return new QueueStatus(
                UUID.fromString(pid),
                qs,
                pos,
                serverId == null ? Optional.empty() : Optional.of(serverId),
                regionKey == null ? Optional.empty() : Optional.of(regionKey),
                updatedAt);
    }

    private static boolean isTerminal(QueueState s) {
        return s == QueueState.COMPLETED
                || s == QueueState.FAILED
                || s == QueueState.CANCELLED;
    }

    private static boolean isIntegrityConstraintViolation(SQLException ex) {
        String state = ex.getSQLState();
        if (state == null) return false;
        return state.startsWith(SQLSTATE_INTEGRITY_PREFIX);
    }

    private static void safeRollback(Connection c) {
        try { c.rollback(); }
        catch (SQLException re) {
            LOG.log(Level.WARNING, "SqlNetworkRequestQueue: rollback failed: " + re.getMessage(), re);
        }
    }

    private static void safeRestoreAuto(Connection c, boolean prev) {
        try { c.setAutoCommit(prev); }
        catch (SQLException re) {
            LOG.log(Level.WARNING, "SqlNetworkRequestQueue: setAutoCommit restore failed: " + re.getMessage(), re);
        }
    }

    private <T> CompletableFuture<T> runAsync(Callable<T> body) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return body.call();
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, executor);
    }
}
