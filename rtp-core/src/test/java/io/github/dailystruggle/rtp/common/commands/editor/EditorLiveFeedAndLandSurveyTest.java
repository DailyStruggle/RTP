package io.github.dailystruggle.rtp.common.commands.editor;

import io.github.dailystruggle.rtp.common.metrics.LandingHeatmap;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ADR-104 §4.6: coarse-first world biome store, live tiles, overlays and walk-path units")
class EditorLiveFeedAndLandSurveyTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir.resolve("plugin").toFile());
        WorldBiomeStore.clearForTests();
    }

    /**
     * Adapter stand-in: r.0.0 is PLAINS with a DESERT 4x4 corner (lx, lz < 4), r.-1.0 is IRIS:ASH;
     * every chunk generated. Records every sampled read and its chunk count.
     */
    private static class FakeRegions implements WorldLandSurvey.RegionBiomeSampler, WorldLandSurvey.RegionMtime {
        final List<int[]> reads = new ArrayList<>();
        long decodes;
        final Map<String, Long> mtimes = new HashMap<>();

        String biomeAt(int rcx, int rcz, int lx, int lz) {
            if (rcx == 0 && rcz == 0) return (lx < 4 && lz < 4) ? "DESERT" : "PLAINS";
            if (rcx == -1 && rcz == 0) return "IRIS:ASH";
            return null;
        }

        @Override
        public Map<Long, String> sample(int rcx, int rcz, int[] localIndices) {
            reads.add(new int[]{rcx, rcz, localIndices.length});
            decodes += localIndices.length;
            Map<Long, String> out = new HashMap<>();
            for (int idx : localIndices) {
                String b = biomeAt(rcx, rcz, idx & 31, idx >> 5);
                if (b == null) continue;
                int cx = (rcx << 5) | (idx & 31);
                int cz = (rcz << 5) | (idx >> 5);
                out.put(((long) cx << 32) | (cz & 0xFFFFFFFFL), b);
            }
            return out;
        }

        @Override
        public long of(int rcx, int rcz) {
            return mtimes.getOrDefault(rcx + "," + rcz, 1_000L);
        }
    }

    private static WorldLandSurvey survey(WorldBiomeStore store, FakeRegions fake, List<int[]> files) {
        return new WorldLandSurvey("world", 64, store, fake, fake, files, 3, WorldLandSurvey.MAX_TILES_PER_WORLD);
    }

    private static Square square(int radius, int cx, int cz) {
        Square square = new Square("TEST_WALK_" + radius + "_" + cx + "_" + cz);
        square.set(GenericMemoryShapeParams.radius, (long) radius);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        square.set(GenericMemoryShapeParams.centerX, (long) cx);
        square.set(GenericMemoryShapeParams.centerZ, (long) cz);
        return square;
    }

    private static int cellAt(WorldBiomeStore store, int rx, int rz, int lx, int lz) {
        WorldBiomeStore.Layer l = store.layer(rx, rz, 64);
        assertNotNull(l);
        return BiomeBinCodec.decode(l.runs())[BiomeBinCodec.xz2d(lx, lz)];
    }

    private static String feedJson(Path liveDir) throws IOException {
        String script = Files.readString(liveDir.resolve(EditorLiveFeed.FEED_FILE), StandardCharsets.UTF_8);
        assertTrue(script.startsWith("window.__rtpLiveFeed&&window.__rtpLiveFeed({"), script);
        assertTrue(script.endsWith("});\n"), script);
        return script;
    }

    @Test
    @DisplayName("Survey fills the whole world coarse-first, then sharpens every file to chunk detail")
    void coarseFirstThenFine() {
        FakeRegions fake = new FakeRegions();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        List<int[]> files = List.of(new int[]{900, -900}, new int[]{-1, 0}, new int[]{0, 0}, new int[]{0, 0});
        WorldLandSurvey s = survey(store, fake, files);
        assertTrue(s.listed());
        assertEquals(3, s.fileTotal(), "duplicates collapse; far files are kept");

        WorldLandSurvey.Batch first = s.nextBatch();
        assertNotNull(first);
        assertArrayEquals(new int[]{0, 0, 1}, fake.reads.get(0), "origin file first, one chunk at level 0");
        assertArrayEquals(new int[]{-1, 0, 1}, fake.reads.get(1));
        assertArrayEquals(new int[]{900, -900, 1}, fake.reads.get(2), "every file gets its coarse sample first");
        int firstFull = -1;
        for (int i = 0; i < fake.reads.size() && firstFull < 0; i++) if (fake.reads.get(i)[2] == 1024) firstFull = i;
        assertTrue(firstFull > 2, "no full-detail read before the coarse pass finished");
        assertTrue(first.decodes() <= WorldLandSurvey.DECODES_PER_TICK + 1024, "budget counts decodes: " + first.decodes());

        while (!s.isDone()) assertNotNull(s.nextBatch());
        assertEquals(BiomeBinCodec.FINEST_LEVEL, store.levelAt(0, 0, 64));
        assertEquals("minecraft:desert", store.palette().get(cellAt(store, 0, 0, 3, 3) - 1), "finest pass shows the corner");
        assertEquals("minecraft:plains", store.palette().get(cellAt(store, 0, 0, 4, 4) - 1));
        assertEquals("iris:ash", store.palette().get(cellAt(store, -1, 0, 9, 9) - 1), "modded namespace kept, lowercased");
        assertTrue(Arrays.stream(fake.reads.toArray(new int[0][])).anyMatch(r -> r[0] == 900 && r[1] == -900),
                "a file 460k blocks out is still read");
        assertNull(s.nextBatch(), "a finished survey yields no batches");
        int[] hist = BiomeBinCodec.histogram(store.layer(0, 0, 64).runs(), store.palette().size() + 1);
        assertEquals(16, hist[store.paletteIndex("minecraft:desert") + 1]);
    }

    @Test
    @DisplayName("Per-batch budget counts chunk decodes, so coarse passes cover many files per tick")
    void decodeBudget() {
        FakeRegions fake = new FakeRegions() {
            @Override
            String biomeAt(int rcx, int rcz, int lx, int lz) {
                return "PLAINS";
            }
        };
        List<int[]> files = new ArrayList<>();
        for (int i = 0; i < 3000; i++) files.add(new int[]{i % 60, i / 60});
        WorldLandSurvey s = survey(new WorldBiomeStore("world", null), fake, files);
        WorldLandSurvey.Batch b = s.nextBatch();
        assertEquals(2048, b.visits(), "level 0 costs one decode per file");
        assertTrue(b.decodes() <= WorldLandSurvey.DECODES_PER_TICK);
        while (s.level() == 0) s.nextBatch();
        long before = fake.decodes;
        WorldLandSurvey.Batch fine = s.nextBatch();
        assertTrue(fine.decodes() <= WorldLandSurvey.DECODES_PER_TICK + 64, "level 1 batch stays in budget");
        assertTrue(fake.decodes - before <= WorldLandSurvey.DECODES_PER_TICK + 64);
    }

    @Test
    @DisplayName("Persisted bins with an unchanged .mca mtime are skipped; a changed file is re-read at full detail only")
    void persistedStoreAndMtime() throws IOException {
        FakeRegions fake = new FakeRegions();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        List<int[]> files = List.of(new int[]{0, 0}, new int[]{-1, 0});
        WorldLandSurvey first = survey(store, fake, files);
        while (!first.isDone()) first.nextBatch();

        Path file = tempDir.resolve("cache").resolve("world.rbs");
        store.save(file);
        WorldBiomeStore reloaded = new WorldBiomeStore("world", file);
        reloaded.load(file);
        assertEquals(store.binCount(), reloaded.binCount());
        assertEquals("minecraft:desert", reloaded.palette().get(cellAt(reloaded, 0, 0, 0, 0) - 1));

        FakeRegions second = new FakeRegions();
        WorldLandSurvey again = survey(reloaded, second, files);
        while (!again.isDone()) again.nextBatch();
        assertTrue(second.reads.isEmpty(), "nothing re-read after a restart when files are unchanged");

        FakeRegions third = new FakeRegions();
        third.mtimes.put("0,0", 2_000L);
        WorldLandSurvey changed = survey(reloaded, third, files);
        while (!changed.isDone()) changed.nextBatch();
        assertEquals(1, third.reads.size(), "only the changed file is read");
        assertArrayEquals(new int[]{0, 0, 1024}, third.reads.get(0), "and only at full detail: coarse passes never overwrite it");
    }

    @Test
    @DisplayName("Corrupt cache files are ignored and logged, not fatal")
    void corruptStoreIgnored() throws IOException {
        Path file = tempDir.resolve("bad.rbs");
        Files.write(file, new byte[]{1, 2, 3});
        WorldBiomeStore s = new WorldBiomeStore("world", file);
        s.load();
        assertEquals(0, s.binCount());
    }

    @Test
    @DisplayName("Y layers: lookups take the nearest stored layer within 16 blocks")
    void yTolerance() {
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        int[] idx = BiomeBinCodec.sampleIndices(0);
        store.apply(0, 0, 64, 0, idx, Map.of(idx[0], "minecraft:plains"), 1L);
        store.apply(0, 0, -30, 0, idx, Map.of(idx[0], "minecraft:deep_dark"), 1L);
        assertEquals(64, store.layer(0, 0, 70).y());
        assertEquals(64, store.layer(0, 0, 48).y());
        assertNull(store.layer(0, 0, 20), "no layer within 16 blocks");
        assertEquals(-30, store.layer(0, 0, -40).y());
        assertEquals(Integer.valueOf(-30), store.nearestStoredY(-20));
        assertNull(store.nearestStoredY(30));
    }

    @Test
    @DisplayName("Without a listing the survey probes a disc around the origin once and skips misses later")
    void probingFallback() {
        FakeRegions fake = new FakeRegions();
        WorldLandSurvey s = new WorldLandSurvey("world", 64, new WorldBiomeStore("world", null), fake, fake,
                null, 3, WorldLandSurvey.MAX_TILES_PER_WORLD);
        assertFalse(s.listed());
        assertEquals(29, s.fileTotal(), "disc of radius 3 region files");
        while (!s.isDone()) s.nextBatch();
        Set<String> probed = new HashSet<>();
        int laterMisses = 0;
        for (int[] r : fake.reads) {
            probed.add(r[0] + "," + r[1]);
            boolean land = fake.biomeAt(r[0], r[1], 0, 0) != null;
            if (!land && r[2] == 1024) laterMisses++;
        }
        assertEquals(29, probed.size());
        assertEquals(0, laterMisses, "empty coordinates are not re-probed at full detail");
        assertTrue(s.filesRead() >= 2, "both populated files were read");
        assertEquals(BiomeBinCodec.FINEST_LEVEL, s.store().levelAt(-1, 0, 64));
    }

    @Test
    @DisplayName("Viewport priority takes the requested bins to full detail before the world pass")
    void viewportPriority() {
        FakeRegions fake = new FakeRegions() {
            @Override
            String biomeAt(int rcx, int rcz, int lx, int lz) {
                return "PLAINS";
            }
        };
        List<int[]> files = new ArrayList<>();
        for (int rx = -10; rx <= 10; rx++) for (int rz = -10; rz <= 10; rz++) files.add(new int[]{rx, rz});
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey s = survey(store, fake, files);
        s.prioritize(8, 8, 9, 9);
        // ~2 full-detail bins fit one batch's decode budget
        s.nextBatch();
        s.nextBatch();
        for (int rx = 8; rx <= 9; rx++) {
            for (int rz = 8; rz <= 9; rz++) assertEquals(BiomeBinCodec.FINEST_LEVEL, store.levelAt(rx, rz, 64));
        }
        assertEquals(-1, store.levelAt(0, 0, 64), "world pass waits for the requested bins");
        assertEquals(-1, store.levelAt(-10, -10, 64));
        s.prioritize(-1000, -1000, 1000, 1000);
        assertDoesNotThrow(s::nextBatch, "views wider than the cap fall back to the coarse passes");
    }

    @Test
    @DisplayName("Hazard bins follow the learned bad-location memory and report only changes")
    void hazardTiles() {
        Square sq = square(40, 0, 0);
        HazardTiles hz = HazardTiles.of(sq);
        int total = hz.binTotal();
        assertTrue(total > 0);
        assertTrue(hz.nextBatch(total).isEmpty(), "no hazards learned yet");
        long loc = sq.xzToLocation(5, 6);
        sq.addBadLocation(loc, LocationGenerator.FailTypes.biome);
        List<int[]> changed = hz.nextBatch(total);
        assertEquals(1, changed.size());
        int[] b = changed.get(0);
        int[] coords = sq.locationToXZ(loc);
        int[] cells = BiomeBinCodec.decode(hz.runs(b[0], b[1]));
        int d = BiomeBinCodec.xz2d(coords[0] & 31, coords[1] & 31);
        assertEquals(LocationGenerator.FailTypes.biome.ordinal() + 1, cells[d]);
        assertTrue(hz.nextBatch(total).isEmpty(), "unchanged bins are not reported again");
    }

    @Test
    @DisplayName("Landing heatmap buckets per chunk and versions per region file")
    void landingHeatmap() {
        long v0 = LandingHeatmap.version();
        for (int i = 0; i < 5; i++) LandingHeatmap.record("heat_r", 100, -40);
        LandingHeatmap.record("heat_r", 5000, 5000);
        List<int[]> bins = LandingHeatmap.changedBins("heat_r", v0);
        assertEquals(2, bins.size());
        byte[] runs = EditorLiveFeed.heatRuns("heat_r", 0, -1);
        int[] cells = BiomeBinCodec.decode(runs);
        assertEquals(3, cells[BiomeBinCodec.xz2d(100 >> 4, (-40 >> 4) & 31)], "5 landings -> 1 + floor(log2 5)");
        assertNull(EditorLiveFeed.heatRuns("heat_r", 7, 7));
    }

    @Test
    @DisplayName("Walk path tiles cover the whole curve at chunk resolution and join in curve order")
    void walkPathTilesCoverFullCurve() {
        Square square = square(40, 300, -200);
        long range = square.getRange();
        WalkPathTiles path = WalkPathTiles.of("default", square);
        assertFalse(path.truncated());

        Map<Long, int[]> byLoc = new TreeMap<>();
        int batches = 0;
        while (!path.isDone()) {
            WalkPathTiles.Batch batch = path.nextBatch(4);
            assertNotNull(batch);
            batches++;
            for (WalkPathTiles.Tile t : batch.tiles()) {
                assertEquals(WalkPathTiles.TILE_CHUNKS * WalkPathTiles.TILE_CHUNKS, t.locs().length);
                for (int i = 0; i < t.locs().length; i++) {
                    long loc = t.locs()[i];
                    if (loc < 0) continue;
                    assertTrue(loc < range);
                    byLoc.put(loc, new int[]{t.tx() * 32 + (i % 32), t.tz() * 32 + (i / 32)});
                }
            }
        }
        assertTrue(batches > 1, "produced over several ticks");
        assertEquals(path.tilesTotal(), path.tilesDone());
        assertTrue(byLoc.size() >= range * 0.99, "every curve location is present: " + byLoc.size() + " of " + range);

        int joined = 0;
        int[] prev = null;
        for (int[] c : byLoc.values()) {
            assertTrue(Math.abs(c[0] - 300) <= 41 && Math.abs(c[1] + 200) <= 41, "cells follow the off-centre shape");
            if (prev != null && Math.abs(prev[0] - c[0]) <= 1 && Math.abs(prev[1] - c[1]) <= 1) joined++;
            prev = c;
        }
        assertTrue(joined >= (byLoc.size() - 1) * 0.95, "consecutive locations are neighbouring chunks: " + joined);
    }

    @Test
    @DisplayName("Walk path tiles stop at the per-region cap")
    void walkPathTileCap() {
        WalkPathTiles path = new WalkPathTiles("big", square(400, 0, 0), 3);
        assertTrue(path.truncated());
        assertEquals(3, path.tilesTotal());
        path.nextBatch(10);
        assertTrue(path.isDone());
    }

    @Test
    @DisplayName("Region-file listing returns only non-empty r.X.Z.mca files")
    void regionFileListing() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("world").resolve("region"));
        Files.write(dir.resolve("r.0.0.mca"), new byte[8192]);
        Files.write(dir.resolve("r.-12.3.mca"), new byte[8192]);
        Files.write(dir.resolve("r.4.4.mca"), new byte[0]);
        Files.writeString(dir.resolve("r.1.1.mca.tmp"), "x");
        Files.writeString(dir.resolve("notes.txt"), "x");

        List<int[]> coords = io.github.dailystruggle.rtp.anvil.RegionFileResolver.listAnvilRegionCoords(dir);
        coords.sort(Comparator.comparingInt(c -> c[0]));
        assertEquals(2, coords.size());
        assertArrayEquals(new int[]{-12, 3}, coords.get(0));
        assertArrayEquals(new int[]{0, 0}, coords.get(1));
        assertTrue(io.github.dailystruggle.rtp.anvil.RegionFileResolver.listAnvilRegionCoords(tempDir.resolve("missing")).isEmpty());
    }

    @Test
    @DisplayName("Feed tick writes the head, grouped land bins, path tiles and their index batches")
    void tickWritesFeedAndTiles() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        FakeRegions fake = new FakeRegions();
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey s = survey(store, fake, List.of(new int[]{0, 0}, new int[]{-1, 0}));
        WalkPathTiles path = WalkPathTiles.of("default", square(20, 0, 0));
        AtomicLong tps = new AtomicLong(19);
        List<String> pushed = new ArrayList<>();
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, List.of(s), List.of(path),
                () -> "{\"tps1m\":" + tps.get() + "}", 60_000L, System::currentTimeMillis);
        feed.setPush(pushed::add);
        feed.prepareDirectory();

        assertTrue(feed.tick());
        String head = feedJson(liveDir);
        assertTrue(head.contains("\"seq\":1"), head);
        assertTrue(head.contains("\"pathBatches\":1"), head);
        assertTrue(head.contains("\"regions\":[{\"region\":\"default\",\"r\":0"), head);
        assertTrue(head.contains("\"telemetry\":{\"tps1m\":19}"), head);
        assertTrue(head.contains("\"stopped\":false"), head);
        assertTrue(head.contains("\"palettes\":{\"0\":[\"minecraft:plains\""), head);
        assertTrue(head.contains("\"land\":[[\"0_64\",0,0,1],[\"0_64\",-1,0,1]]"), head);
        assertEquals(1, pushed.size());
        assertTrue(pushed.get(0).startsWith("{\"type\":\"feed\",\"data\":{\"sessionId\":\"" + feed.sessionId() + "\""));

        String group = Files.readString(liveDir.resolve("land-0_64_0_0.js"), StandardCharsets.UTF_8);
        assertTrue(group.startsWith("window.__rtpLiveLandGroup&&window.__rtpLiveLandGroup(0,64,0,0,{"), group);
        String expected = Base64.getEncoder().encodeToString(store.layer(0, 0, 64).runs());
        assertTrue(group.contains("[0,0," + store.levelAt(0, 0, 64) + ",\"" + expected + "\"]"), group);
        assertTrue(Files.exists(liveDir.resolve("land-0_64_-1_0.js")));

        String pathIndex = Files.readString(liveDir.resolve("path-0.js"), StandardCharsets.UTF_8);
        assertTrue(pathIndex.startsWith("window.__rtpLivePath&&window.__rtpLivePath(0,{"), pathIndex);
        assertTrue(Files.exists(liveDir.resolve("path-0_0_0.js")), "centre tile first");

        tps.set(12);
        for (int i = 0; i < 20 && !(s.isDone() && path.isDone()); i++) assertTrue(feed.tick());
        head = feedJson(liveDir);
        assertTrue(head.contains("\"telemetry\":{\"tps1m\":12}"), head);
        assertTrue(s.isDone() && path.isDone());
        assertTrue(head.contains("\"level\":3") && head.contains("\"done\":true"), head);
        group = Files.readString(liveDir.resolve("land-0_64_0_0.js"), StandardCharsets.UTF_8);
        assertTrue(group.contains("[0,0,2,\""), "group rewritten as the bin sharpened: " + group);
        long seqDone = feed.seq();
        assertTrue(feed.tick(), "finished layers keep the telemetry feed alive");
        assertTrue(feedJson(liveDir).contains("\"seq\":" + (seqDone + 1)));
        assertFalse(Files.exists(liveDir.resolve("feed.js.tmp")), "no temp files left behind");
    }

    @Test
    @DisplayName("Viewport focus messages reprioritise the matching survey")
    void focusMessage() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        FakeRegions fake = new FakeRegions() {
            @Override
            String biomeAt(int rcx, int rcz, int lx, int lz) {
                return "PLAINS";
            }
        };
        List<int[]> files = new ArrayList<>();
        for (int rx = -30; rx <= 30; rx++) for (int rz = -30; rz <= 30; rz++) files.add(new int[]{rx, rz});
        WorldBiomeStore store = new WorldBiomeStore("world", null);
        WorldLandSurvey s = survey(store, fake, files);
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, List.of(s), List.of(), () -> "{}", 60_000L, System::currentTimeMillis);
        feed.prepareDirectory();
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"world\",\"y\":70,\"minRx\":30,\"minRz\":30,\"maxRx\":30,\"maxRz\":30}");
        assertTrue(feed.tick());
        assertEquals(BiomeBinCodec.FINEST_LEVEL, store.levelAt(30, 30, 64), "y=70 is served by the y=64 survey");
        feed.offerClientMessage("{\"type\":\"focus\",\"world\":\"nope\",\"minRx\":0,\"minRz\":0,\"maxRx\":0,\"maxRz\":0}");
        feed.offerClientMessage("not json");
        assertTrue(feed.tick(), "unknown worlds and junk are ignored");
    }

    @Test
    @DisplayName("Reshaped or removed regions get a new id; their old path, hazard and heat tiles are deleted")
    void regionReshape() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        Map<String, MemoryShape<?>> shapes = new LinkedHashMap<>();
        Square first = square(20, 0, 0);
        first.addBadLocation(first.xzToLocation(1, 1), LocationGenerator.FailTypes.biome);
        shapes.put("alpha", first);
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, List.of(), () -> new LinkedHashMap<>(shapes), null,
                () -> "{}", 60_000L, System::currentTimeMillis);
        feed.prepareDirectory();
        assertTrue(feed.tick());
        assertEquals(0, feed.tracks().get(0).r);
        assertTrue(Files.exists(liveDir.resolve("path-0_0_0.js")));
        try (var files = Files.list(liveDir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("hazard-0_")), "hazard group written");
        }
        assertTrue(feedJson(liveDir).contains("\"hazard\":[[\"0\""));

        shapes.put("alpha", square(30, 100, 100));
        for (int i = 0; i < EditorLiveFeed.REGION_CHECK_TICKS; i++) feed.tick();
        assertEquals(1, feed.tracks().get(0).r, "new shape object -> new id");
        assertFalse(Files.exists(liveDir.resolve("path-0_0_0.js")), "old id's tiles deleted");
        String head = feedJson(liveDir);
        assertFalse(head.contains("\"hazard\":[[\"0\""), head);
        assertTrue(head.contains("\"regions\":[{\"region\":\"alpha\",\"r\":1"), head);

        shapes.clear();
        for (int i = 0; i < EditorLiveFeed.REGION_CHECK_TICKS; i++) feed.tick();
        assertTrue(feed.tracks().isEmpty());
        try (var files = Files.list(liveDir)) {
            assertTrue(files.noneMatch(p -> p.getFileName().toString().startsWith("path-1_")), "removed region's tiles deleted");
        }
    }

    @Test
    @DisplayName("Feed expires after its TTL and records the reason for the page")
    void feedExpires() throws IOException {
        Path liveDir = tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR);
        AtomicLong now = new AtomicLong(1_000_000L);
        EditorLiveFeed feed = new EditorLiveFeed(liveDir, List.of(), List.of(), () -> "{}", 10_000L, now::get);
        feed.prepareDirectory();
        assertTrue(feed.tick());

        now.addAndGet(10_001L);
        assertFalse(feed.tick());
        assertTrue(feed.isStopped());
        assertTrue(feed.stopReason().contains("expired"), feed.stopReason());
        String head = feedJson(liveDir);
        assertTrue(head.contains("\"stopped\":true"), head);
        assertTrue(head.contains("\"telemetry\":null"), head);
        assertFalse(feed.tick(), "a stopped feed never writes again");
    }

    @Test
    @DisplayName("Directory preparation removes only files the feed owns")
    void prepareDirectoryRemovesOnlyOwnFiles() throws IOException {
        Path liveDir = Files.createDirectories(tempDir.resolve("editor").resolve(EditorLiveFeed.LIVE_DIR));
        for (String n : List.of("feed.js", "land-7.js", "land-8.js.tmp", "land-0_64_-3_5.js", "path-2_4_-1.js",
                "path-9.js", "hazard-1_0_-1.js", "heat-3_2_2.js")) {
            Files.writeString(liveDir.resolve(n), "old");
        }
        Files.writeString(liveDir.resolve("operator-notes.txt"), "keep");
        Files.writeString(liveDir.resolve("land-notes.js"), "keep");

        new EditorLiveFeed(liveDir, List.of(), List.of(), () -> "{}", 1_000L, System::currentTimeMillis).prepareDirectory();

        try (var files = Files.list(liveDir)) {
            assertEquals(Set.of("operator-notes.txt", "land-notes.js"),
                    new HashSet<>(files.map(p -> p.getFileName().toString()).toList()));
        }
    }

    @Test
    @DisplayName("Walk path points are absolute block coordinates that follow an off-centre shape")
    void walkPathPointsAreBlocks() {
        Square square = square(64, 500, -300);
        Map<String, Object> data = new EditorSessionManager().generateWalkPathPayload(square);
        assertEquals("block", data.get("units"));
        assertFalse(data.containsKey("bins"), "export-time bins replaced by live path tiles");
        @SuppressWarnings("unchecked")
        List<int[]> points = (List<int[]>) data.get("points");
        assertTrue(points.size() > 16, "path must have enough vertices to draw");
        assertTrue(points.size() <= EditorSessionManager.WALK_PATH_MAX_POINTS);
        for (int[] p : points) {
            assertTrue(p[0] >= (500 - 65) * 16 && p[0] <= (500 + 65) * 16, "x in blocks around centre: " + p[0]);
            assertTrue(p[1] >= (-300 - 65) * 16 && p[1] <= (-300 + 65) * 16, "z in blocks around centre: " + p[1]);
        }
    }

    @Test
    @DisplayName("Local export embeds the live feed hint and the signed channel block (ADR-106 §5.2) only when requested")
    void exportLiveFeedHint() throws IOException {
        EditorSessionManager manager = new EditorSessionManager();
        Map<String, String> configs = Map.of("config.yml", "teleport:\n  radius: 5000\n");

        Path withFeed = tempDir.resolve("a").resolve("index.html");
        Map<String, Object> channel = new LinkedHashMap<>();
        channel.put("relay", "ws://127.0.0.1:4711/rtp-editor-ws?token=0123456789abcdef0123456789abcdef");
        channel.put("id", "0123456789abcdef");
        channel.put("pluginKey", "AAAA");
        manager.exportLocalEditorHtml(withFeed, configs, true, channel);
        String html = Files.readString(withFeed);
        assertTrue(html.contains("\"liveFeed\":{\"path\":\"live/feed.js\",\"intervalMs\":" + EditorLiveFeed.PERIOD_MILLIS + "}"),
                "live feed hint travels with the export, without a socket address of its own");
        assertTrue(html.contains("\"channel\":{\"relay\":\"ws://127.0.0.1:4711/rtp-editor-ws?token=0123456789abcdef0123456789abcdef\","
                + "\"id\":\"0123456789abcdef\",\"pluginKey\":\"AAAA\"}"), "channel {relay, id, pluginKey} travels with the export");
        assertTrue(html.contains("\"worldBiomes\":{"), "stored world biome bins travel with every export");
        assertFalse(html.contains("\"spiralWalkPath\""));

        Path withoutFeed = tempDir.resolve("b").resolve("index.html");
        manager.exportLocalEditorHtml(withoutFeed, configs);
        String plain = Files.readString(withoutFeed);
        assertFalse(plain.contains("\"liveFeed\""));
        assertFalse(plain.contains("\"channel\":{\"relay\""), "no channel block without a channel");
    }
}
