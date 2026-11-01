package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.api.editor.EditorDelivery;
import io.github.dailystruggle.rtp.api.editor.EditorExtension;
import io.github.dailystruggle.rtp.api.editor.EditorMessage;
import io.github.dailystruggle.rtp.api.editor.EditorSession;
import io.github.dailystruggle.rtp.api.editor.EditorTab;
import io.github.dailystruggle.rtp.api.editor.EditorWidget;
import io.github.dailystruggle.rtp.api.hooks.EditorExtensionRegistry;
import io.github.dailystruggle.rtp.common.commands.editor.channel.ChannelPageStub;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.InMemoryTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import io.github.dailystruggle.rtp.common.hooks.DefaultRTPHooks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-107: addon editor extensions over the signed channel, driven with the in-memory transport
 * and the page stub - registry validation, snapshot and hello-reply negotiation, namespaced routing,
 * pushes and replies, live state, failure isolation, inbound rate limit, budgets and bundling.
 */
@DisplayName("ADR-107: addon editor extensions")
class EditorExtensionsTest {

    private EditorChannel channel;
    private ChannelPageStub page;

    /** Test extension: declares one tab, two push types, one inbound type and one config file. */
    static class Demo implements EditorExtension {
        final String id;
        volatile String state;
        volatile String snapshot = "{\"zones\":[1,2]}";
        volatile RuntimeException throwOnMessage;
        volatile EditorSession session;
        volatile boolean closed;
        final List<EditorMessage> got = new CopyOnWriteArrayList<>();

        Demo(String id) {
            this.id = id;
        }

        @Override public String id() { return id; }
        @Override public int version() { return 3; }
        @Override public String displayName() { return "Demo"; }

        @Override
        public List<EditorTab> tabs() {
            return List.of(new EditorTab("triggers", "Triggers", "\u26a1",
                    List.of(new EditorWidget("table", Map.of("source", "snapshot:/zones")))));
        }

        @Override
        public Map<String, EditorDelivery> pushTypes() {
            return Map.of("zones", EditorDelivery.LATEST, "log", EditorDelivery.ORDERED);
        }

        @Override public Set<String> inboundTypes() { return Set.of("run"); }
        @Override public List<String> configFiles() { return List.of("addons/Demo/actions.yml"); }
        @Override public String snapshotJson() { return snapshot; }
        @Override public String stateJson() { return state; }

        @Override
        public void onMessage(EditorMessage message) {
            RuntimeException t = throwOnMessage;
            if (t != null) throw t;
            got.add(message);
            message.reply("result", "{\"ok\":true}");
        }

        @Override public void sessionOpened(EditorSession s) { session = s; }
        @Override public void sessionClosed(EditorSession s) { closed = true; }
    }

    private static EditorExtensionRegistry registry() {
        return EditorExtensions.registry();
    }

    private void open() throws IOException {
        EditorKeys keys = EditorKeys.generate();
        InMemoryTransport[] pair = InMemoryTransport.pair();
        EditorLoopbackApply apply = new EditorLoopbackApply(null, json -> CompletableFuture.completedFuture(null));
        channel = EditorChannelWiring.ready(EditorChannelWiring.open(pair[0], keys, TrustedEditors.inMemory(),
                (nonce, fp) -> { }, () -> null, apply));
        page = new ChannelPageStub(pair[1], channel.id(), keys.publicKey());
        page.hello();
        assertEquals(EditorChannel.TrustResult.TRUSTED, channel.trust(page.nonce));
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.close("test done");
        EditorExtensions.unregisterAll();
    }

