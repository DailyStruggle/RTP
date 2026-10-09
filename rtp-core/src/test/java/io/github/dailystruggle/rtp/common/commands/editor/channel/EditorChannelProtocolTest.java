package io.github.dailystruggle.rtp.common.commands.editor.channel;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5 signed editor channel, driven end to end over {@link InMemoryTransport} with a Java
 * page stub: handshake, trust, acceptance rules, routing, caps and lifecycle.
 */
@DisplayName("ADR-106 §5: signed editor channel protocol (in-memory transport)")
class EditorChannelProtocolTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final List<String[]> prompts = new ArrayList<>();
    private final List<String> closedReasons = new ArrayList<>();
    private final List<EditorChannel.Inbound> routed = new ArrayList<>();
    private EditorKeys pluginKeys;
    private TrustedEditors trusted;
    private EditorChannel channel;
    private ChannelPageStub page;

    @BeforeEach
    void setUp() throws IOException {
        pluginKeys = EditorKeys.generate();
        trusted = TrustedEditors.inMemory();
        InMemoryTransport[] pair = InMemoryTransport.pair();
        channel = new EditorChannel(EditorChannel.newChannelId(), pluginKeys, trusted, pair[0], new EditorChannel.Notifier() {
            @Override
            public void trustPrompt(String nonce, String fingerprint) {
                prompts.add(new String[]{nonce, fingerprint});
            }

            @Override
            public void closed(String reason) {
                closedReasons.add(reason);
            }
        }, now::get);
        channel.register("echo", in -> {
            routed.add(in);
            in.reply("{\"type\":\"echo\",\"n\":" + in.body().get("n") + "}");
        });
        channel.register("feed", routed::add);
        channel.start();
        page = new ChannelPageStub(pair[1], channel.id(), pluginKeys.publicKey());
    }

    @AfterEach
    void tearDown() {
        channel.close("test done");
    }

    private void trustPage() {
        page.hello();
        assertEquals(EditorChannel.TrustResult.TRUSTED, channel.trust(page.nonce));
    }

    @Test
    @DisplayName("Snapshot block names the relay, the channel id and the plugin's SPKI key")
    void snapshotBlock() {
        Map<String, Object> block = channel.snapshotBlock();
        assertEquals("memory://plugin", block.get("relay"));
        assertEquals(channel.id(), block.get("id"));
        assertEquals(pluginKeys.publicKeyBase64(), block.get("pluginKey"));
    }

    @Test
    @DisplayName("hello from an untrusted key: challenge + nonce, one operator prompt, then trust makes it trusted")
    void handshakeAndTrust() {
        page.hello();
        JsonObject reply = page.last("hello-reply");
        assertNotNull(reply, "plugin answers hello");
        assertEquals(page.fingerprint(), reply.get("to").getAsString(), "addressed to this browser key");
        assertEquals("stub1", reply.get("hid").getAsString(), "echoes the tab's hello id");
        assertEquals("untrusted", page.state);
        assertTrue(page.challenge.matches("[0-9a-f]{32}"), "128-bit challenge");
        assertTrue(page.nonce.matches("[" + EditorChannel.NONCE_ALPHABET + "]{8}"), "8-character nonce");
        assertEquals(1, prompts.size(), "one prompt to the operator");
        assertEquals(page.nonce, prompts.get(0)[0], "the operator sees the page's code");
        assertEquals(page.fingerprint(), prompts.get(0)[1]);

        String firstChallenge = page.challenge;
        page.hello();
        assertEquals(1, prompts.size(), "a repeated hello does not prompt again while the code is valid");
        assertNotEquals(firstChallenge, page.challenge, "every hello gets a new challenge");
        assertEquals(prompts.get(0)[0], page.nonce, "same code until it expires");

        assertEquals(EditorChannel.TrustResult.TRUSTED, channel.trust(page.nonce));
        assertEquals("trusted", page.state);
        assertTrue(trusted.isTrusted(page.fingerprint()));
        assertEquals(EditorChannel.TrustResult.UNKNOWN, channel.trust(prompts.get(0)[0]), "the code is single-use");

        page.hello();
        assertEquals("trusted", page.state, "a trusted key is told so on hello, with no new prompt");
        assertEquals(1, prompts.size());
    }

    @Test
    @DisplayName("Untrusted keys may only hello and ping; other types are dropped before any handler")
    void untrustedRefused() {
        page.hello();
        long dropped = channel.droppedCount();
        page.send("echo", Map.of("n", 1));
        assertTrue(routed.isEmpty(), "no handler ran for an untrusted key");
        assertEquals(dropped + 1, channel.droppedCount());
        assertTrue(channel.lastDropReason().contains("untrusted"), channel.lastDropReason());

        page.send("ping", null);
        assertEquals(1, page.count("pong"), "ping is answered before trust");
    }

    @Test
    @DisplayName("Trusted, signed, fresh messages route by type and replies reach only the sender")
    void routingAndReply() {
        trustPage();
        page.send("echo", Map.of("n", 7));
        assertEquals(1, routed.size());
        assertEquals("echo", routed.get(0).type());
        assertEquals(page.fingerprint(), routed.get(0).from());
        JsonObject echo = page.last("echo");
        assertEquals(7, echo.get("n").getAsInt());
        assertEquals(page.fingerprint(), echo.get("to").getAsString());
        assertEquals(channel.pluginFingerprint(), echo.get("from").getAsString());

        channel.send("{\"type\":\"feed\",\"data\":{\"seq\":1}}");
        JsonObject feed = page.last("feed");
        assertNotNull(feed, "broadcast reaches the page");
        assertFalse(feed.has("to"), "broadcasts are not addressed");
        assertTrue(page.rejected.isEmpty(), "every plugin frame verified against the snapshot key: " + page.rejected);
    }

    @Test
    @DisplayName("Forged signatures, tampered messages and foreign keys are dropped")
    void forgedDropped() {
        trustPage();
        EditorKeys attacker = EditorKeys.generate();
        String forged = "{\"type\":\"echo\",\"n\":1,\"channel\":\"" + channel.id() + "\",\"seq\":1,\"from\":\""
                + page.fingerprint() + "\",\"challenge\":\"" + page.challenge + "\"}";
        page.sendSigned(forged, attacker);
        assertTrue(routed.isEmpty());
        assertTrue(channel.lastDropReason().contains("bad signature"), channel.lastDropReason());

        String good = forged;
        String sig = Base64.getEncoder().encodeToString(page.keys.sign(good.getBytes(StandardCharsets.UTF_8)));
        page.sendRaw(ChannelPageStub.envelope(good.replace("\"n\":1", "\"n\":2"), sig));
        assertTrue(routed.isEmpty(), "a signature over other bytes does not verify");

        // An attacker's own hello cannot claim the trusted browser's fingerprint
        String hello = "{\"type\":\"hello\",\"channel\":\"" + channel.id() + "\",\"from\":\"" + page.fingerprint()
                + "\",\"publicKey\":\"" + attacker.publicKeyBase64() + "\"}";
        page.sendSigned(hello, attacker);
        assertTrue(channel.lastDropReason().contains("does not match"), channel.lastDropReason());

        page.sendRaw("{\"msg\":\"{}\"}");
        assertTrue(channel.lastDropReason().contains("envelope"), channel.lastDropReason());
        page.sendRaw("not json");
        assertTrue(channel.lastDropReason().contains("malformed"), channel.lastDropReason());
    }

    @Test
    @DisplayName("Replayed or out-of-order seq, a retired challenge, another channel or an unknown type are dropped")
    void replayDropped() {
        trustPage();
        String msg = page.send("echo", Map.of("n", 1));
        assertEquals(1, routed.size());
        page.sendSigned(msg, page.keys);
        assertEquals(1, routed.size(), "exact replay dropped");
        assertTrue(channel.lastDropReason().contains("replayed"), channel.lastDropReason());

        page.seq = 0;
        page.send("echo", Map.of("n", 2));
        assertEquals(1, routed.size(), "a lower seq is dropped");

        String old = page.challenge;
        for (int i = 0; i < EditorChannel.MAX_CHALLENGES_PER_KEY; i++) page.hello();
        page.challenge = old;
        page.seq = 100;
        page.send("echo", Map.of("n", 3));
        assertEquals(1, routed.size(), "a challenge pushed out by newer hellos is retired");
        assertTrue(channel.lastDropReason().contains("challenge"), channel.lastDropReason());

        page.hello();
        String other = "{\"type\":\"echo\",\"channel\":\"ffffffffffffffff\",\"seq\":1,\"from\":\"" + page.fingerprint()
                + "\",\"challenge\":\"" + page.challenge + "\"}";
        page.sendSigned(other, page.keys);
        assertTrue(channel.lastDropReason().contains("another channel"), channel.lastDropReason());

        page.send("nonsense", null);
        assertTrue(channel.lastDropReason().contains("unknown type"), channel.lastDropReason());
        assertEquals(1, routed.size());
    }

    @Test
    @DisplayName("Trust codes expire after five minutes; an unknown code is refused; a new hello brings a new prompt")
    void nonceExpiry() {
        page.hello();
        String code = page.nonce;
        assertEquals(EditorChannel.TrustResult.UNKNOWN, channel.trust("zzzzzzzz"));
        assertEquals(EditorChannel.TrustResult.UNKNOWN, channel.trust("not-a-code"));
        now.addAndGet(EditorChannel.NONCE_TTL_MILLIS);
        assertEquals(EditorChannel.TrustResult.EXPIRED, channel.trust(code));
        assertEquals(EditorChannel.TrustResult.EXPIRED, EditorChannel.trustAny(code), "trustAny reports the expiry");
        assertFalse(trusted.isTrusted(page.fingerprint()));

        page.hello();
        assertNotEquals(code, page.nonce, "a fresh code after expiry");
        assertEquals(2, prompts.size(), "and one new prompt for it");
        assertEquals(EditorChannel.TrustResult.TRUSTED, EditorChannel.trustAny(page.nonce));
    }

    @Test
    @DisplayName("Frames over 32 KiB are refused both ways; fitsFrame predicts the outbound cap")
    void frameCap() {
        trustPage();
        String big = "x".repeat(EditorChannel.MAX_FRAME_BYTES);
        page.send("echo", Map.of("n", 1, "pad", big));
        assertTrue(routed.isEmpty());
        assertTrue(channel.lastDropReason().contains("frame over"), channel.lastDropReason());

        String body = "{\"type\":\"feed\",\"pad\":\"" + big + "\"}";
        assertFalse(EditorChannel.fitsFrame(body));
        assertFalse(channel.send(body), "over-cap outbound message is not sent");
        String fitting = "{\"type\":\"feed\",\"pad\":\"" + "y".repeat(EditorChannel.MAX_FRAME_BYTES - 2048) + "\"}";
        assertTrue(EditorChannel.fitsFrame(fitting));
        assertTrue(channel.send(fitting), "what fitsFrame accepts is sent");
        assertThrows(IllegalArgumentException.class, () -> channel.send("{\"type\":\"feed\",\"seq\":3}"),
                "header fields cannot be smuggled into a body");
    }

    @Test
    @DisplayName("Over 2 MiB a minute, droppable pushes are skipped while replies still go out")
    void outboundBudget() {
        trustPage();
        String push = "{\"type\":\"feed\",\"pad\":\"" + "p".repeat(30 * 1024) + "\"}";
        int sent = 0;
        for (int i = 0; i < 100; i++) if (channel.send(push)) sent++;
        assertTrue(sent < 100 && sent >= 60, "pushes stop near the budget: " + sent);
        assertTrue(channel.send("{\"type\":\"walkpath\",\"region\":\"r\"}", page.fingerprint()), "replies are never skipped");
        now.addAndGet(60_000L);
        assertTrue(channel.send(push), "the budget resets after a minute");
    }

    @Test
    @DisplayName("Closing says goodbye, reports the reason once and leaves trustAny")
    void closeLifecycle() {
        page.hello();
        String code = page.nonce;
        channel.close("expired after 30 minutes");
        JsonObject bye = page.last("bye");
        assertNotNull(bye, "the page is told why");
        assertEquals("expired", bye.get("reason").getAsString());
        assertFalse(channel.isOpen());
        assertEquals(List.of("expired after 30 minutes"), closedReasons);
        channel.close("again");
        assertEquals(1, closedReasons.size(), "idempotent");
        assertFalse(channel.send("{\"type\":\"feed\"}"));
        assertEquals(EditorChannel.TrustResult.UNKNOWN, EditorChannel.trustAny(code), "closed channels take no trust");
    }

    @Test
    @DisplayName("Trusted editors persist atomically; a corrupt file is treated as empty and replaced")
    void trustedEditorsPersistence(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("editor").resolve("trusted-editors.json");
        TrustedEditors t = TrustedEditors.load(file);
        String fp = "ab".repeat(32);
        t.add(fp, 42L);
        assertTrue(Files.readString(file).contains(fp));
        assertTrue(TrustedEditors.load(file).isTrusted(fp), "survives a reload");
        assertThrows(IllegalArgumentException.class, () -> t.add("not-a-fingerprint", 1L));

        Files.writeString(file, "{\"trusted\":[{\"fingerprint\":\"nope\"}]}");
        TrustedEditors corrupt = TrustedEditors.load(file);
        assertTrue(corrupt.fingerprints().isEmpty(), "corrupt list treated as empty");
        corrupt.add(fp, 43L);
        assertTrue(TrustedEditors.load(file).isTrusted(fp), "and replaced on the next trust write");
        try (var files = Files.list(file.getParent())) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no temp file left behind");
        }
    }

    @Test
    @DisplayName("The plugin key pair persists; a corrupt pair is replaced with a new key")
    void keyPersistence(@TempDir Path dir) throws IOException {
        EditorKeys first = EditorKeys.loadOrCreate(dir);
        EditorKeys again = EditorKeys.loadOrCreate(dir);
        assertEquals(first.fingerprint(), again.fingerprint(), "same key across restarts");
        byte[] data = "editor".getBytes(StandardCharsets.UTF_8);
        assertTrue(EditorKeys.verify(first.publicKey(), data, again.sign(data)));
        assertEquals(EditorKeys.fingerprint(EditorKeys.decodePublicKey(first.publicKeyBase64())), first.fingerprint());

        Files.write(dir.resolve(EditorKeys.PRIVATE_FILE), new byte[]{1, 2, 3});
        EditorKeys replaced = EditorKeys.loadOrCreate(dir);
        assertNotEquals(first.fingerprint(), replaced.fingerprint(), "unreadable pair replaced");
        assertEquals(replaced.fingerprint(), EditorKeys.loadOrCreate(dir).fingerprint());

        assertThrows(IllegalArgumentException.class, () -> EditorKeys.decodePublicKey("AAAA"));
    }
}
