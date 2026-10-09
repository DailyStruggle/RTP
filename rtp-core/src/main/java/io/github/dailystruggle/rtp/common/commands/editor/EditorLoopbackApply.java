package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Hot-Apply for the web editor (ADR-104, ADR-106 §5.3).
 *
 * <p>Over the signed {@code EditorChannel} the message is {@code {"type":"apply","region":"<name>",
 * "timestamp":<ms>,"files":{"<relative yml path>":"<yaml text>"}}} from a trusted browser key
 * ({@link #handleAuthorized}); the token form {@code {"action":"apply","token":..}} remains for an
 * {@link EditorLoopbackChannel} wired with a token ({@link #handle}). Reply:
 * {@code {"type":"apply_ack","success":bool,"error":"..."}}. Only structural checks live here
 * (string map, YAML extension, size); path,
 * YAML-AST and geometry validation, {@code .bak} copies, atomic writes and the config reload all run
 * in {@link EditorSessionManager#applyPayload(String)}, the same pipeline as {@code /rtp editor apply}.
 * One apply in flight at a time; every outcome is acked and logged (S-004).
 *
 * <p>An apply over the frame cap travels by reference (ADR-106 §5.5):
 * {@code {"type":"apply","region":..,"timestamp":..,"bytebinKey":..,"sha256":..}} names a bytebin
 * upload of {@code {"files":{..}}}; it is fetched, its SHA-256 checked, then handled as if inline.
 */
public final class EditorLoopbackApply {

    public static final String ACTION = "apply";
    public static final String ACK_TYPE = "apply_ack";
    static final int MAX_FILES = 256;
    /** Largest referenced apply fetched from bytebin. */
    static final int MAX_HANDOFF_CHARS = 4 * 1024 * 1024;
    private static final java.util.regex.Pattern BYTEBIN_KEY = java.util.regex.Pattern.compile("[A-Za-z0-9]{4,64}");
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("[0-9a-f]{64}");

    private final byte[] token;
    private final Function<String, CompletableFuture<Void>> applier;
    /** Bytebin key to its text, for referenced applies; {@code null} where there is no byte store (local). */
    private final Function<String, CompletableFuture<String>> fetcher;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    EditorLoopbackApply(String token, Function<String, CompletableFuture<Void>> applier) {
        this(token, applier, null);
    }

    EditorLoopbackApply(String token, Function<String, CompletableFuture<Void>> applier,
                        Function<String, CompletableFuture<String>> fetcher) {
        this.token = token == null ? null : token.getBytes(StandardCharsets.UTF_8);
        this.applier = Objects.requireNonNull(applier, "applier");
        this.fetcher = fetcher;
    }

    /** Production wiring: {@link EditorSessionManager#applyPayload(String)} (validates, writes, reloads). */
    static EditorLoopbackApply forSessionManager(String token) {
        return new EditorLoopbackApply(token, json -> EditorSessionManager.getInstance().applyPayload(json));
    }

    /** Channel wiring: authorisation is the trusted, signed browser key, so there is no token. */
    static EditorLoopbackApply forChannel() {
        return new EditorLoopbackApply(null, json -> EditorSessionManager.getInstance().applyPayload(json));
    }

    /** Hosted channel: as {@link #forChannel()}, plus referenced applies fetched from {@code http}'s bytebin. */
    static EditorLoopbackApply forChannel(EditorHttpTransport http) {
        return new EditorLoopbackApply(null, json -> EditorSessionManager.getInstance().applyPayload(json), http::fetchPayload);
    }

    static boolean isApply(Object message) {
        return message instanceof Map<?, ?> m && ACTION.equals(m.get("action"));
    }

    /**
     * Validates the message shape, hands the rebuilt payload to the apply pipeline and resolves to
     * the {@code apply_ack} JSON. Never completes exceptionally.
     */
    CompletableFuture<String> handle(Map<?, ?> message) {
        Long timestamp = message.get("timestamp") instanceof Number n ? n.longValue() : null;
        String region = message.get("region") instanceof String r ? r : "?";
        if (token == null || !(message.get("token") instanceof String provided)
                || !EditorLoopbackChannel.tokenEquals(token, provided)) {
            return reject(region, timestamp, "invalid session token");
        }
        return handleAuthorized(message);
    }

    /**
     * As {@link #handle} for a sender the caller already authorised (a verified message from a
     * trusted key on the signed channel). Never completes exceptionally.
     */
    CompletableFuture<String> handleAuthorized(Map<?, ?> message) {
        Long timestamp = message.get("timestamp") instanceof Number n ? n.longValue() : null;
        String region = message.get("region") instanceof String r ? r : "?";
        if (message.get("files") == null && message.get("bytebinKey") != null) return handOff(message, region, timestamp);
        Map<String, String> files;
        try {
            files = files(message.get("files"));
        } catch (IllegalArgumentException e) {
            return reject(region, timestamp, e.getMessage());
        }
        return applyFiles(region, timestamp, files);
    }

    /** Referenced apply: fetch, check the digest, then the inline path. Never completes exceptionally. */
    private CompletableFuture<String> handOff(Map<?, ?> message, String region, Long timestamp) {
        if (!(message.get("bytebinKey") instanceof String key) || !BYTEBIN_KEY.matcher(key).matches()
                || !(message.get("sha256") instanceof String sha) || !SHA256.matcher(sha).matches()) {
            return reject(region, timestamp, "malformed bytebin reference");
        }
        if (fetcher == null) return reject(region, timestamp, "no byte store for referenced applies in this session");
        CompletableFuture<String> fetched;
        try {
            fetched = Objects.requireNonNull(fetcher.apply(key), "fetcher returned null");
        } catch (RuntimeException e) {
            return reject(region, timestamp, "bytebin fetch failed: " + e.getMessage());
        }
        return fetched.handle((text, t) -> {
            if (t != null) return reject(region, timestamp, "bytebin fetch failed: " + unwrap(t).getMessage());
            if (text == null || text.length() > MAX_HANDOFF_CHARS) return reject(region, timestamp, "referenced apply too large");
            if (!EditorHttpTransport.computeSha256(text).equals(sha)) {
                return reject(region, timestamp, "referenced apply does not match its sha256");
            }
            Map<String, String> files;
            try {
                Object root = EditorLoopbackJson.parse(text);
                files = files(root instanceof Map<?, ?> m ? m.get("files") : null);
            } catch (IllegalArgumentException e) {
                return reject(region, timestamp, e.getMessage());
            }
            return applyFiles(region, timestamp, files);
        }).thenCompose(f -> f);
    }

    private CompletableFuture<String> applyFiles(String region, Long timestamp, Map<String, String> files) {
        if (!inFlight.compareAndSet(false, true)) {
            return reject(region, timestamp, "another apply is still running");
        }
        CompletableFuture<Void> applied;
        try {
            applied = Objects.requireNonNull(applier.apply(payloadJson(files, timestamp)), "apply pipeline returned null");
        } catch (RuntimeException e) {
            inFlight.set(false);
            RTP.log(Level.WARNING, "[editor] loopback hot-apply for region '" + region + "' failed: " + e.getMessage(), e);
            return CompletableFuture.completedFuture(ack(false, e.getMessage(), timestamp));
        }
        return applied.handle((ok, t) -> {
            inFlight.set(false);
            if (t == null) {
                RTP.log(Level.INFO, "[editor] loopback hot-apply for region '" + region + "' applied "
                        + files.size() + " file(s)");
                return ack(true, null, timestamp);
            }
            Throwable cause = unwrap(t);
            RTP.log(Level.WARNING, "[editor] loopback hot-apply for region '" + region + "' failed: "
                    + cause.getMessage(), cause);
            return ack(false, cause.getMessage(), timestamp);
        });
    }

    private static CompletableFuture<String> reject(String region, Long timestamp, String error) {
        RTP.log(Level.WARNING, "[editor] loopback hot-apply for region '" + region + "' rejected: " + error);
        return CompletableFuture.completedFuture(ack(false, error, timestamp));
    }

    /** Hot-Apply scope: a non-empty {@code {path: yamlText}} map of {@code .yml}/{@code .yaml} files. */
    static Map<String, String> files(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) {
            throw new IllegalArgumentException("'files' must be a non-empty object");
        }
        if (m.size() > MAX_FILES) throw new IllegalArgumentException("too many files (max " + MAX_FILES + ")");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String path) || !(e.getValue() instanceof String text)) {
                throw new IllegalArgumentException("'files' entries must map a path to YAML text");
            }
            String lower = path.toLowerCase(Locale.ROOT);
            if (path.indexOf('\0') >= 0 || !(lower.endsWith(".yml") || lower.endsWith(".yaml"))) {
                throw new IllegalArgumentException("Illegal file path in payload: '" + path + "'");
            }
            out.put(path, text);
        }
        return out;
    }

    /**
     * ADR-104 payload for {@link EditorSessionManager#applyPayload(String)}. Escaped with its own
     * {@code escapeJson} so its reader round-trips; header fields precede {@code files} because that
     * reader locates fields by first occurrence.
     */
    static String payloadJson(Map<String, String> files, Long timestamp) {
        List<String> keys = new ArrayList<>(files.keySet());
        Collections.sort(keys);
        StringBuilder sb = new StringBuilder("{\"version\":1,\"pluginVersion\":\"loopback\",\"sha256\":\"")
                .append(EditorSessionManager.computeCanonicalFilesSha256(files)).append("\",\"timestamp\":")
                .append(timestamp == null ? 0L : timestamp).append(",\"files\":{");
        for (int k = 0; k < keys.size(); k++) {
            if (k > 0) sb.append(',');
            String key = keys.get(k);
            sb.append('"').append(EditorSessionManager.escapeJson(key)).append("\":\"")
                    .append(EditorSessionManager.escapeJson(files.get(key))).append('"');
        }
        return sb.append("}}").toString();
    }

    static String ack(boolean success, String error, Long timestamp) {
        StringBuilder sb = new StringBuilder("{\"type\":\"").append(ACK_TYPE).append("\",\"success\":").append(success);
        if (!success) sb.append(",\"error\":").append(EditorLoopbackJson.quote(error == null ? "unknown error" : error));
        if (timestamp != null) sb.append(",\"timestamp\":").append(timestamp);
        return sb.append('}').toString();
    }

    private static Throwable unwrap(Throwable t) {
        Throwable c = t;
        while ((c instanceof CompletionException || c instanceof ExecutionException) && c.getCause() != null) {
            c = c.getCause();
        }
        return c;
    }
}
