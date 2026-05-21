package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfigException;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.transport.sql.SqlNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.sql.SqlNetworkStateBinding;

import javax.sql.DataSource;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single entry-point for picking a {@link NetworkTransport} implementation
 * from a {@link NetworkConfig#transportType()} string. Every host adapter
 * (Velocity, BungeeCord, and the backend-side wiring in
 * {@code RTPBukkitPlugin}) routes through {@link #open(NetworkConfig, DataSource)}
 * so the choice of binding ("in-memory" vs "sql" vs "redis") is one config
 * key, not a wiring decision baked into each adapter.
 *
 * <p>Pinned by rtp-proxy-ADR-011 (Proposed) §Factory Placement. The factory
 * lives in {@code rtp-proxy-common} so vendor-free callers can consume it;
 * SQL drivers are supplied by the host's database accessor pool, not bundled
 * here.</p>
 *
 * <p>{@code redis} is recognised but currently throws
 * {@link UnsupportedOperationException}; it ships in Phase 2e-Redis.</p>
 */
public final class NetworkBindings {

    private static final Logger LOG = Logger.getLogger(NetworkBindings.class.getName());

    private NetworkBindings() { /* static-only */ }

    /**
     * Construct a transport binding from {@code cfg.transportType()}.
     *
     * @param cfg        validated config (must have {@code enabled() == true})
     * @param dataSource shared JDBC source for the {@code sql} binding;
     *                   may be {@code null} for {@code in-memory}
     * @return an opened {@link NetworkTransport}; the caller owns
     *         {@link NetworkTransport#close()}
     */
    public static NetworkTransport open(NetworkConfig cfg, DataSource dataSource) {
        Objects.requireNonNull(cfg, "cfg");
        String t = cfg.transportType() == null ? "in-memory" : cfg.transportType().toLowerCase(Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new InMemoryNetworkStateBinding();
            case "sql":
                if (dataSource == null) {
                    throw new IllegalArgumentException(
                            "NetworkBindings.open: transport.type=sql requires a non-null DataSource "
                                    + "(typically the host's AbstractSQLDatabaseAccessor pool).");
                }
                // Phase 2e-SQL A3: load the HMAC envelope verifier the same
                // way as the Redis branch. Loader failure degrades-to-disabled
                // (InMemory fallback) per MULTI_SERVER_PLAN.md §Failure-Mode
                // Policy (network-mode bootstrap).
                HmacVerifier sqlVerifier;
                try {
                    sqlVerifier = HmacVerifier.loadFromEnv(
                            cfg.secretEnv(), cfg.schemaVersion(), cfg.schemaVersion());
                } catch (NetworkConfigException e) {
                    LOG.log(Level.WARNING,
                            "NetworkBindings.open: HMAC verifier load failed for sql transport; "
                                    + "falling back to in-memory transport (network disabled): "
                                    + e.getMessage());
                    return new InMemoryNetworkStateBinding();
                }
                return new SqlNetworkStateBinding(
                        dataSource, cfg.heartbeatIntervalMs(),
                        sqlVerifier, cfg.schemaVersion());
            case "redis":
                // Phase 2e-Redis A3: heartbeats + snapshot + pub/sub fan-out +
                // atomic claim + HMAC envelope. Verifier is constructed from
                // network.secretEnv (REQ-RTP-PROXY-007); loader failure is the
                // single fail-fast on the security path per
                // MULTI_SERVER_PLAN.md §Failure-Mode Policy - other Redis-side
                // faults (connect, SCRIPT LOAD, pub/sub) degrade-to-disabled.
                HmacVerifier verifier;
                try {
                    verifier = HmacVerifier.loadFromEnv(
                            cfg.secretEnv(), cfg.schemaVersion(), cfg.schemaVersion());
                } catch (NetworkConfigException e) {
                    LOG.log(Level.WARNING,
                            "NetworkBindings.open: HMAC verifier load failed; "
                                    + "falling back to in-memory transport (network disabled): "
                                    + e.getMessage());
                    return new InMemoryNetworkStateBinding();
                }
                return new RedisNetworkStateBinding(
                        cfg.redisHost(), cfg.redisPort(), cfg.redisPassword(),
                        cfg.heartbeatIntervalMs(), verifier, cfg.schemaVersion());
            default:
                throw new IllegalArgumentException(
                        "NetworkBindings.open: unrecognised transport.type '" + cfg.transportType()
                                + "' (expected 'in-memory', 'sql', or 'redis').");
        }
    }

    /**
     * Backwards-compatible overload: equivalent to
     * {@link #openRequestQueue(NetworkConfig, DataSource)} with
     * {@code dataSource = null}. {@code sql} transport requires a non-null
     * DataSource and will throw {@link IllegalArgumentException}; callers
     * that may need SQL should use the 2-arg form.
     */
    public static NetworkRequestQueue openRequestQueue(NetworkConfig cfg) {
        return openRequestQueue(cfg, null);
    }

    /**
     * Construct a {@link NetworkRequestQueue} matching {@code cfg.transportType()}.
     * Slice D rows D2/D4/D5 of {@code CHECKLIST-cross-server-rtp-L6.md}.
     *
     * <p>The {@code in-memory} kind returns {@link InMemoryNetworkRequestQueue}.
     * The {@code sql} kind constructs {@link SqlNetworkRequestQueue} against
     * the supplied {@link DataSource} (typically the host's shared
     * {@code AbstractSQLDatabaseAccessor} pool per rtp-proxy-ADR-011
     * §HikariCP Pool Sharing); a null DataSource throws
     * {@link IllegalArgumentException}. The {@code redis} kind constructs
     * {@link RedisNetworkRequestQueue}; open-time failures degrade to
     * in-memory per {@code MULTI_SERVER_PLAN.md} §Failure-Mode Policy.</p>
     *
     * @param cfg        validated config (must have {@code enabled() == true})
     * @param dataSource shared JDBC source for the {@code sql} branch;
     *                   may be {@code null} for {@code in-memory} / {@code redis}
     * @return an opened {@link NetworkRequestQueue}; the caller owns its
     *         lifecycle
     */
    public static NetworkRequestQueue openRequestQueue(NetworkConfig cfg, DataSource dataSource) {
        Objects.requireNonNull(cfg, "cfg");
        String t = cfg.transportType() == null ? "in-memory" : cfg.transportType().toLowerCase(Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new InMemoryNetworkRequestQueue();
            case "sql":
                if (dataSource == null) {
                    throw new IllegalArgumentException(
                            "NetworkBindings.openRequestQueue: transport.type=sql requires a "
                                    + "non-null DataSource (typically the host's "
                                    + "AbstractSQLDatabaseAccessor pool).");
                }
                try {
                    return new SqlNetworkRequestQueue(dataSource);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING,
                            "NetworkBindings.openRequestQueue: sql queue open failed; "
                                    + "falling back to in-memory queue (cross-server requests "
                                    + "will not propagate): " + e.getMessage());
                    return new InMemoryNetworkRequestQueue();
                }
            case "redis":
                // Slice D row D4: terminal transitions in the D3 scripts
                // already delete per-envelope and per-status HASHes; passing
                // ttlSeconds = 0 disables EXPIRE so entries persist until
                // their terminal COMPLETED/FAILED/CANCELLED transition.
                // Operators who want passive aging on a crashed-backend
                // path should later wire a `network.queue.ttlSeconds` knob;
                // tracked under MULTI_SERVER_PLAN.md Phase 3.
                try {
                    return new RedisNetworkRequestQueue(
                            cfg.redisHost(), cfg.redisPort(), cfg.redisPassword(), 0);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING,
                            "NetworkBindings.openRequestQueue: redis queue open failed; "
                                    + "falling back to in-memory queue (cross-server requests "
                                    + "will not propagate): " + e.getMessage());
                    return new InMemoryNetworkRequestQueue();
                }
            default:
                throw new IllegalArgumentException(
                        "NetworkBindings.openRequestQueue: unrecognised transport.type '"
                                + cfg.transportType() + "' (expected 'in-memory', 'sql', or 'redis').");
        }
    }
}
