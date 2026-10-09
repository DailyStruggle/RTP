package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.metrics.LiveLoadGateRow;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ADR-110: speculative fill never loads natively unless the result is pinned. */
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
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);
        perf.set(PerformanceKeys.maxAttempts, 1L);
        LiveLoadGate.resetAll();
        ProbeFirstGovernor.resetAll();
    }

    @AfterEach
    void tearDown() {
        LiveLoadGate.resetAll();
        ProbeFirstGovernor.resetAll();
        RTP.serverAccessor = null;
        RTP.scheduler = null;
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
        PregenState state = PregenState.build(r, Set.of("PLAINS"));
        assertNotNull(state);
        state.maxAttempts = 1L;
        state.purpose = purpose;
        CompletableFuture<GenerationResult> result = new CompletableFuture<>();
        new PregenTask(state, result, 1L).run();
        assertTrue(result.isDone());
        return result.get();
    }

    @Test
    @DisplayName("speculative + unreadable chunk + no pin slot: deferred, no native load")
    void speculativeDefersWithoutPinSlot() throws Exception {
        MockRTPWorld spy = spy(world);
        doReturn(CompletableFuture.completedFuture(null)).when(spy).getOrReadChunk(anyInt(), anyInt());
        Region r = region(spy, 0);

        GenerationResult res = run(r, LoadPurpose.SPECULATIVE);

        assertNull(res.coords());
        verify(spy, never()).getOrLoadChunk(anyInt(), anyInt(), any());
        verify(spy, never()).getOrLoadChunk(anyInt(), anyInt());
        LiveLoadGateRow row = LiveLoadGate.of(REGION).snapshot();
        assertEquals(1, row.deferredCenter());
        assertEquals(0, row.pinnedLoads());
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
}
