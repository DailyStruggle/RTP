package io.github.dailystruggle.rtp.proxy.common.transport.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * DDL bootstrap for {@link SqlNetworkStateBinding}. Three tables, all
 * {@code CREATE TABLE IF NOT EXISTS} so {@link SqlNetworkStateBinding#open}
 * is idempotent across reboots and across multiple JVMs racing on the same
 * shared database.
 *
 * <p>Pinned by rtp-proxy-ADR-011 (Proposed -&gt; Accepted in the implementing
 * turn) and reflected in REQ-RTP-NET-005 / REQ-RTP-NET-007 / REQ-RTP-NET-008
 * Section in TRACEABILITY.</p>
 *
 * <p>Dialect notes:
 * <ul>
 *   <li>SQL types use {@code BIGINT} / {@code DOUBLE PRECISION} /
 *       {@code VARCHAR} which are portable across H2, MySQL 8+, PostgreSQL,
 *       and SQLite.</li>
 *   <li>The partial unique index on {@code rtp_network_tokens} (active states
 *       only) is supported by PostgreSQL, SQLite, and H2; MySQL ignores the
 *       {@code WHERE} clause but enforces a wider uniqueness which is
 *       acceptable because terminal states get distinct token ids and never
 *       collide on {@code player_id}. The binding tolerates the redundant
 *       constraint silently.</li>
 *   <li>UPSERTs are emitted as the dialect-agnostic
 *       {@code MERGE INTO ... USING ... WHEN MATCHED} pattern for H2 /
 *       {@code INSERT ... ON CONFLICT} for PostgreSQL / SQLite /
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE} for MySQL. See
 *       {@link SqlNetworkStateBinding#dialect(java.sql.Connection)}.</li>
 * </ul>
 *
 * <p>All collection columns ({@code regions_available}, {@code worlds_loaded})
 * are stored as comma-separated {@code VARCHAR} for simplicity. The list size
 * cap is documented at {@link #COLLECTION_COLUMN_MAX_LENGTH}.</p>
 */
public final class SqlNetworkStateSchema {

    /** Maximum length of comma-separated list columns. ~4 KB headroom. */
    public static final int COLLECTION_COLUMN_MAX_LENGTH = 4000;

    private static final String DDL_PROXIES = """
            CREATE TABLE IF NOT EXISTS rtp_network_proxies (
                proxy_id         VARCHAR(64)  NOT NULL PRIMARY KEY,
                schema_version   INT          NOT NULL,
                last_seen_ms     BIGINT       NOT NULL,
                player_count     INT          NOT NULL,
                in_flight        INT          NOT NULL,
                kill_switch      BOOLEAN      NOT NULL DEFAULT FALSE,
                updated_at_ms    BIGINT       NOT NULL,
                hmac             VARCHAR(128)
            )
            """;

    /**
     * Phase 2e-SQL A3 envelope (rtp-proxy-ADR-010): add the {@code hmac}
     * column to pre-A3 deployments. Idempotent and dialect-tolerant: H2 /
     * Postgres / SQLite honour {@code ADD COLUMN IF NOT EXISTS}; MySQL/MariaDB
     * raises a duplicate-column SQLException (SQLSTATE 42S21 / errno 1060)
     * which {@link #execIgnoringDuplicate} swallows.
     */
    private static final String DDL_PROXIES_ADD_HMAC =
            "ALTER TABLE rtp_network_proxies ADD COLUMN IF NOT EXISTS hmac VARCHAR(128)";
    private static final String DDL_PROXIES_ADD_HMAC_MYSQL =
            "ALTER TABLE rtp_network_proxies ADD COLUMN hmac VARCHAR(128)";

    private static final String DDL_BACKENDS = """
            CREATE TABLE IF NOT EXISTS rtp_network_backends (
                server_id            VARCHAR(64)   NOT NULL PRIMARY KEY,
                schema_version       INT           NOT NULL,
                plugin_state         VARCHAR(16)   NOT NULL,
                accepting_requests   BOOLEAN       NOT NULL,
                last_seen_ms         BIGINT        NOT NULL,
                mspt                 DOUBLE PRECISION NOT NULL,
                queue_depth          INT           NOT NULL,
                soft_cap             INT           NOT NULL,
                heap_used_bytes      BIGINT        NOT NULL,
                heap_max_bytes       BIGINT        NOT NULL,
                player_count         INT           NOT NULL,
                regions_available    VARCHAR(4000) NOT NULL,
                worlds_loaded        VARCHAR(4000) NOT NULL,
                kill_switch          BOOLEAN       NOT NULL DEFAULT FALSE,
                updated_at_ms        BIGINT        NOT NULL,
                hmac                 VARCHAR(128),
                kept_count             INT           NOT NULL DEFAULT 0,
                network_reserved_count INT           NOT NULL DEFAULT 0,
                regions                VARCHAR(4000) NOT NULL DEFAULT '',
                region_kept_counts     VARCHAR(4000) NOT NULL DEFAULT ''
            )
            """;

    private static final String DDL_BACKENDS_ADD_HMAC =
            "ALTER TABLE rtp_network_backends ADD COLUMN IF NOT EXISTS hmac VARCHAR(128)";
    private static final String DDL_BACKENDS_ADD_HMAC_MYSQL =
            "ALTER TABLE rtp_network_backends ADD COLUMN hmac VARCHAR(128)";

    private static final String DDL_BACKENDS_IDX =
            "CREATE INDEX IF NOT EXISTS idx_rtp_network_backends_last_seen "
                    + "ON rtp_network_backends(last_seen_ms)";

    /**
     * L6 cross-server fields (rtp-proxy region-aware selection): add the
     * {@code kept_count}, {@code network_reserved_count}, {@code regions}, and
     * {@code region_kept_counts} columns to pre-existing deployments so the
     * durable SQL tier carries the same heartbeat field set the shared
     * {@link io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec}
     * signs over (otherwise the read-side HMAC, recomputed from the persisted
     * columns, would mismatch and drop every signed row). Idempotent and
     * dialect-tolerant, like the {@code hmac} migration above. Defaults keep
     * legacy rows valid (zero / empty).
     */
    private static final String[] DDL_BACKENDS_ADD_L6 = {
            "ALTER TABLE rtp_network_backends ADD COLUMN IF NOT EXISTS kept_count INT NOT NULL DEFAULT 0",
            "ALTER TABLE rtp_network_backends ADD COLUMN IF NOT EXISTS network_reserved_count INT NOT NULL DEFAULT 0",
            "ALTER TABLE rtp_network_backends ADD COLUMN IF NOT EXISTS regions VARCHAR(4000) NOT NULL DEFAULT ''",
            "ALTER TABLE rtp_network_backends ADD COLUMN IF NOT EXISTS region_kept_counts VARCHAR(4000) NOT NULL DEFAULT ''"
    };
    private static final String[] DDL_BACKENDS_ADD_L6_MYSQL = {
            "ALTER TABLE rtp_network_backends ADD COLUMN kept_count INT NOT NULL DEFAULT 0",
            "ALTER TABLE rtp_network_backends ADD COLUMN network_reserved_count INT NOT NULL DEFAULT 0",
            "ALTER TABLE rtp_network_backends ADD COLUMN regions VARCHAR(4000) NOT NULL DEFAULT ''",
            "ALTER TABLE rtp_network_backends ADD COLUMN region_kept_counts VARCHAR(4000) NOT NULL DEFAULT ''"
    };

    private static final String DDL_TOKENS = """
            CREATE TABLE IF NOT EXISTS rtp_network_tokens (
                token_id         VARCHAR(64)  NOT NULL PRIMARY KEY,
                server_id        VARCHAR(64)  NOT NULL,
                player_id        VARCHAR(36)  NOT NULL,
                expires_at_ms    BIGINT       NOT NULL,
                state            VARCHAR(16)  NOT NULL,
                created_at_ms    BIGINT       NOT NULL,
                released_at_ms   BIGINT,
                hmac             VARCHAR(128),
                region_key       VARCHAR(64)
            )
            """;

    private static final String DDL_TOKENS_ADD_HMAC =
            "ALTER TABLE rtp_network_tokens ADD COLUMN IF NOT EXISTS hmac VARCHAR(128)";
    private static final String DDL_TOKENS_ADD_HMAC_MYSQL =
            "ALTER TABLE rtp_network_tokens ADD COLUMN hmac VARCHAR(128)";

    /**
     * Cross-server region support: add the {@code region_key} column to
     * pre-existing deployments so a {@code /rtp region=<server>:<region>}
     * request can persist its requested region on the reservation row and
     * the destination backend can re-attach it on arrival. Nullable;
     * legacy rows carry {@code region_key=NULL} (regionless request).
     */
    private static final String DDL_TOKENS_ADD_REGION =
            "ALTER TABLE rtp_network_tokens ADD COLUMN IF NOT EXISTS region_key VARCHAR(64)";
    private static final String DDL_TOKENS_ADD_REGION_MYSQL =
            "ALTER TABLE rtp_network_tokens ADD COLUMN region_key VARCHAR(64)";

    /**
     * Per-player active-state uniqueness. Postgres / SQLite / H2 honour the
     * partial predicate; MySQL ignores it but still enforces a wider
     * uniqueness on {@code player_id} which the binding tolerates because
     * terminal-state rows never share a {@code player_id} value with an
     * active row at the same instant (CLAIMED transitions are atomic and
     * release flips state to a terminal value).
     */
    private static final String DDL_TOKENS_IDX =
            "CREATE UNIQUE INDEX IF NOT EXISTS uq_rtp_network_tokens_active_player "
                    + "ON rtp_network_tokens(player_id)";

    // --- Slice D row D5: cross-server wait-queue tables ---------------------
    //
    // {@code rtp_net_wq_ready} holds the FIFO of envelopes awaiting proxy
    // dequeue. Order is established by {@code (enqueued_at_ms, correlation_id)}.
    // {@code state = 'READY'} for envelopes still waiting; the dequeue path
    // atomically flips to {@code 'CLAIMED'} (via UPDATE...WHERE state='READY'
    // returning row count) and reads the row by primary key. Terminal
    // transitions delete the row outright (see {@link
    // io.github.dailystruggle.rtp.proxy.common.transport.sql.SqlNetworkRequestQueue}).
    //
    // {@code rtp_net_wq_status} holds the per-player live status. Single-row-
    // per-player; UPSERTs on transition; row is deleted when the state
    // becomes terminal ({@code COMPLETED} / {@code FAILED} / {@code CANCELLED}).

    private static final String DDL_WQ_READY = """
            CREATE TABLE IF NOT EXISTS rtp_net_wq_ready (
                correlation_id   VARCHAR(36)  NOT NULL PRIMARY KEY,
                player_id        VARCHAR(36)  NOT NULL,
                region_key       VARCHAR(64),
                server_hint      VARCHAR(64),
                enqueued_at_ms   BIGINT       NOT NULL,
                state            VARCHAR(16)  NOT NULL DEFAULT 'READY',
                claimed_at_ms    BIGINT
            )
            """;

    private static final String DDL_WQ_READY_IDX_ORDER =
            "CREATE INDEX IF NOT EXISTS idx_rtp_net_wq_ready_order "
                    + "ON rtp_net_wq_ready(state, enqueued_at_ms, correlation_id)";

    private static final String DDL_WQ_READY_IDX_PLAYER =
            "CREATE INDEX IF NOT EXISTS idx_rtp_net_wq_ready_player "
                    + "ON rtp_net_wq_ready(player_id)";

    private static final String DDL_WQ_STATUS = """
            CREATE TABLE IF NOT EXISTS rtp_net_wq_status (
                player_id        VARCHAR(36)  NOT NULL PRIMARY KEY,
                correlation_id   VARCHAR(36),
                state            VARCHAR(16)  NOT NULL,
                position_in_queue INT         NOT NULL DEFAULT 0,
                server_id        VARCHAR(64),
                region_key       VARCHAR(64),
                reason           VARCHAR(255),
                updated_at_ms    BIGINT       NOT NULL
            )
            """;

    private SqlNetworkStateSchema() { /* static-only */ }

    /**
     * Run the idempotent DDL bootstrap. Each statement is wrapped in its own
     * try/execute block so a benign "already exists" failure on one dialect
     * doesn't abort the others.
     */
    public static void bootstrap(Connection conn) throws SQLException {
        execIgnoringDuplicate(conn, DDL_PROXIES);
        execIgnoringDuplicate(conn, DDL_BACKENDS);
        execIgnoringDuplicate(conn, DDL_BACKENDS_IDX);
        execIgnoringDuplicate(conn, DDL_TOKENS);
        execIgnoringDuplicate(conn, DDL_TOKENS_IDX);
        // A3 envelope migration for pre-A3 deployments. The fresh-create DDL
        // above already includes the column, so these ALTERs no-op on new DBs
        // (caught by execIgnoringDuplicate). On pre-A3 DBs they add the
        // nullable column without touching existing rows; legacy rows simply
        // carry hmac=NULL and are filtered out at read time when a verifier
        // is wired in (REQ-RTP-S-004, rtp-proxy-ADR-010).
        addColumnIgnoringMissing(conn, DDL_PROXIES_ADD_HMAC, DDL_PROXIES_ADD_HMAC_MYSQL);
        addColumnIgnoringMissing(conn, DDL_BACKENDS_ADD_HMAC, DDL_BACKENDS_ADD_HMAC_MYSQL);
        for (int i = 0; i < DDL_BACKENDS_ADD_L6.length; i++) {
            addColumnIgnoringMissing(conn, DDL_BACKENDS_ADD_L6[i], DDL_BACKENDS_ADD_L6_MYSQL[i]);
        }
        addColumnIgnoringMissing(conn, DDL_TOKENS_ADD_HMAC, DDL_TOKENS_ADD_HMAC_MYSQL);
        addColumnIgnoringMissing(conn, DDL_TOKENS_ADD_REGION, DDL_TOKENS_ADD_REGION_MYSQL);
        // Slice D row D5: wait-queue tables. Independent of the heartbeat /
        // reservation-token tables above; safe to bootstrap on every open
        // because the create statements are IF NOT EXISTS.
        execIgnoringDuplicate(conn, DDL_WQ_READY);
        execIgnoringDuplicate(conn, DDL_WQ_READY_IDX_ORDER);
        execIgnoringDuplicate(conn, DDL_WQ_READY_IDX_PLAYER);
        execIgnoringDuplicate(conn, DDL_WQ_STATUS);
    }

    /**
     * Run an {@code ALTER TABLE ... ADD COLUMN IF NOT EXISTS} idempotently.
     * H2/Postgres/SQLite support the {@code IF NOT EXISTS} clause directly;
     * MySQL/MariaDB do not, so we try the portable form first and fall back
     * to the bare {@code ADD COLUMN} on syntax error (SQLSTATE 42xxx). Both
     * paths swallow duplicate-column errors (SQLSTATE 42S21, MariaDB errno
     * 1060 surfaces as 42S21 via the JDBC driver).
     */
    private static void addColumnIgnoringMissing(Connection conn, String portable, String mysqlFallback)
            throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(portable);
            return;
        } catch (SQLException e) {
            String sqlState = e.getSQLState();
            if (sqlState != null && sqlState.equals("42S21")) return; // duplicate column - already migrated
            if (sqlState == null || !sqlState.startsWith("42")) throw e;
            // fall through to MySQL-style ADD COLUMN
        }
        try (Statement st = conn.createStatement()) {
            st.execute(mysqlFallback);
        } catch (SQLException e) {
            String sqlState = e.getSQLState();
            // 42S21: duplicate column (MySQL/MariaDB); 42xxx: ignore other
            // syntax-class errors so a vendor we haven't tested doesn't crash
            // the bootstrap. Misses surface as null hmac at read time.
            if (sqlState != null && (sqlState.equals("42S21") || sqlState.startsWith("42"))) return;
            throw e;
        }
    }

    private static void execIgnoringDuplicate(Connection conn, String ddl) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            // SQLSTATE 42S01 / 42P07 / 42000 cover "already exists" across MySQL / Postgres / H2.
            String sqlState = e.getSQLState();
            if (sqlState != null && (sqlState.startsWith("42") || sqlState.equals("X0Y32"))) {
                return;
            }
            throw e;
        }
    }
}
