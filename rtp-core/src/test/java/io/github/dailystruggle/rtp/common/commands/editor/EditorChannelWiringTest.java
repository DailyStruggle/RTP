package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonObject;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.channel.ChannelPageStub;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.InMemoryTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5.3: the editor's handlers on {@link EditorChannel} - Hot-Apply reaches the
 * {@code /rtp editor apply} pipeline only from a trusted key, walk-path previews are answered by the
 * live feed tick over the channel, focus reaches the feed queue - with the in-memory transport.
 */
@DisplayName("ADR-106 §5.3: editor handlers registered on the signed channel")
class EditorChannelWiringTest {

    @TempDir
    Path tempDir;

    private final AtomicReference<EditorLiveFeed> feed = new AtomicReference<>();
    private final List<String> applied = new ArrayList<>();
    private EditorChannel channel;
    private ChannelPageStub page;

    private void open() throws IOException {
        EditorKeys keys = EditorKeys.generate();
        InMemoryTransport[] pair = InMemoryTransport.pair();
        EditorLoopbackApply apply = new EditorLoopbackApply(null, json -> {
            applied.add(json);
            return CompletableFuture.completedFuture(null);
        });
        CompletableFuture<EditorChannel> opening =
                EditorChannelWiring.open(pair[0], keys, TrustedEditors.inMemory(), (nonce, fp) -> { }, feed::get, apply);
        assertTrue(opening.isDone(), "in-memory transports open synchronously");
        channel = EditorChannelWiring.ready(opening);
        page = new ChannelPageStub(pair[1], channel.id(), keys.publicKey());
        page.hello();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.close("test done");
    }

