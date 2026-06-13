package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkSnapshot;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.ProxyHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.RedeemOutcome;
import io.github.dailystruggle.rtp.proxy.common.spi.ReleaseReason;
import io.github.dailystruggle.rtp.proxy.common.spi.ReservationToken;
import io.github.dailystruggle.rtp.proxy.common.spi.Subscription;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQL-backed {@link NetworkTransport} implementation: the DB-as-bus binding
 * described in rtp-proxy-ADR-011 (Proposed). Every participating JVM (proxy
 * or backend) shares one database; heartbeats and reservation tokens live
 * in three tables (see {@link SqlNetworkStateSchema}). Fan-out is by
 * fixed-cadence polling, not pub/sub.
 *
 * <p><strong>Dialect coverage.</strong> Tested against H2 (test-only),
 * MySQL 8+, PostgreSQL 14+. SQLite is supported for single-writer dev/test
 * but not recommended for multi-writer production. The binding uses
 * dialect-agnostic SQL (vanilla {@code MERGE} or {@code INSERT ... ON ...}
 * via {@link Dialect}) and stores collection columns as comma-separated
 * VARCHAR.</p>
 *
 * <p><strong>Threading.</strong> All public methods return on the binding's
 * private executor. The poll loop runs on a single-threaded
 * {@link ScheduledExecutorService}. Subscribers are dispatched on that same
 * poll thread; consumers must hop to their host scheduler before touching
 * shared state, per rtp-proxy-ADR-001 §Threading Contract.</p>
 *
 * <p><strong>Atomic claim.</strong> Backed by
 * {@code INSERT ... WHERE NOT EXISTS} guarded by the partial unique index
 * on {@code rtp_network_tokens(player_id)} per
 * {@link SqlNetworkStateSchema}. Concurrent claims race the index; the
 * losers see a {@link SQLException} with SQLSTATE 23xxx and the binding
 * translates that into a {@link java.util.concurrent.CompletionException}
 * wrapping {@link IllegalStateException}, matching the in-memory
 * binding's contract (REQ-RTP-PROXY-004).</p>
 */
public final class SqlNetworkStateBinding implements NetworkTransport {

    /** SQL dialect family. Determines the UPSERT/MERGE shape. */
    public enum Dialect {
        H2,
        MYSQL,
        POSTGRES,
        SQLITE,
        /** Unknown vendor; binding emits the H2-style MERGE which most engines accept. */
        UNKNOWN
    }

    private final DataSource dataSource;
    private final Dialect dialect;
    private final Executor executor;
    private final ScheduledExecutorService poller;
    private final long pollIntervalMs;
    private final ConcurrentLinkedQueue<Sub> subscribers = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final AtomicInteger threadCounter = new AtomicInteger();
    private final Map<String, BackendHeartbeat> lastObserved = new java.util.concurrent.ConcurrentHashMap<>();
    private final boolean ownsPoller;
    private static final Logger LOG = Logger.getLogger(SqlNetworkStateBinding.class.getName());

    /**
     * HMAC envelope verifier per rtp-proxy-ADR-010 (A3). When non-null,
     * publishes sign over a deterministic canonical payload and reads verify
     * before returning rows; tampered or legacy-NULL hmac rows are dropped
     * with a REQ-RTP-S-004 WARNING. {@code null} preserves the pre-A3 wire
     * shape for tests and back-compat (the schema column is nullable).
     */
    private final HmacVerifier verifier;
    private final int schemaVersion;

    /**
     * Open a binding against the supplied {@code DataSource}. The poll
     * interval defaults to {@code 1000ms}; bootstrap-DDL runs once on
     * construction so multiple JVMs racing on the same shared DB all
     * converge on the schema.
     */
    public SqlNetworkStateBinding(DataSource dataSource) {
        this(dataSource, 1000L, null, 1);
    }

    public SqlNetworkStateBinding(DataSource dataSource, long pollIntervalMs) {
        this(dataSource, pollIntervalMs, null, 1);
    }

    /**
     * A3 ctor: thread an {@link HmacVerifier} and {@code schemaVersion} through
     * the binding so every published row carries an {@code hmac} column and
     * every read row is verified. {@code verifier == null} preserves the
     * pre-A3 wire shape (used by tests and by deployments that have not yet
     * set {@code network.secretEnv}).
     */
    public SqlNetworkStateBinding(DataSource dataSource, long pollIntervalMs,
                                  HmacVerifier verifier, int schemaVersion) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (pollIntervalMs <= 0) {
            throw new IllegalArgumentException("pollIntervalMs must be > 0, got " + pollIntervalMs);
        }
        this.pollIntervalMs = pollIntervalMs;
        this.verifier = verifier;
        this.schemaVersion = schemaVersion;

