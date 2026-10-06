package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Web editor hardening (ADR-104, ADR-106): secrets never leave in a snapshot and survive a round
 * trip, the apply path allow-list and digest rule, https-only transport, the gunzip cap, local
 * session expiry, the trust attempt limit and owner-only exports. Failures are refused visibly (S-004).
 */
@DisplayName("ADR-104 / ADR-106 / S-004: web editor secret redaction, apply and transport hardening")
class EditorSecurityHardeningTest {

    private static final String VAULT = String.join("\n",
            "# credentials for tests",
            "database:",
            "  host: \"db.example\"",
            "  password: \"hunter2\"   # keep me",
            "  url: \"jdbc:mysql://rtp:s3cr3t@db.example:3306/rtp?useSSL=true\"",
            "  pool:",
            "    size: 4",
            "api-key: abcdef123",
            "secretEnv: \"RTP_NET_SECRET\"",
            "token: |",
            "  line-one-secret",
            "  line-two-secret",
            "servers:",
            "  - name: a",
            "    authToken: \"tok-aaa\"",
            "  - name: b",
            "    authToken: 'tok-bbb'",
            "jdbc: \"jdbc:postgresql://db/rtp?user=rtp&password=pg-secret\"",
            "");

    @TempDir
    Path tempDir;
    private Path pluginDir;

    @BeforeEach
    void setUp() throws IOException {
        File dir = tempDir.resolve("plugins/RTP").toFile();
        dir.mkdirs();
        RTPTestSetup.install(dir);
        pluginDir = RTP.serverAccessor.getPluginDirectory().toPath();
        Files.createDirectories(pluginDir.resolve("advanced"));
        Files.writeString(pluginDir.resolve("advanced/vault.yml"), VAULT, StandardCharsets.UTF_8);
        TrustCmd.resetFailures();
    }

    @AfterEach
    void tearDown() {
        TrustCmd.resetFailures();
        TrustCmd.clock = System::currentTimeMillis;
        EditorLiveFeed.stopActive("test teardown");
        RTPTestSetup.cleanUp();
    }

    // ---- 1. redaction ----

    @Test
    @DisplayName("No secret value reaches the snapshot or its upload payload")
    void snapshotRedactsSecrets() {
        EditorSessionManager m = EditorSessionManager.getInstance();
        Map<String, String> configs = m.collectCurrentConfigs();
        String vault = configs.get("advanced/vault.yml");
        assertNotNull(vault, configs.keySet().toString());
        String payload = m.createPayloadJson(configs);
        for (String secret : new String[]{"hunter2", "s3cr3t", "abcdef123", "line-one-secret", "line-two-secret",
                "tok-aaa", "tok-bbb", "pg-secret"}) {
            assertFalse(vault.contains(secret), secret + " leaked into the snapshot:\n" + vault);
            assertFalse(payload.contains(secret), secret + " leaked into the upload payload");
        }
        assertTrue(vault.contains("password: \"<redacted>\"   # keep me"), vault);
        assertTrue(vault.contains("host: \"db.example\""), "non-secret values stay");
        assertTrue(vault.contains("size: 4"));
        assertTrue(vault.contains("secretEnv: \"RTP_NET_SECRET\""), "an env var name is not a secret");
        assertTrue(vault.contains("name: b"));
    }