    @Test
    @DisplayName("REQ-RTP-S-004 Unreachable relay: openHosted returns at once and completes with null (snapshot-only), never exceptionally")
    void unreachableRelayIsSnapshotOnly() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        EditorHttpTransport http = new EditorHttpTransport(HttpClient.newHttpClient(), "http://127.0.0.1:1",
                "http://127.0.0.1:1/editor/", "http://127.0.0.1:" + port);
        CompletableFuture<EditorChannel> hosted = EditorChannelWiring.openHosted(null, http, feed::get);
        assertNull(hosted.get(30, TimeUnit.SECONDS), "a failed create gives a snapshot-only session");
        assertFalse(hosted.isCompletedExceptionally());
    }

    @Test
    @DisplayName("A failed open surfaces as IOException from ready(), not as a blocking wait")
    void readyReportsFailure() {
        IOException e = assertThrows(IOException.class,
                () -> EditorChannelWiring.ready(CompletableFuture.failedFuture(new IOException("socket refused"))));
        assertEquals("socket refused", e.getMessage());
        assertThrows(IOException.class, () -> EditorChannelWiring.ready(new CompletableFuture<>()),
                "an unfinished open is refused instead of awaited");
    }

    @Test
    @DisplayName("Hot-Apply from an untrusted key never reaches the apply pipeline")
    void untrustedApplyRefused() throws IOException {
        open();
        page.send("apply", Map.of("region", "default", "timestamp", 5, "files", Map.of("config.yml", "a: 1\n")));
        assertTrue(applied.isEmpty(), "untrusted key refused before the pipeline");
        assertNull(page.last("apply_ack"), "no ack for a dropped frame");
        assertTrue(channel.lastDropReason().contains("untrusted"), channel.lastDropReason());
    }

    @Test
    @DisplayName("Hot-Apply from a trusted key runs the apply pipeline and is acknowledged to that browser")
    void trustedApplyReachesPipeline() throws IOException {
        open();
        assertEquals(EditorChannel.TrustResult.TRUSTED, channel.trust(page.nonce));
        page.send("apply", Map.of("region", "default", "timestamp", 5, "files", Map.of("regions/default.yml", "shape:\n  name: CIRCLE\n")));
        assertEquals(1, applied.size(), "the full /rtp editor apply payload is handed on");
        assertTrue(applied.get(0).contains("\"regions/default.yml\""), applied.get(0));
        assertTrue(applied.get(0).contains("\"sha256\":\""), "the pipeline's integrity hash is computed");
        JsonObject ack = page.last("apply_ack");
        assertNotNull(ack);
        assertTrue(ack.get("success").getAsBoolean(), ack.toString());
        assertEquals(page.fingerprint(), ack.get("to").getAsString());

        page.send("apply", Map.of("region", "default", "files", Map.of("../evil.sh", "x")));
        JsonObject bad = page.last("apply_ack");
        assertFalse(bad.get("success").getAsBoolean(), "structural checks still run");
        assertEquals(1, applied.size());
    }

    @Test
    @DisplayName("walkpath / curve-state go through the live feed's queue and its reply returns over the channel")
    void previewRoutedThroughFeed() throws IOException {
        open();
        channel.trust(page.nonce);
        page.send("walkpath", Map.of("region", "default", "sig", "s1", "shape", Map.of("name", "CIRCLE")));
        JsonObject noFeed = page.last("walkpath");
        assertNotNull(noFeed, "without a running feed the page still gets an answer");
        assertEquals("s1", noFeed.get("sig").getAsString());
        assertTrue(noFeed.has("error"));

        EditorLiveFeed live = new EditorLiveFeed(tempDir.resolve("live"), List.of(), List.of(), () -> "{}", 60_000L,
                System::currentTimeMillis);
        live.prepareDirectory();
        feed.set(live);
        page.send("curve-state", Map.of("reqId", "r1", "region", "default", "shape", Map.of("name", "CIRCLE")));
        assertNull(page.last("curve-state"), "answered on the feed tick, not on the socket thread");
        assertTrue(live.tick());
        JsonObject cs = page.last("curve-state");
        assertNotNull(cs, "the tick's reply travels back signed");
        assertEquals("r1", cs.get("reqId").getAsString());
        assertEquals(page.fingerprint(), cs.get("to").getAsString());
    }

    @Test
    @DisplayName("Walk-path replies are thinned (recorded as data.thinned) to fit one 32 KiB frame")
    @SuppressWarnings("unchecked")
    void walkPathReplyFitsFrame() {
        Factory<Shape<?>> shapes = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapes == null) {
            shapes = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.shape, shapes);
        }
        shapes.add("channel_thin_test", new Square());
        try {
            EditorWalkPathPreview.Request req = new EditorWalkPathPreview.Request("r", "s",
                    Map.of("name", "CHANNEL_THIN_TEST", "radius", "4096c", "centerRadius", "0c", "centerX", "-20000", "centerZ", "20000"));
            String full = EditorWalkPathPreview.reply(req);
            String fitted = EditorWalkPathPreview.reply(req, EditorChannel::fitsFrame);
            assertTrue(EditorChannel.fitsFrame(fitted), "every reply fits a frame");
            Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) EditorLoopbackJson.parse(fitted)).get("data");
            assertNotNull(data, fitted.substring(0, Math.min(300, fitted.length())));
            int fullPoints = ((List<?>) ((Map<?, ?>) ((Map<?, ?>) EditorLoopbackJson.parse(full)).get("data")).get("points")).size();
            int kept = ((List<?>) data.get("points")).size();
            if (EditorChannel.fitsFrame(full)) {
                assertEquals(fullPoints, kept, "a reply that fits is not thinned");
            } else {
                assertTrue(data.get("thinned") instanceof Number, "thinning is recorded");
                assertTrue(kept >= 64 && kept < fullPoints, kept + " of " + fullPoints);
            }
            String tiny = EditorWalkPathPreview.reply(req, json -> json.length() < 4_000);
            assertTrue(tiny.length() < 4_000 || tiny.contains("\"error\""), "a hard cap thins further or errors");
            assertTrue(((Map<?, ?>) EditorLoopbackJson.parse(tiny)).get("data") != null
                    && ((Map<?, ?>) ((Map<?, ?>) EditorLoopbackJson.parse(tiny)).get("data")).get("thinned") instanceof Number,
                    "forced thinning: " + tiny.substring(0, Math.min(200, tiny.length())));
        } finally {
            shapes.remove("channel_thin_test");
        }
    }
}
