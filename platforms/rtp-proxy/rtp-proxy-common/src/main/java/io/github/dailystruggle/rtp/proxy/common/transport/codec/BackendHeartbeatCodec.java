package io.github.dailystruggle.rtp.proxy.common.transport.codec;

import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat.PluginState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shared, transport-agnostic flat-field encode/decode for {@link BackendHeartbeat}.
 *
 * <p>Lifted verbatim from {@code RedisNetworkStateBinding}'s previously-private
 * {@code encodeBackend} / {@code canonicalBackend} / {@code decodeBackend} so the
 * Redis, SQL, and plugin-message transports all carry an identical field set and
 * byte ordering. The codec is intentionally HMAC-agnostic: the canonical byte
 * sequence it produces is exactly what the Redis HMAC layer signs over, so the
 * signing layer can wrap this output without the codec knowing about it.</p>
 *
 * <p>Field order is fixed; both encode (sign) and decode (verify) must reconstruct
 * the canonical byte sequence in exactly this order. A {@code HGETALL}-read returns
 * a plain {@code HashMap} with arbitrary iteration order, so canonical bytes must be
 * derived from the fixed order here rather than the parsed map's iteration order
 * (REQ-RTP-S-004).</p>
 */
public final class BackendHeartbeatCodec {

    private BackendHeartbeatCodec() {
    }

    /**
     * Encodes a heartbeat to the canonical, newline-delimited flat-field form
     * (no HMAC line). This is the byte sequence the Redis HMAC layer signs over.
     */
    public static String encode(BackendHeartbeat r) {
        return canonical(toFieldMap(r));
    }

    /**
     * Decodes a canonical flat-field payload back into a heartbeat. Any trailing
     * {@code hmac} key is ignored (HMAC verification is the caller's concern).
     * Returns {@code null} for null/empty or malformed payloads.
     */
    public static BackendHeartbeat decode(String payload) {
        if (payload == null || payload.isEmpty()) return null;
        return fromFieldMap(parseFlat(payload));
    }

