package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Handles outbound HTTP/WebSocket byte-store session transport for the web editor (ADR-104).
 *
 * <p>Dispatches GZIP-compressed JSON payloads asynchronously using {@link HttpClient} on
 * non-blocking threads conforming to S-005 (100% async scheduling for network and disk I/O)
 * and S-004 (zero silent swallows on HTTP failures).
 */
public class EditorHttpTransport {

    public static final String DEFAULT_BYTEBIN_URL = "https://bytebin.lucko.me";
    public static final String DEFAULT_EDITOR_URL = "https://dailystruggle.github.io/RTP/editor/";
    /**
     * WebSocket relay for the hosted editor channel (ADR-106 §5.4): LuckPerms' public bytesocks
     * instance ({@code GET /create} -> 201 + {@code Location}). Interim default; operators override
     * it with {@code advanced/network.yml editor.relayUrl} (e.g. a self-hosted bytesocks).
     */
    public static final String DEFAULT_RELAY_URL = "https://usersockets.luckperms.net";
    /** Largest decompressed byte-store body accepted (gzip bomb guard). */
    public static final int MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024;

    private static volatile EditorHttpTransport configured;

    private final HttpClient httpClient;
    private final String bytebinUrl;
    private final String editorBaseUrl;
    private final String relayUrl;

    public EditorHttpTransport() {
        this(DEFAULT_BYTEBIN_URL, DEFAULT_EDITOR_URL);
    }

