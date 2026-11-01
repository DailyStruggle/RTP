package io.github.dailystruggle.rtp.common.bit.config;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.bit.Vocabulary;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.selection.region.util.DurationParser;
import io.github.dailystruggle.rtp.common.selection.region.util.TemporalUnit;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code network} config owner for {@code advanced/network.yml} (ADR-121 section 6; REQ-RTP-NET-002).
 *
 * <p>The file has no {@code ConfigParser}; accepted shapes and values mirror its readers
 * ({@code NetworkModeBootstrap}, {@code NetworkRouter.Mode}, {@code DispatchMode}, proxy
 * {@code NetworkConfig}). Faults that make the readers refuse or fail network mode are staged ERROR
 * while {@code network.enabled} is true and WARNING otherwise (the file is then ignored). Values
 * the readers silently replace with a default are WARNING. Never resolves env vars or opens sockets.
 */
public final class NetworkConfigCheck implements ConfigOwnerCheck {
    public static final String ID = "network";
    public static final String PATH = "advanced/network.yml";
    private static final String SUBSYSTEM = ConfigPbit.SUBSYSTEM;

    /** Top-level sections read by the backend and proxy readers. */
    public static final Set<String> KNOWN_TOP_LEVEL = Set.of("network", "transport", "heartbeat", "routing",
            "queue", "reservation", "regionCollision", "loadBalancer", "editor", "metrics", "servers", "version");
    /** {@code transport.type} values accepted by {@code NetworkModeBootstrap.openTransport}. */
    public static final Set<String> TRANSPORT_TYPES = Set.of("auto", "in-memory", "memory", "plugin-message",
            "pluginmessage", "proxy-cache", "proxycache", "proxy-direct", "proxydirect", "sql", "redis");
    /** {@code routing.mode} spellings {@code NetworkRouter.Mode.parse} maps (after {@code -} to {@code _}). */
    public static final Set<String> ROUTING_MODES = Set.of("local", "always", "cross_server", "crossserver",
            "cross", "remote", "auto", "smart");
    /** {@code routing.dispatchMode} spellings {@code DispatchMode.parse} maps (after {@code -} to {@code _}). */
    public static final Set<String> DISPATCH_MODES = Set.of("auto", "direct_db", "directdb", "direct",
            "proxy_broker", "proxybroker", "broker", "proxy");
    /** {@code network.role} values the proxy reader accepts. */
    public static final Set<String> ROLES = Set.of("auto", "proxy", "backend");
    /** {@code regionCollision.policy}: only {@code warn} is implemented; others fall back to it. */
    public static final Set<String> COLLISION_POLICIES = Set.of("warn");

    private static final List<String> DURATION_KEYS = List.of("heartbeat.intervalMs", "heartbeat.staleAfterMs",
            "queue.flushIntervalMs", "queue.pollIntervalMs", "queue.entryTtlMs", "reservation.reapIntervalMs",
            "reservation.ttlMs", "transport.connectTimeoutMs", "transport.readTimeoutMs");

