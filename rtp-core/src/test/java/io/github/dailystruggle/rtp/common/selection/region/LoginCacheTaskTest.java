package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

@DisplayName("ADR-023: LoginCacheTask Unit Tests")
class LoginCacheTaskTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor serverAccessor;
    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        serverAccessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("login_world");
        serverAccessor.addWorld(world);
    }

    @AfterEach
    void tearDown() {
        RTP.serverAccessor = null;
        RTP.scheduler = null;
    }

    private Region createRegion(String name) {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                name,
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
        return new Region(name, settings);
    }

    @Test
    @DisplayName("promoteUpTo early exits when region world or loginLocations is null")
    void testEarlyExitsOnNullWorldOrLoginLocations() {
        Region region = createRegion("null_test");
        Region spyRegion = spy(region);
        doReturn(null).when(spyRegion).getWorld();

        LoginCacheTask task = new LoginCacheTask(spyRegion);
        task.promoteUpTo(5);

        // When loginLocations is null
        region.queueManager.loginLocations = null;
        task = new LoginCacheTask(region);
        task.promoteUpTo(5);
        task.run();
    }

    @Test
    @DisplayName("promoteOne successful promotion from unkept to login queue on non-Folia")
    void testSuccessfulPromotionNonFolia() {
        Region region = createRegion("promote_success");

        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = mock(ChunkSet.class);
        when(chunkSet.complete()).thenReturn(CompletableFuture.completedFuture(true));
        doReturn(CompletableFuture.completedFuture(chunkSet)).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        RTPChunk<?> rtpChunk = mock(RTPChunk.class);
        doReturn(rtpChunk).when(spyWorld).getCachedChunk(anyLong());

        VerticalAdjustor<?> vert = mock(VerticalAdjustor.class);
        RTPCoords adjustedCoords = new RTPCoords("login_world", 32, 64, 32);
        doReturn(adjustedCoords).when(vert).adjust(any());

        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();
        doReturn(vert).when(spyRegion).getVert();

        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);
        RTPCoords coldCoords = new RTPCoords("login_world", 32, 0, 32);
        RTPLocation coldLoc = new RTPLocation(coldCoords, 1, null);
        region.queueManager.unkeptLocations.offer(coldLoc);

        LoginCacheTask task = new LoginCacheTask(spyRegion);
        task.run();

        assertEquals(0, region.queueManager.unkeptLocations.size());
        assertEquals(1, region.queueManager.loginLocations.size());
        assertEquals(0, spyRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne with Folia platform delegates verification to region scheduler")
    void testPromotionFoliaPlatform() {
        serverAccessor.setPlatform("Folia");

        Region region = createRegion("promote_folia");

        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = mock(ChunkSet.class);
        when(chunkSet.complete()).thenReturn(CompletableFuture.completedFuture(true));
        doReturn(CompletableFuture.completedFuture(chunkSet)).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        RTPChunk<?> rtpChunk = mock(RTPChunk.class);
        doReturn(rtpChunk).when(spyWorld).getCachedChunk(anyLong());

        VerticalAdjustor<?> vert = mock(VerticalAdjustor.class);
        RTPCoords adjustedCoords = new RTPCoords("login_world", 16, 70, 16);
        doReturn(adjustedCoords).when(vert).adjust(any());

        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();
        doReturn(vert).when(spyRegion).getVert();

        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);
        RTPLocation coldLoc = new RTPLocation(new RTPCoords("login_world", 16, 0, 16), 1, null);
        region.queueManager.unkeptLocations.offer(coldLoc);

        LoginCacheTask task = new LoginCacheTask(spyRegion);
        task.promoteUpTo(1);

        assertEquals(0, region.queueManager.unkeptLocations.size());
        assertEquals(1, region.queueManager.loginLocations.size());
        assertEquals(0, spyRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne re-offers coldLoc when chunkSet completion fails")
    void testChunkSetCompletionFailure() {
        Region region = createRegion("chunk_fail");

        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = mock(ChunkSet.class);
        when(chunkSet.complete()).thenReturn(CompletableFuture.completedFuture(false));
        doReturn(CompletableFuture.completedFuture(chunkSet)).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();

        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);
        RTPLocation coldLoc = new RTPLocation(new RTPCoords("login_world", 16, 0, 16), 1, null);
        region.queueManager.unkeptLocations.offer(coldLoc);

        LoginCacheTask task = new LoginCacheTask(spyRegion);
        task.run();

        assertEquals(1, region.queueManager.unkeptLocations.size());
        assertEquals(0, region.queueManager.loginLocations.size());
        assertEquals(0, spyRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne re-offers coldLoc when getChunkAtAsync completes exceptionally")
    void testChunkLoadAsyncExceptionally() {
        Region region = createRegion("chunk_async_fail");

        RTPWorld<?> spyWorld = spy(world);
        CompletableFuture<ChunkSet> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Chunk load failed"));
        doReturn(failedFuture).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();

        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);
        RTPLocation coldLoc = new RTPLocation(new RTPCoords("login_world", 16, 0, 16), 1, null);
        region.queueManager.unkeptLocations.offer(coldLoc);

        LoginCacheTask task = new LoginCacheTask(spyRegion);
        task.run();

        assertEquals(1, region.queueManager.unkeptLocations.size());
        assertEquals(0, region.queueManager.loginLocations.size());
        assertEquals(0, spyRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne discards candidate when vert.adjust returns null or throws")
    void testAdjustorReturnsNullDiscardsCandidate() {
        Region region = createRegion("adjustor_null");

        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = mock(ChunkSet.class);
        when(chunkSet.complete()).thenReturn(CompletableFuture.completedFuture(true));
        doReturn(CompletableFuture.completedFuture(chunkSet)).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        RTPChunk<?> rtpChunk = mock(RTPChunk.class);
        doReturn(rtpChunk).when(spyWorld).getCachedChunk(anyLong());

        VerticalAdjustor<?> vert = mock(VerticalAdjustor.class);
        when(vert.adjust(any())).thenThrow(new RuntimeException("Adjustment error"));

        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();
        doReturn(vert).when(spyRegion).getVert();

        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);
        RTPLocation coldLoc = new RTPLocation(new RTPCoords("login_world", 16, 0, 16), 1, null);
        region.queueManager.unkeptLocations.offer(coldLoc);

        LoginCacheTask task = new LoginCacheTask(spyRegion);
        task.run();

        // Discarded (purged via offer+poll)
        assertEquals(0, region.queueManager.unkeptLocations.size());
        assertEquals(0, region.queueManager.loginLocations.size());
        assertEquals(0, spyRegion.inFlightCalculations.get());
    }

    @Test
    @DisplayName("promoteOne closes reservation and re-offers when loginLocations is full")
    void testLoginLocationsBufferFull() {
        Region region = createRegion("login_full");

        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = mock(ChunkSet.class);
        when(chunkSet.complete()).thenReturn(CompletableFuture.completedFuture(true));
        doReturn(CompletableFuture.completedFuture(chunkSet)).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        RTPChunk<?> rtpChunk = mock(RTPChunk.class);
        doReturn(rtpChunk).when(spyWorld).getCachedChunk(anyLong());

        VerticalAdjustor<?> vert = mock(VerticalAdjustor.class);
        RTPCoords adjustedCoords = new RTPCoords("login_world", 16, 64, 16);
        doReturn(adjustedCoords).when(vert).adjust(any());

        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();
        doReturn(vert).when(spyRegion).getVert();

        // Capacity 1 buffer already full
        region.queueManager.loginLocations = new LockFreeLocationBuffer(1);
        region.queueManager.loginLocations.offer(new RTPLocation(new RTPCoords("login_world", 0, 64, 0), 1, null));

        RTPLocation coldLoc = new RTPLocation(new RTPCoords("login_world", 16, 0, 16), 1, null);
        region.queueManager.unkeptLocations.offer(coldLoc);

        LoginCacheTask task = new LoginCacheTask(spyRegion);
        task.promoteUpTo(1);

        // Cold loc returned to unkept
        assertEquals(1, region.queueManager.unkeptLocations.size());
        assertEquals(1, region.queueManager.loginLocations.size());
        assertEquals(0, spyRegion.inFlightCalculations.get());
    }
}
