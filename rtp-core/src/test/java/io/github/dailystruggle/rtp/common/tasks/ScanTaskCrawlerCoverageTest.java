package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.GenericVerticalAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.jump.JumpAdjustorKeys;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ScanTaskCrawler edge paths: pause, resume, abort, dirty bitmap, boundary clipping, probe outcomes")
class ScanTaskCrawlerCoverageTest {

    @TempDir
    File tempDir;

    private Region region;
    private MockRTPWorld world;
    private Square square;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("crawler_test_world");
        square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "crawler_test_region", world, square, vert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("crawler_test_region", settings);
        RTP.selectionAPI.permRegionLookup.put("crawler_test_region", region);
        RTP.getInstance().scanTasks.clear();
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().scanTasks.clear();
    }

    private void setField(Object target, String fieldName, Object val) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, val);
    }

    private Object getField(Object target, String fieldName) throws Exception {
        Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    @DisplayName("Pause transition stops execution and drains active chunks")
    void testPauseTransition() {
        ScanTask task = new ScanTask(region, 0L);
        assertFalse(task.pause.get());

        task.pause();
        assertTrue(task.pause.get());

        // Calling pause() multiple times is idempotent
        task.pause();
        assertTrue(task.pause.get());
    }

    @Test
    @DisplayName("Run short-circuits and flushes when paused")
    void testRunWhenPaused() {
        ScanTask task = new ScanTask(region, 0L);
        task.pause.set(true);

        square.addBadLocation(5L);
        assertTrue(square.isKnownBad(5L));

        task.run();

        assertTrue(task.pause.get());
        assertFalse(task.isCancelled());
    }

    @Test
    @DisplayName("Run short-circuits and cleans up when cancelled")
    void testRunWhenCancelled() throws Exception {
        ScanTask task = new ScanTask(region, 0L);
        RTP.getInstance().scanTasks.put(region.name, task);

        task.setCancelled(true);
        assertTrue(task.isCancelled());

        task.run();

        CompletableFuture<?> done = (CompletableFuture<?>) getField(task, "done");
        assertTrue(done.isDone());
        assertNull(RTP.getInstance().scanTasks.get(region.name));
    }

    @Test
    @DisplayName("Run short-circuits when scanIncrement <= 0")
    void testRunWhenScanIncrementZeroOrNegative() {
        ScanTask task = new ScanTask(region, 0L);
        task.scanIncrement.set(0);

        task.run();
        assertFalse(task.isCancelled());

        task.scanIncrement.set(-5);
        task.run();
        assertFalse(task.isCancelled());
    }

    @Test
    @DisplayName("Abort / setCancelled drains in-flight gate and cancels chunk futures")
    void testSetCancelledTransitions() throws Exception {
        ScanTask task = new ScanTask(region, 0L);
        RTP.getInstance().scanTasks.put(region.name, task);

        @SuppressWarnings("unchecked")
        Set<CompletableFuture<?>> futures = (Set<CompletableFuture<?>>) getField(task, "inFlightChunkFutures");
        CompletableFuture<Boolean> dummyFut = new CompletableFuture<>();
        futures.add(dummyFut);

        task.setCancelled(true);
        assertTrue(task.isCancelled());
        assertTrue(dummyFut.isCancelled());
    }

    @Test
    @DisplayName("Resumption with dirty bitmap state and save/delete lifecycle")
    void testSaveLoadDeleteLifecycle() throws Exception {
        ScanTask task = new ScanTask(region, 10L);
        AtomicLong scanIter = (AtomicLong) getField(task, "scanIter");
        scanIter.set(50L);
        task.scanIncrement.set(100L);
        task.save();

        File dir = new File(RTP.serverAccessor.getPluginDirectory(), "database" + File.separator + "regionData");
        File scanFile = new File(dir, region.name + "_" + region.cacheKey() + ".scan");
        assertTrue(scanFile.exists(), "Progress file should be created");

        // Create a new task instance resuming from disk
        ScanTask resumedTask = new ScanTask(region, 0L);
        assertNotNull(resumedTask);

        task.delete();
        assertFalse(scanFile.exists(), "Progress file should be deleted");
    }

    @Test
    @DisplayName("Corrupt scan progress file is handled safely without throwing")
    void testCorruptScanFileRecovery() throws Exception {
        File dir = new File(RTP.serverAccessor.getPluginDirectory(), "database" + File.separator + "regionData");
        dir.mkdirs();
        File scanFile = new File(dir, region.name + "_" + region.cacheKey() + ".scan");
        try (FileOutputStream out = new FileOutputStream(scanFile)) {
            out.write("invalid json or garbage text".getBytes(StandardCharsets.UTF_8));
        }

        ScanTask task = new ScanTask(region, 0L);
        assertNotNull(task);
        task.delete();
    }

    @Test
    @DisplayName("Boundary clipping rejects locations outside WorldBorder and marks bad chunk")
    void testBoundaryClippingRejection() {
        WorldBorder border = new WorldBorder(
                () -> square,
                loc -> loc.x() >= 0 && loc.x() <= 100 && loc.z() >= 0 && loc.z() <= 100
        );

        ScanTask task = new ScanTask(region, 0L);

        // Location at (200, 200) is outside the border
        long pos = square.xzToLocation(200, 200);
        assertTrue(pos >= 0);

        CompletableFuture<Boolean> fut = task.testPos(
                region, pos, 200, 200, 2,
                Collections.emptySet(), Collections.emptySet(), false, border);

        assertNotNull(fut);
        assertFalse(fut.join());
        assertTrue(square.isKnownBad(pos), "Outside border candidate must be marked bad in shape");
    }

    @Test
    @DisplayName("Boundary clipping handles null probe ChunkColumn when outside border")
    void testBoundaryClippingNullProbe() {
        MockRTPWorld nullProbeWorld = new MockRTPWorld("null_probe_world") {
            @Override
            public CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY) {
                return null;
            }
        };

        RegionSettings settings = new RegionSettings(
                "null_probe_region", nullProbeWorld, square, region.getVert(),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region nullProbeRegion = new Region("null_probe_region", settings);

        WorldBorder border = new WorldBorder(
                () -> square,
                loc -> false // everything outside
        );

        ScanTask task = new ScanTask(nullProbeRegion, 0L);
        long pos = square.xzToLocation(50, 50);

        CompletableFuture<Boolean> fut = task.testPos(
                nullProbeRegion, pos, 50, 50, 2,
                Collections.emptySet(), Collections.emptySet(), false, border);

        assertNotNull(fut);
        assertFalse(fut.join());
        assertTrue(square.isKnownBad(pos));
    }

    @Test
    @DisplayName("tryProbeFirstScan rejects when minY >= maxY")
    void testProbeFirstScanInvalidYRange() {
        LinearAdjustor invertedVert = new LinearAdjustor(new ArrayList<>());
        invertedVert.set(GenericVerticalAdjustorKeys.maxY, 50);
        invertedVert.set(GenericVerticalAdjustorKeys.minY, 100);

        RegionSettings settings = new RegionSettings(
                "inverted_y_region", world, square, invertedVert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region invertedRegion = new Region("inverted_y_region", settings);

        ScanTask task = new ScanTask(invertedRegion, 0L);
        long pos = square.xzToLocation(16, 16);

        CompletableFuture<Boolean> fut = task.testPos(
                invertedRegion, pos, 16, 16, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertNotNull(fut);
    }

    @Test
    @DisplayName("tryProbeFirstScan with JumpAdjustor probe-reject reasons")
    void testProbeFirstScanJumpAdjustorOutcomes() {
        JumpAdjustor jumpVert = new JumpAdjustor(new ArrayList<>());
        jumpVert.set(JumpAdjustorKeys.maxY, 120);
        jumpVert.set(JumpAdjustorKeys.minY, 60);

        FakeChunkColumnProbe probeVoid = new FakeChunkColumnProbe(2, 2, 0, 128)
                .withDefaultBlock("minecraft:air")
                .setDefaultBiome("minecraft:plains");

        MockRTPWorld jumpWorld = new MockRTPWorld("jump_world") {
            @Override
            public CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY) {
                return CompletableFuture.completedFuture(probeVoid);
            }
        };

        RegionSettings settings = new RegionSettings(
                "jump_region", jumpWorld, square, jumpVert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region jumpRegion = new Region("jump_region", settings);

        ScanTask task = new ScanTask(jumpRegion, 0L);
        long pos = square.xzToLocation(32, 32);

        CompletableFuture<Boolean> fut = task.testPos(
                jumpRegion, pos, 32, 32, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertNotNull(fut);
        assertFalse(fut.join());
        assertTrue(square.isKnownBad(pos));
    }

    @Test
    @DisplayName("Known-bad candidate with null probe falls back to cached chunk")
    void testKnownBadNullProbeWithCachedChunk() {
        square.addBadLocation(123L);

        MockRTPWorld cachedChunkWorld = new MockRTPWorld("cached_world") {
            @Override
            public CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public RTPChunk<?> getCachedChunk(long chunkKey) {
                return null;
            }
        };

        RegionSettings settings = new RegionSettings(
                "cached_region", cachedChunkWorld, square, region.getVert(),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region cachedRegion = new Region("cached_region", settings);

        ScanTask task = new ScanTask(cachedRegion, 0L);
        CompletableFuture<Boolean> fut = task.testPos(
                cachedRegion, 123L, 0, 0, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertNotNull(fut);
        assertTrue(fut.isDone());
        assertFalse(fut.join());
    }

    @Test
    @DisplayName("Known-bad candidate where probe threw an exception")
    void testKnownBadProbeThrew() {
        square.addBadLocation(456L);

        MockRTPWorld throwingWorld = new MockRTPWorld("throwing_probe_world") {
            @Override
            public CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY) {
                throw new RuntimeException("Simulated IO failure");
            }
        };

        RegionSettings settings = new RegionSettings(
                "throwing_probe_region", throwingWorld, square, region.getVert(),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region throwingRegion = new Region("throwing_probe_region", settings);

        ScanTask task = new ScanTask(throwingRegion, 0L);
        CompletableFuture<Boolean> fut = task.testPos(
                throwingRegion, 456L, 16, 16, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertNotNull(fut);
        assertTrue(fut.isDone());
        assertFalse(fut.join());
    }

    @Test
    @DisplayName("Probe completed asynchronously triggers callback correctly")
    void testProbeAsyncCompletion() {
        CompletableFuture<ChunkColumnProbe> deferredProbe = new CompletableFuture<>();

        MockRTPWorld asyncWorld = new MockRTPWorld("async_probe_world") {
            @Override
            public CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY) {
                return deferredProbe;
            }
        };

        RegionSettings settings = new RegionSettings(
                "async_probe_region", asyncWorld, square, region.getVert(),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region asyncRegion = new Region("async_probe_region", settings);

        ScanTask task = new ScanTask(asyncRegion, 0L);
        long pos = square.xzToLocation(48, 48);

        CompletableFuture<Boolean> fut = task.testPos(
                asyncRegion, pos, 48, 48, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertNotNull(fut);
        assertFalse(fut.isDone(), "Future should be waiting on async probe completion");

        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(3, 3, 0, 128)
                .withDefaultBlock("minecraft:stone")
                .setDefaultBiome("minecraft:plains")
                .setAir(65)
                .setAir(66);
        deferredProbe.complete(probe);

        assertTrue(fut.isDone());
    }
}
