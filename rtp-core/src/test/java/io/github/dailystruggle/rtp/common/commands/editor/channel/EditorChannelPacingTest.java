package io.github.dailystruggle.rtp.common.commands.editor.channel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5.5 frame pacing: over a transport that declares a frame limit (the public bytesocks
 * relay: 30 frames per IP per 2 minutes, closing with 1008 over it) the channel keeps to its share,
 * sends replies first and coalesces pushes into {@code bundle} frames.
 */
@DisplayName("ADR-106 §5.5: editor channel frame pacing for rate-limited relays")
class EditorChannelPacingTest {

    private static final int LIMIT = BytesocksTransport.FRAMES_PER_WINDOW;

    private final AtomicLong now = new AtomicLong(1_000_000L);
    /** Plugin frame arrival times at the page. */
    private final List<Long> arrivals = new ArrayList<>();
    private InMemoryTransport pluginEnd;
    private EditorChannel channel;
    private ChannelPageStub page;

    @BeforeEach
    void setUp() {
        InMemoryTransport[] pair = InMemoryTransport.pair();
        pluginEnd = pair[0].withFramesPerWindow(LIMIT);
        EditorKeys keys = EditorKeys.generate();
        channel = new EditorChannel(EditorChannel.newChannelId(), keys, TrustedEditors.inMemory(), pluginEnd,
                (nonce, fingerprint) -> { }, now::get);
        channel.register("echo", in -> in.reply("{\"type\":\"echo\",\"n\":" + in.body().get("n") + "}"));
        channel.start();
        page = new ChannelPageStub(pair[1], channel.id(), keys.publicKey());
        page.hello();
        assertEquals(EditorChannel.TrustResult.TRUSTED, channel.trust(page.nonce));
        record(0);
    }

    private void ask(int n) {
        long before = pluginEnd.sentCount();
        page.send("echo", java.util.Map.of("n", n));
        record(before);
    }

    @AfterEach
    void tearDown() {
        channel.close("test done");
    }

    private void step(long millis) {
        now.addAndGet(millis);
        long before = pluginEnd.sentCount();
        channel.flush();
        record(before);
    }

    private void record(long before) {
        for (long i = before; i < pluginEnd.sentCount(); i++) arrivals.add(now.get());
    }

    private boolean push(String body) {
        long before = pluginEnd.sentCount();
        boolean ok = channel.send(body);
        record(before);
        return ok;
    }

