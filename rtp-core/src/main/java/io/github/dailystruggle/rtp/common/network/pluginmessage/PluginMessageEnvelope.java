package io.github.dailystruggle.rtp.common.network.pluginmessage;

import io.github.dailystruggle.rtp.proxy.common.security.HmacVerifier;
import io.github.dailystruggle.rtp.proxy.common.spi.BackendHeartbeat;
import io.github.dailystruggle.rtp.proxy.common.transport.codec.BackendHeartbeatCodec;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned, optionally HMAC-signed envelope for {@link BackendHeartbeat} rows
 * carried over plugin messages (backend gossip, proxy-cache push, proxy-cache
 * snapshot reply). Shared by backends and the Velocity companion.
 *
 * <p>Wire form (UTF-8, newline-delimited):</p>
 * <pre>
 *   pmv=2
 *   &lt;BackendHeartbeatCodec canonical fields&gt;
 *   hmac=&lt;hex&gt;          (present iff the sender holds a verifier)
 * </pre>
 *
 * <p>MAC input: {@code HmacVerifier.sign(row.schemaVersion, "pmv=2\n" + canonical)},
 * canonical rebuilt in fixed field order from the parsed map (never from wire
 * order), so the verified bytes are exactly the bytes decoded. Legacy (v1)
 * payloads carry no {@code pmv} line; with a verifier they are rejected as
 * {@link Rejection#UNSIGNED}, never mis-parsed. Old codec readers ignore the
 * extra keys, so v2 stays readable by unsigned v1 peers.</p>
 *
 * <p>Bounded parse (REQ-RTP-S-004 hardening): payload bytes, line count,
 * server-id length and per-collection entry counts are capped before the row
 * is admitted anywhere.</p>
 */
public final class PluginMessageEnvelope {

    /** Envelope format version emitted by this build. */
    public static final int VERSION = 2;
    static final String VERSION_KEY = "pmv";
    static final String HMAC_KEY = "hmac";

    /** Max envelope bytes; fits the signed-short length prefix of the plugin-message frames. */
    public static final int MAX_PAYLOAD_BYTES = Short.MAX_VALUE;
    /** Max whole plugin-message frame (envelope + framing headers) accepted before parsing. */
    public static final int MAX_FRAME_BYTES = MAX_PAYLOAD_BYTES + 512;
    /** Max {@code key=value} lines in one envelope (19 codec fields + pmv + hmac + slack). */
    public static final int MAX_LINES = 64;
    /** Max server-id length. */
    public static final int MAX_SERVER_ID_CHARS = 128;
    /** Max entries in any list / set / map field of a heartbeat. */
    public static final int MAX_COLLECTION_ENTRIES = 512;

    /** Why an inbound envelope was dropped. */
    public enum Rejection {
        OVERSIZED, MALFORMED, UNSUPPORTED_VERSION, UNSIGNED, BAD_SIGNATURE, LIMITS
    }

    /** Outcome of {@link #open}: exactly one of {@code heartbeat} / {@code rejection} is non-null. */
    public record Result(BackendHeartbeat heartbeat, Rejection rejection) {
        static Result ok(BackendHeartbeat hb) {
            return new Result(hb, null);
        }

        static Result reject(Rejection r) {
            return new Result(null, r);
        }

        public boolean accepted() {
            return heartbeat != null;
        }
    }

    private PluginMessageEnvelope() {
    }

    /**
     * Encode {@code row} as a v2 envelope, signed when {@code verifier} is non-null.
     *
     * @return UTF-8 envelope bytes, or {@code null} when the encoding exceeds
     *         {@link #MAX_PAYLOAD_BYTES} (caller drops + logs)
     */
    public static byte[] seal(BackendHeartbeat row, HmacVerifier verifier) {
        String canonical = BackendHeartbeatCodec.encode(row);
        String body = signedBody(Integer.toString(VERSION), canonical);
        StringBuilder sb = new StringBuilder(body.length() + 80).append(body);
        if (verifier != null) {
            sb.append('\n').append(HMAC_KEY).append('=').append(verifier.sign(row.schemaVersion(), body));
        }
        byte[] out = sb.toString().getBytes(StandardCharsets.UTF_8);
        return out.length > MAX_PAYLOAD_BYTES ? null : out;
    }

    /**
     * Verify-then-decode an inbound envelope. With a verifier: only signed v2
     * envelopes with a valid MAC pass (fail closed). Without: v1 and v2 are
     * accepted unauthenticated (legacy / no-secret deployments).
     */
    public static Result open(byte[] payload, HmacVerifier verifier) {
        if (payload == null || payload.length == 0) return Result.reject(Rejection.MALFORMED);
        if (payload.length > MAX_PAYLOAD_BYTES) return Result.reject(Rejection.OVERSIZED);
        Map<String, String> m = parseBounded(new String(payload, StandardCharsets.UTF_8));
        if (m == null) return Result.reject(Rejection.MALFORMED);
        String version = m.remove(VERSION_KEY);
        String hmacHex = m.remove(HMAC_KEY);
        if (version != null && !Integer.toString(VERSION).equals(version)) {
            return Result.reject(Rejection.UNSUPPORTED_VERSION);
        }
        if (verifier != null) {
            if (version == null || hmacHex == null || hmacHex.isEmpty()) {
                return Result.reject(Rejection.UNSIGNED);
            }
            int sv;
            try {
                sv = Integer.parseInt(m.getOrDefault("schemaVersion", "1"));
            } catch (NumberFormatException e) {
                return Result.reject(Rejection.MALFORMED);
            }
            if (!verifier.verify(sv, signedBody(version, BackendHeartbeatCodec.canonical(m)), hmacHex)) {
                return Result.reject(Rejection.BAD_SIGNATURE);
            }
        }
        BackendHeartbeat hb = BackendHeartbeatCodec.fromFieldMap(m);
        if (hb == null) return Result.reject(Rejection.MALFORMED);
        if (!withinLimits(hb)) return Result.reject(Rejection.LIMITS);
        return Result.ok(hb);
    }

    private static String signedBody(String version, String canonical) {
        return VERSION_KEY + "=" + version + "\n" + canonical;
    }

    /** Line-capped flat parse; duplicate keys are malformed (no last-wins ambiguity). */
    private static Map<String, String> parseBounded(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        int lines = 0;
        int start = 0;
        int len = s.length();
        while (start <= len) {
            int nl = s.indexOf('\n', start);
            int end = nl < 0 ? len : nl;
            if (++lines > MAX_LINES) return null;
            String line = s.substring(start, end);
            int eq = line.indexOf('=');
            if (eq > 0) {
                if (m.put(line.substring(0, eq), line.substring(eq + 1)) != null) return null;
            }
            if (nl < 0) break;
            start = nl + 1;
        }
        return m;
    }

    private static boolean withinLimits(BackendHeartbeat hb) {
        String id = hb.serverId();
        if (id == null || id.isEmpty() || id.length() > MAX_SERVER_ID_CHARS) return false;
        return size(hb.regionsAvailable()) <= MAX_COLLECTION_ENTRIES
                && size(hb.worldsLoaded()) <= MAX_COLLECTION_ENTRIES
                && size(hb.regions()) <= MAX_COLLECTION_ENTRIES
                && (hb.regionKeptCounts() == null || hb.regionKeptCounts().size() <= MAX_COLLECTION_ENTRIES)
                && (hb.regionMetadata() == null || hb.regionMetadata().size() <= MAX_COLLECTION_ENTRIES);
    }

    private static int size(java.util.Collection<?> c) {
        return c == null ? 0 : c.size();
    }
}
