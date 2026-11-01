package io.github.dailystruggle.rtp.common.commands.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dailystruggle.rtp.common.commands.editor.channel.ChannelPageStub;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorChannel;
import io.github.dailystruggle.rtp.common.commands.editor.channel.EditorKeys;
import io.github.dailystruggle.rtp.common.commands.editor.channel.InMemoryTransport;
import io.github.dailystruggle.rtp.common.commands.editor.channel.TrustedEditors;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-106 §5.3 hosted land: the page's view (plus padding) goes to bytebin by reference
 * ({@code land-ref}), at most every {@link EditorLiveFeed#LAND_UPLOAD_MILLIS}, only bins the page
 * does not hold (snapshot embed, earlier uploads), nothing while idle; a failed upload or a page's
 * {@code landReset} sends the bins again. The snapshot's region view stays finite for any size.
 */
@DisplayName("ADR-106 §5.3: hosted land bins by bytebin reference, view-driven and idle-silent")
class EditorLandHandoffTest {

    @TempDir
    Path tempDir;

    private EditorChannel channel;
    private ChannelPageStub page;
    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> uploads = new ArrayList<>();
    private final AtomicBoolean failUploads = new AtomicBoolean();

    @AfterEach
    void tearDown() {
        if (channel != null) channel.close("test done");
    }

    private void open() throws IOException {
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
        if (failUploads.get()) return CompletableFuture.failedFuture(new IOException("bytebin down"));
        return CompletableFuture.completedFuture("key" + uploads.size());
    }

    /** Store with region-file bins -1..1 on both axes surveyed at y 64. */
    private WorldLandSurvey survey(WorldBiomeStore store) {
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

    private static Set<String> binsOf(String upload) {
        JsonObject body = JsonParser.parseString(upload).getAsJsonObject();
        Set<String> out = new HashSet<>();
        for (var b : body.getAsJsonArray("bins")) {
            JsonArray row = b.getAsJsonArray();
            out.add(row.get(0).getAsInt() + "," + row.get(1).getAsInt());
        }
        return out;
    }

    private void tick(EditorLiveFeed live) {
        assertTrue(live.tick(), "stopped: " + live.stopReason());
    }

    @Test
    @DisplayName("View upload by reference: skips snapshot bins, one per period, none while idle, only changed bins after")
    void viewGoesByReference() throws IOException {
        RTPTestSetup.install(tempDir.resolve("plugin").toFile());
        WorldBiomeStore.clearForTests();
        open();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey survey = survey(store);
        EditorLiveFeed live = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(survey), null, null,
                () -> "{}", 60 * 60_000L, clock::get);
        live.attachChannel(channel, this::handoff);
        // The snapshot embedded bin 0,0 at its current version
        live.seedLand(Map.of(EditorLiveFeed.landKey("world", 64, 0, 0), store.bin(0, 0).version()));

        tick(live);
        assertTrue(uploads.isEmpty(), "no view yet: nothing uploaded");
        live.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        tick(live);
        assertEquals(1, uploads.size(), "a view uploads at once");
        Set<String> first = binsOf(uploads.get(0));
        assertEquals(8, first.size(), "the padded view minus the snapshot's bin: " + first);
        assertFalse(first.contains("0,0"), "bins the snapshot embedded are not sent again");
        JsonObject body = JsonParser.parseString(uploads.get(0)).getAsJsonObject();
        assertEquals("world", body.get("world").getAsString());
        assertEquals(store.palette().size(), body.getAsJsonArray("palette").size(), "the upload names every cell");

        tick(live);
        JsonObject ref = page.last("land-ref");
        assertNotNull(ref, "the page gets a land-ref once the upload has a key");
        assertEquals("key1", ref.get("bytebinKey").getAsString());
        assertEquals(EditorHttpTransport.computeSha256(uploads.get(0)), ref.get("sha256").getAsString(),
                "the signed frame pins the uploaded text");
        assertEquals(8, ref.get("bins").getAsInt());
        assertFalse(ref.has("palette") || ref.has("runs"), "the relay carries the reference only: " + ref);
        assertEquals(0, page.count("land"), "a hosted feed sends no land frames");

        clock.addAndGet(EditorLiveFeed.LAND_UPLOAD_MILLIS + 1);
        for (int i = 0; i < 3; i++) tick(live);
        assertEquals(1, uploads.size(), "idle: nothing changed, nothing uploaded");

        // One bin sharpens: only it goes, and not before the period has passed
        store.apply(1, 1, 64, BiomeBinCodec.FINEST_LEVEL, new int[]{0}, Map.of(0, "minecraft:ocean"), 2_000L);
        tick(live);
        assertEquals(2, uploads.size(), "a change after the period uploads");
        assertEquals(Set.of("1,1"), binsOf(uploads.get(1)), "only the changed bin");
        store.apply(-1, -1, 64, BiomeBinCodec.FINEST_LEVEL, new int[]{0}, Map.of(0, "minecraft:ocean"), 2_000L);
        tick(live);
        assertEquals(2, uploads.size(), "within the period the change waits");
        clock.addAndGet(EditorLiveFeed.LAND_UPLOAD_MILLIS);
        tick(live);
        assertEquals(Set.of("-1,-1"), binsOf(uploads.get(2)));
    }

    @Test
    @DisplayName("A failed upload sends its bins again next period; landReset from the page resends all but the snapshot")
    void failuresAndResetResend() throws IOException {
        RTPTestSetup.install(tempDir.resolve("plugin").toFile());
        WorldBiomeStore.clearForTests();
        open();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey survey = survey(store);
        EditorLiveFeed live = new EditorLiveFeed(tempDir.resolve("live"), false, List.of(survey), null, null,
                () -> "{}", 60 * 60_000L, clock::get);
        live.attachChannel(channel, this::handoff);
        live.seedLand(Map.of());
        String focus = "{\"type\":\"focus\",\"world\":\"world\",\"y\":64,\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}";

        failUploads.set(true);
        live.offerClientMessage(focus);
        tick(live);
        tick(live);
        assertEquals(1, uploads.size());
        assertNull(page.last("land-ref"), "no reference for a failed upload");

        failUploads.set(false);
        clock.addAndGet(EditorLiveFeed.LAND_UPLOAD_MILLIS + 1);
        tick(live);
        assertEquals(2, uploads.size());
        assertEquals(binsOf(uploads.get(0)), binsOf(uploads.get(1)), "the failed bins go again");
        assertEquals(9, binsOf(uploads.get(1)).size());

        clock.addAndGet(EditorLiveFeed.LAND_UPLOAD_MILLIS + 1);
        tick(live);
        assertEquals(2, uploads.size(), "the page holds everything: idle");
        // The page could not fetch an upload: it asks for a resend
        live.offerClientMessage("{\"type\":\"focus\",\"landReset\":true,\"verified\":[],\"hazardVersions\":{}}");
        tick(live);
        assertEquals(3, uploads.size(), "landReset resends the view");
        assertEquals(9, binsOf(uploads.get(2)).size());
    }

    @Test
    @DisplayName("A region's snapshot view is its walked extent plus padding, clamped to the focus edge for any size")
    void regionViewIsFinite() {
        Square small = new Square("TEST_LAND_VIEW_SMALL");
        small.set(GenericMemoryShapeParams.radius, 64L);
        small.set(GenericMemoryShapeParams.centerRadius, 0L);
        small.set(GenericMemoryShapeParams.centerX, 0L);
        small.set(GenericMemoryShapeParams.centerZ, 0L);
        int[] v = EditorLiveFeed.regionViewBins(small);
        assertNotNull(v);
        int pad = EditorLiveFeed.LAND_PAD_BINS;
        assertTrue(v[0] <= -2 - pad + 1 && v[2] >= 1 + pad - 1, "x covers the region bins plus padding: " + v[0] + ".." + v[2]);
        assertTrue(v[2] - v[0] + 1 <= 4 + 2 * pad + 1, "no wider than region plus padding: " + (v[2] - v[0] + 1));

        Square huge = new Square("TEST_LAND_VIEW_HUGE");
        huge.set(GenericMemoryShapeParams.radius, 200_000L);
        huge.set(GenericMemoryShapeParams.centerRadius, 0L);
        huge.set(GenericMemoryShapeParams.centerX, 0L);
        huge.set(GenericMemoryShapeParams.centerZ, 0L);
        int[] h = EditorLiveFeed.regionViewBins(huge);
        assertNotNull(h);
        assertEquals(EditorLiveFeed.MAX_FOCUS_EDGE, h[2] - h[0] + 1, "clamped edge");
        assertEquals(EditorLiveFeed.MAX_FOCUS_EDGE, h[3] - h[1] + 1, "clamped edge");
        // Sampled extent of a huge spiral is lopsided by about a ring step (~1 bin), plus clamp rounding
        assertTrue(Math.abs(h[0] + h[2]) <= 4 && Math.abs(h[1] + h[3]) <= 4,
                "clamped around the region centre: " + java.util.Arrays.toString(h));
    }
}