    /** Builds the fixed-order field map for a heartbeat (insertion order is canonical). */
    public static Map<String, String> toFieldMap(BackendHeartbeat r) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("serverId", r.serverId());
        m.put("schemaVersion", Integer.toString(r.schemaVersion()));
        m.put("pluginState", r.pluginState().name());
        m.put("acceptingRequests", Boolean.toString(r.acceptingRequests()));
        m.put("lastSeenEpochMs", Long.toString(r.lastSeenEpochMs()));
        m.put("mspt", Double.toString(r.mspt()));
        m.put("queueDepth", Integer.toString(r.queueDepth()));
        m.put("softCap", Integer.toString(r.softCap()));
        m.put("heapUsedBytes", Long.toString(r.heapUsedBytes()));
        m.put("heapMaxBytes", Long.toString(r.heapMaxBytes()));
        m.put("playerCount", Integer.toString(r.playerCount()));
        m.put("regionsAvailable", joinList(r.regionsAvailable()));
        m.put("worldsLoaded", joinList(r.worldsLoaded()));
        m.put("killSwitch", Boolean.toString(r.killSwitch()));
        // L6 fields, appended after killSwitch to preserve the pre-L6 canonical
        // byte prefix (older rows simply omit them and decode to zero/empty).
        m.put("keptCount", Integer.toString(r.keptCount()));
        m.put("networkReservedCount", Integer.toString(r.networkReservedCount()));
        m.put("regions", joinSet(r.regions()));
        m.put("regionKeptCounts", joinIntMap(r.regionKeptCounts()));
        return m;
    }

    /**
     * Reconstructs the canonical byte sequence for a backend heartbeat in the
     * fixed field order. Missing fields are emitted as empty values to keep the
     * line count fixed; {@link #fromFieldMap(Map)} rejects malformed rows.
     */
    public static String canonical(Map<String, String> m) {
        StringBuilder sb = new StringBuilder(256);
        appendField(sb, m, "serverId", true);
        appendField(sb, m, "schemaVersion", false);
        appendField(sb, m, "pluginState", false);
        appendField(sb, m, "acceptingRequests", false);
        appendField(sb, m, "lastSeenEpochMs", false);
        appendField(sb, m, "mspt", false);
        appendField(sb, m, "queueDepth", false);
        appendField(sb, m, "softCap", false);
        appendField(sb, m, "heapUsedBytes", false);
        appendField(sb, m, "heapMaxBytes", false);
        appendField(sb, m, "playerCount", false);
        appendField(sb, m, "regionsAvailable", false);
        appendField(sb, m, "worldsLoaded", false);
        appendField(sb, m, "killSwitch", false);
        appendField(sb, m, "keptCount", false);
        appendField(sb, m, "networkReservedCount", false);
        appendField(sb, m, "regions", false);
        appendField(sb, m, "regionKeptCounts", false);
        return sb.toString();
    }

    /** Parses a newline-delimited {@code key=value} payload into a map (insertion-ordered). */
    public static Map<String, String> parseFlat(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        if (s == null || s.isEmpty()) return m;
        for (String line : s.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            m.put(line.substring(0, eq), line.substring(eq + 1));
        }
        return m;
    }

    /**
     * Builds a {@link BackendHeartbeat} from a parsed field map. Returns
     * {@code null} (rather than throwing) for malformed rows so callers can drop
     * a bad payload without aborting a batch read.
     */
    public static BackendHeartbeat fromFieldMap(Map<String, String> m) {
        try {
            return new BackendHeartbeat(
                    requireKey(m, "serverId"),
                    Integer.parseInt(m.getOrDefault("schemaVersion", "1")),
                    PluginState.valueOf(m.getOrDefault("pluginState", "READY")),
                    Boolean.parseBoolean(m.getOrDefault("acceptingRequests", "true")),
                    Long.parseLong(m.getOrDefault("lastSeenEpochMs", "0")),
                    Double.parseDouble(m.getOrDefault("mspt", "0")),
                    Integer.parseInt(m.getOrDefault("queueDepth", "0")),
                    Integer.parseInt(m.getOrDefault("softCap", "0")),
                    Long.parseLong(m.getOrDefault("heapUsedBytes", "0")),
                    Long.parseLong(m.getOrDefault("heapMaxBytes", "0")),
                    Integer.parseInt(m.getOrDefault("playerCount", "0")),
                    splitList(m.getOrDefault("regionsAvailable", "")),
                    splitList(m.getOrDefault("worldsLoaded", "")),
                    Boolean.parseBoolean(m.getOrDefault("killSwitch", "false")),
                    Integer.parseInt(m.getOrDefault("keptCount", "0")),
                    Integer.parseInt(m.getOrDefault("networkReservedCount", "0")),
                    splitSet(m.getOrDefault("regions", "")),
                    splitIntMap(m.getOrDefault("regionKeptCounts", ""))
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static void appendField(StringBuilder sb, Map<String, String> m, String key, boolean first) {
        if (!first) sb.append('\n');
        sb.append(key).append('=').append(m.getOrDefault(key, ""));
    }

    private static String requireKey(Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null) throw new IllegalArgumentException("missing required key: " + key);
        return v;
    }

    public static String joinList(List<String> xs) {
        if (xs == null || xs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(xs.get(i).replace(',', '_').replace('\n', '_'));
        }
        return sb.toString();
    }

    public static List<String> splitList(String s) {
        if (s == null || s.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String tok : s.split(",")) {
            if (!tok.isEmpty()) out.add(tok);
        }
        return out;
    }

    /**
     * Joins a region Set in a deterministic (sorted) order so the canonical
     * byte sequence is stable regardless of the Set's iteration order - the
     * Redis HMAC layer signs over these bytes (REQ-RTP-S-004).
     */
    public static String joinSet(Set<String> xs) {
        if (xs == null || xs.isEmpty()) return "";
        List<String> sorted = new ArrayList<>(xs);
        sorted.sort(null);
        return joinList(sorted);
    }

    public static Set<String> splitSet(String s) {
        return new LinkedHashSet<>(splitList(s));
    }

    /**
     * Joins a {@code region:count} map as {@code region=count} pairs separated
     * by commas, in sorted key order for a stable canonical byte sequence.
     * Region keys containing reserved separators ({@code , = \n}) are sanitised
     * the same way {@link #joinList(List)} sanitises list elements.
     */
    public static String joinIntMap(Map<String, Integer> m) {
        if (m == null || m.isEmpty()) return "";
        Map<String, Integer> sorted = new TreeMap<>(m);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Integer> e : sorted.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            String key = e.getKey().replace(',', '_').replace('=', '_').replace('\n', '_');
            sb.append(key).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    public static Map<String, Integer> splitIntMap(String s) {
        if (s == null || s.isEmpty()) return Map.of();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String tok : s.split(",")) {
            if (tok.isEmpty()) continue;
            int eq = tok.indexOf('=');
            if (eq <= 0) continue;
            try {
                out.put(tok.substring(0, eq), Integer.parseInt(tok.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // drop a malformed pair rather than failing the whole row
            }
        }
        return out;
    }
}