    public EditorHttpTransport(String bytebinUrl, String editorBaseUrl) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                bytebinUrl,
                editorBaseUrl
        );
    }

    public EditorHttpTransport(HttpClient httpClient, String bytebinUrl, String editorBaseUrl) {
        this(httpClient, bytebinUrl, editorBaseUrl, DEFAULT_RELAY_URL);
    }

    /** As the 3-argument form with the relay ({@code http(s)://host[:port]}) the hosted channel joins. */
    public EditorHttpTransport(HttpClient httpClient, String bytebinUrl, String editorBaseUrl, String relayUrl) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        String bytebin = url(bytebinUrl, "bytebinUrl");
        String relay = url(relayUrl, "relayUrl");
        this.bytebinUrl = bytebin != null ? bytebin.replaceAll("/+$", "") : DEFAULT_BYTEBIN_URL;
        this.editorBaseUrl = (editorBaseUrl != null && !editorBaseUrl.isBlank()) ? editorBaseUrl.replaceAll("/+$", "") : DEFAULT_EDITOR_URL;
        this.relayUrl = relay != null ? relay.replaceAll("/+$", "") : DEFAULT_RELAY_URL;
    }

    /**
     * The transport for the {@code editor} section of {@code advanced/network.yml} ({@code bytebinUrl},
     * {@code relayUrl}); built-in defaults when the file, the section or a value is missing or blank.
     * Reads the file on every call (disk I/O: off the main thread only), so an edit applies to the next
     * session; reuses the last instance while the URLs are unchanged.
     */
    public static EditorHttpTransport fromConfig() {
        Map<String, Object> block = null;
        File file = null;
        try {
            File dir = RTP.serverAccessor != null ? RTP.serverAccessor.getPluginDirectory() : null;
            if (dir != null) file = new File(dir, "advanced" + File.separator + "network.yml");
            if (file != null && file.isFile()) {
                RtpYamlSection sec = RtpYamlConfig.load(file).getConfigurationSection("editor");
                if (sec != null) {
                    block = new HashMap<>();
                    block.put("bytebinUrl", sec.getString("bytebinUrl"));
                    block.put("relayUrl", sec.getString("relayUrl"));
                }
            }
        } catch (IOException | RuntimeException e) {
            RTP.log(Level.WARNING, "[editor] could not read " + file + " editor section; using the default URLs: "
                    + e.getMessage(), e);
        }
        String[] urls = settingsUrls(block);
        EditorHttpTransport last = configured;
        if (last != null && last.bytebinUrl.equals(urls[0]) && last.relayUrl.equals(urls[1])) return last;
        EditorHttpTransport fresh = build(urls);
        configured = fresh;
        return fresh;
    }

    /** A transport for one {@code editor} config block (a map, or anything else for all defaults). */
    static EditorHttpTransport fromSettings(Object block) {
        return build(settingsUrls(block));
    }

    private static EditorHttpTransport build(String[] urls) {
        return new EditorHttpTransport(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                urls[0], DEFAULT_EDITOR_URL, urls[1]);
    }

    /** {bytebin, relay} of an {@code editor} block, normalised as the constructor does. */
    static String[] settingsUrls(Object block) {
        String bytebin = null;
        String relay = null;
        if (block instanceof Map<?, ?> m) {
            bytebin = url(m.get("bytebinUrl"), "bytebinUrl");
            relay = url(m.get("relayUrl"), "relayUrl");
        }
        return new String[]{
                bytebin != null ? bytebin.replaceAll("/+$", "") : DEFAULT_BYTEBIN_URL,
                relay != null ? relay.replaceAll("/+$", "") : DEFAULT_RELAY_URL};
    }

    /**
     * A configured https URL (http only to a loopback host), or null (default) for blank values; anything
     * else is logged and refused (S-004): sessions, tokens and applies must not cross the network in clear.
     */
    private static String url(Object raw, String key) {
        if (raw == null) return null;
        String s = String.valueOf(raw).trim();
        if (s.isEmpty()) return null;
        try {
            URI u = URI.create(s);
            String scheme = u.getScheme();
            if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) && EditorSecurity.secureOrLoopback(u)) return s;
        } catch (IllegalArgumentException ignored) {
            // logged below
        }
        RTP.log(Level.WARNING, "[editor] advanced/network.yml editor." + key + " '" + s
                + "' is not an https URL (http is allowed only to localhost / 127.0.0.1 / ::1); using the default");
        return null;
    }

    public String getRelayUrl() {
        return relayUrl;
    }

    /** The shared client: bytebin uploads and the relay's WebSocket use the same one. */
    public HttpClient httpClient() {
        return httpClient;
    }

    public String getBytebinUrl() {
        return bytebinUrl;
    }

    public String getEditorBaseUrl() {
        return editorBaseUrl;
    }

    /**
     * Compresses the provided UTF-8 payload using GZIP.
     *
     * @param payload raw payload text
     * @return gzipped byte array
     * @throws IOException on compression error
     */
    public static byte[] gzipCompress(String payload) throws IOException {
        Objects.requireNonNull(payload, "payload");
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(bytes);
        }
        return baos.toByteArray();
    }

    /**
     * Decompresses the provided GZIP byte array into a UTF-8 string.
     * If the input is not GZIP compressed (e.g. plain text response), it falls back to raw UTF-8.
     *
     * @param bytes compressed or raw bytes
     * @return decompressed payload text
     * @throws IOException on read error, or when the text exceeds {@link #MAX_DECOMPRESSED_BYTES}
     */
    public static String gzipDecompress(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
            try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(readCapped(gzis, MAX_DECOMPRESSED_BYTES), StandardCharsets.UTF_8);
            }
        }
        if (bytes.length > MAX_DECOMPRESSED_BYTES) {
            throw new IOException("payload exceeds " + MAX_DECOMPRESSED_BYTES + " bytes");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Reads {@code in} to its end, failing as soon as more than {@code max} bytes arrive. */
    static byte[] readCapped(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > max) throw new IOException("decompressed payload exceeds " + max + " bytes");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /**
     * Computes the SHA-256 hex digest of the given string payload.
     *
     * @param payload string content
     * @return 64-character lowercase hex digest
     */
    public static String computeSha256(String payload) {
        Objects.requireNonNull(payload, "payload");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest algorithm not available", e);
        }
    }

    /**
     * Posts a JSON payload asynchronously to the configured bytebin store.
     *
     * @param jsonPayload raw JSON payload string
     * @return CompletableFuture resolving to the created session token/key
     */
    public CompletableFuture<String> postPayload(String jsonPayload) {
        Objects.requireNonNull(jsonPayload, "jsonPayload");

        return CompletableFuture.supplyAsync(() -> {
            try {
                byte[] compressed = gzipCompress(jsonPayload);
                return compressed;
            } catch (IOException e) {
                throw new RuntimeException("Failed to compress JSON payload: " + e.getMessage(), e);
            }
        }).thenCompose(compressed -> {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(bytebinUrl + "/post"))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Content-Encoding", "gzip")
                    .header("User-Agent", "RTP-Plugin-Editor/1.0")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(compressed))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        int statusCode = response.statusCode();
                        if (statusCode < 200 || statusCode >= 300) {
                            String err = "Bytebin POST failed with status code " + statusCode + ": " + response.body();
                            RTP.log(Level.WARNING, err);
                            throw new RuntimeException(err);
                        }

                        // Bytebin returns {"key":"xxxx"} or plain key depending on endpoint / response
                        String body = response.body().trim();
                        String key = extractKeyFromJsonOrPlain(body);
                        if (key == null || key.isBlank()) {
                            String err = "Bytebin returned empty or unparseable key: " + body;
                            RTP.log(Level.WARNING, err);
                            throw new RuntimeException(err);
                        }
                        return key;
                    });
        });
    }

    /**
     * Fetches a session payload asynchronously by token from the configured bytebin store.
     *
     * @param token session token / bytebin key
     * @return CompletableFuture resolving to decompressed JSON payload string
     */
    public CompletableFuture<String> fetchPayload(String token) {
        if (token == null || token.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Token cannot be null or empty"));
        }
        if (!EditorSecurity.isToken(token)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Malformed session token"));
        }
        String shown = EditorSecurity.tokenPrefix(token);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(bytebinUrl + "/" + token))
                .header("User-Agent", "RTP-Plugin-Editor/1.0")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    int statusCode = response.statusCode();
                    if (statusCode < 200 || statusCode >= 300) {
                        String bodyStr = new String(response.body(), StandardCharsets.UTF_8);
                        String err = "Bytebin GET failed for token '" + shown + "' with status code " + statusCode + ": " + bodyStr;
                        RTP.log(Level.WARNING, err);
                        throw new RuntimeException(err);
                    }

                    try {
                        return gzipDecompress(response.body());
                    } catch (IOException e) {
                        String err = "Failed to decompress bytebin payload for token '" + shown + "': " + e.getMessage();
                        RTP.log(Level.WARNING, err, e);
                        throw new RuntimeException(err, e);
                    }
                });
    }

    /**
     * Builds the full web editor URL with token parameter for the operator.
     *
     * @param token session token
     * @return clickable editor URL
     */
    public String buildEditorUrl(String token) {
        Objects.requireNonNull(token, "token");
        return editorBaseUrl + "?token=" + token;
    }

    private static String extractKeyFromJsonOrPlain(String body) {
        if (body.startsWith("{") && body.endsWith("}")) {
            int keyIdx = body.indexOf("\"key\"");
            if (keyIdx != -1) {
                int colonIdx = body.indexOf(':', keyIdx);
                if (colonIdx != -1) {
                    int quoteStart = body.indexOf('"', colonIdx);
                    if (quoteStart != -1) {
                        int quoteEnd = body.indexOf('"', quoteStart + 1);
                        if (quoteEnd != -1) {
                            return body.substring(quoteStart + 1, quoteEnd);
                        }
                    }
                }
            }
            // If the body is a JSON object but contains no "key" property (e.g. {"error": "..."}),
            // it is an error or invalid response and must not be treated as a token.
            throw new RuntimeException("Byte-store response is JSON but does not contain a 'key' field: " + body);
        }
        return body.replaceAll("[\"'{}\r\n ]", "");
    }
}
