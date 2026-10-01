package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class ScanTaskTest {

    @TempDir
    File tempDir;

    private Region region;
    private MockRTPWorld world;
    private Square square;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("scan_test_world");
        square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "scan_region", world, square, vert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("scan_region", settings);
        RTP.selectionAPI.permRegionLookup.put("scan_region", region);
        RTP.getInstance().scanTasks.clear();
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().scanTasks.clear();
    }

    @Test
    void constructor_withStartZero_clearsShapeWhenPrescan() {
        square.addBadLocation(5L);
        assertTrue(square.isKnownBad(5L));

        ScanTask task = new ScanTask(region, 0L);
        assertNotNull(task);
        assertEquals(region, task.region);
        assertEquals("rtp_scan_crawler", task.sparkFrameName());
        assertFalse(square.isKnownBad(5L));
        assertTrue(task.scanIncrement.get() > 0);
    }

    @Test
    void constructor_withPerformanceTrackingParameters() {
        BigInteger cpsAll = BigInteger.valueOf(1000);
        BigInteger divisor = BigInteger.valueOf(10);
        ScanTask task = new ScanTask(region, 50L, cpsAll, divisor, 250L, 100L);

        assertNotNull(task);
        assertEquals(region, task.region);
        assertEquals(250L, task.scanIncrement.get());
    }

    @Test
    void constructor_withNonPositiveIncrement_computesDefault() {
        ScanTask task = new ScanTask(region, 0L, BigInteger.ZERO, BigInteger.ZERO, 0L, 50L);
        assertTrue(task.scanIncrement.get() > 0);
    }

    @Test
    void saveAndLoadProgressAndCleanDelete() {
        ScanTask task = new ScanTask(region, 123L);
        task.currentOffset = 4L;
        task.save();

        long[] loaded = ScanTask.loadProgress(region.name, region.cacheKey());
        assertNotNull(loaded);
        assertEquals(123L, loaded[0]); // scanIter
        assertEquals(4L, loaded[3]); // currentOffset

        task.delete();
        assertNull(ScanTask.loadProgress(region.name, region.cacheKey()));
    }

    @Test
    void save_whenCancelled_deletesProgressFile() {
        ScanTask task = new ScanTask(region, 456L);
        task.save();
        assertNotNull(ScanTask.loadProgress(region.name, region.cacheKey()));

        task.setCancelled(true);
        task.save();
        assertNull(ScanTask.loadProgress(region.name, region.cacheKey()));
    }

    @Test
    void deleteByRegionName_removesMatchingFiles() {
        ScanTask task = new ScanTask(region, 789L);
        task.save();
        assertNotNull(ScanTask.loadProgress(region.name, region.cacheKey()));

        ScanTask.delete(region.name);
        assertNull(ScanTask.loadProgress(region.name, region.cacheKey()));
    }

    @Test
    void deleteByRegionName_whenDirEmptyOrNonExistent_doesNotThrow() {
        assertDoesNotThrow(() -> ScanTask.delete("non_existent_region_xyz"));
    }

    @Test
    void loadProgress_nonExistentFile_returnsNull() {
        assertNull(ScanTask.loadProgress("unknown_region", "unknown_key"));
    }

    @Test
    void pause_setsPauseAndFlushesShape() {
        ScanTask task = new ScanTask(region, 10L);
        assertFalse(task.pause.get());

        task.pause();
        assertTrue(task.pause.get());
    }

    @Test
    void setCancelled_cancelsAndRemovesFromScanTasks() {
        ScanTask task = new ScanTask(region, 10L);
        RTP.getInstance().scanTasks.put(region.name, task);

        task.setCancelled(true);
        assertTrue(task.isCancelled());
        assertNull(RTP.getInstance().scanTasks.get(region.name));
    }

    @Test
    void kill_cancelsAllAndClearsScanTasks() {
        ScanTask task1 = new ScanTask(region, 10L);
        RegionSettings settings2 = new RegionSettings(
                "scan_region2", world, square, region.getVert(),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region region2 = new Region("scan_region2", settings2);
        ScanTask task2 = new ScanTask(region2, 20L);

        RTP.getInstance().scanTasks.put("scan_region", task1);
        RTP.getInstance().scanTasks.put("scan_region2", task2);

        ScanTask.kill();
        assertTrue(task1.isCancelled());
        assertTrue(task2.isCancelled());
        assertTrue(RTP.getInstance().scanTasks.isEmpty());
    }

    @Test
    void run_whenPausedOrCancelled_shortCircuitsImmediately() {
        ScanTask task = new ScanTask(region, 10L);
        task.pause.set(true);

        assertDoesNotThrow(task::run);
        assertFalse(task.isRunning());

        task.pause.set(false);
        task.setCancelled(true);
        assertDoesNotThrow(task::run);
        assertFalse(task.isRunning());
    }

    @Test
    void run_reentryGuard_skipsExecutionIfAlreadyRunning() {
        ScanTask task = new ScanTask(region, 10L);
        task.isRunning.set(true);

        assertDoesNotThrow(task::run);
        assertTrue(task.isRunning());
    }

    @Test
    void getEtaSeconds_calculatesReasonableEta() {
        ScanTask task = new ScanTask(region, 10L);
        long eta = task.getEtaSeconds(1000L, 100L, square, 50L);
        assertTrue(eta >= 0);

        // Edge case: range < finalPos1
        long etaZero = task.getEtaSeconds(50L, 100L, square, 50L);
        assertEquals(0L, etaZero);
    }

    @Test
    void recordBiomeForChunk_recordsBiomeInShape() {
        ScanTask.recordBiomeForChunk(square, 0L, 0, 0, "minecraft:plains");
        square.flushAndRebuild(1);
        assertEquals("PLAINS", square.biomeAt(0L));
    }

    @Test
    void testPos_whenKnownBadWithRecordedBiome_returnsCompletedFalseImmediately() {
        ScanTask task = new ScanTask(region, 0L);
        square.addBadLocation(12L);
        square.addBiomeLocation(12L, 1, "minecraft:ocean");
        square.flushAndRebuild(1);

        CompletableFuture<Boolean> fut = task.testPos(
                region, 12L, 0, 0, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertTrue(fut.isDone());
        assertFalse(fut.join());
    }

    @Test
    void testPos_withNullShapeOrNullVert_returnsCompletedFalse() {
        ScanTask task = new ScanTask(region, 0L);
        Region brokenRegion = new Region("broken", new RegionSettings(
                "broken", world, null, null,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false));

        CompletableFuture<Boolean> fut = task.testPos(
                brokenRegion, 0L, 0, 0, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertTrue(fut.isDone());
        assertFalse(fut.join());
    }

    @Test
    void testPos_outsideWorldBorder_rejectsAndReturnsFalse() {
        ScanTask task = new ScanTask(region, 0L);
        WorldBorder border = new WorldBorder(() -> null, loc -> false); // rejects every location

        CompletableFuture<Boolean> fut = task.testPos(
                region, 10L, 100, 100, 2,
                Collections.emptySet(), Collections.emptySet(), false, border);

        assertTrue(fut.isDone());
        assertFalse(fut.join());
    }

    @Test
    void run_fullBatchExecution_progressesIteration() {
        ScanTask task = new ScanTask(region, 0L);
        task.scanIncrement.set(10L);

        assertDoesNotThrow(task::run);
        assertFalse(task.isRunning());
    }

    @Test
    void scanTask_landPercentage_calculatesAccuratelyFromOutcomes() {
        ScanTask task = new ScanTask(region, 0L);
        task.scanIncrement.set(50L);
        task.run();

        // In MockRTPWorld default mock setup, evaluateScanProbe / chunk column returns safe land.
        // latestLandPercentage must reflect actual tested outcomes and not be 0.00% when land is found.
        assertTrue(task.latestLandPercentage > 0.0,
                "Land percentage should be > 0.0% when valid land is found, got: " + task.latestLandPercentage);
    }

    @Test
    void testClaimBoundingBoxBlanking_encapsulatesWholeClaimAndSkipsSubsequent() {
        square.set(GenericMemoryShapeParams.radius, 300L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        // Mock a claim boundary at (100, 100) -> (200, 200)
        io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTrackerTest.RectangularClaimBoundary boundary =
                new io.github.dailystruggle.rtp.common.selection.region.claim.ClaimAnchoredRegionTrackerTest.RectangularClaimBoundary(
                        "claim_test_1", "scan_test_world", 100, 100, 200, 200);

        io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider provider =
                new io.github.dailystruggle.rtp.api.claim.ClaimBoundaryProvider() {
                    @Override
                    public String namespace() {
                        return "mock_claims";
                    }

                    @Override
                    public java.util.Optional<io.github.dailystruggle.rtp.api.claim.ClaimBoundary> getBoundary(
                            java.util.UUID playerId, String worldName) {
                        return java.util.Optional.of(boundary);
                    }

                    @Override
                    public java.util.Optional<io.github.dailystruggle.rtp.api.claim.ClaimBoundary> getBoundaryAt(
                            String worldName, int x, int z) {
                        if ("scan_test_world".equalsIgnoreCase(worldName) && boundary.contains(x, z)) {
                            return java.util.Optional.of(boundary);
                        }
                        return java.util.Optional.empty();
                    }
                };

        io.github.dailystruggle.rtp.api.hooks.ClaimBoundaryRegistry claimRegistry =
                io.github.dailystruggle.rtp.api.RTPAPI.hooks().claimBoundaries();
        AutoCloseable claimHandle = claimRegistry.register(provider);

        java.util.concurrent.atomic.AtomicInteger verifierInvocations = new java.util.concurrent.atomic.AtomicInteger(0);
        AutoCloseable verifierHandle = io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers.addGlobalRegionVerifier(
                loc -> {
                    if (boundary.contains(loc.x(), loc.z())) {
                        verifierInvocations.incrementAndGet();
                        return false;
                    }
                    return true;
                });

        try {
            ScanTask task = new ScanTask(region, 0L);
            task.scanPhase.set(ScanTask.PHASE_FULLSCAN);
            long posFirst = square.xzToLocation(100, 100);
            assertTrue(posFirst >= 0);

            // Start scan task crossing (100, 100)
            CompletableFuture<Boolean> futFirst = task.testPos(
                    region, posFirst, 100, 100, 2,
                    Collections.emptySet(), Collections.singleton("PLAINS"), false, null);

            assertNotNull(futFirst);
            assertFalse(futFirst.join(), "First test within claim must be rejected");
            assertEquals(1, verifierInvocations.get(), "External verifier should be called on first hit");

            // Verify that after the first hit, the entire [100..200, 100..200] box is marked bad in MemoryShape
            for (int x = 100; x <= 200; x += 10) {
                for (int z = 100; z <= 200; z += 10) {
                    assertTrue(square.isKnownBad(x, z), "Point (" + x + "," + z + ") must be marked bad");
                }
            }
            assertTrue(square.isKnownBad(100, 100));
            assertTrue(square.isKnownBad(200, 200));
            assertTrue(square.isKnownBad(150, 150));

            // Confirm subsequent points within [100..200, 100..200] are skipped without re-evaluating external verifiers
            long posSubsequent = square.xzToLocation(150, 150);
            assertTrue(posSubsequent >= 0);
            CompletableFuture<Boolean> futSubsequent = task.testPos(
                    region, posSubsequent, 150, 150, 2,
                    Collections.emptySet(), Collections.singleton("PLAINS"), false, null);

            assertNotNull(futSubsequent);
            assertFalse(futSubsequent.join());
            assertEquals(1, verifierInvocations.get(), "External verifiers must NOT be re-evaluated for subsequent points");

        } finally {
            try {
                claimHandle.close();
            } catch (Exception ignored) {
            }
            try {
                verifierHandle.close();
            } catch (Exception ignored) {
            }
        }
    }
}