        Dialect detected;
        try (Connection c = dataSource.getConnection()) {
            detected = dialectOf(c);
            SqlNetworkStateSchema.bootstrap(c);
        } catch (SQLException e) {
            throw new IllegalStateException("SqlNetworkStateBinding.open: schema bootstrap failed", e);
        }
        this.dialect = detected;

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "rtp-sql-binding-" + threadCounter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(tf);
        this.poller = Executors.newSingleThreadScheduledExecutor(tf);
        this.ownsPoller = true;
        this.poller.scheduleAtFixedRate(this::pollOnce, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    /** Force-detect the dialect from a connection's {@code DatabaseMetaData}. Package-private for tests. */
    static Dialect dialectOf(Connection c) throws SQLException {
        String name = c.getMetaData().getDatabaseProductName();
        if (name == null) return Dialect.UNKNOWN;
        String lower = name.toLowerCase();
        if (lower.contains("h2"))         return Dialect.H2;
        if (lower.contains("mysql"))      return Dialect.MYSQL;
        if (lower.contains("mariadb"))    return Dialect.MYSQL;
        if (lower.contains("postgres"))   return Dialect.POSTGRES;
        if (lower.contains("sqlite"))     return Dialect.SQLITE;
        return Dialect.UNKNOWN;
    }

    public Dialect dialect() { return dialect; }

    // --- NetworkTransport ----------------------------------------------------

    @Override
    public CompletableFuture<Void> publishProxyHeartbeat(ProxyHeartbeat row) {
        Objects.requireNonNull(row, "row");
        checkOpen();
        return CompletableFuture.runAsync(() -> upsertProxy(row), executor);
    }

    @Override
    public CompletableFuture<Void> publishBackendHeartbeat(BackendHeartbeat row) {
        Objects.requireNonNull(row, "row");
        checkOpen();
        return CompletableFuture.runAsync(() -> upsertBackend(row), executor);
    }

    @Override
    public CompletableFuture<NetworkSnapshot> readSnapshot() {
        checkOpen();
        return CompletableFuture.supplyAsync(this::readSnapshotSync, executor);
    }

    @Override
    public Subscription subscribeBackendHeartbeats(Consumer<BackendHeartbeat> listener) {
        Objects.requireNonNull(listener, "listener");
        checkOpen();
        Sub sub = new Sub(listener);
        subscribers.add(sub);
        return sub;
    }

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId, Duration ttl) {
        return claim(serverId, playerId, ttl, Optional.empty());
    }