    /** Pushes inside {@code bundle} frames and on their own, in arrival order. */
    private List<JsonObject> pushesOf(String type) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonObject m : page.received) {
            String t = m.get("type").getAsString();
            if (t.equals(type)) out.add(m);
            if (t.equals(EditorChannel.BUNDLE_TYPE)) {
                JsonArray items = m.getAsJsonArray("items");
                for (JsonElement e : items) {
                    if (type.equals(e.getAsJsonObject().get("type").getAsString())) out.add(e.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private int maxInAnyWindow() {
        int max = 0;
        for (int i = 0, j = 0; i < arrivals.size(); i++) {
            while (arrivals.get(i) - arrivals.get(j) >= ChannelTransport.FRAME_WINDOW_MILLIS) j++;
            max = Math.max(max, i - j + 1);
        }
        return max;
    }

    @Test
    @DisplayName("Ten minutes of 2 s feed heads plus curve / hazard pushes stay within the frame share of any 2-minute window")
    void feedCadenceStaysUnderRelayLimit() {
        long hazard = 0;
        for (int tick = 1; tick <= 300; tick++) {
            step(2_000L);
            push("{\"type\":\"feed\",\"data\":{\"n\":" + tick + "}}");
            if (tick % 5 == 0) push("{\"type\":\"curve\",\"region\":\"alpha\",\"curve\":{\"n\":" + tick + "}}");
            if (tick % 7 == 0 && push("{\"type\":\"hazard-delta\",\"region\":\"alpha\",\"base\":" + hazard
                    + ",\"version\":" + (hazard + 1) + ",\"add\":\"\",\"remove\":\"\"}")) {
                hazard++;
            }
        }
        assertTrue(maxInAnyWindow() <= LIMIT, "at most " + LIMIT + " frames in any 2 minutes, saw " + maxInAnyWindow());
        assertTrue(pluginEnd.sentCount() >= 600_000L / ChannelTransport.FRAME_WINDOW_MILLIS * (LIMIT - EditorChannel.REPLY_RESERVE) / 2,
                "pushes keep flowing: " + pluginEnd.sentCount() + " frames");

        List<JsonObject> feeds = pushesOf("feed");
        int newest = feeds.get(feeds.size() - 1).getAsJsonObject("data").get("n").getAsInt();
        // Bundles may bunch inside a window and then pause until its oldest frame ages out
        assertTrue(newest >= 290, "the page holds a recent feed head: " + newest);
        long feedsPerBundle = pushesOf("feed").size();
        assertTrue(feedsPerBundle < 300, "older heads are coalesced away");

        List<JsonObject> hazards = pushesOf("hazard-delta");
        for (int i = 0; i < hazards.size(); i++) {
            assertEquals(i, hazards.get(i).get("base").getAsLong(), "hazard deltas arrive complete and in order");
        }
        assertTrue(channel.isOpen());
    }

    @Test
    @DisplayName("Held pushes leave as one bundle with the newest curve per region; a push too large for the backlog is refused")
    void coalescesIntoBundle() {
        assertTrue(push("{\"type\":\"feed\",\"data\":{\"n\":1}}"));
        assertNotNull(page.last("feed"), "the first push goes out at once, unwrapped");

        assertTrue(push("{\"type\":\"curve\",\"region\":\"alpha\",\"curve\":{\"v\":1}}"));
        assertTrue(push("{\"type\":\"curve\",\"region\":\"alpha\",\"curve\":{\"v\":2}}"));
        assertTrue(push("{\"type\":\"curve\",\"region\":\"beta\",\"curve\":{\"v\":1}}"));
        assertTrue(push("{\"type\":\"land\",\"world\":\"w\",\"bins\":[]}"));
        assertTrue(push("{\"type\":\"feed\",\"data\":{\"n\":2}}"));
        assertTrue(push("{\"type\":\"feed\",\"data\":{\"n\":3}}"));
        String big = "{\"type\":\"land\",\"world\":\"w\",\"pad\":\""
                + "x".repeat(EditorChannel.MAX_FRAME_BYTES - EditorChannel.ENVELOPE_OVERHEAD - 100) + "\"}";
        assertFalse(push(big), "a new item that would overflow one frame's backlog waits at its producer");
        assertNull(page.last(EditorChannel.BUNDLE_TYPE), "nothing more before the bundle interval");

        step(EditorChannel.BUNDLE_INTERVAL_MILLIS);
        JsonObject bundle = page.last(EditorChannel.BUNDLE_TYPE);
        assertNotNull(bundle, "one bundle after the interval");
        JsonArray items = bundle.getAsJsonArray("items");
        assertEquals(4, items.size(), items.toString());
        List<JsonObject> curves = pushesOf("curve");
        assertEquals(2, curves.size(), "one curve per region");
        assertEquals(2, curves.get(0).getAsJsonObject("curve").get("v").getAsInt(), "the newest alpha curve");
        assertEquals(3, pushesOf("feed").get(pushesOf("feed").size() - 1).getAsJsonObject("data").get("n").getAsInt());
        assertFalse(bundle.has("to"), "a bundle is a broadcast");
        assertEquals(0, channel.heldCount());
        assertTrue(push(big), "room again once the backlog drained");
    }

    @Test
    @DisplayName("Replies over the frame share wait in order and go out first once the window frees; bye is never held")
    void repliesWaitAndByeBypasses() {
        int asked = LIMIT * 2;
        for (int n = 1; n <= asked; n++) ask(n);
        long delivered = page.count("echo");
        assertTrue(delivered < asked, "replies beyond the share are held: " + delivered);
        assertTrue(channel.heldCount() > 0);
        assertTrue(push("{\"type\":\"feed\",\"data\":{\"n\":1}}"), "pushes are still accepted (held)");

        step(ChannelTransport.FRAME_WINDOW_MILLIS);
        long afterWindow = page.count("echo");
        assertTrue(afterWindow > delivered, "held replies drain once the window frees");
        step(ChannelTransport.FRAME_WINDOW_MILLIS);
        step(EditorChannel.BUNDLE_INTERVAL_MILLIS);
        List<JsonObject> echoes = new ArrayList<>();
        for (JsonObject m : page.received) if ("echo".equals(m.get("type").getAsString())) echoes.add(m);
        assertEquals(asked, echoes.size(), "every reply arrives");
        for (int i = 0; i < echoes.size(); i++) assertEquals(i + 1, echoes.get(i).get("n").getAsInt(), "in order");
        assertTrue(maxInAnyWindow() <= LIMIT, "within the share: " + maxInAnyWindow());

        for (int n = 1; n <= asked; n++) ask(n);
        channel.close("expired after 30 minutes");
        assertEquals("expired", page.last("bye").get("reason").getAsString(), "bye goes out over an exhausted budget");
    }

    @Test
    @DisplayName("A paced channel skips the pong right after other traffic (the page counts any verified frame as liveness)")
    void pongSkippedAfterTraffic() {
        step(EditorChannel.PONG_SKIP_MILLIS);
        long pongs = page.count("pong");
        page.send("ping", null);
        assertEquals(pongs + 1, page.count("pong"), "an idle link answers a ping");
        page.send("ping", null);
        assertEquals(pongs + 1, page.count("pong"), "no pong right after another frame");
        step(EditorChannel.PONG_SKIP_MILLIS);
        page.send("ping", null);
        assertEquals(pongs + 2, page.count("pong"));
    }

    @Test
    @DisplayName("Unlimited transports (loopback, in-memory) send every message at once, unwrapped")
    void unlimitedTransportUnchanged() {
        pluginEnd.withFramesPerWindow(0);
        for (int n = 1; n <= 50; n++) push("{\"type\":\"feed\",\"data\":{\"n\":" + n + "}}");
        assertEquals(50, page.count("feed"));
        assertEquals(0, page.count(EditorChannel.BUNDLE_TYPE));
    }

    @Test
    @DisplayName("A rejoin refused with HTTP 400 / 404 means bytesocks deleted the channel; 429 and network errors retry")
    void channelGoneDetection() {
        assertTrue(BytesocksTransport.channelGone(new IOException("Unexpected HTTP response status code 400")));
        assertTrue(BytesocksTransport.channelGone(new RuntimeException("wrapped",
                new IOException("CheckFailedException: Unexpected HTTP response status code 404"))));
        assertFalse(BytesocksTransport.channelGone(new IOException("Unexpected HTTP response status code 429")));
        assertFalse(BytesocksTransport.channelGone(new IOException("Connection refused")));
        assertFalse(BytesocksTransport.channelGone(new IOException("status code 4000")));
    }
}