    @Test
    @DisplayName("Round trip: applying the redacted snapshot keeps every on-disk secret")
    void roundTripKeepsSecrets() throws IOException {
        EditorSessionManager m = EditorSessionManager.getInstance();
        String redacted = m.collectCurrentConfigs().get("advanced/vault.yml");
        String edited = redacted.replace("size: 4", "size: 8");
        m.applyPayload(m.createPayloadJson(Map.of("advanced/vault.yml", edited))).join();
        assertEquals(VAULT.replace("size: 4", "size: 8"),
                Files.readString(pluginDir.resolve("advanced/vault.yml"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Hot-Apply over the channel restores sentinels the same way")
    void channelApplyKeepsSecrets() throws IOException {
        String redacted = EditorSessionManager.getInstance().collectCurrentConfigs().get("advanced/vault.yml");
        String ack = EditorLoopbackApply.forChannel()
                .handleAuthorized(Map.of("region", "default", "files", Map.of("advanced/vault.yml", redacted)))
                .join();
        assertTrue(ack.contains("\"success\":true"), ack);
        assertEquals(VAULT, Files.readString(pluginDir.resolve("advanced/vault.yml"), StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("A sentinel with no current value to keep fails the apply instead of writing it")
    void sentinelWithoutValueRefused() {
        EditorSessionManager m = EditorSessionManager.getInstance();
        CompletionException e = assertThrows(CompletionException.class, () -> m.applyPayload(
                m.createPayloadJson(Map.of("fresh.yml", "password: \"<redacted>\"\n"))).join());
        assertTrue(e.getCause().getMessage().contains("no current value"), e.getCause().getMessage());
        assertFalse(Files.exists(pluginDir.resolve("fresh.yml")));
    }

    @Test
    @DisplayName("Flow-style maps and lists holding a secret are redacted and restored as whole values")
    void flowStyleSecretsRedacted() {
        String yaml = "db: {host: h, password: fl0w-a}\n"
                + "json: {\"user\": \"u\", \"apiKey\": \"fl0w-b\"}\n"
                + "nested: {auth: {user: u, token: fl0w-c}}\n"
                + "multi: {user: u,\n"
                + "  password: fl0w-d}\n"
                + "servers:\n"
                + "  - {name: a, secret: fl0w-e}\n"
                + "  - {name: b}\n"
                + "indented:\n"
                + "  {credentials: fl0w-f}\n"
                + "urls: [\"mysql://root:fl0w-g@db/x\"]\n"
                + "plain: {size: 4, note: don't}\n"
                + "after:\n"
                + "  password: fl0w-h\n";
        String red = EditorSecurity.redactYaml(yaml);
        for (String s : new String[]{"fl0w-a", "fl0w-b", "fl0w-c", "fl0w-d", "fl0w-e", "fl0w-f", "fl0w-g", "fl0w-h"}) {
            assertFalse(red.contains(s), s + " leaked:\n" + red);
        }
        assertTrue(red.contains("db: \"<redacted>\""), red);
        assertTrue(red.contains("  - {name: b}"), "a flow value without secrets stays:\n" + red);
        assertTrue(red.contains("plain: {size: 4, note: don't}"), red);

        assertEquals(yaml, EditorSecurity.restoreRedacted("f.yml", red, yaml));
        String edited = red.replace("  - {name: b}", "  - {name: c}");
        assertEquals(yaml.replace("  - {name: b}", "  - {name: c}"), EditorSecurity.restoreRedacted("f.yml", edited, yaml));
    }

    @Test
    @DisplayName("A sentinel the restore cannot place is refused, never written to disk")
    void unplacedSentinelRefused() {
        String current = "db: {password: real}\n";
        String incoming = "db: {password: \"<redacted>\", extra: 1}\n";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditorSecurity.restoreRedacted("f.yml", incoming, current));
        assertTrue(e.getMessage().contains("<redacted>"), e.getMessage());
    }

    // ---- 2. apply path, digest, transport ----

    @Test
    @DisplayName("Apply paths: relative YAML only, nothing hidden, traversing or under editor/")
    void applyPathRules() {
        for (String ok : new String[]{"config.yml", "regions/default.yml", "addons/Demo/a b.yaml"}) {
            assertDoesNotThrow(() -> EditorSecurity.checkConfigPath(ok), ok);
        }
        for (String bad : new String[]{"editor/keys/editor-private.yml", "editor/trusted-editors.json", "Editor/x.yml",
                ".hidden/x.yml", "regions/.x.yml", "a/../b.yml", "../b.yml", "./a.yml", "a//b.yml", "/abs.yml",
                "C:/x.yml", "a\\b.yml", "x.yml\0.yml", "plugin.jar", "notes.txt", " a.yml", ""}) {
            assertThrows(IllegalArgumentException.class, () -> EditorSecurity.checkConfigPath(bad), bad);
        }
        EditorSessionManager m = EditorSessionManager.getInstance();
        assertThrows(IllegalArgumentException.class, () -> m.parseAndValidatePayload(
                m.createPayloadJson(Map.of("editor/keys/x.yml", "a: 1\n"))));
    }

    @Test
    @DisplayName("A byte-store payload without sha256 is refused; with one it applies")
    void remotePayloadNeedsDigest() throws IOException {
        Path config = pluginDir.resolve("digest.yml");
        Files.writeString(config, "a: 1\n", StandardCharsets.UTF_8);
        String noDigest = "{\"version\":1,\"files\":{\"digest.yml\":\"a: 2\\n\"}}";
        assertThrows(EditorSessionManager.MissingDigestException.class,
                () -> EditorSessionManager.getInstance().parseAndValidatePayload(noDigest, true));

        new ApplyCmd(null, remote(noDigest)).applyToken(UUID.randomUUID(), "remoteKey1").join();
        assertEquals("a: 1\n", Files.readString(config, StandardCharsets.UTF_8), "not applied without a digest");

        String withDigest = EditorSessionManager.getInstance().createPayloadJson(Map.of("digest.yml", "a: 3\n"));
        new ApplyCmd(null, remote(withDigest)).applyToken(UUID.randomUUID(), "remoteKey2").join();
        assertEquals("a: 3\n", Files.readString(config, StandardCharsets.UTF_8));
    }

    private static EditorHttpTransport remote(String body) {
        return new EditorHttpTransport(HttpClient.newHttpClient(), "https://bytebin.invalid", "https://editor.invalid") {
            @Override
            public CompletableFuture<String> fetchPayload(String token) {
                return CompletableFuture.completedFuture(body);
            }
        };
    }

    @Test
    @DisplayName("Byte-store and relay URLs must be https unless they point at a loopback host")
    void httpsUnlessLoopback() {
        String[] urls = EditorHttpTransport.settingsUrls(Map.of("bytebinUrl", "http://bin.example.org",
                "relayUrl", "ws://sockets.example.org"));
        assertEquals(EditorHttpTransport.DEFAULT_BYTEBIN_URL, urls[0]);
        assertEquals(EditorHttpTransport.DEFAULT_RELAY_URL, urls[1]);
        urls = EditorHttpTransport.settingsUrls(Map.of("bytebinUrl", "http://localhost:3000", "relayUrl", "http://[::1]:9"));
        assertEquals("http://localhost:3000", urls[0]);
        assertEquals("http://[::1]:9", urls[1]);
        assertEquals(EditorHttpTransport.DEFAULT_BYTEBIN_URL,
                new EditorHttpTransport(HttpClient.newHttpClient(), "http://bin.example.org", "https://e").getBytebinUrl());
        assertTrue(EditorSecurity.secureOrLoopback(URI.create("https://bin.example.org")));
        assertTrue(EditorSecurity.secureOrLoopback(URI.create("ws://127.0.0.1:8080")));
        assertFalse(EditorSecurity.secureOrLoopback(URI.create("http://127.0.0.2")));
        assertFalse(EditorSecurity.secureOrLoopback(URI.create("http://localhost.evil.example")));
    }

    @Test
    @DisplayName("gunzip stops at 4 MiB with an IOException (no decompression bomb)")
    void gzipBombRefused() throws IOException {
        byte[] bomb = EditorHttpTransport.gzipCompress("0".repeat(EditorHttpTransport.MAX_DECOMPRESSED_BYTES + 1));
        assertTrue(bomb.length < 64 * 1024, "the bomb itself is small");
        assertThrows(IOException.class, () -> EditorHttpTransport.gzipDecompress(bomb));
        String fits = "1".repeat(EditorHttpTransport.MAX_DECOMPRESSED_BYTES);
        assertEquals(fits, EditorHttpTransport.gzipDecompress(EditorHttpTransport.gzipCompress(fits)));
    }

    @Test
    @DisplayName("Logs carry a token prefix only; fetch errors do not echo the token")
    void tokenPrefixOnly() {
        assertEquals("abcdef\u2026", EditorSecurity.tokenPrefix("abcdefghijklmnop"));
        assertEquals("abc", EditorSecurity.tokenPrefix("abc"));
        assertFalse(EditorSecurity.isToken("../../x"));
        assertTrue(EditorSecurity.isToken("test-token-777"));
    }

    // ---- 3. trust attempts ----

    @Test
    @DisplayName("Five failed trust codes in a minute lock the sender out until the window passes")
    void trustAttemptLimit() {
        UUID op = UUID.randomUUID();
        for (int i = 0; i < TrustCmd.MAX_FAILURES; i++) {
            assertFalse(TrustCmd.rateLimited(op, 1_000L + i));
            TrustCmd.recordFailure(op, 1_000L + i);
        }
        assertTrue(TrustCmd.rateLimited(op, 2_000L));
        assertFalse(TrustCmd.rateLimited(UUID.randomUUID(), 2_000L), "per sender");
        assertFalse(TrustCmd.rateLimited(op, 1_000L + TrustCmd.FAILURE_WINDOW_MILLIS), "the oldest failure left the window");

        // The command itself refuses before looking at the code
        TrustCmd.clock = () -> 5_000L;
        for (int i = 0; i < TrustCmd.MAX_FAILURES; i++) TrustCmd.recordFailure(op, 5_000L);
        assertTrue(new TrustCmd(null).onCommand(op, Map.of("nonce", List.of("abcd2345")), null));
        assertTrue(TrustCmd.rateLimited(op, 5_000L));
    }

    // ---- 5. session TTL ----

    @Test
    @DisplayName("Local sessions expire after the relay TTL, are capped, and are dropped after apply")
    void sessionTtl() {
        AtomicLong now = new AtomicLong(0L);
        EditorSessionManager m = new EditorSessionManager(now::get);
        String t = m.createSession("{}");
        assertEquals("{}", m.getSession(t));
        now.set(EditorSessionManager.SESSION_TTL_MILLIS);
        assertNull(m.getSession(t), "expired");
        assertFalse(m.getActiveTokens().contains(t));

        for (int i = 0; i < EditorSessionManager.MAX_SESSIONS + 5; i++) {
            now.incrementAndGet();
            m.createSession("{\"n\":" + i + "}");
        }
        assertEquals(EditorSessionManager.MAX_SESSIONS, m.getActiveTokens().size());
        String last = m.createSession("{}");
        assertTrue(m.removeSession(last));
        assertNull(m.getSession(last));
    }

    @Test
    @DisplayName("A token apply drops its local session payload")
    void appliedSessionDropped() {
        EditorSessionManager m = EditorSessionManager.getInstance();
        String token = m.createSession(m.createPayloadJson(Map.of("dropped.yml", "a: 1\n")));
        new ApplyCmd(null, remote("{}")).applyToken(UUID.randomUUID(), token).join();
        assertNull(m.getSession(token));
    }

    // ---- 7. owner-only export ----

    @Test
    @DisplayName("The exported local page (it embeds the loopback token) is owner-only")
    void exportOwnerOnly() throws IOException {
        Path html = pluginDir.resolve("editor/index.html");
        EditorSessionManager.getInstance().exportLocalEditorHtml(html, Map.of("config.yml", "a: 1\n"));
        assertTrue(Files.size(html) > 0);
        PosixFileAttributeView posix = Files.getFileAttributeView(html, PosixFileAttributeView.class);
        if (posix != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(html, AclFileAttributeView.class);
        Assumptions.assumeTrue(acl != null, "neither POSIX nor ACL view on this file system");
        List<AclEntry> entries = acl.getAcl();
        assertFalse(entries.isEmpty());
        for (AclEntry e : entries) assertEquals(acl.getOwner(), e.principal(), entries.toString());
    }
}
