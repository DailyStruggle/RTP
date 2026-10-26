package io.github.dailystruggle.rtp.common.commands.editor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code advanced/network.yml editor} block: blank or missing values use the built-in bytebin and
 * relay, valid http(s) URLs replace them, anything else is logged and falls back (S-004).
 */
@DisplayName("REQ-RTP-S-004 editor bytebin / relay URL settings")
class EditorHttpTransportSettingsTest {

    private static final String[] DEFAULTS = {EditorHttpTransport.DEFAULT_BYTEBIN_URL, EditorHttpTransport.DEFAULT_RELAY_URL};

    private static Map<String, Object> block(Object bytebin, Object relay) {
        Map<String, Object> m = new HashMap<>();
        m.put("bytebinUrl", bytebin);
        m.put("relayUrl", relay);
        return m;
    }

    @Test
    @DisplayName("no section, blank values: built-in defaults (a resolving relay, not bytesocks.lucko.me)")
    void defaults() {
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(null));
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block("", "  ")));
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block(null, null)));
        assertEquals("https://usersockets.luckperms.net", EditorHttpTransport.DEFAULT_RELAY_URL);
    }

    @Test
    @DisplayName("configured URLs replace the defaults, trailing slashes trimmed")
    void configured() {
        assertArrayEquals(new String[]{"https://bin.example.org", "http://127.0.0.1:3000"},
                EditorHttpTransport.settingsUrls(block("https://bin.example.org/", " http://127.0.0.1:3000// ")));
        EditorHttpTransport t = EditorHttpTransport.fromSettings(block(null, "https://sockets.example.org"));
        assertEquals(EditorHttpTransport.DEFAULT_BYTEBIN_URL, t.getBytebinUrl());
        assertEquals("https://sockets.example.org", t.getRelayUrl());
    }

    @Test
    @DisplayName("non-http(s) or malformed values fall back to the default instead of failing the session")
    void invalidFallsBack() {
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block("ftp://bin.example.org", "wss://x y")));
        assertArrayEquals(DEFAULTS, EditorHttpTransport.settingsUrls(block("bytebin.lucko.me", 42)));
    }

    @Test
    @DisplayName("ADR-104 / ADR-108: hosted snapshots reference static ./editor-data.json while local exports embed it")
    void hostedSnapshotUsesStaticEditorDataReference() {
        EditorSessionManager manager = EditorSessionManager.getInstance();
        Map<String, String> files = Map.of("config.yml", "radius: 1000\n");

        String hosted = manager.createPayloadJson(files, Map.of("relay", "wss://relay.example.org/room1", "id", "room1", "pluginKey", "AAA"));
        assertTrue(hosted.contains("\"editorDataRef\":\"./editor-data.json\""));
        assertFalse(hosted.contains("\"docTracker\":"), "hosted snapshot must omit bulky inline docTracker");

        String local = manager.createPayloadJson(files, true);
        assertTrue(local.contains("\"shipped\":") && local.contains("\"docTracker\":"),
                "local air-gapped export must embed editor-data.json inline");
        assertTrue(hosted.length() * 3 < local.length(),
                "hosted snapshot must be dramatically smaller than inline local snapshot");
    }

    @Test
    @DisplayName("ADR-108 / S-004: primary bytebin 429/503 fails over to fallbackBytebinUrl and sends edition/service headers")
    void primaryBytebinFailsOverToFallbackWithServiceHeaders() throws Exception {
        List<HttpRequest> seenRequests = new ArrayList<>();
        byte[] gzippedPayload = EditorHttpTransport.gzipCompress("{\"version\":1}");
        HttpClient failoverClient = new HttpClient() {
            @Override public Optional<java.net.CookieHandler> cookieHandler() { return Optional.empty(); }
            @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public Optional<java.net.ProxySelector> proxy() { return Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public Optional<java.net.Authenticator> authenticator() { return Optional.empty(); }
            @Override public Version version() { return Version.HTTP_2; }
            @Override public Optional<java.util.concurrent.Executor> executor() { return Optional.of(Runnable::run); }
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
                return sendAsync(req, h);
            }
            @Override public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h) {
                throw new UnsupportedOperationException();
            }
            @Override
            @SuppressWarnings("unchecked")
            public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest req, HttpResponse.BodyHandler<T> h) {
                seenRequests.add(req);
                boolean isPrimary = req.uri().getHost().equals("primary.bytebin.example");
                if ("POST".equals(req.method())) {
                    int code = isPrimary ? 429 : 200;
                    String body = isPrimary ? "rate limited" : "{\"key\":\"fallback-tok-123\"}";
                    return CompletableFuture.completedFuture(stubResponse(req, code, (T) body));
                }
                int code = isPrimary ? 503 : 200;
                byte[] body = isPrimary ? "unavailable".getBytes(StandardCharsets.UTF_8) : gzippedPayload;
                return CompletableFuture.completedFuture(stubResponse(req, code, (T) body));
            }
        };

        EditorHttpTransport transport = new EditorHttpTransport(
                failoverClient,
                "https://primary.bytebin.example",
                EditorHttpTransport.DEFAULT_EDITOR_URL,
                "https://primary.relay.example",
                "https://fallback.bytebin.example",
                EditorHttpTransport.DEFAULT_RELAY_URL,
                "pro-token-secret-xyz"
        );

        String key = transport.postPayload("{\"version\":1}").join();
        assertEquals("fallback-tok-123", key);
        assertEquals(2, seenRequests.size(), "must try primary then fallback on POST");
        assertNotNull(seenRequests.get(0).headers().firstValue("X-RTP-Edition").orElse(null));
        assertEquals("Bearer pro-token-secret-xyz",
                seenRequests.get(0).headers().firstValue("Authorization").orElse(null));

        String fetched = transport.fetchPayload("fallback-tok-123").join();
        assertEquals("{\"version\":1}", fetched);
        assertEquals(4, seenRequests.size(), "must try primary then fallback on GET");
        assertEquals("https://dailystruggle.github.io/RTP/editor?token=fallback-tok-123&bytebin=https%3A%2F%2Ffallback.bytebin.example#k=sec123",
                transport.buildEditorUrl("fallback-tok-123", "sec123"),
                "buildEditorUrl on official editor must include active bytebin query param when custom/fallback bytebin is used");
    }

    private static <T> HttpResponse<T> stubResponse(HttpRequest req, int code, T body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return code; }
            @Override public HttpRequest request() { return req; }
            @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            @Override public T body() { return body; }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return req.uri(); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }
}
