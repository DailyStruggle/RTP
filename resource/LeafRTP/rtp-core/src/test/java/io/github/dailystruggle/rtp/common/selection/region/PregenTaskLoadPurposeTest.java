package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ADR-110: speculative fill reads first, loads natively pinned when it can, unpinned otherwise. */
@DisplayName("ADR-110 purpose-gated native loads in PregenTask")
class PregenTaskLoadPurposeTest {

    private static final String REGION = "purpose_gate_region";

    @TempDir
    File tempDir;

    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("purpose_world");
        accessor.addWorld(world);
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxAttempts, 1L);
        // 100 disables the heap gate: deterministic regardless of the test JVM's heap.
        perf.set(PerformanceKeys.maxHeapPercent, 100.0);
        HeapPressureMonitor.resetForTesting();
        LiveLoadGate.resetAll();
        ProbeFirstGovernor.resetAll();
        ImmediateLoadGovernor.resetAll();
    }

    @AfterEach
    void tearDown() {
        HeapPressureMonitor.resetForTesting();
        LiveLoadGate.resetAll();
        ProbeFirstGovernor.resetAll();
        ImmediateLoadGovernor.resetAll();
        RTPRuntime.setServerAccessor(null);
        RTPRuntime.setScheduler(null);
        LocationGenerator.setRng(null);
    }

    private Region region(MockRTPWorld w, int activeChunkCap) {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        RegionSettings settings = new RegionSettings(
                REGION, w, square, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 1000L, 0L, activeChunkCap, 0.0, 1L, "", false);
        return new Region(REGION, settings);
    }

    private GenerationResult run(Region r, LoadPurpose purpose) throws Exception {
        return run(r, purpose, 1L);
    }

    private GenerationResult run(Region r, LoadPurpose purpose, long maxAttempts) throws Exception {
        return run(r, purpose, maxAttempts, null);
    }

    private GenerationResult run(Region r, LoadPurpose purpose, long maxAttempts,
                                 BacklogLocationBuffer backlog) throws Exception {
        PregenState state = PregenState.build(r, null);
        assertNotNull(state);
        state.maxAttempts = maxAttempts;
        state.purpose = purpose;
        state.backlog = backlog;
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        new PregenTask(state, result, 1L).run();
        assertTrue(result.isDone());
        return result.get();
    }

    @Test
    @DisplayName("speculative + unreadable chunk + no pin slot: unpinned native load (fresh-world fill)")
    void speculativeLoadsUnpinnedWithoutPinSlot() throws Exception {
        MockRTPWorld spy = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spy).getOrReadChunk(anyInt(), anyInt());
        CompletableFuture<RTPChunk<?>> none = CompletableFuture.completedFuture(null);
        doReturn(none).when(spy).getOrLoadChunk(anyInt(), anyInt(), any());
        Region r = region(spy, 0);

        GenerationResult res = run(r, LoadPurpose.SPECULATIVE);

        assertNull(res.reservation(), "an unpinned load must never return a pin");
        verify(spy, times(1)).getOrLoadChunk(anyInt(), anyInt(), any());
        LiveLoadGateRow row = LiveLoadGate.of(REGION).snapshot();
        assertEquals(1, row.unpinnedLoads());
        assertEquals(0, row.pinnedLoads());
        assertEquals(0, row.deferredCenter());
        assertEquals(0, row.pinsInFlight());
    }

    @Test
    @DisplayName("speculative + unreadable chunk + no pin slot + heap pressure: deferred once, run ends")
    void speculativeDefersUnderHeapPressure() throws Exception {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxHeapPercent, 0.001);
        HeapPressureMonitor.resetForTesting();
        MockRTPWorld spy = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spy).getOrReadChunk(anyInt(), anyInt());
        Region r = region(spy, 0);

        GenerationResult res = run(r, LoadPurpose.SPECULATIVE, 32L);

        assertNull(res.coords());
        verify(spy, never()).getOrLoadChunk(anyInt(), anyInt(), any());
        verify(spy, never()).getOrLoadChunk(anyInt(), anyInt());
        verify(spy, times(1)).getOrReadChunk(anyInt(), anyInt());
        LiveLoadGateRow row = LiveLoadGate.of(REGION).snapshot();
        assertEquals(1, row.deferredCenter(), "remaining attempts must not spin on a deferral");
        assertEquals(0, row.pinnedLoads());
        assertEquals(0, row.unpinnedLoads());
        assertEquals(0, row.pinsInFlight());
    }

    @Test
    @DisplayName("speculative + unreadable chunk + free kept slot: pinned native load, slot released")
    void speculativePinsWhenKeptHasRoom() throws Exception {
        MockRTPWorld spy = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spy).getOrReadChunk(anyInt(), anyInt());
        CompletableFuture<RTPChunk<?>> none = CompletableFuture.completedFuture(null);
        doReturn(none).when(spy).getOrLoadChunk(anyInt(), anyInt(), any());
        Region r = region(spy, 5);

        run(r, LoadPurpose.SPECULATIVE);

        verify(spy, times(1)).getOrLoadChunk(anyInt(), anyInt(), any());
        LiveLoadGateRow row = LiveLoadGate.of(REGION).snapshot();
        assertEquals(1, row.pinnedLoads());
        assertEquals(0, row.unpinnedLoads(), "a free pin slot is preferred over an unpinned load");
        assertEquals(0, row.deferredCenter());
        assertEquals(0, row.pinsInFlight(), "pin slot must be released when the attempt ends");
    }

    @Test
    @DisplayName("speculative + readable chunk: no native load, counted as read")
    void speculativeUsesReadPath() throws Exception {
        MockRTPWorld spy = spy(world);
        RTPChunk<?> chunk = mock(RTPChunk.class);
        when(chunk.isSelfContained()).thenReturn(true);
        doReturn(CompletableFuture.completedFuture(chunk)).when(spy).getOrReadChunk(anyInt(), anyInt());
        Region r = region(spy, 0);

        run(r, LoadPurpose.SPECULATIVE);

        verify(spy, never()).getOrLoadChunk(anyInt(), anyInt(), any());
        assertEquals(1, LiveLoadGate.of(REGION).snapshot().readResolved());
    }

    /** Stages one backlog entry on chunk (5, 3) with {@code validity}. */
    private static BacklogLocationBuffer.BacklogEntry stage(Region r, BacklogLocationBuffer.Validity validity) {
        BacklogLocationBuffer backlog = r.queueManager.backlogLocations;
        assertNotNull(backlog);
        BacklogLocationBuffer.BacklogEntry e = backlog.offerUnverified(new RTPLocation(
                new io.github.dailystruggle.rtp.api.world.RTPCoords(r.getWorld().name(), (5 << 4) + 7, 64, (3 << 4) + 7), 0L));
        e.setValidity(validity);
        return e;
    }

    @Test
    @DisplayName("ADR-028: the cold fill checks the backlog's UNKNOWN entry instead of a shape draw")
    void coldFillChecksUnknownBacklogEntry() throws Exception {
        MockRTPWorld spy = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spy).getOrReadChunk(anyInt(), anyInt());
        CompletableFuture<RTPChunk<?>> none = CompletableFuture.completedFuture(null);
        doReturn(none).when(spy).getOrLoadChunk(anyInt(), anyInt(), any());
        Region r = region(spy, 0);
        stage(r, BacklogLocationBuffer.Validity.UNKNOWN);

        run(r, LoadPurpose.SPECULATIVE, 1L, r.queueManager.backlogLocations);

        verify(spy, times(1)).getOrReadChunk(5, 3);
        verify(spy, times(1)).getOrLoadChunk(eq(5), eq(3), any());
        assertEquals(0, r.queueManager.backlogLocations.size(), "the fill took the entry");
    }

    @Test
    @DisplayName("ADR-028: backlog entries but none UNKNOWN: the fill neither draws nor touches chunks")
    void coldFillSkipsWhenBacklogHasNoUnknown() throws Exception {
        MockRTPWorld spy = spy(world);
        Region r = region(spy, 0);
        stage(r, BacklogLocationBuffer.Validity.UNVERIFIED);

        GenerationResult res = run(r, LoadPurpose.SPECULATIVE, 1L, r.queueManager.backlogLocations);

        assertNull(res.coords());
        verify(spy, never()).getOrReadChunk(anyInt(), anyInt());
        verify(spy, never()).getOrLoadChunk(anyInt(), anyInt(), any());
        assertEquals(1, r.queueManager.backlogLocations.size());
    }

    @Test
    @DisplayName("ADR-028: a deferred backlog entry goes back as UNKNOWN, never dropped")
    void deferredBacklogEntryReturnsUnknown() throws Exception {
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) ActiveConfigs.get().getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxHeapPercent, 0.001);
        HeapPressureMonitor.resetForTesting();
        MockRTPWorld spy = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spy).getOrReadChunk(anyInt(), anyInt());
        Region r = region(spy, 0);
        stage(r, BacklogLocationBuffer.Validity.UNKNOWN);

        GenerationResult res = run(r, LoadPurpose.SPECULATIVE, 1L, r.queueManager.backlogLocations);

        assertNull(res.coords());
        verify(spy, never()).getOrLoadChunk(anyInt(), anyInt(), any());
        assertEquals(1, r.queueManager.backlogLocations.unknownSize());
    }

    @Test
    @DisplayName("immediate purpose keeps the native-load path and never consults the gate")
    void immediateKeepsNativePath() throws Exception {
        MockRTPWorld spy = spy(world);
        CompletableFuture<RTPChunk<?>> none = CompletableFuture.completedFuture(null);
        doReturn(none).when(spy).getOrLoadChunk(anyInt(), anyInt(), any());
        Region r = region(spy, 0);

        run(r, LoadPurpose.IMMEDIATE);

        verify(spy, times(1)).getOrLoadChunk(anyInt(), anyInt(), any());
        verify(spy, never()).getOrReadChunk(anyInt(), anyInt());
        LiveLoadGateRow row = LiveLoadGate.of(REGION).snapshot();
        assertEquals(0, row.deferredCenter());
        assertEquals(0, row.pinnedLoads());
    }

    @Test
    @DisplayName("ADR-114: ImmediateLoadGovernor bypass evaluates generated chunk off-thread via Anvil without pinning live ticket")
    void immediateBypassLoadsLiveChunkWithoutAnvilProbe() throws Exception {
        java.util.concurrent.atomic.AtomicInteger anvilProbeCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger liveAsyncLoads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<Long, RTPChunk<?>> anvilCache = new java.util.concurrent.ConcurrentHashMap<>();

        MockRTPWorld anvilBackedWorld = new MockRTPWorld("anvil_bypass_world") {
            @Override
            public java.nio.file.Path anvilWorldFolder() {
                return tempDir.toPath();
            }

            @Override
            public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
                long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);
                if (anvilCache.containsKey(key)) {
                    return CompletableFuture.completedFuture(key);
                }
                anvilProbeCalls.incrementAndGet();
                io.github.dailystruggle.rtp.common.mock.MockRTPChunk anvilView =
                        new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(chunkX, chunkZ, this) {
                            @Override
                            public boolean isSelfContained() {
                                return true;
                            }

                            @Override
                            public boolean isSafe(int x, int y, int z, java.util.Set<String> unsafeBlocks) {
                                return true;
                            }
                        };
                anvilCache.put(key, anvilView);
                return CompletableFuture.completedFuture(key);
            }

            @Override
            public CompletableFuture<Long> getChunkIfReadable(int cx, int cz) {
                long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                anvilProbeCalls.incrementAndGet();
                return CompletableFuture.completedFuture(key);
            }

            @Override
            public CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkSet> getChunkAtAsync(int cx, int cz) {
                liveAsyncLoads.incrementAndGet();
                long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                return CompletableFuture.completedFuture(new io.github.dailystruggle.rtp.api.world.ChunkSet(
                        this, cx, cz,
                        java.util.List.of(CompletableFuture.completedFuture(key)),
                        new CompletableFuture<>()));
            }

            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                return anvilCache.get(key);
            }
        };

        @SuppressWarnings("unchecked")
        ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys> safety =
                (ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys>)
                        ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class);
        safety.set(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.safetyRadius, 2);

        Region r = region(anvilBackedWorld, 0);
        GenerationResult res = run(r, LoadPurpose.IMMEDIATE);

        assertNotNull(res.coords(), "Bypass path must resolve a valid landing coordinate");
        assertEquals(9, anvilProbeCalls.get(),
                "safetyRadius=2 blocks must convert to 1-chunk (3x3=9) neighbour grid, not 5x5=25");
        assertEquals(1, res.verifiedChunks().chunks().size(),
                "completeSuccess must use viewDistanceSelect (0 -> 1x1), not block-unit safetyRadius");
        assertEquals(0, liveAsyncLoads.get(),
                "Generated candidate evaluation in PregenTask must use off-thread AnvilChunkView without pinning a 5x5 live ticket");
        assertEquals(0L, anvilBackedWorld.activeChunkTickets.get(),
                "No live chunk tickets may be pinned during Anvil-backed candidate evaluation");
    }

    @Test
    @DisplayName("ADR-114: after Anvil prefilter passes, loadChunk loads live chunk without re-probing Anvil")
    void immediatePrefilterPassDoesNotReprobeDuringLoadChunk() throws Exception {
        java.util.concurrent.atomic.AtomicInteger anvilReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger getChunkAtProbes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger liveAsyncLoads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<Long, RTPChunk<?>> cache = new java.util.concurrent.ConcurrentHashMap<>();

        // Force ImmediateLoadGovernor into PREFILTER mode via elevated rejection history
        ImmediateLoadGovernor immGov = ImmediateLoadGovernor.of(REGION);
        for (int i = 0; i < 50; i++) {
            immGov.recordOutcome(true);
        }

        MockRTPWorld anvilBackedWorld = new MockRTPWorld("anvil_prefilter_world") {
            @Override
            public java.nio.file.Path anvilWorldFolder() {
                return tempDir.toPath();
            }

            @Override
            public CompletableFuture<Long> getChunkIfReadable(int cx, int cz) {
                anvilReads.incrementAndGet();
                long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                io.github.dailystruggle.rtp.common.mock.MockRTPChunk anvilView =
                        new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(cx, cz, this) {
                            @Override
                            public boolean isSelfContained() {
                                return true;
                            }

                            @Override
                            public boolean isSafe(int x, int y, int z, java.util.Set<String> unsafeBlocks) {
                                return true;
                            }
                        };
                cache.put(key, anvilView);
                return CompletableFuture.completedFuture(key);
            }

            @Override
            public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
                long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);
                RTPChunk<?> existing = cache.get(key);
                if (existing != null && !existing.isSelfContained()) {
                    return CompletableFuture.completedFuture(key);
                }
                getChunkAtProbes.incrementAndGet();
                return CompletableFuture.completedFuture(key);
            }

            @Override
            public CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkSet> getChunkAtAsync(int cx, int cz) {
                liveAsyncLoads.incrementAndGet();
                long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                io.github.dailystruggle.rtp.common.mock.MockRTPChunk live =
                        new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(cx, cz, this) {
                            @Override
                            public boolean isSelfContained() {
                                return false;
                            }

                            @Override
                            public boolean isSafe(int x, int y, int z, java.util.Set<String> unsafeBlocks) {
                                return true;
                            }
                        };
                cache.put(key, live);
                return CompletableFuture.completedFuture(new io.github.dailystruggle.rtp.api.world.ChunkSet(
                        this, cx, cz,
                        java.util.List.of(CompletableFuture.completedFuture(key)),
                        new CompletableFuture<>()));
            }

            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                return cache.get(key);
            }
        };

        Region r = region(anvilBackedWorld, 0);
        GenerationResult res = run(r, LoadPurpose.IMMEDIATE);

        assertNotNull(res.coords(), "Prefilter path must resolve a valid landing coordinate from the self-contained Anvil chunk");
        assertEquals(1, anvilReads.get(), "Prefilter should read Anvil view once via getOrReadChunk");
        assertEquals(0, getChunkAtProbes.get(), "Prefilter path must not re-enter getChunkAt Anvil probe");
        assertEquals(0, liveAsyncLoads.get(),
                "When ImmediateLoadGovernor selects Anvil prefilter, PregenTask evaluates the self-contained Anvil chunk without also calling loadChunk");
        assertEquals(0L, anvilBackedWorld.activeChunkTickets.get(),
                "No leaked chunk tickets on completion");
    }

    @Test
    @DisplayName("ADR-114: when getOrReadChunk returns null on prefilter path, loadChunk falls back to one live load without re-probing getChunkAt")
    void immediatePrefilterNullFallbackLoadsLiveChunkOnceWithoutReprobe() throws Exception {
        java.util.concurrent.atomic.AtomicInteger anvilReads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger getChunkAtProbes = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger liveAsyncLoads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<Long, RTPChunk<?>> cache = new java.util.concurrent.ConcurrentHashMap<>();

        ImmediateLoadGovernor immGov = ImmediateLoadGovernor.of(REGION);
        for (int i = 0; i < 50; i++) {
            immGov.recordOutcome(true);
        }

        MockRTPWorld anvilFallbackWorld = new MockRTPWorld("anvil_fallback_world") {
            @Override
            public java.nio.file.Path anvilWorldFolder() {
                return tempDir.toPath();
            }

            @Override
            public CompletableFuture<Long> getChunkIfReadable(int cx, int cz) {
                anvilReads.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public CompletableFuture<Long> getChunkAt(int chunkX, int chunkZ) {
                getChunkAtProbes.incrementAndGet();
                long key = ((long) chunkX & 0xffffffffL) | ((long) chunkZ << 32);
                return CompletableFuture.completedFuture(key);
            }

            @Override
            public CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkSet> getChunkAtAsync(int cx, int cz) {
                liveAsyncLoads.incrementAndGet();
                long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                io.github.dailystruggle.rtp.common.mock.MockRTPChunk live =
                        new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(cx, cz, this) {
                            @Override
                            public boolean isSelfContained() {
                                return false;
                            }

                            @Override
                            public boolean isSafe(int x, int y, int z, java.util.Set<String> unsafeBlocks) {
                                return true;
                            }
                        };
                cache.put(key, live);
                return CompletableFuture.completedFuture(new io.github.dailystruggle.rtp.api.world.ChunkSet(
                        this, cx, cz,
                        java.util.List.of(CompletableFuture.completedFuture(key)),
                        new CompletableFuture<>()));
            }

            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                return cache.get(key);
            }
        };

        Region r = region(anvilFallbackWorld, 0);
        GenerationResult res = run(r, LoadPurpose.IMMEDIATE);

        assertNotNull(res.coords(), "Fallback from null getOrReadChunk must resolve via live load");
        assertEquals(1, anvilReads.get(), "Should attempt getOrReadChunk once");
        assertEquals(0, getChunkAtProbes.get(), "loadChunk fallback must not re-enter getChunkAt Anvil probe");
        assertEquals(1, liveAsyncLoads.get(), "loadChunk fallback must load the live chunk once via getChunkAtAsync");
        assertEquals(0L, anvilFallbackWorld.activeChunkTickets.get(),
                "Per-attempt chunk reservation must be cleanly released on completion");
    }

    @Test
    @DisplayName("ADR-109 / ADR-114: tryProbeFirst rejects ocean chunk on LinearAdjustor SCAN_MISS without falling through to loadChunk")
    void tryProbeFirstRejectsOceanChunkOnScanMissWithoutLiveLoad() throws Exception {
        @SuppressWarnings("unchecked")
        ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys> blocks =
                (ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys>)
                        ActiveConfigs.get().getParser(io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys.class);
        blocks.set(io.github.dailystruggle.rtp.common.configuration.enums.BlocksKeys.unsafeBlocks,
                java.util.List.of("WATER", "LAVA"));

        java.util.concurrent.atomic.AtomicInteger probeCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger liveAsyncLoads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.ConcurrentHashMap<Long, RTPChunk<?>> liveCache = new java.util.concurrent.ConcurrentHashMap<>();

        MockRTPWorld probeWorld = new MockRTPWorld("ocean_probe_world") {
            @Override
            public CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkColumnProbe> probeChunkColumn(
                    int cx, int cz, int minY, int maxY) {
                int call = probeCalls.incrementAndGet();
                if (call == 1) {
                    return CompletableFuture.completedFuture(
                            new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe(cx, cz, minY, maxY)
                                    .setAirRange(63, maxY)
                                    .setDefaultBiome("OCEAN")
                                    .withDefaultBlock("minecraft:water"));
                }
                return CompletableFuture.completedFuture(
                        new io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.FakeChunkColumnProbe(cx, cz, minY, maxY)
                                .setSolid(64)
                                .setAirRange(65, maxY)
                                .setDefaultBiome("PLAINS")
                                .withDefaultBlock("minecraft:stone"));
            }

            @Override
            public CompletableFuture<io.github.dailystruggle.rtp.api.world.ChunkSet> getChunkAtAsync(int cx, int cz) {
                liveAsyncLoads.incrementAndGet();
                long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
                io.github.dailystruggle.rtp.common.mock.MockRTPChunk live =
                        new io.github.dailystruggle.rtp.common.mock.MockRTPChunk(cx, cz, this) {
                            @Override
                            public boolean isSelfContained() {
                                return false;
                            }

                            @Override
                            public boolean isSafe(int x, int y, int z, java.util.Set<String> unsafeBlocks) {
                                return true;
                            }
                        };
                liveCache.put(key, live);
                return CompletableFuture.completedFuture(new io.github.dailystruggle.rtp.api.world.ChunkSet(
                        this, cx, cz,
                        java.util.List.of(CompletableFuture.completedFuture(key)),
                        new CompletableFuture<>()));
            }

            @Override
            public RTPChunk<?> getCachedChunk(long key) {
                return liveCache.get(key);
            }
        };

        Region r = region(probeWorld, 0);
        GenerationResult res = run(r, LoadPurpose.IMMEDIATE, 2L);

        assertNotNull(res.coords(), "Second attempt (land probe) must succeed");
        assertEquals(2, probeCalls.get(), "Must probe both attempt 1 (ocean) and attempt 2 (land)");
        assertEquals(1, liveAsyncLoads.get(),
                "Rejected ocean probe (SCAN_MISS) must not fall through to live loadChunk; only the accepted land chunk should load");
        assertEquals(2L, res.attempts(), "GenerationResult should report 2 attempts");
    }
}
