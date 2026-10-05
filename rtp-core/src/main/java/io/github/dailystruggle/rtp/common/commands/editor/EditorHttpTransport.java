package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
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
    /** WebSocket relay for the hosted editor channel (ADR-106 §5.4), the bytebin sibling. */
    public static final String DEFAULT_RELAY_URL = "https://bytesocks.lucko.me";

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
        this.bytebinUrl = (bytebinUrl != null && !bytebinUrl.isBlank()) ? bytebinUrl.replaceAll("/+$", "") : DEFAULT_BYTEBIN_URL;
        this.editorBaseUrl = (editorBaseUrl != null && !editorBaseUrl.isBlank()) ? editorBaseUrl.replaceAll("/+$", "") : DEFAULT_EDITOR_URL;
        this.relayUrl = (relayUrl != null && !relayUrl.isBlank()) ? relayUrl.replaceAll("/+$", "") : DEFAULT_RELAY_URL;
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
     * @throws IOException on read error
     */
    public static String gzipDecompress(byte[] bytes) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length >= 2 && bytes[0] == (byte) 0x1f && bytes[1] == (byte) 0x8b) {
            try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
                return new String(gzis.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
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
                        String err = "Bytebin GET failed for token '" + token + "' with status code " + statusCode + ": " + bodyStr;
                        RTP.log(Level.WARNING, err);
                        throw new RuntimeException(err);
                    }

                    try {
                        return gzipDecompress(response.body());
                    } catch (IOException e) {
                        String err = "Failed to decompress bytebin payload for token '" + token + "': " + e.getMessage();
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
