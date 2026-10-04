package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EditorHttpTransport and Atomic Apply Pipeline Tests (ADR-104 Phase 2)")
class EditorTransportAndApplyTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        File pluginDir = tempDir.resolve("plugins/RTP").toFile();
        pluginDir.mkdirs();

        RTPTestSetup.install(pluginDir);
    }

    @AfterEach
    void tearDown() {
        RTPTestSetup.cleanUp();
    }

    @Test
    @DisplayName("GZIP compression and decompression roundtrip")
    void gzipCompressionRoundtrip() throws IOException {
        String original = "version: 1\nregion:\n  radius: 5000\n  centerRadius: 1000\n";
        byte[] compressed = EditorHttpTransport.gzipCompress(original);
        assertNotNull(compressed);
        assertTrue(compressed.length > 0);

        String decompressed = EditorHttpTransport.gzipDecompress(compressed);
        assertEquals(original, decompressed);
    }

    @Test
    @DisplayName("SHA-256 calculation produces deterministic 64-char hex string")
    void sha256Calculation() {
        String content = "test content for sha256";
        String hash1 = EditorHttpTransport.computeSha256(content);
        String hash2 = EditorHttpTransport.computeSha256(content);
        assertNotNull(hash1);
        assertEquals(64, hash1.length());
        assertEquals(hash1, hash2);
    }

    @Test
    @DisplayName("Mocked HTTP post and fetch roundtrip")
    void mockHttpPostAndFetchRoundtrip() {
        AtomicReference<byte[]> storedPayload = new AtomicReference<>();
        String expectedKey = "abc123token";

        HttpClient mockClient = new MockHttpClient(storedPayload, expectedKey);
        EditorHttpTransport transport = new EditorHttpTransport(mockClient, "https://mock.bytebin", "https://editor.rtp");

        String jsonPayload = "{\"version\":1,\"files\":{\"config.yml\":\"radius: 5000\"}}";

        // Post
        String token = transport.postPayload(jsonPayload).join();
        assertEquals(expectedKey, token);
        assertNotNull(storedPayload.get());

        // Fetch
        String fetched = transport.fetchPayload(token).join();
        assertEquals(jsonPayload, fetched);

        // Editor URL
        assertEquals("https://editor.rtp?token=" + token, transport.buildEditorUrl(token));
    }

    @Test
    @DisplayName("S-004: HTTP error status throws informative exception on post and fetch")
    void httpErrorsThrowInformativeExceptions() {
        HttpClient errorClient = new MockErrorHttpClient(500, "Internal Server Error");
        EditorHttpTransport transport = new EditorHttpTransport(errorClient, "https://mock.bytebin", "https://editor.rtp");

        assertThrows(Exception.class, () -> transport.postPayload("{\"test\":true}").join());
        assertThrows(Exception.class, () -> transport.fetchPayload("badtoken").join());
    }

    @Test
    @DisplayName("Payload creation includes files and SHA-256 integrity hash")
    void payloadCreationAndAstValidation() {
        EditorSessionManager manager = EditorSessionManager.getInstance();

        Map<String, String> configs = Map.of(
                "config.yml", "teleport:\n  radius: 5000\n",
                "regions/default.yml", "region:\n  radius: 2500\n  centerRadius: 500\n"
        );

        String payloadJson = manager.createPayloadJson(configs);
        assertNotNull(payloadJson);
        assertTrue(payloadJson.contains("\"files\""));
        assertTrue(payloadJson.contains("\"sha256\""));

        EditorSessionManager.ParsedPayload parsed = manager.parseAndValidatePayload(payloadJson);
        assertEquals(2, parsed.files().size());
        assertEquals("teleport:\n  radius: 5000\n", parsed.files().get("config.yml"));
    }

    @Test
    @DisplayName("AST validation rejects malformed YAML syntax")
    void astValidationRejectsMalformedYaml() {
        EditorSessionManager manager = EditorSessionManager.getInstance();

        Map<String, String> configs = Map.of(
                "config.yml", "a:\n\tb: 1\n"
        );

        String payloadJson = manager.createPayloadJson(configs);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.parseAndValidatePayload(payloadJson));
        assertTrue(ex.getMessage().contains("AST validation error"));
    }

    @Test
    @DisplayName("Geometry validation rejects negative radius in region configuration")
    void geometryValidationRejectsNegativeRadius() {
        EditorSessionManager manager = EditorSessionManager.getInstance();

        Map<String, String> configs = Map.of(
                "regions/default.yml", "region:\n  radius: -500\n"
        );

        String payloadJson = manager.createPayloadJson(configs);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.parseAndValidatePayload(payloadJson));
        assertTrue(ex.getMessage().contains("Geometry error"));
        assertTrue(ex.getMessage().contains("radius cannot be negative"));
    }

    @Test
    @DisplayName("SHA-256 tamper detection throws on payload modification")
    void sha256TamperDetection() {
        EditorSessionManager manager = EditorSessionManager.getInstance();

        Map<String, String> configs = Map.of("config.yml", "radius: 1000\n");
        String payloadJson = manager.createPayloadJson(configs);

        // Tamper with the content inside "files"
        String tampered = payloadJson.replace("1000", "9999");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> manager.parseAndValidatePayload(tampered));
        assertTrue(ex.getMessage().contains("SHA-256 mismatch"));
    }

    @Test
    @DisplayName("Atomic apply creates .bak backup of dirty files before writing")
    void atomicApplyCreatesDirtyFileBackup() throws IOException {
        Path pluginPath = RTP.serverAccessor.getPluginDirectory().toPath();
        Path configFile = pluginPath.resolve("config.yml");
        Files.writeString(configFile, "original: true\n");

        EditorSessionManager manager = EditorSessionManager.getInstance();
        Map<String, String> newConfigs = Map.of("config.yml", "updated: true\n");
        String payloadJson = manager.createPayloadJson(newConfigs);

        manager.applyPayload(payloadJson).join();

        // Verify updated
        String updatedContent = Files.readString(configFile);
        assertTrue(updatedContent.contains("updated: true"));

        // Verify .bak file created
        Path bakFile = pluginPath.resolve("config.yml.bak");
        assertTrue(Files.exists(bakFile));
        assertEquals("original: true\n", Files.readString(bakFile));
    }

    @Test
    @DisplayName("S-006: Pre-init fail-closed guards in EditorCmd and ApplyCmd")
    void preInitFailClosed() {
        RTP.configs = null;
        RTP.serverAccessor = null;

        EditorCmd editorCmd = new EditorCmd(null);
        ApplyCmd applyCmd = new ApplyCmd(null);

        // Command returns true but fails closed gracefully
        assertTrue(editorCmd.onCommand(UUID.randomUUID(), Map.of(), null));
        assertTrue(applyCmd.onCommand(UUID.randomUUID(), Map.of("token", java.util.List.of("123")), null));

        assertThrows(IllegalStateException.class, () -> EditorSessionManager.getInstance().collectCurrentConfigs());
    }

    @Test
    @DisplayName("EditorCmd and ApplyCmd full execution flow with mock transport")
    void commandsExecutionFlow() {
        AtomicReference<byte[]> storedPayload = new AtomicReference<>();
        String expectedKey = "test-token-777";
        HttpClient mockClient = new MockHttpClient(storedPayload, expectedKey);
        EditorHttpTransport transport = new EditorHttpTransport(mockClient, "https://mock.bytebin", "https://editor.rtp");

        EditorCmd editorCmd = new EditorCmd(null, transport);
        ApplyCmd applyCmd = new ApplyCmd(editorCmd, transport);

        UUID caller = UUID.randomUUID();

        // Run /rtp editor
        boolean editorResult = editorCmd.onCommand(caller, Map.of(), null);
        assertTrue(editorResult);

        // Run /rtp editor apply <token>
        boolean applyResult = applyCmd.onCommand(caller, Map.of("token", java.util.List.of(expectedKey)), null);
        assertTrue(applyResult);
    }

    @Test
    @DisplayName("EditorLocalSubCmd exports standalone HTML bundle under <dataFolder>/editor/index.html")
    void editorLocalSubCmdExportsHtmlBundle() {
        EditorLocalSubCmd localSubCmd = new EditorLocalSubCmd(null);
        UUID caller = UUID.randomUUID();

        boolean result = localSubCmd.onCommand(caller, Map.of(), null);
        assertTrue(result);

        Path expectedHtml = tempDir.resolve("plugins/RTP/editor/index.html");
        // Wait briefly for async completion
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && (!Files.exists(expectedHtml) || getFileSize(expectedHtml) == 0)) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        assertTrue(Files.exists(expectedHtml));
        try {
            String htmlContent = Files.readString(expectedHtml, StandardCharsets.UTF_8);
            assertTrue(htmlContent.contains("<!DOCTYPE html>"));
            assertTrue(htmlContent.contains("LeafRTP Engine"));
            assertTrue(htmlContent.contains("const configs ="));
            assertTrue(htmlContent.contains("const docs ="));
        } catch (IOException e) {
            fail("Failed to read exported HTML file: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("EditorCmd falls back to local HTML bundle when byte-store upload fails")
    void editorCmdFallbackToLocalOnNetworkError() {
        HttpClient errorClient = new MockErrorHttpClient(500, "Internal Server Error");
        EditorHttpTransport transport = new EditorHttpTransport(errorClient, "https://mock.bytebin", "https://editor.rtp");
        EditorCmd editorCmd = new EditorCmd(null, transport);
        UUID caller = UUID.randomUUID();

        Path expectedHtml = tempDir.resolve("plugins/RTP/editor/index.html");
        try {
            Files.deleteIfExists(expectedHtml);
        } catch (IOException ignored) {}

        boolean result = editorCmd.onCommand(caller, Map.of(), null);
        assertTrue(result);

        // Wait briefly for async fallback export
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && (!Files.exists(expectedHtml) || getFileSize(expectedHtml) == 0)) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
        }

        assertTrue(Files.exists(expectedHtml));
    }

    private static long getFileSize(Path p) {
        try {
            return Files.exists(p) ? Files.size(p) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private static class MockHttpClient extends HttpClient {
        private final AtomicReference<byte[]> storedPayload;
        private final String key;

        public MockHttpClient(AtomicReference<byte[]> storedPayload, String key) {
            this.storedPayload = storedPayload;
            this.key = key;
        }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override
        public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override
        public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override
        public Version version() { return Version.HTTP_2; }
        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.of(Runnable::run); }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseHandler);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            if ("POST".equals(request.method())) {
                try {
                    // Extract body from publisher
                    var subscriber = HttpResponse.BodySubscribers.ofByteArray();
                    request.bodyPublisher().ifPresent(pub -> pub.subscribe(new FlowSubscriberBridge(subscriber)));
                    storedPayload.set(subscriber.getBody().toCompletableFuture().join());

                    HttpResponse<T> resp = new MockHttpResponse<>(200, (T) ("{\"key\":\"" + key + "\"}"));
                    return CompletableFuture.completedFuture(resp);
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            } else if ("GET".equals(request.method())) {
                byte[] data = storedPayload.get();
                if (data == null) data = new byte[0];
                HttpResponse<T> resp = new MockHttpResponse<>(200, (T) data);
                return CompletableFuture.completedFuture(resp);
            }
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported method"));
        }
    }

    private static class MockErrorHttpClient extends HttpClient {
        private final int statusCode;
        private final String message;

        public MockErrorHttpClient(int statusCode, String message) {
            this.statusCode = statusCode;
            this.message = message;
        }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override
        public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override
        public Redirect followRedirects() { return Redirect.NEVER; }
        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override
        public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override
        public Version version() { return Version.HTTP_2; }
        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.of(Runnable::run); }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return sendAsync(request, responseHandler);
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            throw new UnsupportedOperationException();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
            HttpResponse<T> resp = new MockHttpResponse<>(statusCode, (T) message);
            return CompletableFuture.completedFuture(resp);
        }
    }

    private static class MockHttpResponse<T> implements HttpResponse<T> {
        private final int code;
        private final T body;

        public MockHttpResponse(int code, T body) {
            this.code = code;
            this.body = body;
        }

        @Override public int statusCode() { return code; }
        @Override public HttpRequest request() { return null; }
        @Override public java.util.Optional<HttpResponse<T>> previousResponse() { return java.util.Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true); }
        @Override public T body() { return body; }
        @Override public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        @Override public URI uri() { return URI.create("http://localhost"); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }

    private static class FlowSubscriberBridge implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final HttpResponse.BodySubscriber<byte[]> target;

        public FlowSubscriberBridge(HttpResponse.BodySubscriber<byte[]> target) {
            this.target = target;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            target.onSubscribe(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer item) {
            target.onNext(java.util.List.of(item));
        }

        @Override
        public void onError(Throwable throwable) {
            target.onError(throwable);
        }

        @Override
        public void onComplete() {
            target.onComplete();
        }
    }
}
