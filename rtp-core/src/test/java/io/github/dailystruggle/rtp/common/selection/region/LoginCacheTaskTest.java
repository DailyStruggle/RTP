package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@DisplayName("LoginCacheTask Tests")
class LoginCacheTaskTest {

    @TempDir
    File tempDir;

    private Region region;
    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        world = (MockRTPWorld) accessor.getRTPWorld("world");
        region = (Region) RTP.selectionAPI.getRegion("default");
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
    }

    @Test
    @DisplayName("promoteUpTo no-ops when world is null")
    void promoteUpTo_nullWorld_noOps() {
        Region mockRegion = mock(Region.class);
        when(mockRegion.getWorld()).thenReturn(null);

        LoginCacheTask task = new LoginCacheTask(mockRegion);
        assertDoesNotThrow(() -> task.promoteUpTo(5));
    }

    @Test
    @DisplayName("promoteUpTo no-ops when loginLocations buffer is null")
    void promoteUpTo_nullLoginLocations_noOps() {
        Region mockRegion = mock(Region.class);
        when(mockRegion.getWorld()).thenReturn((RTPWorld) world);
        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        qm.loginLocations = null;
        mockRegion.queueManager = qm;

        LoginCacheTask task = new LoginCacheTask(mockRegion);
        assertDoesNotThrow(() -> task.promoteUpTo(5));
    }

    @Test
    @DisplayName("promoteUpTo no-ops when unkeptLocations is empty")
    void promoteUpTo_emptyUnkeptLocations_noOps() {
        region.queueManager.unkeptLocations.clear();
        int initialInFlight = region.inFlightCalculations.get();

        LoginCacheTask task = new LoginCacheTask(region);
        task.promoteUpTo(1);

        assertEquals(initialInFlight, region.inFlightCalculations.get());
    }

    @Test
    @DisplayName("run promotes single candidate when available")
    void run_promotesOne() {
        // Enqueue a candidate in unkeptLocations
        RTPLocation testLoc = new RTPLocation(new RTPCoords("world", 16, 64, 16), 1L, null);
        region.queueManager.unkeptLocations.offer(testLoc);
        region.queueManager.loginLocations.clear();

        LoginCacheTask task = new LoginCacheTask(region);
        task.run();

        // Advance scheduler so any async or timer tasks execute
        MockRTPServerAccessor accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.getMockScheduler().tick(5L);

        // Location should have been processed
        assertEquals(0, region.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne re-offers to unkeptLocations if chunk loading fails")
    void promoteOne_chunkLoadingFailure_returnsToUnkept() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("mockWorld");

        CompletableFuture<Boolean> completeFuture = CompletableFuture.completedFuture(false);
        CompletableFuture<Long> chunkFuture = new CompletableFuture<>();
        ChunkSet chunkSet = new ChunkSet(mockWorld, 2, 2, List.of(chunkFuture), completeFuture);

        when(mockWorld.getChunkAtAsync(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(chunkSet));

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        mockRegion.inFlightCalculations = new java.util.concurrent.atomic.AtomicInteger(0);

        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        qm.unkeptLocations.clear();
        qm.loginLocations.clear();

        RTPLocation testLoc = new RTPLocation(new RTPCoords("world", 32, 64, 32), 1L, null);
        qm.unkeptLocations.offer(testLoc);
        doReturn(region.getVert()).when(mockRegion).getVert();
        mockRegion.queueManager = qm;

        LoginCacheTask task = new LoginCacheTask(mockRegion);
        task.promoteUpTo(1);

        assertEquals(1, qm.unkeptLocations.size());
        assertEquals(0, mockRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne re-offers to unkeptLocations if getChunkAtAsync completes exceptionally")
    void promoteOne_chunkLoadingThrows_returnsToUnkept() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("mockWorld");

        CompletableFuture<ChunkSet> exceptionalFuture = new CompletableFuture<>();
        exceptionalFuture.completeExceptionally(new RuntimeException("Simulated chunk load failure"));

        when(mockWorld.getChunkAtAsync(anyInt(), anyInt())).thenReturn(exceptionalFuture);

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        mockRegion.inFlightCalculations = new java.util.concurrent.atomic.AtomicInteger(0);

        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        qm.unkeptLocations.clear();
        qm.loginLocations.clear();

        RTPLocation testLoc = new RTPLocation(new RTPCoords("world", 48, 64, 48), 1L, null);
        qm.unkeptLocations.offer(testLoc);
        mockRegion.queueManager = qm;

        LoginCacheTask task = new LoginCacheTask(mockRegion);
        task.promoteUpTo(1);

        assertEquals(1, qm.unkeptLocations.size());
        assertEquals(0, mockRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne drops candidate if vertical adjustor cannot re-verify standable Y")
    void promoteOne_vertFails_dropsUnsafeLocation() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("mockWorld");

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        ChunkSet successfulChunkSet = new ChunkSet(mockWorld, 1, 1, List.of(CompletableFuture.completedFuture(1L)), new CompletableFuture<>());
        when(mockWorld.getChunkAtAsync(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(successfulChunkSet));

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        when(mockVert.adjust(any())).thenReturn(null); // vertical re-verification fails

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();
        mockRegion.inFlightCalculations = new java.util.concurrent.atomic.AtomicInteger(0);

        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        qm.unkeptLocations.clear();
        qm.loginLocations.clear();

        RTPLocation testLoc = new RTPLocation(new RTPCoords("world", 16, 64, 16), 1L, null);
        qm.unkeptLocations.offer(testLoc);
        mockRegion.queueManager = qm;

        LoginCacheTask task = new LoginCacheTask(mockRegion);
        task.promoteUpTo(1);

        // Re-verification failed -> candidate is purged, not returned to login
        assertEquals(0, qm.loginLocations.size());
        assertEquals(0, mockRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne successfully promotes safe candidate to loginLocations")
    void promoteOne_success_addsToLoginLocations() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("mockWorld");
        when(mockWorld.setForceLoaded(anyInt(), anyInt(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        ChunkSet successfulChunkSet = new ChunkSet(mockWorld, 1, 1, List.of(CompletableFuture.completedFuture(1L)), new CompletableFuture<>());
        when(mockWorld.getChunkAtAsync(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(successfulChunkSet));

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        RTPCoords resolved = new RTPCoords("mockWorld", 16, 64, 16);
        when(mockVert.adjust(any())).thenReturn(resolved);

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();
        mockRegion.inFlightCalculations = new java.util.concurrent.atomic.AtomicInteger(0);

        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        qm.unkeptLocations.clear();
        qm.loginLocations.clear();

        RTPLocation testLoc = new RTPLocation(new RTPCoords("world", 16, 64, 16), 1L, null);
        qm.unkeptLocations.offer(testLoc);
        mockRegion.queueManager = qm;

        LoginCacheTask task = new LoginCacheTask(mockRegion);
        task.promoteUpTo(1);

        assertEquals(1, qm.loginLocations.size());
        assertEquals(0, qm.unkeptLocations.size());
        assertEquals(0, mockRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne returns location to unkept when loginLocations is full")
    void promoteOne_loginFull_returnsToUnkept() {
        RTPWorld<?> mockWorld = mock(RTPWorld.class);
        when(mockWorld.name()).thenReturn("mockWorld");
        when(mockWorld.setForceLoaded(anyInt(), anyInt(), anyBoolean())).thenReturn(CompletableFuture.completedFuture(null));

        RTPChunk<?> mockChunk = mock(RTPChunk.class);
        when(mockWorld.getCachedChunk(anyLong())).thenReturn((RTPChunk) mockChunk);

        CompletableFuture<Boolean> completeFuture = CompletableFuture.completedFuture(true);
        CompletableFuture<Long> chunkFuture = CompletableFuture.completedFuture(1L);
        ChunkSet successfulChunkSet = new ChunkSet(mockWorld, 1, 1, List.of(chunkFuture), completeFuture);
        when(mockWorld.getChunkAtAsync(anyInt(), anyInt())).thenReturn(CompletableFuture.completedFuture(successfulChunkSet));

        VerticalAdjustor<?> mockVert = mock(VerticalAdjustor.class);
        RTPCoords resolved = new RTPCoords("mockWorld", 16, 64, 16);
        when(mockVert.adjust(any())).thenReturn(resolved);

        Region mockRegion = mock(Region.class);
        doReturn(mockWorld).when(mockRegion).getWorld();
        doReturn(mockVert).when(mockRegion).getVert();
        mockRegion.inFlightCalculations = new java.util.concurrent.atomic.AtomicInteger(0);

        RegionQueueManager qm = new RegionQueueManager(mockRegion);
        qm.unkeptLocations.clear();
        qm.loginLocations.clear();
        for (int i = 0; i < qm.loginLocations.capacity(); i++) {
            qm.loginLocations.offer(new RTPLocation(new RTPCoords("mockWorld", i * 16, 64, i * 16), 1L, null));
        }

        RTPLocation testLoc = new RTPLocation(new RTPCoords("world", 32, 64, 32), 1L, null);
        qm.unkeptLocations.offer(testLoc);
        mockRegion.queueManager = qm;

        LoginCacheTask task = new LoginCacheTask(mockRegion);
        task.promoteUpTo(1);

        assertEquals(1, qm.unkeptLocations.size());
        assertEquals(0, mockRegion.inFlightCalculations.get());
    }
}