    public static final BitCode KEY_UNKNOWN = RtpBitCodes.RTP.register(1701,
            "RTP_NETWORK_KEY_UNKNOWN", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Rename or remove the top-level key; network.yml readers ignore it.");
    public static final BitCode SECTION_NOT_MAP = RtpBitCodes.RTP.register(1702,
            "RTP_NETWORK_SECTION_NOT_MAP", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Make the section a YAML mapping; a scalar is read as an absent section (all defaults).");
    public static final BitCode SERVER_ID_MISSING = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1703,
            "RTP_NETWORK_SERVER_ID_MISSING", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Set network.serverId to this server's unique name; network mode is refused without it."));
    public static final BitCode TRANSPORT_UNKNOWN = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1704,
            "RTP_NETWORK_TRANSPORT_UNKNOWN", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Set transport.type to auto, plugin-message, proxy-cache, proxy-direct, in-memory, sql or redis."));
    public static final BitCode REDIS_HOST_MISSING = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1705,
            "RTP_NETWORK_REDIS_HOST_MISSING", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Set transport.redis.host (host or redis[s]:// URL) shared by every server."));
    public static final BitCode PROXIES_MISSING = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1706,
            "RTP_NETWORK_PROXIES_MISSING", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "List each proxy companion as host:port under transport.proxies."));
    public static final BitCode PORT_RANGE = BitSeverityPolicy.stagedConfig(RtpBitCodes.RTP.register(1707,
            "RTP_NETWORK_PORT_RANGE", Level.ERROR, BitTier.PBIT, SUBSYSTEM, "",
            "Use a TCP port between 1 and 65535."));
    public static final BitCode DURATION_INVALID = RtpBitCodes.RTP.register(1708,
            "RTP_NETWORK_DURATION_INVALID", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Use a number of milliseconds or a duration with a unit (e.g. 500, 5s, 1m); invalid values fall back to the default.");
    public static final BitCode STALE_BEFORE_INTERVAL = RtpBitCodes.RTP.register(1709,
            "RTP_NETWORK_STALE_BEFORE_INTERVAL", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Keep heartbeat.staleAfterMs above heartbeat.intervalMs, or live peers flap to stale between beats.");
    public static final BitCode VALUE_UNKNOWN = RtpBitCodes.RTP.register(1710,
            "RTP_NETWORK_VALUE_UNKNOWN", Level.WARNING, BitTier.PBIT, SUBSYSTEM, "",
            "Use one of the listed values; unrecognised values silently fall back to the default.");

    public static final NetworkConfigCheck INSTANCE = new NetworkConfigCheck();

    private NetworkConfigCheck() {}

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean owns(String path) {
        return PATH.equals(path);
    }

    @Override
    public List<BitFinding> check(ConfigSnapshot snapshot, ConfigEnvironment environment) {
        if (snapshot == null) return List.of();
        ConfigSnapshot.Document document = snapshot.document(PATH).orElse(null);
        if (document == null) return List.of();
        MessageKeyParity.ResourceReader bundled = environment == null ? null
                : MessageKeyParity.ResourceReader.classpath(environment.resources());
        return check(document.values(), bundled == null ? Set.of() : MessageKeyParity.keys(bundled, PATH));
    }

    /** Checks a parsed {@code network.yml} top level; {@code bundledKeys} extends the known sections. */
    public static List<BitFinding> check(Map<String, Object> root, Set<String> bundledKeys) {
        List<BitFinding> findings = new ArrayList<>();
        Set<String> known = new LinkedHashSet<>(KNOWN_TOP_LEVEL);
        if (bundledKeys != null) known.addAll(bundledKeys);
        Vocabulary vocabulary = new Vocabulary("network.yml section", known);
        for (Map.Entry<String, Object> entry : root.entrySet()) {
            String key = entry.getKey();
            if (!known.contains(key)) {
                findings.add(BitFinding.of(KEY_UNKNOWN, PATH, key, "Unknown top-level key: " + key,
                        String.join(", ", new TreeSet<>(known)),
                        vocabulary.suggest(key).map(s -> "did you mean " + s + "?").orElse(KEY_UNKNOWN.remediation())));
            } else if (!"version".equals(key) && entry.getValue() != null && !isMapping(entry.getValue())) {
                findings.add(BitFinding.of(SECTION_NOT_MAP, PATH, key, "Section is not a mapping: " + key,
                        "a mapping", SECTION_NOT_MAP.remediation()));
            }
        }

        Map<String, Object> network = section(root, "network");
        Map<String, Object> transport = section(root, "transport");
        Map<String, Object> heartbeat = section(root, "heartbeat");
        Map<String, Object> routing = section(root, "routing");
        boolean enabled = bool(network.get("enabled"));
        Level fatal = enabled ? Level.ERROR : Level.WARNING;
        String disabledNote = enabled ? "" : " (network.enabled is false, so the file is ignored today)";

        if (enabled && blank(network.get("serverId"))) {
            findings.add(BitFinding.of(SERVER_ID_MISSING, PATH, "network.serverId",
                    "network.enabled is true but network.serverId is empty; network mode is refused",
                    "a unique non-empty server name", SERVER_ID_MISSING.remediation()));
        }

        Object typeRaw = transport.get("type");
        String type = typeRaw == null ? "in-memory" : String.valueOf(typeRaw).trim().toLowerCase(Locale.ROOT);
        if (!TRANSPORT_TYPES.contains(type)) {
            findings.add(BitFinding.of(TRANSPORT_UNKNOWN, fatal, PATH, "transport.type",
                    "Unrecognised transport.type '" + typeRaw + "'; the transport cannot open" + disabledNote,
                    String.join(", ", new TreeSet<>(TRANSPORT_TYPES)),
                    new Vocabulary("transport.type", TRANSPORT_TYPES).suggest(type)
                            .map(s -> "did you mean " + s + "?").orElse(TRANSPORT_UNKNOWN.remediation())));
        }

        Map<String, Object> redis = section(transport, "redis");
        if ("redis".equals(type) && blank(redis.getOrDefault("host", "localhost"))) {
            findings.add(BitFinding.of(REDIS_HOST_MISSING, fatal, PATH, "transport.redis.host",
                    "transport.type is redis but transport.redis.host is empty" + disabledNote,
                    "a host or redis[s]:// URL", REDIS_HOST_MISSING.remediation()));
        }
        if (("proxy-direct".equals(type) || "proxydirect".equals(type)) && emptyList(transport.get("proxies"))) {
            findings.add(BitFinding.of(PROXIES_MISSING, fatal, PATH, "transport.proxies",
                    "transport.type is proxy-direct but transport.proxies is empty" + disabledNote,
                    "a list of host:port entries", PROXIES_MISSING.remediation()));
        }
        Object port = redis.get("port");
        if (port != null) {
            Long value = integer(port);
            if (value == null || value < 1 || value > 65535) {
                Level level = enabled && "redis".equals(type) ? Level.ERROR : Level.WARNING;
                findings.add(BitFinding.of(PORT_RANGE, level, PATH, "transport.redis.port",
                        "transport.redis.port is not a valid TCP port: " + port, "1..65535", PORT_RANGE.remediation()));
            }
        }

        Long intervalMs = null;
        Long staleAfterMs = null;
        for (String dotted : DURATION_KEYS) {
            int dot = dotted.indexOf('.');
            Object raw = section(root, dotted.substring(0, dot)).get(dotted.substring(dot + 1));
            if (raw == null) continue;
            Long millis = durationMs(raw);
            if (millis == null) {
                findings.add(BitFinding.of(DURATION_INVALID, PATH, dotted, "Not a duration: " + raw,
                        "milliseconds or a duration such as 5s", DURATION_INVALID.remediation()));
            } else if ("heartbeat.intervalMs".equals(dotted)) {
                intervalMs = millis;
            } else if ("heartbeat.staleAfterMs".equals(dotted)) {
                staleAfterMs = millis;
            }
        }
        long interval = intervalMs == null || intervalMs <= 0 ? 1000L : intervalMs;
        long stale = staleAfterMs == null || staleAfterMs <= 0 ? 5000L : staleAfterMs;
        if ((heartbeat.containsKey("intervalMs") || heartbeat.containsKey("staleAfterMs")) && stale <= interval) {
            findings.add(BitFinding.of(STALE_BEFORE_INTERVAL, PATH, "heartbeat.staleAfterMs",
                    "heartbeat.staleAfterMs (" + stale + " ms) is not above heartbeat.intervalMs (" + interval + " ms)",
                    "> " + interval + " ms", STALE_BEFORE_INTERVAL.remediation()));
        }

        enumValue(findings, routing.get("mode"), "routing.mode", ROUTING_MODES, true, "local");
        enumValue(findings, routing.get("dispatchMode"), "routing.dispatchMode", DISPATCH_MODES, true, "auto");
        enumValue(findings, network.get("role"), "network.role", ROLES, false, "auto");
        enumValue(findings, section(root, "regionCollision").get("policy"), "regionCollision.policy",
                COLLISION_POLICIES, false, "warn");
        return findings;
    }

    private static void enumValue(List<BitFinding> findings, Object raw, String path, Set<String> allowed,
                                  boolean underscores, String fallback) {
        if (raw == null) return;
        String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
        if (underscores) value = value.replace('-', '_');
        if (allowed.contains(value)) return;
        Vocabulary vocabulary = new Vocabulary(path, allowed);
        findings.add(BitFinding.of(VALUE_UNKNOWN, PATH, path,
                "Unrecognised " + path + " '" + raw + "'; the reader uses '" + fallback + "' instead",
                String.join(", ", new TreeSet<>(allowed)),
                vocabulary.suggest(value).map(s -> "did you mean " + s + "?").orElse(VALUE_UNKNOWN.remediation())));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        if (value instanceof RtpYamlSection yaml) return yaml.getValues(false);
        return Map.of();
    }

    private static boolean isMapping(Object value) {
        return value instanceof Map<?, ?> || value instanceof RtpYamlSection;
    }

    private static boolean bool(Object value) {
        if (value instanceof Boolean b) return b;
        return value != null && Boolean.parseBoolean(String.valueOf(value).trim());
    }

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static boolean emptyList(Object value) {
        if (value instanceof List<?> list) return list.isEmpty();
        return blank(value);
    }

    private static Long integer(Object value) {
        if (value instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) ? n.longValue() : null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Same acceptance as {@code NetworkModeBootstrap.durationMs}; {@code null} when the reader would reject it. */
    private static Long durationMs(Object raw) {
        DurationParser.ParsedDuration parsed = DurationParser.parse(String.valueOf(raw), TemporalUnit.MILLISECOND);
        double millis = parsed == null ? Double.NaN : parsed.toMillis();
        if (!Double.isFinite(millis) || millis < -0x1.0p63 || millis >= 0x1.0p63) return null;
        return Math.round(millis);
    }
}