    @Override
    public CompletableFuture<ReservationToken> claim(String serverId, UUID playerId,
                                                     Duration ttl, Optional<String> regionKey) {
        Objects.requireNonNull(serverId, "serverId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(ttl, "ttl");
        Objects.requireNonNull(regionKey, "regionKey");
        checkOpen();
        return CompletableFuture.supplyAsync(
                () -> claimSync(serverId, playerId, ttl, regionKey.orElse(null)), executor);
    }

    @Override
    public CompletableFuture<Void> release(String tokenId, ReleaseReason reason) {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(reason, "reason");
        checkOpen();
        return CompletableFuture.runAsync(() -> releaseSync(tokenId, reason), executor);
    }

    @Override
    public CompletableFuture<RedeemOutcome> redeem(String tokenId, UUID playerId, String expectedServerId) {
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(expectedServerId, "expectedServerId");
        checkOpen();
        return CompletableFuture.supplyAsync(
                () -> redeemSync(tokenId, playerId, expectedServerId), executor);
    }

    private RedeemOutcome redeemSync(String tokenId, UUID playerId, String expectedServerId) {
        // First: SELECT the row to classify the outcome. We need to distinguish
        // NOT_FOUND / WRONG_SERVER / ALREADY_CONSUMED / EXPIRED / BAD_STATE before
        // attempting the CAS update, because the row-count of the UPDATE alone
        // cannot tell those apart (all yield 0 affected rows).
        String selectSql = """
                SELECT server_id, player_id, expires_at_ms, state, released_at_ms
                FROM rtp_network_tokens
                WHERE token_id = ?
                """;
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection()) {
            String rowServer;
            String rowPlayer;
            long rowExpires;
            String rowState;
            try (PreparedStatement ps = c.prepareStatement(selectSql)) {
                ps.setString(1, tokenId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return RedeemOutcome.NOT_FOUND;
                    rowServer = rs.getString("server_id");
                    rowPlayer = rs.getString("player_id");
                    rowExpires = rs.getLong("expires_at_ms");
                    rowState = rs.getString("state");
                }
            }
            if (rowServer == null || !rowServer.equals(expectedServerId)) {
                return RedeemOutcome.WRONG_SERVER;
            }
            if (rowPlayer == null || !rowPlayer.equals(playerId.toString())) {
                return RedeemOutcome.WRONG_SERVER;
            }
            if ("CONSUMED".equals(rowState) || "RELEASED".equals(rowState)) {
                return RedeemOutcome.ALREADY_CONSUMED;
            }
            if (rowExpires > 0 && rowExpires <= now) {
                return RedeemOutcome.EXPIRED;
            }
            if (!"CLAIMED".equals(rowState) && !"PENDING".equals(rowState)) {
                return RedeemOutcome.BAD_STATE;
            }
            // Row-count-atomic CAS: only the call that finds the row still in
            // (CLAIMED|PENDING) AND released_at_ms IS NULL transitions it; a
            // racing peer redeem or the TTL reaper that landed first leaves
            // released_at_ms non-null and this UPDATE affects 0 rows.
            String updateSql = """
                    UPDATE rtp_network_tokens
                    SET state = ?, released_at_ms = ?
                    WHERE token_id = ?
                      AND server_id = ?
                      AND player_id = ?
                      AND released_at_ms IS NULL
                      AND state IN ('CLAIMED','PENDING')
                    """;
            try (PreparedStatement ps = c.prepareStatement(updateSql)) {
                ps.setString(1, ReservationToken.State.CONSUMED.name());
                ps.setLong(2, now);
                ps.setString(3, tokenId);
                ps.setString(4, expectedServerId);
                ps.setString(5, playerId.toString());
                int affected = ps.executeUpdate();
                return affected == 1 ? RedeemOutcome.REDEEMED : RedeemOutcome.ALREADY_CONSUMED;
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING,
                    "SqlNetworkStateBinding.redeem failed for token " + tokenId + ": " + e.getMessage(), e);
            return RedeemOutcome.TRANSPORT_ERROR;
        }
    }

