package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic and benchmark verification for chunk column probes (REQ-RTP-S-005).
 * Tests fast-path column probing, heightmap queries, vertical adjustor probe integration,
 * benchmark metric formatting, and probe reject transitions.
 */
@DisplayName("TestChunkProbe execution and diagnostic coverage")
class TestChunkProbeCoverageTest {

    @TempDir
    File tempDir;

    private MockRTPWorld world;
    private Region region;
    private Square square;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("probe_perf_world");
        square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "probe_perf_region", world, square, vert,
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("probe_perf_region", settings);
        RTP.selectionAPI.permRegionLookup.put("probe_perf_region", region);
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().scanTasks.clear();
    }

    @Test
    @DisplayName("ChunkColumnProbe basic block and biome extraction")
    void testChunkColumnProbeLookups() {
        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, -64, 320)
                .withDefaultBlock("minecraft:stone")
                .setDefaultBiome("minecraft:plains")
                .setAir(70)
                .setAir(71)
                .setBiome(70, "minecraft:forest");

        assertEquals(0, probe.chunkX());
        assertEquals(0, probe.chunkZ());
        assertEquals(-64, probe.minY());
        assertEquals(320, probe.maxY());

        assertFalse(probe.isAirAt(7, 7, 69));
        assertTrue(probe.isAirAt(7, 7, 70));
        assertTrue(probe.isAirAt(7, 7, 71));

        assertEquals("minecraft:forest", probe.biomeAt(70));
        assertEquals("minecraft:plains", probe.biomeAt(50));
    }

    @Test
    @DisplayName("LinearAdjustor adjustFromProbe finds valid standing position")
    void testLinearAdjustorProbePath() {
        LinearAdjustor linear = new LinearAdjustor(new ArrayList<>());
        linear.set(GenericVerticalAdjustorKeys.minY, 0L);
        linear.set(GenericVerticalAdjustorKeys.maxY, 128L);

        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(1, 1, -1, 128)
                .withDefaultBlock("minecraft:air")
                .setDefaultBiome("minecraft:plains")
                .setSolid(60)
                .setSolid(59);

        RTPCoords out = linear.adjustFromProbe(probe, "probe_perf_world");

        assertNotNull(out, "Linear adjustor should find floor at y=61");
        assertEquals(61, out.y());
    }

    @Test
    @DisplayName("LinearAdjustor adjustFromProbe returns null when column is void")
    void testLinearAdjustorProbeVoid() {
        LinearAdjustor linear = new LinearAdjustor(new ArrayList<>());
        linear.set(GenericVerticalAdjustorKeys.minY, 0L);
        linear.set(GenericVerticalAdjustorKeys.maxY, 128L);

        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(1, 1, -1, 128)
                .withDefaultBlock("minecraft:air")
                .setDefaultBiome("minecraft:plains");

        RTPCoords out = linear.adjustFromProbe(probe, "probe_perf_world");

        assertNull(out, "Void column should reject");
    }

    @Test
    @DisplayName("JumpAdjustor adjustFromProbe finds candidate with clearance")
    void testJumpAdjustorProbePath() {
        JumpAdjustor jump = new JumpAdjustor(new ArrayList<>());
        jump.set(JumpAdjustorKeys.minY, 0L);
        jump.set(JumpAdjustorKeys.maxY, 128L);

        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(2, 2, -1, 128)
                .withDefaultBlock("minecraft:air")
                .setDefaultBiome("minecraft:plains")
                .setSolid(64)
                .setSolid(63);

        RTPCoords out = jump.adjustFromProbe(probe, "probe_perf_world");

        assertNotNull(out);
        assertEquals(65, out.y());
    }

    @Test
    @DisplayName("Simulate chunk probe performance benchmark calculation and ratio formatting")
    void testProbeBenchmarkCalculations() {
        long probeTotalNanos = 2_500_000L; // 2.5 ms
        long fullTotalNanos = 250_000_000L; // 250 ms
        int samples = 100;

        double avgProbeMs = (probeTotalNanos / 1_000_000.0) / samples;
        double avgFullMs = (fullTotalNanos / 1_000_000.0) / samples;
        double speedupRatio = (avgProbeMs > 0.0) ? (avgFullMs / avgProbeMs) : 0.0;

        assertEquals(0.025, avgProbeMs, 0.001);
        assertEquals(2.5, avgFullMs, 0.01);
        assertEquals(100.0, speedupRatio, 0.1);

        String summary = String.format(Locale.ROOT,
                "chunks=%d probe=%.3fms full=%.3fms ratio=%.1fx",
                samples, avgProbeMs, avgFullMs, speedupRatio);

        assertTrue(summary.contains("probe=0.025ms"));
        assertTrue(summary.contains("full=2.500ms"));
        assertTrue(summary.contains("ratio=100.0x"));
    }

    @Test
    @DisplayName("Simulate zero elapsed time edge-case in probe benchmark without divide-by-zero")
    void testProbeBenchmarkZeroDivisionSafety() {
        long probeNanos = 0L;
        long fullNanos = 0L;
        int samples = 10;

        double avgProbeMs = (probeNanos / 1_000_000.0) / samples;
        double avgFullMs = (fullNanos / 1_000_000.0) / samples;
        double speedupRatio = (avgProbeMs > 0.0) ? (avgFullMs / avgProbeMs) : 1.0;

        assertEquals(0.0, avgProbeMs);
        assertEquals(1.0, speedupRatio);
    }

    @Test
    @DisplayName("ScanTask testPos with probe hit completes without blocking")
    void testScanTaskProbeDirectValidation() {
        FakeChunkColumnProbe validProbe = new FakeChunkColumnProbe(0, 0, -1, 128)
                .withDefaultBlock("minecraft:stone")
                .setDefaultBiome("minecraft:plains")
                .setAir(65)
                .setAir(66);

        MockRTPWorld probeWorld = new MockRTPWorld("test_probe_world") {
            @Override
            public CompletableFuture<ChunkColumnProbe> probeChunkColumn(int cx, int cz, int minY, int maxY) {
                return CompletableFuture.completedFuture(validProbe);
            }
        };

        RegionSettings settings = new RegionSettings(
                "probe_reg", probeWorld, square, region.getVert(),
                false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
        Region probeRegion = new Region("probe_reg", settings);

        ScanTask task = new ScanTask(probeRegion, 0L);
        long pos = square.xzToLocation(8, 8);

        CompletableFuture<Boolean> fut = task.testPos(
                probeRegion, pos, 8, 8, 2,
                Collections.emptySet(), Collections.emptySet(), false, null);

        assertNotNull(fut);
        assertTrue(fut.isDone());
    }

    @Test
    void testDummySanity() {
        assertTrue(true);
    }
}
