package io.github.dailystruggle.rtp.proxy.common.transport;

import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfig;
import io.github.dailystruggle.rtp.proxy.common.config.NetworkConfigException;
import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkTransport;
import io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.spi.PlayerOwnershipTracker;
import io.github.dailystruggle.rtp.proxy.common.spi.WaitlistLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisPlayerOwnershipTracker;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.AlwaysLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.transport.memory.InMemoryNetworkWaitlist;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisLeaderLease;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisNetworkRequestQueue;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisNetworkStateBinding;
import io.github.dailystruggle.rtp.proxy.common.transport.redis.RedisNetworkWaitlist;
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

    /**
     * Construct the shared cross-proxy {@link NetworkWaitlist} matching
     * {@code cfg.transportType()}, or {@code null} when the operator has
     * disabled the waitlist subsystem via {@code network.waitlist.enabled = false}
     * (ADR-015 / REQ-RTP-NET-015). {@code sql} transport currently falls
     * back to in-memory with a WARNING since the SQL waitlist binding is
     * not yet implemented. {@code redis} open-time failures degrade to
     * in-memory per {@code MULTI_SERVER_PLAN.md} §Failure-Mode Policy, so
     * a misconfigured Redis host does not break the proxy boot.
     *
     * <p>The returned waitlist is owned by the caller; closing the
     * Redis-backed waitlist releases its {@link redis.clients.jedis.JedisPool}.
     * The in-memory waitlist does not implement {@link AutoCloseable} and
     * needs no teardown.</p>
     */
    public static NetworkWaitlist openWaitlist(NetworkConfig cfg, DataSource dataSource) {
        Objects.requireNonNull(cfg, "cfg");
        if (!cfg.waitlistEnabled()) {
            return null;
        }
        String t = cfg.transportType() == null ? "in-memory" : cfg.transportType().toLowerCase(Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new InMemoryNetworkWaitlist(cfg.waitlistMaxSize());
            case "sql":
                LOG.log(Level.WARNING,
                        "NetworkBindings.openWaitlist: transport.type=sql waitlist not yet implemented; "
                                + "falling back to in-memory waitlist for this proxy session "
                                + "(cross-proxy waitlist will not propagate via SQL).");
                return new InMemoryNetworkWaitlist(cfg.waitlistMaxSize());
            case "redis":
                try {
                    return new RedisNetworkWaitlist(
                            cfg.redisHost(), cfg.redisPort(), cfg.redisPassword(), cfg.waitlistMaxSize());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING,
                            "NetworkBindings.openWaitlist: redis waitlist open failed; "
                                    + "falling back to in-memory waitlist (cross-proxy waitlist "
                                    + "will not propagate via Redis): " + e.getMessage());
                    return new InMemoryNetworkWaitlist(cfg.waitlistMaxSize());
                }
            default:
                throw new IllegalArgumentException(
                        "NetworkBindings.openWaitlist: unrecognised transport.type '"
                                + cfg.transportType() + "' (expected 'in-memory', 'sql', or 'redis').");
        }
    }

    /**
     * Construct the {@link WaitlistLeaderLease} matching {@code cfg.transportType()},
     * or {@code null} when the operator has disabled the waitlist subsystem.
     * The {@code redis} kind constructs a {@link RedisLeaderLease} so multi-proxy
     * deployments mutually exclude drain pulses; other kinds fall back to
     * {@link AlwaysLeaderLease}, which is safe only on single-proxy installs.
     *
     * <p>Open-time failures on the Redis path degrade to {@link AlwaysLeaderLease}
     * with a WARNING log; a misconfigured Redis host therefore continues to
     * drain locally rather than freezing the waitlist subsystem.</p>
     */
    public static WaitlistLeaderLease openLeaderLease(NetworkConfig cfg, DataSource dataSource) {
        Objects.requireNonNull(cfg, "cfg");
        if (!cfg.waitlistEnabled()) {
            return null;
        }
        String t = cfg.transportType() == null ? "in-memory" : cfg.transportType().toLowerCase(Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
                return new AlwaysLeaderLease();
            case "sql":
                LOG.log(Level.WARNING,
                        "NetworkBindings.openLeaderLease: transport.type=sql leader lease not implemented; "
                                + "falling back to AlwaysLeaderLease (safe only on single-proxy installs).");
                return new AlwaysLeaderLease();
            case "redis":
                try {
                    return new RedisLeaderLease(cfg.redisHost(), cfg.redisPort(), cfg.redisPassword());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING,
                            "NetworkBindings.openLeaderLease: redis leader lease open failed; "
                                    + "falling back to AlwaysLeaderLease (safe only on single-proxy "
                                    + "installs): " + e.getMessage());
                    return new AlwaysLeaderLease();
                }
            default:
                throw new IllegalArgumentException(
                        "NetworkBindings.openLeaderLease: unrecognised transport.type '"
                                + cfg.transportType() + "' (expected 'in-memory', 'sql', or 'redis').");
        }
    }

    /**
     * Construct the {@link PlayerOwnershipTracker} matching {@code cfg.transportType()}
     * (rtp-proxy-ADR-016 / REQ-RTP-NET-016). Returns {@link PlayerOwnershipTracker#NO_OP}
     * for single-JVM transports (in-memory, sql) where ownership is implicit;
     * the trigger source then uses the legacy {@link NetworkRequestQueue#dequeueReady}
     * codepath. Redis open-time failures degrade to NO_OP with a WARNING.
     */
    public static PlayerOwnershipTracker openOwnershipTracker(NetworkConfig cfg) {
        Objects.requireNonNull(cfg, "cfg");
        String t = cfg.transportType() == null ? "in-memory" : cfg.transportType().toLowerCase(Locale.ROOT);
        switch (t) {
            case "in-memory":
            case "memory":
            case "sql":
                return PlayerOwnershipTracker.NO_OP;
            case "redis":
                try {
                    return new RedisPlayerOwnershipTracker(
                            cfg.redisHost(), cfg.redisPort(), cfg.redisPassword());
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING,
                            "NetworkBindings.openOwnershipTracker: redis tracker open failed; "
                                    + "falling back to NO_OP. Multi-proxy ownership filtering "
                                    + "will not function this session: " + e.getMessage());
                    return PlayerOwnershipTracker.NO_OP;
                }
            default:
                throw new IllegalArgumentException(
                        "NetworkBindings.openOwnershipTracker: unrecognised transport.type '"
                                + cfg.transportType() + "' (expected 'in-memory', 'sql', or 'redis').");
        }
    }
}
