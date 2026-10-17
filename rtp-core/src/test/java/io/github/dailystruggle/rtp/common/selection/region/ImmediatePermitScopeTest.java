package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.OnDemandChunkReadBenchmarkTest.BenchmarkedRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.OnDemandChunkReadBenchmarkTest.TerrainProfile;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-114: the generation permit caps native worldgen only. On-demand loads of chunks already on
 * disk shall not queue behind it; ungenerated chunks shall still wait for a permit.
 */
@DisplayName("ADR-114 generation permit applies to ungenerated chunks only")
class ImmediatePermitScopeTest {

    private static final String REGION_NAME = "permit_scope_region";

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxAttempts, 50L);
        perf.set(PerformanceKeys.maxHeapPercent, 100.0);
        HeapPressureMonitor.resetForTesting();
        ImmediateLoadGovernor.resetAll();
        LocationGenerator.setRng(new java.util.Random(20261017L));
    }

    @AfterEach
    void tearDown() {
        HeapPressureMonitor.resetForTesting();
        ImmediateLoadGovernor.resetAll();
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        LocationGenerator.setRng(null);
    }

    private Region createRegion(BenchmarkedRTPWorld w) {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 1000L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        RegionSettings settings = new RegionSettings(
                REGION_NAME, w, square, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, 0, 0.0, 50L, "", false);
        return new Region(REGION_NAME, settings);
    }

    private CompletableFuture<GenerationResult> start(Region r) {
        PregenState state = PregenState.build(r, null);
        assertNotNull(state);
        state.maxAttempts = 50L;
        state.purpose = LoadPurpose.IMMEDIATE;
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        new PregenTask(state, result, 1L).run();
        return result;
    }

    @Test
    @DisplayName("Generated chunk loads proceed while the region's generation permit is held")
    void generatedChunkLoadDoesNotWaitForPermit() throws Exception {
        BenchmarkedRTPWorld world = new BenchmarkedRTPWorld("permit_gen_world", TerrainProfile.PREGENERATED_100);
        accessor.addWorld(world);
        Region region = createRegion(world);

        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of(REGION_NAME);
        ImmediateLoadGovernor.GenerationPermit held = gov.tryAcquirePermit(20.0);
        assertNotNull(held, "test precondition: region permit must be free");
        try {
            CompletableFuture<GenerationResult> result = start(region);
            assertTrue(result.isDone(), "on-disk load must not queue behind a held generation permit");
            assertNotNull(result.get().coords());
            assertTrue(world.nativeLoadInvocations.get() > 0);
            assertEquals(1, gov.regionalInFlight(), "generated loads must not take a generation permit");
        } finally {
            held.close();
        }
    }

    @Test
    @DisplayName("Ungenerated chunk loads still wait for the region's generation permit")
    void ungeneratedChunkLoadWaitsForPermit() throws Exception {
        BenchmarkedRTPWorld world = new BenchmarkedRTPWorld("permit_ungen_world", TerrainProfile.UNGENERATED_0);
        accessor.addWorld(world);
        Region region = createRegion(world);

        ImmediateLoadGovernor gov = ImmediateLoadGovernor.of(REGION_NAME);
        ImmediateLoadGovernor.GenerationPermit held = gov.tryAcquirePermit(20.0);
        assertNotNull(held, "test precondition: region permit must be free");
        CompletableFuture<GenerationResult> result;
        try {
            result = start(region);
            assertFalse(result.isDone(), "worldgen must wait for the generation permit");
            assertEquals(0, world.nativeLoadInvocations.get(), "no native generation while the permit is held");
        } finally {
            held.close();
        }
        GenerationResult r = result.get(5, TimeUnit.SECONDS);
        assertNotNull(r.coords());
        assertTrue(world.nativeLoadInvocations.get() > 0);
    }
}
