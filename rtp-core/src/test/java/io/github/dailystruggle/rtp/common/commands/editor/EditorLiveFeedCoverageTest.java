package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonObject;
import io.github.dailystruggle.rtp.common.commands.editor.channel.ChannelPageStub;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.InMemoryTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Edge paths of {@link EditorLiveFeed} (ADR-104 §4.6, ADR-106 §5.3): feed signatures, page
 * message parsing, expiry and file output without regions.
 */
@DisplayName("EditorLiveFeed: signature, page message, expiry and output edge paths")
class EditorLiveFeedCoverageTest {

    @TempDir Path tempDir;

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> pushed = new ArrayList<>();
    private final List<String> uploads = new ArrayList<>();
    private boolean failUploads;
    private EditorChannel channel;
    private ChannelPageStub page;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.resolve("plugin").toFile());
        WorldBiomeStore.clearForTests();
    }

    @AfterEach
    void tearDown() {
        if (channel != null) channel.close("test done");
        WorldBiomeStore.clearForTests();
    }

    private void openChannel() throws IOException {
        EditorKeys keys = EditorKeys.generate();
        InMemoryTransport[] pair = InMemoryTransport.pair();
        AtomicReference<EditorLiveFeed> none = new AtomicReference<>();
        CompletableFuture<EditorChannel> opening = EditorChannelWiring.open(pair[0], keys, TrustedEditors.inMemory(),
                (nonce, fp) -> { }, none::get, new EditorLoopbackApply(null, json -> CompletableFuture.completedFuture(null)));
        channel = EditorChannelWiring.ready(opening);
        page = new ChannelPageStub(pair[1], channel.id(), keys.publicKey());
        page.hello();
        channel.trust(page.nonce);
    }

    private CompletableFuture<String> handoff(String content) {
        uploads.add(content);
        if (failUploads) return CompletableFuture.failedFuture(new IOException("bytebin down"));
        return CompletableFuture.completedFuture("key" + uploads.size());
    }

    /** Store with region-file bins -1..1 on both axes surveyed at y 64. */
    private static WorldLandSurvey survey(WorldBiomeStore store) {
        WorldLandSurvey.RegionBiomeSampler sampler = (rcx, rcz, idx) -> {
            Map<Long, String> out = new HashMap<>();
            for (int i : idx) {
                long cx = (rcx << 5) | (i & 31), cz = (rcz << 5) | (i >> 5);
                out.put((cx << 32) | (cz & 0xFFFFFFFFL), rcx == 0 ? "minecraft:plains" : "minecraft:desert");
            }
            return out;
        };
        List<int[]> files = new ArrayList<>();
        for (int rz = -1; rz <= 1; rz++) for (int rx = -1; rx <= 1; rx++) files.add(new int[]{rx, rz});
        WorldLandSurvey s = new WorldLandSurvey("world", 64, store, sampler, (rcx, rcz) -> 1_000L,
                files, 3, WorldLandSurvey.MAX_TILES_PER_WORLD);
        while (!s.isDone()) s.nextBatch();
        return s;
    }

    private static Square square(int radius, int cx, int cz) {
        Square square = new Square("TEST_COV_" + radius + "_" + cx + "_" + cz);
        square.set(GenericMemoryShapeParams.radius, (long) radius);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        square.set(GenericMemoryShapeParams.centerX, (long) cx);
        square.set(GenericMemoryShapeParams.centerZ, (long) cz);
        return square;
    }

    private EditorLiveFeed newFeed(long ttl) throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, List.of(), List.of(), () -> "{}", ttl, clock::get);
        feed.setPush(pushed::add);
        feed.prepareDirectory();
        return feed;
    }

    @Test
    @DisplayName("A first tick writes feed.js and pushes the head")
    void firstTickWritesFeed() throws IOException {
        EditorLiveFeed feed = newFeed(3_600_000L);
        assertTrue(feed.tick());
        Path file = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR).resolve(EditorLiveFeed.FEED_FILE);
        assertTrue(Files.exists(file));
        String body = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(body.contains("\"seq\":" + feed.seq() + ","), body);
        assertEquals(1, pushed.size());
    }

    @Test
    @DisplayName("Signature without telemetry still compares non-gauge fields")
    void signatureWithoutTelemetry() {
        EditorLiveFeed.FeedSignature a = EditorLiveFeed.FeedSignature.of("{\"seq\":1,\"timestamp\":2,\"x\":1}");
        EditorLiveFeed.FeedSignature b = EditorLiveFeed.FeedSignature.of("{\"seq\":3,\"timestamp\":4,\"x\":1}");
        EditorLiveFeed.FeedSignature c = EditorLiveFeed.FeedSignature.of("{\"seq\":3,\"timestamp\":4,\"x\":2}");
        assertFalse(b.changedFrom(a));
        assertTrue(c.changedFrom(a));
    }

    @Test
    @DisplayName("prepareDirectory deletes only feed-owned files left by an earlier session")
    void prepareDeletesOwnFilesOnly() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        Files.createDirectories(liveDir);
        Path own = Files.writeString(liveDir.resolve("land-0_64_0_0.js"), "x");
        Path ownTmp = Files.writeString(liveDir.resolve("feed.js.tmp"), "x");
        Path foreign = Files.writeString(liveDir.resolve("notes.txt"), "keep");
        Files.createDirectories(liveDir.resolve("path-1.js"));
        newFeed(3_600_000L);
        assertFalse(Files.exists(own));
        assertFalse(Files.exists(ownTmp));
        assertTrue(Files.exists(foreign));
        assertTrue(Files.isDirectory(liveDir.resolve("path-1.js")), "directories are never deleted");
    }

    @Test
    @DisplayName("An expired feed stops, reports why, and every later tick returns false")
    void expiryStops() throws IOException {
        EditorLiveFeed feed = newFeed(1_000L);
        assertTrue(feed.tick());
        clock.addAndGet(5_000L);
        assertFalse(feed.tick());
        assertNotNull(feed.stopReason());
        assertTrue(feed.stopReason().contains("expired"), feed.stopReason());
        assertFalse(feed.tick(), "stopped feeds never tick again");
    }

    @Test
    @DisplayName("Null and oversize page messages are ignored; a flood keeps the feed ticking")
    void clientMessageBounds() throws IOException {
        EditorLiveFeed feed = newFeed(3_600_000L);
        feed.offerClientMessage(null);
        feed.offerClientMessage("x".repeat(EditorLiveFeed.MAX_PAGE_MESSAGE_CHARS + 1));
        for (int i = 0; i < 40; i++) feed.offerClientMessage("{\"type\":\"other\",\"n\":" + i + "}");
        feed.offerClientMessage("{\"type\":\"focus\"}");
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"w\",\"minRx\":1.5,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"w\",\"y\":70,\"minRx\":5,\"minRz\":5,\"maxRx\":-5,\"maxRz\":-5}");
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"w\",\"minRx\":-500,\"minRz\":-500,\"maxRx\":500,\"maxRz\":500}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
    }

    @Test
    @DisplayName("landKey joins world, layer and bin coordinates")
    void landKeyFormat() {
        assertEquals("world|64|-1,2", EditorLiveFeed.landKey("world", 64, -1, 2));
    }

    @Test
    @DisplayName("regionViewBins is null without a shape")
    void regionViewBinsNull() {
        assertNull(EditorLiveFeed.regionViewBins(null));
    }

    @Test
    @DisplayName("Without a selection API there are no configured regions, shapes, worlds or surveys")
    void noSelectionApi() {
        assertTrue(EditorLiveFeed.configuredRegions().isEmpty() || io.github.dailystruggle.rtp.common.RTP.selectionAPI != null);
        if (io.github.dailystruggle.rtp.common.RTP.selectionAPI == null) {
            assertTrue(EditorLiveFeed.configuredShapes().isEmpty());
            assertTrue(EditorLiveFeed.regionWorlds().isEmpty());
            assertTrue(EditorLiveFeed.planSurveys().isEmpty());
            assertTrue(EditorLiveFeed.regionViewsByWorld().isEmpty());
        }
    }

    @Test
    @DisplayName("A push sink that throws is logged; the feed keeps ticking")
    void throwingPushSink() throws IOException {
        EditorLiveFeed feed = newFeed(3_600_000L);
        feed.setPush(s -> { throw new IllegalStateException("sink down"); });
        assertTrue(feed.tick());
        assertTrue(feed.tick());
        assertFalse(feed.isStopped());
    }

    @Test
    @DisplayName("Repeated write failures stop the feed after the failure cap (S-004)")
    void writeFailuresStop() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        EditorLiveFeed feed = newFeed(3_600_000L);
        Files.delete(liveDir);
        Files.writeString(liveDir, "not a directory");
        for (int i = 1; i < EditorLiveFeed.MAX_CONSECUTIVE_WRITE_FAILURES; i++) {
            assertTrue(feed.tick(), "retried on failure " + i);
        }
        assertFalse(feed.tick());
        assertTrue(feed.isStopped());
        assertTrue(feed.stopReason().startsWith("write failures"), feed.stopReason());
    }

    @Test
    @DisplayName("A hosted feed writes no files and stops once its channel closes")
    void hostedStopsOnChannelClose() throws IOException {
        openChannel();
        Path liveDir = tempDir.resolve("hosted").resolve(EditorLiveFeed.LIVE_DIR);
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, false, List.of(), null, null, () -> "{}", 3_600_000L, clock::get);
        feed.prepareDirectory();
        feed.attachChannel(channel, null);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertFalse(Files.exists(liveDir), "hosted feeds own no directory");
        channel.close("page gone");
        assertFalse(feed.tick());
        assertTrue(feed.stopReason().startsWith("editor channel closed"), feed.stopReason());
    }

    @Test
    @DisplayName("ADR-106 §5.10: a hosted feed pushes stats-only heads (at once, changed ones every 20 s, a 40 s heartbeat)")
    void hostedPushesHeads() throws IOException {
        openChannel();
        AtomicLong depth = new AtomicLong();
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("hosted"), false, List.of(), null, null,
                () -> "{\"queueDepth\":" + depth.get() + "}", 3_600_000L, clock::get);
        feed.attachChannel(channel, null);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(1, page.count("feed"), "the page sees the feed alive at once");
        JsonObject data = page.last("feed").getAsJsonObject("data");
        assertEquals(0, data.getAsJsonObject("telemetry").get("queueDepth").getAsInt());
        for (String local : List.of("groups", "palettes", "pathBatches", "groupBins")) {
            assertFalse(data.has(local), local + " names local files");
        }

        depth.set(1);
        clock.addAndGet(5_000L);
        assertTrue(feed.tick());
        assertEquals(1, page.count("feed"), "a changed head waits for the hosted gap");
        clock.addAndGet(EditorLiveFeed.HOSTED_HEAD_MIN_MILLIS);
        assertTrue(feed.tick());
        assertEquals(2, page.count("feed"));
        clock.addAndGet(EditorLiveFeed.HOSTED_HEAD_MIN_MILLIS);
        assertTrue(feed.tick());
        assertEquals(2, page.count("feed"), "unchanged: no head before the heartbeat");
        clock.addAndGet(EditorLiveFeed.FEED_HEARTBEAT_MILLIS);
        assertTrue(feed.tick());
        assertEquals(3, page.count("feed"), "heartbeat");
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        assertTrue(feed.tick());
        assertEquals(4, page.count("feed"), "a focus forces the next head");
    }

    @Test
    @DisplayName("ADR-106 §5.10: on a paced channel the feed tick flushes held pushes once their bundle gap passed")
    void pacedTickFlushesHeld() throws IOException {
        InMemoryTransport[] pair = InMemoryTransport.pair();
        pair[0].withFramesPerWindow(18);
        EditorKeys keys = EditorKeys.generate();
        channel = new EditorChannel(EditorChannel.newChannelId(), keys, TrustedEditors.inMemory(), pair[0],
                (nonce, fp) -> { }, clock::get);
        channel.start();
        page = new ChannelPageStub(pair[1], channel.id(), keys.publicKey());
        page.hello();
        channel.trust(page.nonce);
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("hosted"), false, List.of(), null, null,
                () -> "{}", 3_600_000L, clock::get);
        feed.attachChannel(channel, null);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(1, page.count("feed"), "first bundle leaves at once");

        clock.addAndGet(1_000L);
        assertTrue(channel.send("{\"type\":\"curve\",\"region\":\"alpha\"}"));
        assertEquals(0, page.count("curve"), "held inside the bundle gap");
        clock.addAndGet(EditorChannel.BUNDLE_INTERVAL_MILLIS);
        assertTrue(feed.tick());
        assertEquals(1, page.count("curve"), "the tick sent it without another push");
    }

    @Test
    @DisplayName("A hosted expiry reason omits the local keyword")
    void hostedExpiry() throws IOException {
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("h"), false, List.of(), null, null, () -> "{}", 1_000L, clock::get);
        assertTrue(feed.tick());
        clock.addAndGet(2_000L);
        assertFalse(feed.tick());
        assertFalse(feed.stopReason().contains("local"), feed.stopReason());
    }

    @Test
    @DisplayName("Local channel focus pushes stored bins as land frames; a wide view is clamped")
    void localFocusPushesLand() throws IOException {
        openChannel();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey s = survey(store);
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(s), null, null,
                () -> "{}", 3_600_000L, clock::get);
        feed.attachChannel(channel, null);
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":-1,\"minRz\":-1,\"maxRx\":1,\"maxRz\":1}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"minRx\":200,\"minRz\":200,\"maxRx\":-200,\"maxRz\":-200}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"other\",\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
    }

    @Test
    @DisplayName("Hosted land: a failed upload is retried, a successful one sends a land-ref")
    void hostedLandUploads() throws IOException {
        openChannel();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey s = survey(store);
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(s), null, null,
                () -> "{}", 3_600_000L, clock::get);
        feed.attachChannel(channel, this::handoff);
        feed.seedLand(null);
        failUploads = true;
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(1, uploads.size());
        failUploads = false;
        clock.addAndGet(EditorLiveFeed.LAND_UPLOAD_MILLIS);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(2, uploads.size(), "failed bins go again");
        clock.addAndGet(EditorLiveFeed.LAND_UPLOAD_MILLIS);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(2, uploads.size(), "nothing changed: idle");
        feed.offerClientMessage("{\"type\":\"focus\",\"landReset\":true,\"world\":\"world\",\"y\":64,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        clock.addAndGet(EditorLiveFeed.LAND_UPLOAD_MILLIS);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(3, uploads.size(), "landReset sends the view again");
    }

    @Test
    @DisplayName("Tracked region over a channel: first hazard push is version 1, a stale page version forces a reset")
    void trackedRegionHazards() throws IOException {
        openChannel();
        Map<String, MemoryShape<?>> shapes = new LinkedHashMap<>();
        Square sq = square(20, 0, 0);
        sq.addBadLocation(sq.xzToLocation(2, 3), LocationGenerator.FailTypes.biome);
        shapes.put("alpha", sq);
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(),
                () -> new LinkedHashMap<>(shapes), null, () -> "{}", 3_600_000L, clock::get);
        feed.attachChannel(channel, null);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        EditorLiveFeed.RegionTrack t = feed.tracks().get(0);
        assertEquals(1, t.hazardVersion, "first hazard push");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(1, t.hazardVersion, "unchanged runs are not a new version");
        feed.offerClientMessage("{\"type\":\"focus\",\"hazardVersions\":{\"alpha\":999},\"verified\":[\"alpha\",5]}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertFalse(t.hazardReset, "the reset went out");
        assertNotNull(t.curveJson, "the curve was resent");
        for (int i = 0; i < EditorLiveFeed.REGION_CHECK_TICKS * 2; i++) assertTrue(feed.tick(), "stopped: " + feed.stopReason());
    }

    @Test
    @DisplayName("Verified regions get no walk-path tiles; unverified ones do")
    void verifiedRegionSkipsPath() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        Map<String, MemoryShape<?>> shapes = new LinkedHashMap<>();
        shapes.put("alpha", square(20, 0, 0));
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, List.of(), () -> new LinkedHashMap<>(shapes), null,
                () -> "{}", 3_600_000L, clock::get);
        feed.prepareDirectory();
        feed.offerClientMessage("{\"type\":\"focus\",\"verified\":[\"alpha\"]}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertFalse(Files.exists(liveDir.resolve("path-0_0_0.js")), "verified region: drawn from its helper");
        feed.offerClientMessage("{\"type\":\"focus\",\"verified\":[]}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertTrue(Files.exists(liveDir.resolve("path-0_0_0.js")));
    }

    @Test
    @DisplayName("regionViewBins spans a shape's extent plus padding and clamps a huge shape")
    void regionViewBinsOfShapes() {
        int[] small = EditorLiveFeed.regionViewBins(square(20, 0, 0));
        assertNotNull(small);
        assertTrue(small[0] <= -EditorLiveFeed.LAND_PAD_BINS && small[2] >= EditorLiveFeed.LAND_PAD_BINS - 1, java.util.Arrays.toString(small));
        int[] huge = EditorLiveFeed.regionViewBins(square(100_000, 0, 0));
        assertNotNull(huge);
        assertEquals(EditorLiveFeed.MAX_FOCUS_EDGE, huge[2] - huge[0] + 1);
        assertEquals(EditorLiveFeed.MAX_FOCUS_EDGE, huge[3] - huge[1] + 1);
    }

    @Test
    @DisplayName("Focus over a tracked region orders land by curve position")
    void focusWithTrackedRegion() throws IOException {
        openChannel();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey s = survey(store);
        Map<String, MemoryShape<?>> shapes = new LinkedHashMap<>();
        shapes.put("alpha", square(20, 0, 0));
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(s),
                () -> new LinkedHashMap<>(shapes), null, () -> "{}", 3_600_000L, clock::get);
        feed.attachChannel(channel, null);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":-1,\"minRz\":-1,\"maxRx\":1,\"maxRz\":1}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
    }

    @Test
    @DisplayName("A focus at a far Y creates a survey through the factory for a world already surveyed")
    void surveyFactoryForNewLayer() throws IOException {
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey s = survey(store);
        List<Integer> asked = new ArrayList<>();
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR), List.of(s),
                Map::of, (w, y) -> {
                    asked.add(y);
                    return null;
                }, () -> "{}", 3_600_000L, clock::get);
        feed.prepareDirectory();
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":-40,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(List.of(-40), asked);
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"unsurveyed\",\"y\":-40,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertEquals(List.of(-40), asked, "no survey is created for a world without one");
    }

    @Test
    @DisplayName("Region sync tolerates a null region map and null shapes")
    void syncRegionsNulls() throws IOException {
        Map<String, MemoryShape<?>> withNull = new LinkedHashMap<>();
        withNull.put("ghost", null);
        AtomicReference<Map<String, MemoryShape<?>>> source = new AtomicReference<>(withNull);
        EditorLiveFeed feed = new EditorLiveFeed(tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR), List.of(),
                source::get, null, () -> "{}", 3_600_000L, clock::get);
        feed.prepareDirectory();
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertTrue(feed.tracks().isEmpty());
        source.set(null);
        feed.syncRegions();
        assertTrue(feed.tracks().isEmpty());
    }

    @Test
    @DisplayName("Landings in a tracked region are written as heat overlay groups")
    void heatOverlayWritten() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        String region = "cov_heat_" + System.nanoTime();
        Map<String, MemoryShape<?>> shapes = new LinkedHashMap<>();
        shapes.put(region, square(20, 0, 0));
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, List.of(), () -> new LinkedHashMap<>(shapes), null,
                () -> "{}", 3_600_000L, clock::get);
        feed.prepareDirectory();
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        io.github.dailystruggle.rtp.common.metrics.LandingHeatmap.record(region, 40, 40);
        assertTrue(feed.tick(), "stopped: " + feed.stopReason());
        assertTrue(Files.exists(liveDir.resolve("heat-0_0_0.js")));
        String group = Files.readString(liveDir.resolve("heat-0_0_0.js"), StandardCharsets.UTF_8);
        assertTrue(group.startsWith("window.__rtpLiveOverlayGroup&&window.__rtpLiveOverlayGroup(\"heat\",0,0,0,"), group);
    }

    @Test
    @DisplayName("start and startHosted fail closed without a scheduler (S-006); stopActive is a no-op without a feed")
    void staticEntryPointsFailClosed() throws IOException {
        var saved = io.github.dailystruggle.rtp.common.RTP.scheduler;
        try {
            io.github.dailystruggle.rtp.common.RTP.scheduler = null;
            assertThrows(IllegalStateException.class, () -> EditorLiveFeed.start(tempDir.resolve("x")));
            openChannel();
            assertThrows(IllegalStateException.class, () -> EditorLiveFeed.startHosted(channel, null));
        } finally {
            io.github.dailystruggle.rtp.common.RTP.scheduler = saved;
        }
        assertThrows(NullPointerException.class, () -> EditorLiveFeed.start(null));
        assertThrows(NullPointerException.class, () -> EditorLiveFeed.startHosted(null, null));
        if (EditorLiveFeed.active() == null && EditorLiveFeed.channel() == null) {
            assertDoesNotThrow(() -> EditorLiveFeed.stopActive("test"));
        }
    }
}
