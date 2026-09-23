package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.mock.MockRTPChunk;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("PregenTask Unit and Branch Tests")
class PregenTaskTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;
    private Square square;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("pregen_world");
        accessor.addWorld(world);

        ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
        logging.set(LoggingKeys.selection_failure, true);

        square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "pregen_test_region",
                world,
                square,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("pregen_test_region", settings);
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
        LocationGenerator.setRng(null);
    }

    private Region createRegionWithWorld(RTPWorld<?> rtpWorld, VerticalAdjustor<?> vert) {
        RegionSettings s = region.getSettings();
        RegionSettings newSettings = new RegionSettings(
                s.name(),
                rtpWorld,
                s.shape(),
                (vert != null) ? vert : s.vert(),
                s.worldBorderOverride(),
                s.requirePermission(),
                s.cacheCap(),
                s.backlogCacheCap(),
                s.networkReserveSize(),
                s.activeChunkCap(),
                s.price(),
                s.spatialResolution(),
                s.override(),
                s.detailedRegionInit()
        );
        return new Region(s.name(), newSettings);
    }

    private PregenState createState(long maxAttempts, boolean verbose) {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxAttempts, maxAttempts);

        ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
        logging.set(LoggingKeys.selection_failure, verbose);

        return PregenState.build(region, Set.of("PLAINS"));
    }

    @Test
    @DisplayName("Exhausted attempts complete with null coordinates")
    void testExhaustedAttempts() throws ExecutionException, InterruptedException {
        PregenState state = createState(1L, true);
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 5L); // initialAttempt > maxAttempts

        task.run();

        assertTrue(result.isDone());
        GenerationResult gr = result.get();
        assertNotNull(gr);
        assertNull(gr.coords());
    }

    @Test
    @DisplayName("Exhausted attempts without verbose mode")
    void testExhaustedAttemptsNonVerbose() throws ExecutionException, InterruptedException {
        PregenState state = createState(1L, false);
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 5L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
    }

    @Test
    @DisplayName("Forced biome recall with unrecorded biomes aborts immediately")
    void testForcedBiomeRecallUnrecorded() throws ExecutionException, InterruptedException {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.biomeRecall, true);
        perf.set(PerformanceKeys.biomeRecallForced, true);

        PregenState state = PregenState.build(region, Set.of("DESERT"));

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        GenerationResult gr = result.get();
        assertNotNull(gr);
        assertNull(gr.coords());
    }

    @Test
    @DisplayName("WorldBorder check rejects candidate and reschedules until exhaustion")
    void testWorldBorderRejection() throws ExecutionException, InterruptedException {
        PregenState state = createState(1L, true);
        state.worldBorderFails = 999L; // Next fail reaches 1000 and aborts

        WorldBorder mockBorder = mock(WorldBorder.class);
        when(mockBorder.isInside()).thenReturn(loc -> false);
        accessor.setWorldBorderFunction(w -> mockBorder);

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.worldBorderFails > 0);
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.worldBorder).containsKey("OUTSIDE_BORDER"));
    }

    @Test
    @DisplayName("WorldBorder fails > 1000 causes immediate abortion")
    void testWorldBorderTooManyFails() throws ExecutionException, InterruptedException {
        PregenState state = createState(1L, true);
        state.worldBorderFails = 1000L;

        WorldBorder mockBorder = mock(WorldBorder.class);
        when(mockBorder.isInside()).thenReturn(loc -> false);
        accessor.setWorldBorderFunction(w -> mockBorder);

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
    }

    @Test
    @DisplayName("Probabilistic pregenerated rejection rejects candidate chunk")
    void testPregeneratedPreferenceRejection() throws ExecutionException, InterruptedException {
        PregenState state = createState(1L, true);
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.pregeneratedPreference, 1.0); // 100% rejection if ungenerated

        MockRTPWorld spyWorld = spy(world);
        doReturn(false).when(spyWorld).isChunkGenerated(anyInt(), anyInt());
        Region spyRegion = createRegionWithWorld(spyWorld, null);
        state = PregenState.build(spyRegion, Set.of("PLAINS"));
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.ungenerated).containsKey("UNGENERATED"));
    }

    @Test
    @DisplayName("tryProbeFirst rejects chunk with prefilterBiome")
    void testProbeFirstBiomeRejection() throws ExecutionException, InterruptedException {
        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, -1, 320)
                .setSolid(31)
                .setAirRange(32, 320)
                .setDefaultBiome("DESERT")
                .withDefaultBlock("minecraft:stone");

        MockRTPWorld spyWorld = spy(world);
        AtomicBoolean firstProbe = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (firstProbe.compareAndSet(true, false)) {
                return CompletableFuture.completedFuture(probe);
            }
            return CompletableFuture.completedFuture(null);
        }).when(spyWorld).probeChunkColumn(anyInt(), anyInt(), anyInt(), anyInt());

        // On attempt 2 after probe returns null, getOrLoadChunk fails to exhaust
        doReturn(CompletableFuture.completedFuture(null)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        PregenState state = PregenState.build(spyRegion, Set.of("PLAINS"));
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.prefilterBiome).containsKey("biome=DESERT"));
    }

    @Test
    @DisplayName("tryProbeFirst rejects chunk with prefilterBlock")
    void testProbeFirstBlockRejection() throws ExecutionException, InterruptedException {
        FakeChunkColumnProbe probe = new FakeChunkColumnProbe(0, 0, -1, 320)
                .setSolid(31)
                .setAirRange(32, 320)
                .setDefaultBiome("OCEAN")
                .withDefaultBlock("stone");

        MockRTPWorld spyWorld = spy(world);
        AtomicBoolean firstProbe = new AtomicBoolean(true);
        doAnswer(inv -> {
            if (firstProbe.compareAndSet(true, false)) {
                return CompletableFuture.completedFuture(probe);
            }
            return CompletableFuture.completedFuture(null);
        }).when(spyWorld).probeChunkColumn(anyInt(), anyInt(), anyInt(), anyInt());

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjustFromProbe(any(io.github.dailystruggle.rtp.api.world.ChunkColumnProbe.class), anyString()))
                .thenReturn(new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 0, 31, 0));

        // On attempt 2 after probe returns null, getOrLoadChunk fails to exhaust
        doReturn(CompletableFuture.completedFuture(null)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
        PregenState state = PregenState.build(spyRegion, Set.of("PLAINS")); // PLAINS is blacklisted, OCEAN passes
        state.maxAttempts = 1L;
        state.unsafeBlocks.add("MINECRAFT:STONE");
        state.unsafeBlocks.add("STONE");

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
    }

    @Test
    @DisplayName("getOrLoadChunk failure records ticketFailed and reschedules")
    void testGetOrLoadChunkFailure() throws ExecutionException, InterruptedException {
        PregenState state = createState(1L, true);

        MockRTPWorld spyWorld = spy(world);
        CompletableFuture<RTPChunk<?>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Chunk load failed"));
        doReturn(failedFuture).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        state = PregenState.build(spyRegion, Set.of("PLAINS"));
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.nullChunk).containsKey("reason=ticketFailed"));
    }

    @Test
    @DisplayName("getOrLoadChunk returning null records asyncLoadNull")
    void testGetOrLoadChunkReturnsNull() throws ExecutionException, InterruptedException {
        PregenState state = createState(1L, true);

        MockRTPWorld spyWorld = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        state = PregenState.build(spyRegion, Set.of("PLAINS"));
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.nullChunk).containsKey("reason=asyncLoadNull"));
    }

    @Test
    @DisplayName("Self-contained chunk (Anvil) bypasses live reservation and succeeds")
    void testSelfContainedChunkSuccess() throws ExecutionException, InterruptedException {
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        safety.set(SafetyKeys.safetyRadius, 0);

        MockRTPChunk chunk = spy(new MockRTPChunk(0, 0, world));
        doReturn(true).when(chunk).isSelfContained();
        doReturn("PLAINS").when(chunk).getBiome(anyInt(), anyInt(), anyInt());
        doReturn(true).when(chunk).isSafe(anyInt(), anyInt(), anyInt(), any(Set.class));

        MockRTPWorld spyWorld = spy(world);
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        PregenState state = PregenState.build(spyRegion, Set.of("PLAINS"));

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        GenerationResult gr = result.get();
        assertNotNull(gr);
        assertNotNull(gr.coords());
    }

    @Test
    @DisplayName("Live-backed chunk dispatch evaluates on region thread and succeeds")
    void testLiveChunkSuccess() throws ExecutionException, InterruptedException {
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        safety.set(SafetyKeys.safetyRadius, 0);

        MockRTPChunk chunk = spy(new MockRTPChunk(0, 0, world));
        doReturn(false).when(chunk).isSelfContained();
        doReturn("PLAINS").when(chunk).getBiome(anyInt(), anyInt(), anyInt());
        doReturn(true).when(chunk).isSafe(anyInt(), anyInt(), anyInt(), any(Set.class));

        MockRTPWorld spyWorld = spy(world);
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());
        doReturn(chunk).when(spyWorld).getCachedChunk(anyLong());
        doReturn(true).when(spyWorld).isChunkLoaded(anyInt(), anyInt());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        PregenState state = PregenState.build(spyRegion, Set.of("PLAINS"));

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        GenerationResult gr = result.get();
        assertNotNull(gr);
        assertNotNull(gr.coords());
    }

    @Test
    @DisplayName("vert.adjust returning null marks bad chunk and records vert fail")
    void testVertAdjustFails() throws ExecutionException, InterruptedException {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.biomeRecall, true);
        perf.set(PerformanceKeys.maxAttempts, 1L);

        MockRTPChunk chunk = spy(new MockRTPChunk(0, 0, world));
        doReturn(true).when(chunk).isSelfContained();

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.adjust(any())).thenReturn(null);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);

        MockRTPWorld spyWorld = spy(world);
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());
        doReturn(chunk).when(spyWorld).getCachedChunk(anyLong());
        doReturn(true).when(spyWorld).isChunkLoaded(anyInt(), anyInt());

        Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
        PregenState state = PregenState.build(spyRegion, null); // null biomes -> defaultBiomes = true
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.vert).containsKey("biome="));
    }

    @Test
    @DisplayName("Biome mismatch marks bad chunk and records biome fail")
    void testBiomeMismatchFails() throws ExecutionException, InterruptedException {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxAttempts, 1L);

        MockRTPChunk chunk = spy(new MockRTPChunk(0, 0, world));
        doReturn(true).when(chunk).isSelfContained();
        doReturn("DESERT").when(chunk).getBiome(anyInt(), anyInt(), anyInt()); // Not in state.biomeNames (PLAINS)

        MockRTPWorld spyWorld = spy(world);
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        PregenState state = PregenState.build(spyRegion, Set.of("PLAINS"));

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.biome).containsKey("biome=DESERT"));
    }

    @Test
    @DisplayName("Safety check failure records safety fail")
    void testSafetyCheckBlockReject() throws ExecutionException, InterruptedException {
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        safety.set(SafetyKeys.safetyRadius, 0);

        MockRTPWorld spyWorld = spy(world);
        doAnswer(invocation -> {
            int cx = invocation.getArgument(0);
            int cz = invocation.getArgument(1);
            MockRTPChunk c = new MockRTPChunk(cx, cz, spyWorld) {
                @Override
                public boolean isSelfContained() {
                    return true;
                }

                @Override
                public String getBiome(int x, int y, int z) {
                    return "PLAINS";
                }

                @Override
                public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
                    return false;
                }

                @Override
                public boolean isSafe(int x, int y, int z, io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet unsafeBlocks) {
                    return false;
                }
            };
            return CompletableFuture.completedFuture(c);
        }).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        // Use a mock adjustor that returns the center coordinate of the chunk
        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjust(any())).thenAnswer(inv -> {
            RTPChunk<?> c = inv.getArgument(0);
            return new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), c.x() * 16 + 7, 32, c.z() * 16 + 7);
        });

        Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
        PregenState state = PregenState.build(spyRegion, Set.of("PLAINS"));
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);

        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.safety).size() > 0
                || state.failMap.get(LocationGenerator.FailTypes.nullChunk).size() > 0);
    }

    @Test
    @DisplayName("evaluateProbe respects maxAttemptsCeiling on biome and block probe rejection")
    void testProbeRejectionRespectsMaxAttemptsCeiling() throws ExecutionException, InterruptedException {
        FakeChunkColumnProbe probeBiomeFail = new FakeChunkColumnProbe(0, 0, -1, 320)
                .setSolid(31)
                .setAirRange(32, 320)
                .setDefaultBiome("DESERT")
                .withDefaultBlock("stone");

        FakeChunkColumnProbe probeBlockFail = new FakeChunkColumnProbe(0, 0, -1, 320)
                .setSolid(31)
                .setAirRange(32, 320)
                .setDefaultBiome("PLAINS")
                .withDefaultBlock("lava");

        MockRTPWorld spyWorld = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjustFromProbe(any(io.github.dailystruggle.rtp.api.world.ChunkColumnProbe.class), anyString()))
                .thenReturn(new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 0, 31, 0));

        Region spyRegion = createRegionWithWorld(spyWorld, mockVert);

        // 1. Biome rejection test when maxAttempts reaches maxAttemptsCeiling
        doReturn(CompletableFuture.completedFuture(probeBiomeFail))
                .when(spyWorld).probeChunkColumn(anyInt(), anyInt(), anyInt(), anyInt());

        PregenState stateBiome = PregenState.build(spyRegion, Set.of("PLAINS"));
        assertNotNull(stateBiome);
        stateBiome.maxAttempts = stateBiome.maxAttemptsCeiling;
        long ceilingBiome = stateBiome.maxAttemptsCeiling;

        CompletableFuture<GenerationResult> resultBiome = new CompletableFuture<>();
        PregenTask taskBiome = new PregenTask(stateBiome, resultBiome, 1L);
        taskBiome.run();

        assertTrue(resultBiome.isDone());
        // maxAttempts should remain equal to maxAttemptsCeiling, not incremented past it
        assertEquals(ceilingBiome, stateBiome.maxAttempts);

        // 2. Block rejection test when maxAttempts reaches maxAttemptsCeiling
        doReturn(CompletableFuture.completedFuture(probeBlockFail))
                .when(spyWorld).probeChunkColumn(anyInt(), anyInt(), anyInt(), anyInt());

        PregenState stateBlock = PregenState.build(spyRegion, Set.of("PLAINS"));
        assertNotNull(stateBlock);
        stateBlock.maxAttempts = stateBlock.maxAttemptsCeiling;
        stateBlock.unsafeBlocks.add("LAVA");
        long ceilingBlock = stateBlock.maxAttemptsCeiling;

        CompletableFuture<GenerationResult> resultBlock = new CompletableFuture<>();
        PregenTask taskBlock = new PregenTask(stateBlock, resultBlock, 1L);
        taskBlock.run();

        assertTrue(resultBlock.isDone());
        // maxAttempts should remain equal to maxAttemptsCeiling, not incremented past it
        assertEquals(ceilingBlock, stateBlock.maxAttempts);
    }

    @Test
    @DisplayName("proceedWithEvaluation: staleAfterDispatch triggers nullChunk rejection")
    void testStaleAfterDispatchRejection() throws ExecutionException, InterruptedException {
        MockRTPWorld spyWorld = spy(world);
        // getOrLoadChunk returns a live chunk (not self-contained)
        MockRTPChunk chunk = new MockRTPChunk(0, 0, spyWorld);
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());
        doReturn(true).when(spyWorld).isChunkLoaded(anyInt(), anyInt());

        // But getCachedChunk returns null after dispatch!
        doReturn(null).when(spyWorld).getCachedChunk(anyLong());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        PregenState state = PregenState.build(spyRegion, Collections.emptySet());
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);
        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.nullChunk).size() > 0);
    }

    @Test
    @DisplayName("proceedWithEvaluation: vert.adjust null triggers vert adjust failure")
    void testVertAdjustNullRejection() throws ExecutionException, InterruptedException {
        MockRTPWorld spyWorld = spy(world);
        MockRTPChunk chunk = new MockRTPChunk(0, 0, spyWorld);
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());
        doReturn(true).when(spyWorld).isChunkLoaded(anyInt(), anyInt());
        doReturn(chunk).when(spyWorld).getCachedChunk(anyLong());

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjust(any())).thenReturn(null);

        Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
        PregenState state = PregenState.build(spyRegion, Collections.emptySet());
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);
        task.run();

        assertTrue(result.isDone());
        assertNull(result.get().coords());
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.vert).size() > 0);
    }

    @Test
    @DisplayName("proceedWithEvaluation: global region verifier reject")
    void testGlobalRegionVerifierReject() throws ExecutionException, InterruptedException {
        MockRTPWorld spyWorld = spy(world);
        MockRTPChunk chunk = new MockRTPChunk(0, 0, spyWorld) {
            @Override public boolean isSelfContained() { return true; }
            @Override public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) { return true; }
            @Override public boolean isSafe(int x, int y, int z, io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet unsafeBlocks) { return true; }
        };
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjust(any())).thenReturn(new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 5, 64, 5));

        // Add a failing global verifier
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> false);

        try {
            Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
            PregenState state = PregenState.build(spyRegion, Collections.emptySet());
            state.maxAttempts = 1L;

            CompletableFuture<GenerationResult> result = new CompletableFuture<>();
            PregenTask task = new PregenTask(state, result, 1L);
            task.run();

            assertTrue(result.isDone());
            assertNull(result.get().coords());
            assertTrue(state.failMap.get(LocationGenerator.FailTypes.safetyExternal).size() > 0);
        } finally {
            GlobalRegionVerifiers.clearGlobalRegionVerifiers();
        }
    }

    @Test
    @DisplayName("PregenTask probe-first chunk returns null and falls back to full load")
    void testProbeChunkColumnReturnsNull() throws Exception {
        MockRTPWorld spyWorld = spy(world);
        // Probe returns null
        doReturn(CompletableFuture.completedFuture(null))
                .when(spyWorld).probeChunkColumn(anyInt(), anyInt(), anyInt(), anyInt());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        PregenState state = PregenState.build(spyRegion, Collections.emptySet());
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);
        task.run();

        assertTrue(result.isDone());
    }

    @Test
    @DisplayName("PregenTask probe-first exceptionally completes and falls back")
    void testProbeChunkColumnCompletesExceptionally() throws Exception {
        MockRTPWorld spyWorld = spy(world);
        CompletableFuture<FakeChunkColumnProbe> failedProbe = new CompletableFuture<>();
        failedProbe.completeExceptionally(new RuntimeException("Simulated probe error"));
        doReturn(failedProbe).when(spyWorld).probeChunkColumn(anyInt(), anyInt(), anyInt(), anyInt());

        Region spyRegion = createRegionWithWorld(spyWorld, null);
        PregenState state = PregenState.build(spyRegion, Collections.emptySet());
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);
        task.run();

        assertTrue(result.isDone());
    }

    @Test
    @DisplayName("Async candidate evaluation: getOrLoadChunk returns null increments retry count and recovers")
    void testAsyncChunkLoadReturnsNullIncrementsRetryAndRecovers() throws Exception {
        MockRTPWorld spyWorld = spy(world);
        // First attempt returns completed null future, second attempt returns valid self-contained chunk
        MockRTPChunk chunk = new MockRTPChunk(0, 0, spyWorld) {
            @Override public boolean isSelfContained() { return true; }
            @Override public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) { return true; }
            @Override public boolean isSafe(int x, int y, int z, io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet unsafeBlocks) { return true; }
            @Override public String getBiome(int x, int y, int z) { return "PLAINS"; }
        };

        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                return CompletableFuture.completedFuture(null);
            }
            return CompletableFuture.completedFuture(chunk);
        }).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjust(any())).thenReturn(new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 8, 64, 8));

        Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
        PregenState state = PregenState.build(spyRegion, Collections.emptySet());
        state.maxAttempts = 3L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);
        task.run();

        assertTrue(result.isDone());
        GenerationResult gr = result.get();
        assertNotNull(gr);
        assertNotNull(gr.coords(), "Should succeed on second attempt after recovering from null chunk");
        assertEquals(2L, gr.attempts(), "Retry count should have incremented from attempt 1 to 2");
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.nullChunk).containsKey("reason=asyncLoadNull"));
    }

    @Test
    @DisplayName("Async candidate evaluation: getOrLoadChunk completes exceptionally increments retry count and recovers")
    void testAsyncChunkLoadThrowsIncrementsRetryAndRecovers() throws Exception {
        MockRTPWorld spyWorld = spy(world);
        MockRTPChunk chunk = new MockRTPChunk(0, 0, spyWorld) {
            @Override public boolean isSelfContained() { return true; }
            @Override public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) { return true; }
            @Override public boolean isSafe(int x, int y, int z, io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet unsafeBlocks) { return true; }
            @Override public String getBiome(int x, int y, int z) { return "PLAINS"; }
        };

        AtomicBoolean first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.compareAndSet(true, false)) {
                CompletableFuture<RTPChunk<?>> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("Simulated chunk load failure"));
                return failed;
            }
            return CompletableFuture.completedFuture(chunk);
        }).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjust(any())).thenReturn(new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 8, 64, 8));

        Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
        PregenState state = PregenState.build(spyRegion, Collections.emptySet());
        state.maxAttempts = 3L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);
        task.run();

        assertTrue(result.isDone());
        GenerationResult gr = result.get();
        assertNotNull(gr);
        assertNotNull(gr.coords(), "Should succeed on second attempt after recovering from exception");
        assertEquals(2L, gr.attempts(), "Retry count should increment after exceptional failure");
        assertTrue(state.failMap.get(LocationGenerator.FailTypes.nullChunk).containsKey("reason=ticketFailed"));
    }

    @Test
    @DisplayName("Async candidate evaluation: checkGlobalRegionVerifiersDetailed returns null or throws, increments retry and recovers")
    void testAsyncGlobalRegionVerifierFailsIncrementsRetryAndRecovers() throws Exception {
        MockRTPWorld spyWorld = spy(world);
        MockRTPChunk chunk = new MockRTPChunk(0, 0, spyWorld) {
            @Override public boolean isSelfContained() { return true; }
            @Override public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) { return true; }
            @Override public boolean isSafe(int x, int y, int z, io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet unsafeBlocks) { return true; }
            @Override public String getBiome(int x, int y, int z) { return "PLAINS"; }
        };
        doReturn(CompletableFuture.completedFuture(chunk)).when(spyWorld).getOrLoadChunk(anyInt(), anyInt(), any());

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.minY()).thenReturn(0);
        when(mockVert.maxY()).thenReturn(256);
        when(mockVert.adjust(any())).thenReturn(new io.github.dailystruggle.rtp.api.world.RTPCoords(world.name(), 8, 64, 8));

        // Add a verifier that fails on first call, then passes on second call
        AtomicBoolean first = new AtomicBoolean(true);
        GlobalRegionVerifiers.addGlobalRegionVerifier(coords -> {
            if (first.compareAndSet(true, false)) {
                return false;
            }
            return true;
        });

        try {
            Region spyRegion = createRegionWithWorld(spyWorld, mockVert);
            PregenState state = PregenState.build(spyRegion, Collections.emptySet());
            state.maxAttempts = 3L;

            CompletableFuture<GenerationResult> result = new CompletableFuture<>();
            PregenTask task = new PregenTask(state, result, 1L);
            task.run();

            assertTrue(result.isDone());
            GenerationResult gr = result.get();
            assertNotNull(gr);
            assertNotNull(gr.coords());
            assertEquals(2L, gr.attempts());
            assertTrue(state.failMap.get(LocationGenerator.FailTypes.safetyExternal).size() > 0);
        } finally {
            GlobalRegionVerifiers.clearGlobalRegionVerifiers();
        }
    }

    @Test
    @DisplayName("Biome draw tables: multiple biomes with configured weight ratios and limits")
    void testBiomeDrawTablesWithWeightsAndLimits() throws Exception {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.biomeRecall, true);
        perf.set(PerformanceKeys.biomeRecallForced, false);

        Square memSquare = new Square();
        memSquare.set(GenericMemoryShapeParams.radius, 500L);
        memSquare.set(GenericMemoryShapeParams.centerRadius, 0L);

        // Prepopulate memory shape with biome locations for PLAINS and FOREST
        // PLAINS: 10 runs (>= GRAY_SPACE_MIN_RUNS), so grayFraction = 0.0
        for (int b = 0; b < 10; b++) {
            memSquare.addBiomeLocation(100L + b * 20L, 2L, "PLAINS");
        }
        // FOREST: 2 runs (< 8), so grayFraction > 0.0
        memSquare.addBiomeLocation(1000L, 2L, "FOREST");
        memSquare.addBiomeLocation(1020L, 2L, "FOREST");

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "biome_weights_test_region",
                world,
                memSquare,
                vert,
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region biomeRegion = new Region("biome_weights_test_region", settings);

        // Request PLAINS, FOREST, and DESERT (DESERT has 0 runs, but is in worldBiomeRegistry)
        Set<String> requestedBiomes = new LinkedHashSet<>(Arrays.asList("PLAINS", "FOREST", "DESERT"));
        PregenState state = PregenState.build(biomeRegion, requestedBiomes);
        state.worldBiomeRegistry.addAll(Arrays.asList("PLAINS", "FOREST", "DESERT"));

        // Configure weights: PLAINS=2.0, FOREST=1.0, DESERT=3.0
        state.biomeWeights.put("PLAINS", 2.0);
        state.biomeWeights.put("FOREST", 1.0);
        state.biomeWeights.put("DESERT", 3.0);
        state.maxAttempts = 1L;

        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        PregenTask task = new PregenTask(state, result, 1L);
        task.run();

        assertTrue(result.isDone());
        // Verify task executed through refreshBiomeDrawTables and runAttempt without error
        assertNotNull(result.get());
    }

    @Test
    @DisplayName("Edge cases: RNG roll landing on cumulative upper bound in drawWeightedBiome")
    void testDrawWeightedBiomeRngUpperBoundEdgeCase() {
        long[] keysA = {10L, 20L};
        long[] widthsA = {5L, 5L};
        long[] keysB = {100L};
        long[] widthsB = {10L};
        List<long[][]> perBiome = new ArrayList<>();
        perBiome.add(new long[][]{keysA, widthsA});
        perBiome.add(new long[][]{keysB, widthsB});

        double[] weights = {2.0, 3.0}; // totalW = 5.0

        // Custom Random where nextDouble returns exactly nextafter 1.0 or pick reaches upper bound
        Random edgeRng = new Random() {
            @Override
            public double nextDouble() {
                // When multiplied by totalW (5.0), pick = 4.999999999999999
                // which exercises the loop fallback chosenIdx = count - 1
                return Math.nextDown(1.0d);
            }

            @Override
            public int nextInt(int bound) {
                return bound - 1;
            }
        };

        LocationGenerator.setRng(edgeRng);
        try {
            long loc = PregenTask.drawWeightedBiome(perBiome, weights);
            assertTrue(loc >= 100L && loc <= 110L, "Landing on upper bound of CDF should pick last biome (B), loc=" + loc);
        } finally {
            LocationGenerator.setRng(null);
        }
    }

    @Test
    @DisplayName("Edge cases: drawWeightedBiome with zero/empty weights falls back to equal pick")
    void testDrawWeightedBiomeZeroOrNegativeWeightsFallback() {
        long[] keysA = {10L};
        long[] widthsA = {1L};
        long[] keysB = {100L};
        long[] widthsB = {1L};
        List<long[][]> perBiome = new ArrayList<>();
        perBiome.add(new long[][]{keysA, widthsA});
        perBiome.add(new long[][]{keysB, widthsB});

        double[] zeroWeights = {0.0, 0.0};
        long loc = PregenTask.drawWeightedBiome(perBiome, zeroWeights);
        assertTrue(loc == 10L || loc == 100L);

        double[] negWeights = {-1.0, -5.0};
        long locNeg = PregenTask.drawWeightedBiome(perBiome, negWeights);
        assertTrue(locNeg == 10L || locNeg == 100L);
    }

    @Test
    @DisplayName("Edge cases: degenerate all zero-width runs in drawWeightedBiome")
    void testDrawWeightedBiomeAllZeroWidthRuns() {
        long[] keys = {50L, 60L};
        long[] widths = {0L, 0L};
        List<long[][]> perBiome = Collections.singletonList(new long[][]{keys, widths});

        long loc = PregenTask.drawWeightedBiome(perBiome);
        assertTrue(loc == 50L || loc == 60L, "Degenerate zero-width runs should pick a run key, was " + loc);
    }
}
