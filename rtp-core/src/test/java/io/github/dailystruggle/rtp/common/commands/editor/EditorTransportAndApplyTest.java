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
        // Local exports start a live feed and loopback channel that write into tempDir
        EditorLiveFeed.stopActive("test teardown");
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

        // Run /rtp editor apply token=<token>
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
            assertTrue(htmlContent.contains("let configs = defaultConfigs"));
            assertTrue(htmlContent.contains("let docs = defaultDocs"));
            assertTrue(htmlContent.contains("id=\"rtp-embedded-payload\""),
                    "Local export must embed the live payload for offline ingest");
        } catch (IOException e) {
            fail("Failed to read exported HTML file: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Embedded payload cannot terminate its script block when configs contain </script> or <!--")
    void embeddedPayloadEscapesScriptTerminators() {
        String template = "<html><head><title>t</title></head><body><script>boot()</script></body></html>";
        String payload = "{\"files\":{\"config.yml\":\"a: </script><script>alert(1)</script> <!-- x\"}}";

        String html = EditorSessionManager.embedPayload(template, payload);

        int open = html.indexOf("<script type=\"application/json\" id=\"rtp-embedded-payload\">");
        assertTrue(open >= 0 && open < html.indexOf("</head>"));
        int close = html.indexOf("</script>", open);
        String json = html.substring(html.indexOf('>', open) + 1, close);
        assertEquals(-1, json.indexOf('<'), "No raw '<' may remain inside the JSON block");
        assertTrue(json.contains("alert(1)"), "Block must end after the full payload, not at the injected </script>");
        assertTrue(json.contains("\\u003c/script>"));
        assertTrue(html.contains("<script>boot()</script>"), "Template scripts must be left untouched");
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

    @Test
    @DisplayName("REPRODUCER: Transmission failure when payload JSON is reformatted or stringified by web client")
    void transmissionFailureOnReformattedJsonStringify() {
        EditorSessionManager manager = EditorSessionManager.getInstance();
        Map<String, String> configs = Map.of("config.yml", "radius: 1000\n");
        String originalPayload = manager.createPayloadJson(configs);

        // Web clients (like browser JSON.stringify) format JSON with spaces and newlines
        // Or reorder attributes/whitespace
        String prettyPrintedPayload = "{\n" +
                "  \"version\": 1,\n" +
                "  \"sha256\": \"" + manager.parseAndValidatePayload(originalPayload).sha256() + "\",\n" +
                "  \"files\": {\n" +
                "    \"config.yml\": \"radius: 1000\\n\"\n" +
                "  }\n" +
                "}";

        // This reproduces the exact transmission breakage where web clients reserializing the payload
        // causes raw substring SHA-256 calculation to mismatch and reject valid transmissions
        EditorSessionManager.ParsedPayload parsed = manager.parseAndValidatePayload(prettyPrintedPayload);
        assertEquals(1, parsed.files().size());
        assertEquals("radius: 1000\n", parsed.files().get("config.yml"));
    }

    @Test
    @DisplayName("REPRODUCER: Token extraction breaks when byte-store returns JSON with status or additional fields")
    void transmissionFailureOnMultiFieldJsonResponse() {
        // When bytebin or an API proxy returns {"status": 200, "key": "abc-xyz-123"}
        // extractKeyFromJsonOrPlain wrongly extracts "200" or mangles the token
        String jsonWithStatus = "{\"status\": 200, \"key\": \"abc-xyz-123\"}";
        HttpClient mockClient = new HttpClient() {
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public Version version() { return Version.HTTP_2; }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.of(Runnable::run); }
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { return sendAsync(request, responseHandler); }
            @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) { throw new UnsupportedOperationException(); }
            @Override @SuppressWarnings("unchecked") public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
                HttpResponse<T> resp = new MockHttpResponse<>(200, (T) jsonWithStatus);
                return CompletableFuture.completedFuture(resp);
            }
        };

        EditorHttpTransport transport = new EditorHttpTransport(mockClient, "https://mock.bytebin", "https://editor.rtp");
        String token = transport.postPayload("{\"version\":1}").join();
        assertEquals("abc-xyz-123", token);
    }

    @Test
    @DisplayName("REPRODUCER: Token extraction must reject error responses instead of interpreting error messages as keys")
    void transmissionRejectsErrorJsonResponseAsKey() {
        // When bytebin returns an error JSON e.g. {"error": "payload too large"}
        String jsonError = "{\"error\": \"payload too large\"}";
        HttpClient mockClient = new HttpClient() {
            @Override public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
            @Override public Redirect followRedirects() { return Redirect.NEVER; }
            @Override public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
            @Override public javax.net.ssl.SSLContext sslContext() { return null; }
            @Override public javax.net.ssl.SSLParameters sslParameters() { return null; }
            @Override public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
            @Override public Version version() { return Version.HTTP_2; }
            @Override public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.of(Runnable::run); }
            @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) { return sendAsync(request, responseHandler); }
            @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) { throw new UnsupportedOperationException(); }
            @Override @SuppressWarnings("unchecked") public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseHandler) {
                HttpResponse<T> resp = new MockHttpResponse<>(200, (T) jsonError);
                return CompletableFuture.completedFuture(resp);
            }
        };

        EditorHttpTransport transport = new EditorHttpTransport(mockClient, "https://mock.bytebin", "https://editor.rtp");
        assertThrows(RuntimeException.class, () -> transport.postPayload("{\"version\":1}").join());
    }

    @Test
    @DisplayName("REPRODUCER: Fetching non-existent or expired token fails with descriptive 404 error")
    void transmissionFailsDescriptivelyOn404NotFound() {
        HttpClient errorClient = new MockErrorHttpClient(404, "Not Found");
        EditorHttpTransport transport = new EditorHttpTransport(errorClient, "https://mock.bytebin", "https://editor.rtp");

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> transport.fetchPayload("expired-token-123").join());
        assertTrue(ex.getMessage().contains("404"), "Message must reference status 404");
        assertTrue(ex.getMessage().contains("expired-token-123"), "Message must mention failed token");
    }

    @Test
    @DisplayName("AGGRESSIVE: Local ephemeral session allows air-gap token apply without remote transport")
    void localEphemeralSessionAirGapApply() throws IOException {
        Path pluginPath = RTP.serverAccessor.getPluginDirectory().toPath();
        Path configFile = pluginPath.resolve("config.yml");
        Files.writeString(configFile, "radius: 500\n");

        EditorSessionManager manager = EditorSessionManager.getInstance();
        Map<String, String> localEdits = Map.of("config.yml", "radius: 3500\n");
        String payload = manager.createPayloadJson(localEdits);

        // Create local session token
        String localToken = manager.createSession(payload);
        assertNotNull(localToken);
        assertEquals(32, localToken.length());

        // Verify session retrieval
        String retrieved = manager.getSession(localToken);
        assertEquals(payload, retrieved);

        // Apply via ApplyCmd with dummy transport (should not touch remote transport)
        HttpClient errorClient = new MockErrorHttpClient(500, "Should not be called");
        EditorHttpTransport transport = new EditorHttpTransport(errorClient, "https://mock.bytebin", "https://editor.rtp");
        ApplyCmd applyCmd = new ApplyCmd(null, transport);

        // Await full async apply pipeline completion so file I/O and reload finish before test tearDown
        applyCmd.applyToken(UUID.randomUUID(), localToken).join();

        assertTrue(Files.readString(configFile).contains("radius: 3500"));
    }

    @Test
    @DisplayName("AGGRESSIVE: Corrupted or uncompressed payload handling in transport decompressor")
    void transportDecompressorHandlesRawAndGzipBytes() throws IOException {
        String plain = "{\"version\":1,\"files\":{}}";
        byte[] gzipped = EditorHttpTransport.gzipCompress(plain);

        assertEquals(plain, EditorHttpTransport.gzipDecompress(gzipped));
        assertEquals(plain, EditorHttpTransport.gzipDecompress(plain.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("REPRODUCER: Two-way transmission cycle (modified client payload POST and apply)")
    void twoWayTransmissionCycleWithClientModifications() throws IOException {
        Path pluginPath = RTP.serverAccessor.getPluginDirectory().toPath();
        Path configFile = pluginPath.resolve("config.yml");
        Files.writeString(configFile, "radius: 1000\n");

        AtomicReference<byte[]> bytebinStore = new AtomicReference<>();
        String uploadKey = "token-browser-save-999";
        HttpClient mockClient = new MockHttpClient(bytebinStore, uploadKey);
        EditorHttpTransport transport = new EditorHttpTransport(mockClient, "https://mock.bytebin", "https://editor.rtp");

        EditorSessionManager manager = EditorSessionManager.getInstance();

        // 1. Server generates initial payload
        String initialPayload = manager.createPayloadJson(manager.collectCurrentConfigs());
        assertTrue(initialPayload.contains("radius: 1000"));

        // 2. Browser receives initial payload and modifies it: radius 1000 -> 2500
        Map<String, String> modifiedFiles = new java.util.LinkedHashMap<>();
        modifiedFiles.put("config.yml", "radius: 2500\n");

        // Canonical JSON payload constructed by browser / client
        String browserPayload = manager.createPayloadJson(modifiedFiles);

        // 3. Browser POSTs modified payload back to bytebin
        String newSessionToken = transport.postPayload(browserPayload).join();
        assertEquals(uploadKey, newSessionToken);

        // 4. Server receives new session token via /rtp editor apply token=<token>
        String fetchedPayload = transport.fetchPayload(newSessionToken).join();
        assertEquals(browserPayload, fetchedPayload);

        // 5. Server applies the fetched payload
        manager.applyPayload(fetchedPayload).join();

        // 6. Verify changes applied and .bak backup created
        String updatedConfig = Files.readString(configFile);
        assertTrue(updatedConfig.contains("radius: 2500"));
        Path bakFile = pluginPath.resolve("config.yml.bak");
        assertTrue(Files.exists(bakFile));
        assertEquals("radius: 1000\n", Files.readString(bakFile));
    }

    @Test
    @DisplayName("AGGRESSIVE: Exclude hidden files and directories from collected configurations")
    void collectCurrentConfigsExcludesHiddenFilesAndDirectories() throws IOException {
        Path pluginPath = RTP.serverAccessor.getPluginDirectory().toPath();

        // Create normal config file
        Path normalConfig = pluginPath.resolve("regions").resolve("survival.yml");
        Files.createDirectories(normalConfig.getParent());
        Files.writeString(normalConfig, "radius: 2000\n");

        // Create hidden files and directories (.shape, .vert, .hidden.yml)
        Path hiddenDir = pluginPath.resolve(".shape");
        Files.createDirectories(hiddenDir);
        Files.writeString(hiddenDir.resolve("circle.yml"), "hidden_data: true\n");

        Path hiddenFile = pluginPath.resolve(".hidden.yml");
        Files.writeString(hiddenFile, "secret: true\n");

        Path subHiddenDir = pluginPath.resolve("definitions").resolve(".vert");
        Files.createDirectories(subHiddenDir);
        Files.writeString(subHiddenDir.resolve("points.yml"), "points: [1, 2]\n");

        EditorSessionManager manager = EditorSessionManager.getInstance();
        Map<String, String> configs = manager.collectCurrentConfigs();

        // Normal configs must be present
        assertTrue(configs.containsKey("regions/survival.yml") || configs.containsKey("regions\\survival.yml"));

        // Hidden files and hidden directory contents must be excluded
        for (String key : configs.keySet()) {
            assertFalse(key.startsWith("."), "Config key should not start with .: " + key);
            assertFalse(key.contains("/.") || key.contains("\\."), "Config key should not contain hidden segment: " + key);
            assertFalse(key.contains(".shape"), "Config key should not contain .shape: " + key);
            assertFalse(key.contains(".vert"), "Config key should not contain .vert: " + key);
        }
    }

    @Test
    @DisplayName("AGGRESSIVE: Live telemetry snapshot and regionModels with biome data in payload")
    void payloadIncludesLiveTelemetryAndRegionModels() throws IOException {
        EditorSessionManager manager = EditorSessionManager.getInstance();
        String payloadJson = manager.createPayloadJson();

        // Validate payload structure
        assertTrue(payloadJson.contains("\"telemetry\":"), "Payload must contain telemetry");
        assertTrue(payloadJson.contains("\"regionModels\":"), "Payload must contain regionModels");
        assertTrue(payloadJson.contains("\"tps1m\":"), "Telemetry must contain tps1m");
        assertTrue(payloadJson.contains("\"msptMean\":"), "Telemetry must contain msptMean");
        assertTrue(payloadJson.contains("\"heapUsedGb\":"), "Telemetry must contain heapUsedGb");
        assertTrue(payloadJson.contains("\"queueDepth\":"), "Telemetry must contain queueDepth");

        // Validate standalone HTML export embeds region profiles and live telemetry
        Path targetHtml = RTP.serverAccessor.getPluginDirectory().toPath().resolve("editor").resolve("index.html");
        manager.exportLocalEditorHtml(targetHtml, manager.collectCurrentConfigs());
        assertTrue(Files.exists(targetHtml));

        try {
            String html = Files.readString(targetHtml);
            int blockStart = html.indexOf("<script type=\"application/json\" id=\"rtp-embedded-payload\">");
            assertTrue(blockStart >= 0, "Exported HTML must embed the live payload block");
            assertTrue(blockStart < html.indexOf("</head>"), "Payload block must precede the editor bootstrap script");
            String block = html.substring(blockStart, html.indexOf("</script>", blockStart));
            assertTrue(block.contains("\"regionModels\":"), "Embedded payload must carry regionModels");
            assertTrue(block.contains("\"telemetry\":"), "Embedded payload must carry live telemetry");
            assertTrue(block.contains("\"files\":"), "Embedded payload must carry config files");
            assertTrue(html.contains("function ingestSessionPayload("), "Exported HTML must ingest the embedded payload");
            assertTrue(html.contains("let regionProfiles ="), "Exported HTML must expose region profiles for editing");
            assertTrue(html.contains("bytebinUrl}/post`"), "Exported HTML must support bytebin POST");
            assertTrue(html.contains("a.download = `${fallbackToken}.json`"),
                    "Exported HTML must support air-gap <token>.json download fallback");
        } catch (IOException e) {
            fail("Failed reading exported HTML: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("AGGRESSIVE: Local offline file-drop token apply via apply.json or <token>.json")
    void localFileDropApplySucceedsWithoutNetwork() throws IOException {
        Path pluginPath = RTP.serverAccessor.getPluginDirectory().toPath();
        Path editorDir = pluginPath.resolve("editor");
        Files.createDirectories(editorDir);

        Path configFile = pluginPath.resolve("config.yml");
        Files.writeString(configFile, "radius: 500\n");

        // Write an air-gap apply.json drop
        EditorSessionManager manager = EditorSessionManager.getInstance();
        Map<String, String> modified = Map.of("config.yml", "radius: 9999\n");
        String dropPayload = manager.createPayloadJson(modified);
        Files.writeString(editorDir.resolve("apply.json"), dropPayload);

        // Apply using dummy error client transport
        HttpClient errorClient = new MockErrorHttpClient(500, "Should not hit bytebin for local file drop");
        EditorHttpTransport transport = new EditorHttpTransport(errorClient, "https://mock.bytebin", "https://editor.rtp");
        ApplyCmd applyCmd = new ApplyCmd(null, transport);

        // Run apply token=apply
        applyCmd.applyToken(UUID.randomUUID(), "apply").join();

        assertTrue(Files.readString(configFile).contains("radius: 9999"));
    }

    @Test
    @DisplayName("Subcommand dispatch via TreeCommand executes subcommands exactly once without parameter drop or duplicate execution")
    void editorSubcommandsExecuteExactlyOnceWithoutParameterDropOrDuplication() {
        AtomicReference<byte[]> storedPayload = new AtomicReference<>();
        String expectedKey = "test-token-no-dup";
        HttpClient mockClient = new MockHttpClient(storedPayload, expectedKey);
        EditorHttpTransport transport = new EditorHttpTransport(mockClient, "https://mock.bytebin", "https://editor.rtp");

        EditorCmd editorCmd = new EditorCmd(null, transport);
        UUID caller = UUID.randomUUID();

        // 1. Test /rtp editor apply token=<token>
        CompletableFuture<Boolean> applyFuture = editorCmd.onCommand(caller, perm -> true, msg -> {}, new String[]{"apply", "token=" + expectedKey});
        io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
        assertTrue(applyFuture.join());

        io.github.dailystruggle.rtp.api.entity.RTPCommandSender sender = RTP.serverAccessor.getSender(caller);
        assertNotNull(sender);
        io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender mockSender =
                (io.github.dailystruggle.rtp.common.mock.MockRTPCommandSender) sender;

        // Verify no duplicate usage message was sent and fetching occurred exactly once
        long usageCount = mockSender.sentMessages.stream().filter(m -> m.contains("Usage - /rtp editor apply token=<token>")).count();
        assertEquals(0, usageCount, "Should not print usage message when valid token is supplied");
        long fetchCount = mockSender.sentMessages.stream().filter(m -> m.contains("Fetching configuration payload")).count();
        assertEquals(1, fetchCount, "Fetching message should be emitted exactly once");

        mockSender.sentMessages.clear();

        // 2. Test /rtp editor local
        CompletableFuture<Boolean> localFuture = editorCmd.onCommand(caller, perm -> true, msg -> {}, new String[]{"local"});
        io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
        assertTrue(localFuture.join());

        long genCount = mockSender.sentMessages.stream().filter(m -> m.contains("Asynchronously generating standalone local web editor HTML bundle")).count();
        assertEquals(1, genCount, "Generating message should be emitted exactly once without duplicate dispatch");

        // The export finishes off-thread; let it settle before tempDir is deleted
        long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline && mockSender.sentMessages.stream()
                .noneMatch(m -> m.contains("generated successfully") || m.contains("Failed to generate"))) {
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        EditorLiveFeed.stopActive("test end");
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
            byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
            HttpResponse<T> resp = new MockHttpResponse<>(statusCode, (T) bytes);
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
