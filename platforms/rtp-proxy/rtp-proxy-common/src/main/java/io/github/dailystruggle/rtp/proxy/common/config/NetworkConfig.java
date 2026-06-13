package io.github.dailystruggle.rtp.proxy.common.config;

import io.github.dailystruggle.rtp.proxy.common.RTPProxyAccessor;
import io.github.dailystruggle.rtp.proxy.common.Role;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Parsed, validated view of {@code network.yml}. Only the
 * keys the adapter shell + heartbeat publisher need today. The full closed
 * schema lives in rtp-proxy-ADR-002; additional sections land as the
 * features that consume them ship (transport binding, load balancer,
 * reservation TTL, etc.).
 *
 * <p>Construction goes through {@link #fromMap(Map, RTPProxyAccessor)}: the
 * adapter parses {@code network.yml} into a nested {@code Map<String,Object>}
 * (its own YAML library choice; {@code rtp-proxy-common} is vendor-free) and
 * hands the map plus the registered accessor here. The accessor supplies the
 * adapter's hard-coded {@link Role} for {@code role: auto} resolution per
 * ADR-013.</p>
 *
 * <p>Validation is fail-fast: an invalid combination throws
 * {@link NetworkConfigException}; the adapter is expected to log a configurable
 * WARNING and leave the host running with {@code enabled} treated as false
 * (REQ-RTP-NET-002 parity).</p>
 */
public final class NetworkConfig {

    private final boolean enabled;
    private final int schemaVersion;
    private final String serverId;
    private final String proxyId;
    private final Role role;
    private final String secretEnv;
    private final String transportType;
    private final long heartbeatIntervalMs;
    private final long heartbeatStaleAfterMs;
    private final String redisHost;
    private final int redisPort;
    private final String redisPassword;
    private final long reservationReapIntervalMs;
    private final boolean waitlistEnabled;
    private final int waitlistMaxSize;
    private final long waitlistDrainIntervalMs;
    private final long waitlistDrainPauseMs;
    private final long waitlistLeaderLeaseHoldMs;
    private final long waitlistReapIntervalMs;
    private final long waitlistReapMaxAgeMs;

    private NetworkConfig(Builder b) {
        this.enabled = b.enabled;
        this.schemaVersion = b.schemaVersion;
        this.serverId = b.serverId;
        this.proxyId = b.proxyId;
        this.role = Objects.requireNonNull(b.role, "role");
        this.secretEnv = b.secretEnv;
        this.transportType = Objects.requireNonNull(b.transportType, "transportType");
        this.heartbeatIntervalMs = b.heartbeatIntervalMs;
        this.heartbeatStaleAfterMs = b.heartbeatStaleAfterMs;
        this.redisHost = b.redisHost;
        this.redisPort = b.redisPort;
        this.redisPassword = b.redisPassword;
        this.reservationReapIntervalMs = b.reservationReapIntervalMs;
        this.waitlistEnabled = b.waitlistEnabled;
        this.waitlistMaxSize = b.waitlistMaxSize;
        this.waitlistDrainIntervalMs = b.waitlistDrainIntervalMs;
        this.waitlistDrainPauseMs = b.waitlistDrainPauseMs;
        this.waitlistLeaderLeaseHoldMs = b.waitlistLeaderLeaseHoldMs;
        this.waitlistReapIntervalMs = b.waitlistReapIntervalMs;
        this.waitlistReapMaxAgeMs = b.waitlistReapMaxAgeMs;
    }

    public boolean enabled() { return enabled; }
    public int schemaVersion() { return schemaVersion; }
    public String serverId() { return serverId; }
    public String proxyId() { return proxyId; }
    public Role role() { return role; }
    public String secretEnv() { return secretEnv; }
    public String transportType() { return transportType; }
    public long heartbeatIntervalMs() { return heartbeatIntervalMs; }
    public long heartbeatStaleAfterMs() { return heartbeatStaleAfterMs; }
    public String redisHost() { return redisHost; }
    public int redisPort() { return redisPort; }
    public String redisPassword() { return redisPassword; }

    /**
     * Periodic sweep cadence for {@code ReservationTokenReaper}
     * (REQ-RTP-NET-011). Defaults to 30 seconds. Must be a positive value;
     * non-positive values are clamped to the default at parse time so a
     * misconfigured {@code reservation.reapIntervalMs: 0} cannot disable
     * reaping silently.
     */
    public long reservationReapIntervalMs() { return reservationReapIntervalMs; }

    /**
     * ADR-015 / REQ-RTP-NET-015. When {@code true}, the proxy-side dispatcher
     * parks no-backend envelopes on a shared {@link io.github.dailystruggle.rtp.proxy.common.spi.NetworkWaitlist}
     * instead of resolving as terminal {@code FAILED}, and a periodic
     * {@link io.github.dailystruggle.rtp.proxy.common.dispatch.NetworkWaitlistDrainer}
     * pulse reinjects parked envelopes as capacity appears.
     */
    public boolean waitlistEnabled() { return waitlistEnabled; }

    /** Hard cap on the shared waitlist; over-cap enrolments reject as {@code REJECTED_FULL}. */
    public int waitlistMaxSize() { return waitlistMaxSize; }

    /** Drainer pulse cadence in millis; should track {@link #heartbeatIntervalMs()}. */
    public long waitlistDrainIntervalMs() { return waitlistDrainIntervalMs; }

    /** Drainer backoff window after a total-failure pulse (ADR-015). */
    public long waitlistDrainPauseMs() { return waitlistDrainPauseMs; }

    /** Leader-lease TTL in millis; lease is re-extended on every successful pulse. */
    public long waitlistLeaderLeaseHoldMs() { return waitlistLeaderLeaseHoldMs; }

    /** Reaper pulse cadence in millis; catches orphan entries from crashed proxies. */
    public long waitlistReapIntervalMs() { return waitlistReapIntervalMs; }

    /** Maximum age before an entry is reaped on quit-event miss. */
    public long waitlistReapMaxAgeMs() { return waitlistReapMaxAgeMs; }

    /**
     * Parse a raw {@code network.yml} document into a {@code NetworkConfig}.
     *
     * <p>Fail-fast rules enforced here (ADR-002 §Validation Rules):
     * <ul>
     *   <li>{@code role: auto} resolves via {@code accessor.role()}.</li>
     *   <li>If the resolved role is a proxy, {@code network.proxyId} must be
     *       a non-empty string.</li>
     *   <li>If {@code network.enabled: true} and {@code network.secretEnv}
     *       names an unset environment variable, fail.</li>
     * </ul>
     * Other validation (closed-schema rejection of unknown keys, weight
     * ranges, schemaVersion negotiation) deferred to later phases.
     */
    public static NetworkConfig fromMap(Map<String, Object> root, RTPProxyAccessor accessor) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(accessor, "accessor");

        Map<String, Object> network   = asMap(root, "network");
        Map<String, Object> transport = asMap(root, "transport");
        Map<String, Object> heartbeat = asMap(root, "heartbeat");

        Builder b = new Builder();
        b.enabled       = asBool(network, "enabled", false);
        b.schemaVersion = asInt(network, "schemaVersion", 1);
        b.serverId      = asStringOrNull(network, "serverId");
        b.proxyId       = asStringOrNull(network, "proxyId");
        b.secretEnv     = asString(network, "secretEnv", "RTP_NET_SECRET");

        String roleStr = asString(network, "role", "auto").toLowerCase(Locale.ROOT);
        Role declared;
        switch (roleStr) {
            case "auto":
                declared = accessor.role();
                break;
            case "proxy":
                declared = accessor.role();
                if (declared == Role.BACKEND) {
                    throw new NetworkConfigException(
                            "network.role=proxy but the registered RTPProxyAccessor reports BACKEND; "
                                    + "the loaded jar disagrees with the configured role.");
                }
                break;
            case "backend":
                declared = Role.BACKEND;
                break;
            default:
                throw new NetworkConfigException("network.role: unrecognised value '" + roleStr + "'");
        }
        b.role = declared;

        b.transportType         = asString(transport, "type", "in-memory");
        b.heartbeatIntervalMs   = asLong(heartbeat, "intervalMs", 1000L);
        b.heartbeatStaleAfterMs = asLong(heartbeat, "staleAfterMs", 5000L);

        Map<String, Object> redis = asMap(transport, "redis");
        b.redisHost     = asString(redis, "host", "localhost");
        b.redisPort     = asInt(redis, "port", 6379);
        b.redisPassword = asStringOrNull(redis, "password");

        // REQ-RTP-NET-011: reservation token reaper cadence. Defaults to 30s;
        // non-positive values clamp to the default so a misconfigured zero/
        // negative knob cannot silently disable reaping.
        Map<String, Object> reservation = asMap(root, "reservation");
        long reapMs = asLong(reservation, "reapIntervalMs", 30_000L);
        b.reservationReapIntervalMs = reapMs > 0 ? reapMs : 30_000L;

        // ADR-015 / REQ-RTP-NET-015: shared cross-proxy waitlist knobs.
        // Non-positive cadences clamp to the default so a misconfigured zero
        // cannot silently disable the drainer/reaper.
        Map<String, Object> waitlist = asMap(network, "waitlist");
        b.waitlistEnabled = asBool(waitlist, "enabled", true);
        int maxSize = asInt(waitlist, "maxSize", 1024);
        b.waitlistMaxSize = maxSize > 0 ? maxSize : 1024;
        long drainMs = asLong(waitlist, "drainIntervalMs", 1000L);
        b.waitlistDrainIntervalMs = drainMs > 0 ? drainMs : 1000L;
        long pauseMs = asLong(waitlist, "drainPauseMs", 2000L);
        b.waitlistDrainPauseMs = pauseMs >= 0 ? pauseMs : 2000L;
        long leaseMs = asLong(waitlist, "leaderLeaseHoldMs", 5000L);
        b.waitlistLeaderLeaseHoldMs = leaseMs > 0 ? leaseMs : 5000L;
        long reapEveryMs = asLong(waitlist, "reapIntervalMs", 10_000L);
        b.waitlistReapIntervalMs = reapEveryMs > 0 ? reapEveryMs : 10_000L;
        long reapMaxMs = asLong(waitlist, "reapMaxAgeMs", 60_000L);
        b.waitlistReapMaxAgeMs = reapMaxMs > 0 ? reapMaxMs : 60_000L;

        // Fail-fast: redis transport requires a host.
        if (b.enabled && "redis".equalsIgnoreCase(b.transportType)) {
            if (b.redisHost == null || b.redisHost.isEmpty()) {
                throw new NetworkConfigException(
                        "transport.type=redis requires transport.redis.host to be set; "
                                + "refusing to enable network mode.");
            }
        }

        // proxyId is optional: when role resolves to a proxy and no proxyId is
        // configured, default to the local hostname. A Velocity/BungeeCord proxy
        // has no intrinsic self-name, so an operator running a single proxy need
        // not invent one; multi-proxy deployments should still set proxyId
        // explicitly so each proxy has a stable, human-readable identity.
        if (declared == Role.PROXY_VELOCITY || declared == Role.PROXY_BUNGEE) {
            if (b.proxyId == null || b.proxyId.isEmpty()) {
                b.proxyId = defaultProxyId();
            }
        }

        // secretEnv check only when enabled.
        if (b.enabled) {
            String env = System.getenv(b.secretEnv);
            if (env == null || env.isEmpty()) {
                throw new NetworkConfigException(
                        "network.secretEnv='" + b.secretEnv
                                + "' is unset or empty in the environment; cannot enable network mode (REQ-RTP-PROXY-007).");
            }
        }

        return new NetworkConfig(b);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Map<String, Object> parent, String key) {
        Object v = parent.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        return Map.of();
    }

    private static String asString(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v == null ? def : String.valueOf(v);
    }

    private static String asStringOrNull(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v == null) return null;
        String s = String.valueOf(v);
        return s.isEmpty() ? null : s;
    }

    private static boolean asBool(Map<String, Object> m, String key, boolean def) {
        Object v = m.get(key);
        if (v instanceof Boolean) return (Boolean) v;
        if (v == null) return def;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    private static int asInt(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v == null) return def;
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new NetworkConfigException(key + ": expected integer, got '" + v + "'"); }
    }

    private static long asLong(Map<String, Object> m, String key, long def) {
        Object v = m.get(key);
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return def;
        try { return Long.parseLong(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new NetworkConfigException(key + ": expected long, got '" + v + "'"); }
    }

    /**
     * Best-effort stable identity for a proxy that did not configure
     * {@code network.proxyId}. Uses the local hostname; falls back to
     * {@code "proxy"} if the hostname cannot be resolved. Never returns null
     * or empty so the proxy-role contract (non-empty proxyId) still holds.
     */
    private static String defaultProxyId() {
        try {
            String host = java.net.InetAddress.getLocalHost().getHostName();
            if (host != null && !host.isEmpty()) {
                return host;
            }
        } catch (java.net.UnknownHostException | RuntimeException ignored) {
            // fall through to the static default
        }
        return "proxy";
    }

    /** Mutable builder used internally by {@link #fromMap}. */
    private static final class Builder {
        boolean enabled;
        int schemaVersion;
        String serverId;
        String proxyId;
        Role role;
        String secretEnv;
        String transportType;
        long heartbeatIntervalMs;
        long heartbeatStaleAfterMs;
        String redisHost;
        int redisPort;
        String redisPassword;
        long reservationReapIntervalMs;
        boolean waitlistEnabled;
        int waitlistMaxSize;
        long waitlistDrainIntervalMs;
        long waitlistDrainPauseMs;
        long waitlistLeaderLeaseHoldMs;
        long waitlistReapIntervalMs;
        long waitlistReapMaxAgeMs;
    }
}
