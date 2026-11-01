package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tools.HeapPressureMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ADR-114 Criterion C: 2D Spatial Distribution Goodness-of-Fit")
class SpatialDistributionFidelityTest {

    private static final int TOTAL_TELEPORTS = 10_000;
    private static final int GRID_DIM = 8; // 8x8 = 64 equal-area cells
    private static final int NUM_CELLS = GRID_DIM * GRID_DIM;
    private static final long CHUNK_RADIUS = 1000L;
    private static final long BLOCK_RADIUS = CHUNK_RADIUS * 16L;

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private PartiallyPregeneratedWorld world;
    private Region region;

    static class PartiallyPregeneratedWorld extends MockRTPWorld {
        public PartiallyPregeneratedWorld(String name) {
            super(name);
        }

        @Override
        public boolean isChunkGenerated(int cx, int cz) {
            // Center inner circle (r <= 200 chunks = 3200 blocks) is pregenerated; outer area is ungenerated
            return (cx * cx + cz * cz) <= 40_000;
        }
    }

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        world = new PartiallyPregeneratedWorld("spatial_fidelity_world");
        accessor.addWorld(world);

        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxAttempts, 50L);
        perf.set(PerformanceKeys.maxHeapPercent, 100.0);
        perf.set(PerformanceKeys.pregeneratedPreference, 0.0); // Mode A: blind wilderness teleport
        HeapPressureMonitor.resetForTesting();
        ImmediateLoadGovernor.resetAll();
        LocationGenerator.setRng(new java.util.Random(20261014L));

        Square square = new Square();
        square.set(GenericMemoryShapeParams.mode, io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode.NONE);
        square.set(GenericMemoryShapeParams.radius, CHUNK_RADIUS);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        // Shape selection draws from its own RNG (ThreadLocalRandom by default); unseeded, an
        // alpha=0.05 goodness-of-fit test fails ~1 run in 20 on a correct distribution.
        square.setRng(new java.util.Random(20261017L));
        RegionSettings settings = new RegionSettings(
                "spatial_region", world, square, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, 0, 0.0, 1L, "", false);
        region = new Region("spatial_region", settings);
    }

    @AfterEach
    void tearDown() {
        HeapPressureMonitor.resetForTesting();
        ImmediateLoadGovernor.resetAll();
        RTPRuntime.setServerAccessor(null);
        RTPRuntime.setScheduler(null);
        LocationGenerator.setRng(null);
    }

    @Test
    @DisplayName("10,000 on-demand teleports across partially pregenerated world pass Chi-Square test (p > 0.05)")
    void test2DSpatialDistributionGoodnessOfFit() throws Exception {
        int[] cellCounts = new int[NUM_CELLS];
        double cellSize = (2.0 * BLOCK_RADIUS) / GRID_DIM; // 32000 / 8 = 4000 blocks per cell

        for (int i = 0; i < TOTAL_TELEPORTS; i++) {
            PregenState state = PregenState.build(region, null);
            assertNotNull(state);
            state.maxAttempts = 50L;
            state.purpose = LoadPurpose.IMMEDIATE;
            CompletableFuture<GenerationResult> resFuture = new CompletableFuture<>();
            new PregenTask(state, resFuture, 1L).run();
            assertTrue(resFuture.isDone());
            GenerationResult res = resFuture.get();
            assertNotNull(res);
            RTPCoords coords = res.coords();
            assertNotNull(coords, "Every teleport in safe terrain should succeed");

            int gridX = (int) Math.floor((coords.x() + BLOCK_RADIUS) / cellSize);
            int gridZ = (int) Math.floor((coords.z() + BLOCK_RADIUS) / cellSize);
            gridX = Math.max(0, Math.min(GRID_DIM - 1, gridX));
            gridZ = Math.max(0, Math.min(GRID_DIM - 1, gridZ));
            cellCounts[gridZ * GRID_DIM + gridX]++;
        }

        // Expected count per cell in uniform distribution: 10,000 / 64 = 156.25
        double expectedPerCell = (double) TOTAL_TELEPORTS / NUM_CELLS;
        double chiSquare = 0.0;
        int maxCell = 0;
        int minCell = Integer.MAX_VALUE;

        for (int count : cellCounts) {
            double diff = count - expectedPerCell;
            chiSquare += (diff * diff) / expectedPerCell;
            maxCell = Math.max(maxCell, count);
            minCell = Math.min(minCell, count);
        }

        // Degrees of freedom = 64 - 1 = 63.
        // Critical value for alpha = 0.05 with df = 63 is 82.53.
        // If ungenerated chunks were discarded or diverted (ADR-114 flaw before fix),
        // chiSquare would be > 1000 due to mass accumulation inside the inner circle.
        double criticalValueAlpha05 = 82.53;

        System.out.printf("ADR-114 Criterion C 2D Distribution Test:%n");
        System.out.printf("  Total Teleports: %d%n", TOTAL_TELEPORTS);
        System.out.printf("  Expected / Cell: %.2f%n", expectedPerCell);
        System.out.printf("  Min Cell: %d | Max Cell: %d%n", minCell, maxCell);
        System.out.printf("  Calculated Chi-Square: %.2f (Critical alpha=0.05: %.2f)%n", chiSquare, criticalValueAlpha05);

        assertTrue(chiSquare < criticalValueAlpha05,
                String.format("Chi-Square statistic %.2f exceeded critical threshold %.2f (p < 0.05). Spatial distribution distorted!",
                        chiSquare, criticalValueAlpha05));

        // Confirm perimeter cells do not exhibit absorbing boundary accumulation (ratio < 1.35)
        double maxRatio = maxCell / expectedPerCell;
        assertTrue(maxRatio < 1.35, "Perimeter cell density exceeded 1.35x expected: " + maxRatio);
    }
}