    @Override
    public CompletableFuture<Optional<ReservationToken>> findReservation(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> findReservationSync(playerId), executor);
    }

    @Override
    public CompletableFuture<List<String>> reapExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> reapExpiredSync(now), executor);
    }

    @Override
    public CompletableFuture<List<ReservationToken>> listActiveForServer(String serverId) {
        Objects.requireNonNull(serverId, "serverId");
        checkOpen();
        return CompletableFuture.supplyAsync(() -> listActiveForServerSync(serverId), executor);
    }

    /**
     * One-shot listing of PENDING / CLAIMED tokens owned by {@code serverId}
     * whose {@code expires_at_ms > now}. Used by a backend's L6/F1 boot-time
     * reconcile so the cost is borne once per backend lifecycle. Corrupt
     * rows (unknown state, HMAC mismatch) are filtered with WARNING and
     * never raised to the caller, mirroring {@link #findReservationSync(UUID)}.
     */
    private List<ReservationToken> listActiveForServerSync(String serverId) {
        String sql = """
                SELECT token_id, server_id, player_id, expires_at_ms, state, created_at_ms, hmac, region_key
                FROM rtp_network_tokens
                WHERE server_id = ?
                  AND released_at_ms IS NULL
                  AND state IN ('PENDING','CLAIMED')
                  AND expires_at_ms > ?
                """;
        long now = System.currentTimeMillis();
        List<ReservationToken> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, serverId);
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ReservationToken.State state;
                    try {
                        state = ReservationToken.State.valueOf(rs.getString("state"));
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    if (state != ReservationToken.State.PENDING && state != ReservationToken.State.CLAIMED) continue;
                    String tokenId = rs.getString("token_id");
                    String rowServerId = rs.getString("server_id");
                    String playerIdStr = rs.getString("player_id");
                    if (playerIdStr == null) continue;
                    UUID playerId;
                    try {
                        playerId = UUID.fromString(playerIdStr);
                    } catch (IllegalArgumentException ex) {
                        continue;
                    }
                    long expires = rs.getLong("expires_at_ms");
                    long createdAt = rs.getLong("created_at_ms");
                    if (verifier != null) {
                        String storedHmac = rs.getString("hmac");
                        if (storedHmac == null || storedHmac.isEmpty()
                                || !verifier.verify(schemaVersion,
                                        canonicalToken(tokenId, rowServerId, playerIdStr,
                                                expires, createdAt, state),
                                        storedHmac)) {
                            LOG.log(Level.WARNING,
                                    "listActiveForServerSync: HMAC verification failed for token "
                                            + tokenId + "; dropping row (REQ-RTP-S-004)");
                            continue;
                        }
                    }
                    out.add(new ReservationToken(tokenId, rowServerId, playerId, expires, state,
                            rs.getString("region_key")));
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.WARNING,
                    "SqlNetworkStateBinding.listActiveForServer failed: " + e.getMessage()
                            + " (REQ-RTP-S-004)");
            return List.of();
        }
        return out;
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) return;
        if (ownsPoller) {
            poller.shutdownNow();
        }
        // Executor: best-effort terminate via cast; not strictly required since daemon.
        if (executor instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
        for (Sub s : subscribers) {
            s.closed.set(true);
        }
        subscribers.clear();
    }

    // --- Implementation ------------------------------------------------------

    private void upsertProxy(ProxyHeartbeat row) {
        String sql = switch (dialect) {
            case POSTGRES, SQLITE -> """
                INSERT INTO rtp_network_proxies(proxy_id, schema_version, last_seen_ms, player_count, in_flight, kill_switch, updated_at_ms, hmac)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(proxy_id) DO UPDATE SET
                    schema_version = excluded.schema_version,
                    last_seen_ms = excluded.last_seen_ms,
                    player_count = excluded.player_count,
                    in_flight = excluded.in_flight,
                    kill_switch = excluded.kill_switch,
                    updated_at_ms = excluded.updated_at_ms,
                    hmac = excluded.hmac
                """;
            case MYSQL -> """
                INSERT INTO rtp_network_proxies(proxy_id, schema_version, last_seen_ms, player_count, in_flight, kill_switch, updated_at_ms, hmac)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    schema_version = VALUES(schema_version),
                    last_seen_ms = VALUES(last_seen_ms),
                    player_count = VALUES(player_count),
                    in_flight = VALUES(in_flight),
                    kill_switch = VALUES(kill_switch),
                    updated_at_ms = VALUES(updated_at_ms),
                    hmac = VALUES(hmac)
                """;
            default -> """
                MERGE INTO rtp_network_proxies(proxy_id, schema_version, last_seen_ms, player_count, in_flight, kill_switch, updated_at_ms, hmac)
                KEY(proxy_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        };
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, row.proxyId());
            ps.setInt(2, row.schemaVersion());
            ps.setLong(3, row.lastSeenEpochMs());
            ps.setInt(4, row.playerCount());
            ps.setInt(5, row.inFlightRequests());
            ps.setBoolean(6, row.killSwitch());
            ps.setLong(7, now);
            // A3 envelope: sign over the canonical proxy payload (field order
            // matches RedisNetworkStateBinding.encodeProxy). Empty string when
            // verifier is null preserves the pre-A3 wire shape; read-side
            // treats empty + null hmac identically.
            String hmac = signOrEmpty(canonicalProxy(row));
            if (hmac.isEmpty()) {
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                ps.setString(8, hmac);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SqlNetworkStateBinding.publishProxyHeartbeat failed: " + e.getMessage(), e);
        }
    }

    private void upsertBackend(BackendHeartbeat row) {
        String sql = switch (dialect) {
            case POSTGRES, SQLITE -> """
                INSERT INTO rtp_network_backends(server_id, schema_version, plugin_state, accepting_requests,
                        last_seen_ms, mspt, queue_depth, soft_cap, heap_used_bytes, heap_max_bytes,
                        player_count, regions_available, worlds_loaded, kill_switch, updated_at_ms, hmac,
                        kept_count, network_reserved_count, regions, region_kept_counts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(server_id) DO UPDATE SET
                    schema_version = excluded.schema_version,
                    plugin_state = excluded.plugin_state,
                    accepting_requests = excluded.accepting_requests,
                    last_seen_ms = excluded.last_seen_ms,
                    mspt = excluded.mspt,
                    queue_depth = excluded.queue_depth,
                    soft_cap = excluded.soft_cap,
                    heap_used_bytes = excluded.heap_used_bytes,
                    heap_max_bytes = excluded.heap_max_bytes,
                    player_count = excluded.player_count,
                    regions_available = excluded.regions_available,
                    worlds_loaded = excluded.worlds_loaded,
                    kill_switch = excluded.kill_switch,
                    updated_at_ms = excluded.updated_at_ms,
                    hmac = excluded.hmac,
                    kept_count = excluded.kept_count,
                    network_reserved_count = excluded.network_reserved_count,
                    regions = excluded.regions,
                    region_kept_counts = excluded.region_kept_counts
                """;
            case MYSQL -> """
                INSERT INTO rtp_network_backends(server_id, schema_version, plugin_state, accepting_requests,
                        last_seen_ms, mspt, queue_depth, soft_cap, heap_used_bytes, heap_max_bytes,
                        player_count, regions_available, worlds_loaded, kill_switch, updated_at_ms, hmac,
                        kept_count, network_reserved_count, regions, region_kept_counts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    schema_version = VALUES(schema_version),
                    plugin_state = VALUES(plugin_state),
                    accepting_requests = VALUES(accepting_requests),
                    last_seen_ms = VALUES(last_seen_ms),
                    mspt = VALUES(mspt),
                    queue_depth = VALUES(queue_depth),
                    soft_cap = VALUES(soft_cap),
                    heap_used_bytes = VALUES(heap_used_bytes),
                    heap_max_bytes = VALUES(heap_max_bytes),
                    player_count = VALUES(player_count),
                    regions_available = VALUES(regions_available),
                    worlds_loaded = VALUES(worlds_loaded),
                    kill_switch = VALUES(kill_switch),
                    updated_at_ms = VALUES(updated_at_ms),
                    hmac = VALUES(hmac),
                    kept_count = VALUES(kept_count),
                    network_reserved_count = VALUES(network_reserved_count),
                    regions = VALUES(regions),
                    region_kept_counts = VALUES(region_kept_counts)
                """;
            default -> """
                MERGE INTO rtp_network_backends(server_id, schema_version, plugin_state, accepting_requests,
                        last_seen_ms, mspt, queue_depth, soft_cap, heap_used_bytes, heap_max_bytes,
                        player_count, regions_available, worlds_loaded, kill_switch, updated_at_ms, hmac,
                        kept_count, network_reserved_count, regions, region_kept_counts)
                KEY(server_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        };
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            long now = System.currentTimeMillis();
            ps.setString(1, row.serverId());
            ps.setInt(2, row.schemaVersion());
            ps.setString(3, row.pluginState().name());
            ps.setBoolean(4, row.acceptingRequests());
            ps.setLong(5, row.lastSeenEpochMs());
            ps.setDouble(6, row.mspt());
            ps.setInt(7, row.queueDepth());
            ps.setInt(8, row.softCap());
            ps.setLong(9, row.heapUsedBytes());
            ps.setLong(10, row.heapMaxBytes());
            ps.setInt(11, row.playerCount());
            ps.setString(12, joinTrimmed(row.regionsAvailable()));
            ps.setString(13, joinTrimmed(row.worldsLoaded()));
            ps.setBoolean(14, row.killSwitch());
            ps.setLong(15, now);
            // A3 envelope: sign over the canonical backend payload (field
            // order matches RedisNetworkStateBinding.encodeBackend).
            String hmac = signOrEmpty(canonicalBackend(row));
            if (hmac.isEmpty()) {
                ps.setNull(16, java.sql.Types.VARCHAR);
            } else {
                ps.setString(16, hmac);
            }
            // L6 cross-server fields. Persisted so the read-side reconstruction
            // carries the same data the codec signs over (HMAC stays valid) and
            // the proxy selector keeps its region-aware signal.
            ps.setInt(17, row.keptCount());
            ps.setInt(18, row.networkReservedCount());
            ps.setString(19, joinTrimmed(new ArrayList<>(BackendHeartbeatCodec.splitList(
                    BackendHeartbeatCodec.joinSet(row.regions())))));
            ps.setString(20, joinTrimmed(java.util.List.of(
                    BackendHeartbeatCodec.joinIntMap(row.regionKeptCounts()))));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("SqlNetworkStateBinding.publishBackendHeartbeat failed: " + e.getMessage(), e);
        }
    }

    private NetworkSnapshot readSnapshotSync() {
        String sql = """
                SELECT server_id, schema_version, plugin_state, accepting_requests,
                       last_seen_ms, mspt, queue_depth, soft_cap, heap_used_bytes, heap_max_bytes,
                       player_count, regions_available, worlds_loaded, kill_switch, hmac,
                       kept_count, network_reserved_count, regions, region_kept_counts
                FROM rtp_network_backends
                """;
        Map<String, BackendHeartbeat> map = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                BackendHeartbeat hb = rowToBackend(rs);
                if (hb == null) continue; // tampered or legacy-NULL hmac under signed mode
                map.put(hb.serverId(), hb);
            }
        } catch (SQLException e) {
            throw new RuntimeException("SqlNetworkStateBinding.readSnapshot failed: " + e.getMessage(), e);
        }
        return new NetworkSnapshot(System.currentTimeMillis(), map);
    }

    private BackendHeartbeat rowToBackend(ResultSet rs) throws SQLException {
        BackendHeartbeat hb = new BackendHeartbeat(
                rs.getString("server_id"),
                rs.getInt("schema_version"),
                PluginState.valueOf(rs.getString("plugin_state")),
                rs.getBoolean("accepting_requests"),
                rs.getLong("last_seen_ms"),
                rs.getDouble("mspt"),
                rs.getInt("queue_depth"),
                rs.getInt("soft_cap"),
                rs.getLong("heap_used_bytes"),
                rs.getLong("heap_max_bytes"),
                rs.getInt("player_count"),
                splitTrimmed(rs.getString("regions_available")),
                splitTrimmed(rs.getString("worlds_loaded")),
                rs.getBoolean("kill_switch"),
                rs.getInt("kept_count"),
                rs.getInt("network_reserved_count"),
                BackendHeartbeatCodec.splitSet(rs.getString("regions")),
                BackendHeartbeatCodec.splitIntMap(rs.getString("region_kept_counts")));
        // A3 envelope: verify against the row's persisted hmac column. Legacy
        // (pre-A3) rows have NULL hmac; under signed mode they are dropped
        // with REQ-RTP-S-004 WARNING and self-heal on the next heartbeat tick
        // (the publishing backend will rewrite the row with hmac set). When
        // verifier is null, skip verification entirely (pre-A3 wire shape).
        if (verifier != null) {
            String storedHmac = rs.getString("hmac");
            if (storedHmac == null || storedHmac.isEmpty()
                    || !verifier.verify(schemaVersion, canonicalBackend(hb), storedHmac)) {
                LOG.log(Level.WARNING,
                        "rowToBackend: HMAC verification failed for server " + hb.serverId()
                                + "; dropping row (REQ-RTP-S-004)");
                return null;
            }
        }
        return hb;
    }

    private ReservationToken claimSync(String serverId, UUID playerId, Duration ttl, String regionKey) {
        // Phase 2e-SQL first cut: atomic claim via INSERT on the unique
        // (player_id) index. Race losers see SQLSTATE 23xxx and are translated
        // into IllegalStateException to match the in-memory binding's contract.
        String tokenId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long expires = now + ttl.toMillis();
        String region = (regionKey == null || regionKey.isEmpty()) ? null : regionKey;
        String sql = """
                INSERT INTO rtp_network_tokens(token_id, server_id, player_id, expires_at_ms, state, created_at_ms, released_at_ms, hmac, region_key)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """;
        // A3 envelope: sign over the canonical token payload (field order
        // matches RedisNetworkStateBinding.canonicalToken).
        String hmac = signOrEmpty(canonicalToken(
                tokenId, serverId, playerId.toString(), expires, now,
                ReservationToken.State.CLAIMED));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, tokenId);
            ps.setString(2, serverId);
            ps.setString(3, playerId.toString());
            ps.setLong(4, expires);
            ps.setString(5, ReservationToken.State.CLAIMED.name());
            ps.setLong(6, now);
            if (hmac.isEmpty()) {
                ps.setNull(7, java.sql.Types.VARCHAR);
            } else {
                ps.setString(7, hmac);
            }
            if (region == null) {
                ps.setNull(8, java.sql.Types.VARCHAR);
            } else {
                ps.setString(8, region);
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            String sqlState = e.getSQLState();
            if (sqlState != null && sqlState.startsWith("23")) {
                throw new IllegalStateException(
                        "claim race lost for player " + playerId + " (SQLSTATE " + sqlState + ")");
            }
            throw new RuntimeException("SqlNetworkStateBinding.claim failed: " + e.getMessage(), e);
        }
        return new ReservationToken(tokenId, serverId, playerId, expires,
                ReservationToken.State.CLAIMED, region);
    }

    private void releaseSync(String tokenId, ReleaseReason reason) {
        String sql = """
                UPDATE rtp_network_tokens
                SET state = ?, released_at_ms = ?
                WHERE token_id = ? AND released_at_ms IS NULL
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            String terminal = reason == ReleaseReason.CONSUMED
                    ? ReservationToken.State.CONSUMED.name()
                    : ReservationToken.State.RELEASED.name();
            ps.setString(1, terminal);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, tokenId);
            ps.executeUpdate();
            // Idempotent: no row affected = already released or never existed.
        } catch (SQLException e) {
            throw new RuntimeException("SqlNetworkStateBinding.release failed: " + e.getMessage(), e);
        }
    }

    private Optional<ReservationToken> findReservationSync(UUID playerId) {
        // Lookup uses the active partial unique index uq_rtp_network_tokens_active_player
        // (where released_at_ms IS NULL). Filters expired-but-not-yet-reaped rows by
        // expires_at_ms > now so the listener treats them as "no reservation" per
        // REQ-RTP-PROXY-VELOCITY-002.
        String sql = """
                SELECT token_id, server_id, player_id, expires_at_ms, state, created_at_ms, hmac, region_key
                FROM rtp_network_tokens
                WHERE player_id = ?
                  AND released_at_ms IS NULL
                  AND expires_at_ms > ?
                """;
        long now = System.currentTimeMillis();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerId.toString());
            ps.setLong(2, now);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                ReservationToken.State state;
                try {
                    state = ReservationToken.State.valueOf(rs.getString("state"));
                } catch (IllegalArgumentException ex) {
                    // Corrupt / unknown state: treat as no reservation rather than
                    // throw at the connect-time hot path (REQ-RTP-S-004).
                    return Optional.empty();
                }
                // Only PENDING/CLAIMED count as "active" for routing purposes.
                if (state == ReservationToken.State.CONSUMED || state == ReservationToken.State.RELEASED) {
                    return Optional.empty();
                }
                String tokenId = rs.getString("token_id");
                String serverId = rs.getString("server_id");
                long expires = rs.getLong("expires_at_ms");
                long createdAt = rs.getLong("created_at_ms");
                // A3 envelope: verify HMAC on non-terminal rows. A mismatch
                // (forged row, replayed claim, or state tampered from RELEASED
                // back to CLAIMED) drops the row with a REQ-RTP-S-004 WARNING
                // and returns Optional.empty().
                if (verifier != null) {
                    String storedHmac = rs.getString("hmac");
                    if (storedHmac == null || storedHmac.isEmpty()
                            || !verifier.verify(schemaVersion,
                                    canonicalToken(tokenId, serverId, playerId.toString(),
                                            expires, createdAt, state),
                                    storedHmac)) {
                        LOG.log(Level.WARNING,
                                "findReservationSync: HMAC verification failed for token "
                                        + tokenId + "; dropping row (REQ-RTP-S-004)");
                        return Optional.empty();
                    }
                }
                return Optional.of(new ReservationToken(
                        tokenId,
                        serverId,
                        UUID.fromString(rs.getString("player_id")),
                        expires,
                        state,
                        rs.getString("region_key")));
            }
        } catch (SQLException e) {
            throw new RuntimeException("SqlNetworkStateBinding.findReservation failed: " + e.getMessage(), e);
        }
    }

    /**
     * REQ-RTP-NET-011 reap pass. Bulk-marks every row whose
     * {@code released_at_ms IS NULL} and {@code expires_at_ms <= now} as
     * {@code RELEASED} and returns the IDs of the rows that this call
     * actually transitioned.
     *
     * <p>Row-count atomicity. The bulk {@code UPDATE} uses
     * {@code released_at_ms IS NULL} in its predicate, so a row that was
     * already terminated by a racing proxy is naturally skipped (REQ-RTP-PROXY-004).
     * Each winning row carries the call's sentinel {@code released_at_ms = now}
     * value; a follow-up {@code SELECT} on the sentinel re-derives the winner
     * set. The whole pair runs under a single transaction so the sentinel read
     * sees only this call's writes.</p>
     *
     * <p>Portability. Plain ANSI SQL - no {@code RETURNING}, no
     * {@code FETCH FIRST}, no {@code SKIP LOCKED}. Works on H2 / Postgres /
     * MySQL / SQLite. A bounded batch (LIMIT-style) is intentionally omitted
     * here: the sweep is rate-limited by the reaper's cadence
     * ({@code reservation.reapIntervalMs}), and a single unbounded mark-only
     * UPDATE is cheaper than a SELECT+UPDATE pair under contention.</p>
     */
    private List<String> reapExpiredSync(Instant now) {
        final long nowMs = now.toEpochMilli();
        String updateSql = """
                UPDATE rtp_network_tokens
                   SET state = ?, released_at_ms = ?
                 WHERE released_at_ms IS NULL
                   AND expires_at_ms <= ?
                """;
        String selectWinnersSql = """
                SELECT token_id FROM rtp_network_tokens
                 WHERE released_at_ms = ?
                """;
        try (Connection c = dataSource.getConnection()) {
            boolean prevAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int affected;
                try (PreparedStatement ps = c.prepareStatement(updateSql)) {
                    ps.setString(1, ReservationToken.State.RELEASED.name());
                    ps.setLong(2, nowMs);
                    ps.setLong(3, nowMs);
                    affected = ps.executeUpdate();
                }
                if (affected == 0) {
                    c.commit();
                    return List.of();
                }
                List<String> winners = new ArrayList<>(affected);
                try (PreparedStatement ps = c.prepareStatement(selectWinnersSql)) {
                    ps.setLong(1, nowMs);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) winners.add(rs.getString("token_id"));
                    }
                }
                c.commit();
                return winners;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new RuntimeException("SqlNetworkStateBinding.reapExpired failed: " + e.getMessage(), e);
        }
    }

    /** Poll loop entry. Fetches the backend table and fans changed rows out to subscribers. */
    void pollOnce() {
        if (!open.get()) return;
        Map<String, BackendHeartbeat> latest;
        try {
            latest = readSnapshotSync().backends();
        } catch (RuntimeException e) {
            // Best-effort: a transient DB outage must not kill the poll loop.
            return;
        }
        for (BackendHeartbeat hb : latest.values()) {
            BackendHeartbeat prev = lastObserved.put(hb.serverId(), hb);
            if (prev == null || prev.lastSeenEpochMs() != hb.lastSeenEpochMs()) {
                for (Sub s : subscribers) {
                    if (!s.closed.get()) {
                        try { s.sink.accept(hb); } catch (RuntimeException ignored) { /* sub-owned */ }
                    }
                }
            }
        }
    }

    private void checkOpen() {
        if (!open.get()) {
            throw new IllegalStateException("SqlNetworkStateBinding is closed");
        }
    }

    private static String joinTrimmed(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        String joined = String.join(",", values);
        if (joined.length() > SqlNetworkStateSchema.COLLECTION_COLUMN_MAX_LENGTH) {
            // Defensive: callers shouldn't exceed the column cap, but truncate at a
            // separator boundary if they do, so we never throw mid-publish.
            int cut = joined.lastIndexOf(',', SqlNetworkStateSchema.COLLECTION_COLUMN_MAX_LENGTH - 1);
            joined = cut > 0 ? joined.substring(0, cut) : joined.substring(0, SqlNetworkStateSchema.COLLECTION_COLUMN_MAX_LENGTH);
        }
        return joined;
    }

    private static List<String> splitTrimmed(String s) {
        if (s == null || s.isEmpty()) return List.of();
        return List.of(s.split(","));
    }

    // ---- A3 HMAC envelope helpers --------------------------------------
    // Field order is fixed and matches the Redis transport's canonical*()
    // helpers so the same secret signs identically under either binding.
    // Adding a new signed field is a wire-format breaking change.

    private static String canonicalProxy(ProxyHeartbeat r) {
        return "proxyId=" + r.proxyId()
                + "\nschemaVersion=" + r.schemaVersion()
                + "\nlastSeenEpochMs=" + r.lastSeenEpochMs()
                + "\nplayerCount=" + r.playerCount()
                + "\ninFlightRequests=" + r.inFlightRequests()
                + "\nkillSwitch=" + r.killSwitch();
    }

    private static String canonicalBackend(BackendHeartbeat r) {
        // Delegate to the shared codec so SQL, Redis, and the plugin-message
        // tier sign over an identical canonical byte sequence.
        return BackendHeartbeatCodec.encode(r);
    }

    private static String canonicalToken(String tokenId, String serverId, String playerId,
                                         long expiresAtMs, long createdAtMs,
                                         ReservationToken.State state) {
        return "tokenId=" + tokenId
                + "\nserverId=" + serverId
                + "\nplayerId=" + playerId
                + "\nexpiresAtMs=" + expiresAtMs
                + "\ncreatedAtMs=" + createdAtMs
                + "\nstate=" + state.name();
    }

    private String signOrEmpty(String canonical) {
        return verifier == null ? "" : verifier.sign(schemaVersion, canonical);
    }

    private final class Sub implements Subscription {
        final Consumer<BackendHeartbeat> sink;
        final AtomicBoolean closed = new AtomicBoolean(false);

        Sub(Consumer<BackendHeartbeat> sink) { this.sink = sink; }

        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                subscribers.remove(this);
            }
        }
        @Override public boolean isClosed() { return closed.get(); }
    }
}
