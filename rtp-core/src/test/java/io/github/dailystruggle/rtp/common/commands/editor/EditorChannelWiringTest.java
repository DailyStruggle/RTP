package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonObject;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.editor.channel.ChannelPageStub;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.InMemoryTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    @DisplayName("hazard-delta pushes carry base/version, never the from/to header fields, and keep the feed running")
    void hazardDeltaOverChannel() throws IOException {
        RTPTestSetup.install(tempDir.resolve("plugin").toFile());
        open();
        channel.trust(page.nonce);
        Square square = new Square("TEST_CHANNEL_HAZARD");
        square.set(GenericMemoryShapeParams.radius, 20L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        square.set(GenericMemoryShapeParams.centerX, 0L);
        square.set(GenericMemoryShapeParams.centerZ, 0L);
        square.addBadLocation(square.xzToLocation(1, 1), LocationGenerator.FailTypes.biome);
        square.flushAndRebuild(1L);
        Map<String, MemoryShape<?>> shapes = new LinkedHashMap<>(Map.of("alpha", square));
        EditorLiveFeed live = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(),
                () -> new LinkedHashMap<>(shapes), null, () -> "{}", 60_000L, System::currentTimeMillis);
        live.attachChannel(channel, null);

        assertTrue(live.tick(), "stopped: " + live.stopReason());
        JsonObject reset = page.last("hazard-delta");
        assertNotNull(reset, "first hazard push is a reset");
        assertTrue(reset.get("reset").getAsBoolean(), reset.toString());
        assertFalse(reset.get("runs").getAsString().isEmpty(), "learned run published");
        assertEquals(1, reset.get("version").getAsLong());
        assertEquals(channel.pluginFingerprint(), reset.get("from").getAsString(), "from stays the sender header");
        assertFalse(reset.has("to"), "a broadcast has no recipient header");

        square.addBadLocation(square.xzToLocation(-10, 7), LocationGenerator.FailTypes.biome);
        square.flushAndRebuild(2L);
        assertTrue(live.tick(), "stopped: " + live.stopReason());
        JsonObject delta = page.last("hazard-delta");
        assertNotSame(reset, delta, "a changed hazard set is pushed again");
        assertFalse(delta.has("reset"), delta.toString());
        assertEquals(1, delta.get("base").getAsLong());
        assertEquals(2, delta.get("version").getAsLong());
        assertTrue(channel.isOpen(), "the channel survives hazard pushes");
    }

    @Test
    @DisplayName("Every land push carries the whole palette, and a reconnected page's repeated view is pushed again")
    void landPushesCarryPalette() throws IOException {
        RTPTestSetup.install(tempDir.resolve("plugin").toFile());
        WorldBiomeStore.clearForTests();
        open();
        channel.trust(page.nonce);
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey.RegionBiomeSampler sampler = (rcx, rcz, idx) -> {
            Map<Long, String> out = new HashMap<>();
            for (int i : idx) {
                long cx = (rcx << 5) | (i & 31), cz = (rcz << 5) | (i >> 5);
                out.put((cx << 32) | (cz & 0xFFFFFFFFL), rcx == 0 ? "PLAINS" : "DESERT");
            }
            return out;
        };
        WorldLandSurvey survey = new WorldLandSurvey("world", 64, store, sampler, (rcx, rcz) -> 1_000L,
                List.of(new int[]{0, 0}, new int[]{-1, 0}), 3, WorldLandSurvey.MAX_TILES_PER_WORLD);
        while (!survey.isDone()) survey.nextBatch();
        EditorLiveFeed live = new EditorLiveFeed(tempDir.resolve("live"), List.of(survey), List.of(), () -> "{}", 60_000L,
                System::currentTimeMillis);
        live.prepareDirectory();
        live.attachChannel(channel, null);
        String focus = "{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":-1,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}";

        live.offerClientMessage(focus);
        assertTrue(live.tick(), "stopped: " + live.stopReason());
        live.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        assertTrue(live.tick(), "stopped: " + live.stopReason());
        long first = page.count("land");
        assertTrue(first >= 2, "both views pushed land: " + first);
        for (JsonObject land : page.received) {
            if (!"land".equals(land.get("type").getAsString())) continue;
            assertTrue(land.has("palette"), "every land frame names its cells: " + land);
            assertEquals(store.palette().size(), land.getAsJsonArray("palette").size(), land.toString());
        }

        // Same view again (page reloaded): its bins and palette go out again
        live.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        assertTrue(live.tick(), "stopped: " + live.stopReason());
        assertEquals(first + 1, page.count("land"), "a repeated view is pushed again");
        assertTrue(page.last("land").has("palette"));
    }

    @Test
    @DisplayName("Land bins go out along the region's walk (centre outward), bins outside every region after it by ring")
    void landPushFollowsTheWalk() throws IOException {
        RTPTestSetup.install(tempDir.resolve("plugin").toFile());
        WorldBiomeStore.clearForTests();
        open();
        channel.trust(page.nonce);
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey.RegionBiomeSampler sampler = (rcx, rcz, idx) -> {
            Map<Long, String> out = new HashMap<>();
            for (int i : idx) {
                long cx = (rcx << 5) | (i & 31), cz = (rcz << 5) | (i >> 5);
                out.put((cx << 32) | (cz & 0xFFFFFFFFL), "PLAINS");
            }
            return out;
        };
        List<int[]> files = new ArrayList<>();
        for (int rz = -3; rz <= 3; rz++) for (int rx = -3; rx <= 3; rx++) files.add(new int[]{rx, rz});
        WorldLandSurvey survey = new WorldLandSurvey("world", 64, store, sampler, (rcx, rcz) -> 1_000L,
                files, 3, WorldLandSurvey.MAX_TILES_PER_WORLD);
        while (!survey.isDone()) survey.nextBatch();
        // Ring spiral around chunk 0, 64 chunks: region-file bins -2..1 on each axis
        Square square = new Square("TEST_CHANNEL_WALK_ORDER");
        square.set(GenericMemoryShapeParams.radius, 64L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        square.set(GenericMemoryShapeParams.centerX, 0L);
        square.set(GenericMemoryShapeParams.centerZ, 0L);
        Map<String, MemoryShape<?>> shapes = new LinkedHashMap<>(Map.of("alpha", square));
        EditorLiveFeed live = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(survey),
                () -> new LinkedHashMap<>(shapes), null, () -> "{}", 60_000L, System::currentTimeMillis);
        live.attachChannel(channel, null);
        assertTrue(live.tick(), "stopped: " + live.stopReason());
        live.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":-3,\"minRz\":-3,\"maxRx\":3,\"maxRz\":3}");
        for (int i = 0; i < 20; i++) assertTrue(live.tick(), "stopped: " + live.stopReason());

        List<int[]> order = new ArrayList<>();
        for (JsonObject land : page.received) {
            if (!"land".equals(land.get("type").getAsString())) continue;
            land.getAsJsonArray("bins").forEach(b -> order.add(new int[]{b.getAsJsonArray().get(0).getAsInt(), b.getAsJsonArray().get(1).getAsInt()}));
        }
        assertEquals(49, order.size(), "every surveyed bin of the view pushed once");
        java.util.function.ToIntFunction<int[]> walkRing = b -> Math.max(Math.max(-b[0] - 1, b[0]), Math.max(-b[1] - 1, b[1]));
        int seenRing = 0;
        for (int k = 0; k < order.size(); k++) {
            int[] b = order.get(k);
            int ring = walkRing.applyAsInt(b);
            if (k < 16) {
                assertTrue(ring <= 1, "the 16 region bins come first: #" + k + " = " + b[0] + "," + b[1]);
                assertTrue(ring >= seenRing, "region bins follow the walk outward: #" + k + " ring " + ring + " after " + seenRing);
            } else {
                assertTrue(ring >= 2, "bins outside the region after it: #" + k + " = " + b[0] + "," + b[1]);
            }
            seenRing = Math.max(seenRing, k < 16 ? ring : 0);
        }
        java.util.Set<String> firstRing = new java.util.HashSet<>();
        for (int k = 0; k < 4; k++) firstRing.add(order.get(k)[0] + "," + order.get(k)[1]);
        assertEquals(java.util.Set.of("-1,-1", "0,-1", "-1,0", "0,0"), firstRing, "the walk starts at the four bins round the centre");
    }

    @Test
    @DisplayName("Every registered shape's helper ships in curveCode with a sample curve block at its defaults")
    @SuppressWarnings("unchecked")
    void registeredHelpersShipWithSample() {
        Factory<Shape<?>> shapes = (Factory<Shape<?>>) RTP.factoryMap.get(RTP.factoryNames.shape);
        if (shapes == null) {
            shapes = new Factory<>();
            RTP.factoryMap.put(RTP.factoryNames.shape, shapes);
        }
        shapes.add("SAMPLE_CIRCLE_TEST", new Circle("SAMPLE_CIRCLE_TEST"));
        try {
            Map<String, String> sources = new TreeMap<>();
            Map<String, Map<String, Object>> samples = new TreeMap<>();
            EditorSessionManager.addRegisteredCurveHelpers(sources, samples);
            assertNotNull(sources.get("SAMPLE_CIRCLE_TEST"), "a shape no region uses still ships its helper: " + sources.keySet());
            Map<String, Object> sample = samples.get("SAMPLE_CIRCLE_TEST");
            assertNotNull(sample, samples.keySet().toString());
            assertEquals("SAMPLE_CIRCLE_TEST", sample.get("shape"));
            assertTrue(sample.get("hash") instanceof String h && !h.isEmpty(), sample.toString());

            Map<?, ?> code = (Map<?, ?>) EditorLoopbackJson.parse(EditorSessionManager.buildCurveCodeJson(sources, samples));
            Map<?, ?> entry = (Map<?, ?>) code.get("SAMPLE_CIRCLE_TEST");
            assertTrue(entry.get("js") instanceof String && entry.get("sha256") instanceof String, entry.keySet().toString());
            assertEquals(sample.get("hash"), ((Map<?, ?>) entry.get("sample")).get("hash"));
        } finally {
            shapes.remove("SAMPLE_CIRCLE_TEST");
        }
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