    @Test
    @DisplayName("REQ-RTP-S-006 The registry rejects bad, reserved and duplicate ids, reserved types, bad tabs, non-JSON props and escaping paths")
    void registryValidation() {
        assertSame(registry(), new DefaultRTPHooks().editorExtensions(), "every facade serves the one registry");
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("Bad")));
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("x")), "too short");
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("rtp")), "reserved id");
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("a.b")));
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("demo") {
            @Override public Map<String, EditorDelivery> pushTypes() { return Map.of("state", EditorDelivery.LATEST); }
        }), "state is core's");
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("demo") {
            @Override public Set<String> inboundTypes() { return Set.of("Run!"); }
        }));
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("demo") {
            @Override public List<String> configFiles() { return List.of("../config.yml"); }
        }), "no escape from the plugin folder");
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("demo") {
            @Override public List<String> configFiles() { return List.of("C:/x.yml"); }
        }));
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("demo") {
            @Override public List<String> configFiles() { return List.of("addons/Demo/run.sh"); }
        }), "YAML only");
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("demo") {
            @Override public List<EditorTab> tabs() {
                return List.of(new EditorTab("t", "T", null, List.of(new EditorWidget("table", Map.of("source", new Object())))));
            }
        }), "props must be JSON values");
        assertThrows(IllegalArgumentException.class, () -> registry().register(new Demo("demo") {
            @Override public List<EditorTab> tabs() {
                return List.of(new EditorTab("tab", "A", null, List.of()), new EditorTab("tab", "B", null, List.of()));
            }
        }), "tab ids unique");
        assertTrue(registry().registered().isEmpty(), "nothing invalid was kept");

        registry().register(new Demo("demo"));
        assertThrows(IllegalStateException.class, () -> registry().register(new Demo("demo")));
        assertEquals(1, registry().registered().size());
        assertTrue(registry().unregister("demo"));
        assertFalse(registry().unregister("demo"));
    }

    @Test
    @DisplayName("The snapshot carries protocol {channel:2, extensions:1} and each extension's descriptor and snapshot")
    void snapshotDescriptors() {
        Demo d = new Demo("demo");
        registry().register(d);
        java.util.concurrent.atomic.AtomicBoolean breakTabs = new java.util.concurrent.atomic.AtomicBoolean();
        registry().register(new Demo("broken") {
            @Override public List<EditorTab> tabs() {
                if (breakTabs.get()) throw new IllegalStateException("boom");
                return super.tabs();
            }
        });
        breakTabs.set(true); // valid at registration, broken by the time a session is built
        JsonObject p = JsonParser.parseString("{" + EditorExtensions.payloadMembers() + "}").getAsJsonObject();
        assertEquals(2, p.getAsJsonObject("protocol").get("channel").getAsInt());
        assertEquals(1, p.getAsJsonObject("protocol").get("extensions").getAsInt());
        JsonArray ext = p.getAsJsonArray("extensions");
        assertEquals(2, ext.size());
        JsonObject broken = ext.get(0).getAsJsonObject();
        assertEquals("broken", broken.get("id").getAsString(), "id order");
        assertEquals("descriptor-invalid", broken.get("error").getAsString(), "a throwing getter is isolated");
        JsonObject demo = ext.get(1).getAsJsonObject();
        assertEquals(3, demo.get("version").getAsInt());
        assertEquals("Demo", demo.get("name").getAsString());
        assertEquals("triggers", demo.getAsJsonArray("tabs").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("latest", demo.getAsJsonObject("push").get("state").getAsString(), "state is always latest");
        assertEquals("ordered", demo.getAsJsonObject("push").get("log").getAsString());
        assertEquals("run", demo.getAsJsonArray("inbound").get(0).getAsString());
        assertEquals("addons/Demo/actions.yml", demo.getAsJsonArray("files").get(0).getAsString());
        assertEquals(2, demo.getAsJsonObject("snapshot").getAsJsonArray("zones").size());
        assertTrue(demo.get("error").isJsonNull());

        d.snapshot = "{\"big\":\"" + "x".repeat(EditorExtensions.MAX_SNAPSHOT_BYTES) + "\"}";
        JsonObject capped = JsonParser.parseString("{" + EditorExtensions.payloadMembers() + "}").getAsJsonObject()
                .getAsJsonArray("extensions").get(1).getAsJsonObject();
        assertEquals("snapshot-cap", capped.get("error").getAsString());
        assertFalse(capped.has("tabs"), "a capped extension carries only its identity and error");
    }

    @Test
    @DisplayName("A trusted hello-reply repeats protocol and lists the session's extensions; sessionOpened is called")
    void helloReplyNegotiation() throws IOException {
        Demo d = new Demo("demo");
        registry().register(d);
        open();
        JsonObject reply = page.last("hello-reply");
        assertEquals("trusted", reply.get("state").getAsString());
        assertEquals(2, reply.getAsJsonObject("protocol").get("channel").getAsInt());
        JsonObject listed = reply.getAsJsonArray("extensions").get(0).getAsJsonObject();
        assertEquals("demo", listed.get("id").getAsString());
        assertEquals(3, listed.get("version").getAsInt());
        assertNotNull(d.session, "sessionOpened with the session");
        assertTrue(d.session.isOpen());
    }

    @Test
    @DisplayName("Inbound <id>.<type> reaches only its extension without header fields; replies are namespaced and addressed")
    void inboundRoutingAndReplies() throws IOException {
        Demo d = new Demo("demo");
        registry().register(d);
        open();
        page.send("demo.run", Map.of("zone", "spawn"));
        assertEquals(1, d.got.size());
        EditorMessage m = d.got.get(0);
        assertEquals("run", m.type());
        assertEquals("spawn", m.body().get("zone"));
        for (String h : List.of("type", "channel", "seq", "from", "challenge")) assertFalse(m.body().containsKey(h), h);
        assertEquals(page.fingerprint(), m.sender());
        JsonObject result = page.last("demo.result");
        assertNotNull(result, "reply prefixed with the extension id");
        assertTrue(result.get("ok").getAsBoolean());
        assertEquals(page.fingerprint(), result.get("to").getAsString());

        page.send("demo.nope", Map.of());
        assertEquals(1, d.got.size(), "undeclared inbound type dropped");
        page.send("other.run", Map.of());
        assertTrue(channel.lastDropReason().contains("unknown type other.run"), channel.lastDropReason());
        assertThrows(IllegalArgumentException.class, () -> channel.register("demo.run", in -> { }),
                "core handlers stay undotted");
    }

    @Test
    @DisplayName("Pushes are namespaced, declared types only, and bodies cannot set type or header fields")
    void pushes() throws IOException {
        Demo d = new Demo("demo");
        registry().register(d);
        open();
        assertTrue(d.session.push("zones", "{\"n\":1}"));
        JsonObject z = page.last("demo.zones");
        assertNotNull(z);
        assertEquals(1, z.get("n").getAsInt());
        assertFalse(z.has("to"), "a broadcast");
        assertFalse(d.session.push("secret", "{}"), "undeclared push type");
        assertFalse(d.session.push("zones", "{\"type\":\"feed\"}"), "type is core's");
        assertFalse(d.session.push("zones", "{\"to\":\"x\"}"), "header fields are core's");
        assertFalse(d.session.push("zones", "[1]"), "bodies are objects");
        assertEquals(1, page.count("demo.zones"));
    }

    @Test
    @DisplayName("Live state is pushed as <id>.state only when it changes, and again after a page (re)connects")
    void statePolling() throws IOException {
        Demo d = new Demo("demo");
        registry().register(d);
        open();
        EditorExtensions.pollStates();
        assertEquals(0, page.count("demo.state"), "no state, no push");
        d.state = "{\"armed\":true}";
        EditorExtensions.pollStates();
        EditorExtensions.pollStates();
        assertEquals(1, page.count("demo.state"), "unchanged state is not resent");
        assertTrue(page.last("demo.state").getAsJsonObject("state").get("armed").getAsBoolean());
        d.state = "{\"armed\":false}";
        EditorExtensions.pollStates();
        assertEquals(2, page.count("demo.state"));
        page.hello();
        EditorExtensions.pollStates();
        assertEquals(3, page.count("demo.state"), "a reconnected page gets the current state");
        d.state = "not json";
        EditorExtensions.pollStates();
        assertEquals(3, page.count("demo.state"), "invalid state is refused");
    }

    @Test
    @DisplayName("REQ-RTP-S-004 Three handler failures disable only that extension for the session; core and other extensions carry on")
    void failureIsolation() throws IOException {
        Demo bad = new Demo("bad");
        Demo good = new Demo("good");
        registry().register(bad);
        registry().register(good);
        open();
        bad.throwOnMessage = new IllegalStateException("addon bug");
        for (int i = 0; i < 3; i++) page.send("bad.run", Map.of());
        JsonObject err = page.last("bad.error");
        assertNotNull(err, "the page is told");
        assertEquals("failed", err.get("reason").getAsString());
        assertFalse(bad.session.isOpen(), "disabled for this session");
        bad.throwOnMessage = null;
        page.send("bad.run", Map.of());
        assertTrue(bad.got.isEmpty(), "no longer routed");
        page.send("good.run", Map.of());
        assertEquals(1, good.got.size(), "other extensions unaffected");
        assertTrue(channel.isOpen(), "core channel unaffected");
    }

    @Test
    @DisplayName("Inbound extension messages are limited to 20 per second, the excess answered with <id>.error {reason: rate}")
    void inboundRateLimit() throws IOException {
        Demo d = new Demo("demo");
        registry().register(d);
        open();
        for (int i = 0; i < 30; i++) page.send("demo.run", Map.of("i", i));
        assertTrue(d.got.size() <= EditorExtensions.INBOUND_PER_SECOND, "delivered " + d.got.size());
        assertTrue(d.got.size() >= EditorExtensions.INBOUND_PER_SECOND - 1, "the first ones get through: " + d.got.size());
        JsonObject err = page.last("demo.error");
        assertNotNull(err);
        assertEquals("rate", err.get("reason").getAsString());
    }

    @Test
    @DisplayName("An extension's outbound bytes are capped per minute; core traffic still flows")
    void perExtensionBudget() throws IOException {
        Demo d = new Demo("demo");
        registry().register(d);
        open();
        String body = "{\"pad\":\"" + "x".repeat(28 * 1024) + "\"}";
        int sent = 0;
        while (sent < 20 && d.session.push("log", body)) sent++;
        long expected = EditorChannel.EXTENSION_BYTES_PER_MINUTE / (28 * 1024 + 40);
        assertTrue(sent >= expected - 1 && sent <= expected, "sent " + sent + " of ~" + expected);
        assertTrue(channel.send("{\"type\":\"feed\",\"data\":{}}"), "core pushes are not starved");
    }

    @Test
    @DisplayName("On a paced channel extension pushes are bundled with core's, coalesce by key and hold at most 8 KiB each")
    void pacedBundling() throws IOException {
        AtomicLong now = new AtomicLong(1_000_000L);
        EditorKeys keys = EditorKeys.generate();
        InMemoryTransport[] pair = InMemoryTransport.pair();
        pair[0].withFramesPerWindow(18);
        Demo d = new Demo("demo");
        registry().register(d);
        channel = new EditorChannel(EditorChannel.newChannelId(), keys, TrustedEditors.inMemory(), pair[0], (n, f) -> { }, now::get);
        EditorChannelWiring.register(channel, () -> null, new EditorLoopbackApply(null, j -> CompletableFuture.completedFuture(null)));
        channel.start();
        page = new ChannelPageStub(pair[1], channel.id(), keys.publicKey());
        page.hello();
        channel.trust(page.nonce);

        assertTrue(d.session.push("zones", "a", "{\"v\":1}"), "first push leaves at once");
        assertTrue(d.session.push("zones", "a", "{\"v\":2}"));
        assertTrue(d.session.push("zones", "a", "{\"v\":3}"), "same key replaces the held push");
        assertTrue(d.session.push("zones", "b", "{\"v\":9}"));
        assertTrue(channel.send("{\"type\":\"feed\",\"data\":{}}"));
        String third = "{\"pad\":\"" + "y".repeat(3 * 1024) + "\"}";
        assertTrue(d.session.push("log", third));
        assertTrue(d.session.push("log", third));
        assertFalse(d.session.push("log", third), "over the extension's held backlog");

        now.addAndGet(EditorChannel.BUNDLE_INTERVAL_MILLIS + 1);
        channel.flush();
        JsonObject bundle = page.last(EditorChannel.BUNDLE_TYPE);
        assertNotNull(bundle, "held pushes leave as one bundle");
        List<String> types = new java.util.ArrayList<>();
        int zonesA = 0;
        for (var item : bundle.getAsJsonArray("items")) {
            JsonObject o = item.getAsJsonObject();
            types.add(o.get("type").getAsString());
            if ("demo.zones".equals(o.get("type").getAsString()) && o.get("v").getAsInt() == 3) zonesA++;
        }
        assertTrue(types.contains("feed") && types.contains("demo.zones") && types.contains("demo.log"), types.toString());
        assertEquals(1, zonesA, "only the newest of key a");
        assertEquals(2, types.stream().filter("demo.zones"::equals).count(), "one held push per coalescing key");
        assertEquals(2, types.stream().filter("demo.log"::equals).count(), "ordered pushes are not coalesced");
    }

    @Test
    @DisplayName("Unregistering detaches the extension from open sessions; closing the channel calls sessionClosed")
    void lifecycle() throws IOException {
        Demo d = new Demo("demo");
        Demo e = new Demo("other");
        registry().register(d);
        registry().register(e);
        open();
        assertTrue(registry().unregister("demo"));
        assertTrue(d.closed);
        assertFalse(d.session.isOpen());
        page.send("demo.run", Map.of());
        assertTrue(d.got.isEmpty());
        channel.close("test");
        assertTrue(e.closed, "sessionClosed on channel close");
        assertEquals(0, EditorExtensions.sessionCount());
    }
}
