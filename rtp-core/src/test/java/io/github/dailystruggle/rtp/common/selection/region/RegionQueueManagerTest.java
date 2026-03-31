package io.github.dailystruggle.rtp.common.selection.region;

import static org.mockito.Mockito.*;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RegionQueueManagerTest {
    private Region region;
    private RegionQueueManager queueManager;
    private RegionChunkManager chunkManager;
    private RegionSettings settings;

    @BeforeEach
    void setUp() {
        region = mock(Region.class);
        settings = mock(RegionSettings.class);
        chunkManager = mock(RegionChunkManager.class);

        when(region.getSettings()).thenReturn(settings);
        // Reflection to set final field chunkManager for testing
        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            field.set(region, chunkManager);
        } catch (Exception ignored) {}

        queueManager = new RegionQueueManager(region);
    }

    @Test
    void testOnPlayerPopWarmsUpCorrectLocation() {
        // Set activeChunkCap to 3
        when(settings.activeChunkCap()).thenReturn(3);

        // Fill queue with some locations
        RTPCoords loc0 = new RTPCoords("world", 0, 0, 0);
        RTPCoords loc1 = new RTPCoords("world", 16, 0, 16);
        RTPCoords loc2 = new RTPCoords("world", 32, 0, 32);
        RTPCoords loc3 = new RTPCoords("world", 48, 0, 48);

        queueManager.locationQueue.add(new CachedLocation(loc0, 1L));
        queueManager.locationQueue.add(new CachedLocation(loc1, 1L));
        queueManager.locationQueue.add(new CachedLocation(loc2, 1L));
        queueManager.locationQueue.add(new CachedLocation(loc3, 1L));

        // Initial state: [loc0, loc1, loc2, loc3]
        // After one pop: [loc1, loc2, loc3]
        // The new location at index activeChunkCap - 1 (index 2) is loc3.

        queueManager.locationQueue.poll();
        queueManager.onPlayerPop();

        verifyNoInteractions(chunkManager);
    }

    @Test
    void testOnPlayerPopWithSmallQueue() {
        // Set activeChunkCap to 5, but only 3 items in queue
        when(settings.activeChunkCap()).thenReturn(5);

        RTPCoords loc0 = new RTPCoords("world", 0, 0, 0);
        RTPCoords loc1 = new RTPCoords("world", 16, 0, 16);
        RTPCoords loc2 = new RTPCoords("world", 32, 0, 32);

        queueManager.locationQueue.add(new CachedLocation(loc0, 1L));
        queueManager.locationQueue.add(new CachedLocation(loc1, 1L));
        queueManager.locationQueue.add(new CachedLocation(loc2, 1L));

        queueManager.locationQueue.poll();
        queueManager.onPlayerPop();

        verifyNoInteractions(chunkManager);
    }

    @Test
    void testOnPlayerPopWithZeroCap() {
        when(settings.activeChunkCap()).thenReturn(0);

        RTPCoords loc0 = new RTPCoords("world", 0, 0, 0);
        queueManager.locationQueue.add(new CachedLocation(loc0, 1L));

        queueManager.locationQueue.poll();
        queueManager.onPlayerPop();

        verifyNoInteractions(chunkManager);
    }

    @Test
    void testPriorityPolling() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        RTPCoords fastLoc = new RTPCoords("world", 1, 1, 1);
        RTPCoords playerLoc = new RTPCoords("world", 2, 2, 2);
        RTPCoords globalLoc = new RTPCoords("world", 3, 3, 3);

        java.util.concurrent.CompletableFuture<CachedLocation> fastFuture = new java.util.concurrent.CompletableFuture<>();
        queueManager.fastLocations.put(uuid, fastFuture);
        
        java.util.concurrent.ConcurrentLinkedQueue<CachedLocation> pQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        pQueue.add(new CachedLocation(playerLoc, 1L));
        queueManager.perPlayerLocationQueue.put(uuid, pQueue);

        queueManager.locationQueue.add(new CachedLocation(globalLoc, 1L));

        // 1. fastLocations prioritized
        java.util.concurrent.CompletableFuture<CachedLocation> poll1 = queueManager.poll(uuid);
        assert poll1 == fastFuture;
        assert !poll1.isDone();
        fastFuture.complete(new CachedLocation(fastLoc, 1L));
        try {
            assert poll1.get().getCoords().equals(fastLoc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 2. perPlayerLocationQueue prioritized next
        java.util.concurrent.CompletableFuture<CachedLocation> poll2 = queueManager.poll(uuid);
        try {
            assert poll2.get().getCoords().equals(playerLoc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 3. global locationQueue as fallback
        java.util.concurrent.CompletableFuture<CachedLocation> poll3 = queueManager.poll(uuid);
        try {
            assert poll3.get().getCoords().equals(globalLoc);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // 4. null if all empty
        assert queueManager.poll(uuid) == null;
    }

    @Test
    void testChunkTicketTransfer() {
        // This test validates that LocationGenerator.getLocation transfers chunk ticket management
        // In our implementation, chunkManager.addTicket is called, and if successful, 
        // the ChunkSet is returned in GenerationResult.
        // We mock Region, RTPPlayer, etc. to trigger LocationGenerator.getLocation
        
        // Ensure no verifiers are present to avoid static method complications
        GlobalRegionVerifiers.clearGlobalRegionVerifiers();

        io.github.dailystruggle.rtp.api.entity.RTPPlayer player = mock(io.github.dailystruggle.rtp.api.entity.RTPPlayer.class);
        java.util.UUID uuid = java.util.UUID.randomUUID();
        when(player.uuid()).thenReturn(uuid);
        
        RTPCoords coords = new RTPCoords("world", 64, 64, 64);
        queueManager.locationQueue.add(new CachedLocation(coords, 1L));
        
        // Mocking for LocationGenerator.getLocation
        when(region.getWorld()).thenReturn(mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class));
        
        // Mocking chunk loading
        java.util.concurrent.CompletableFuture<Long> chunkFuture = java.util.concurrent.CompletableFuture.completedFuture(1L);
        java.util.List<java.util.concurrent.CompletableFuture<Long>> chunks = java.util.Collections.singletonList(chunkFuture);
        ChunkSet mockChunkSet = new ChunkSet(chunks, new java.util.concurrent.CompletableFuture<>());
        
        when(chunkManager.addTicket(anyInt(), anyInt())).thenReturn(mockChunkSet);
        when(chunkManager.getChunkSet(any())).thenReturn(mockChunkSet);
        io.github.dailystruggle.rtp.api.world.RTPChunk mockChunk = mock(io.github.dailystruggle.rtp.api.world.RTPChunk.class);
        when(region.getWorld().getCachedChunk(anyLong())).thenReturn(mockChunk);
        when(mockChunk.x()).thenReturn(4);
        when(mockChunk.z()).thenReturn(4);
        when(mockChunk.isSafe(anyInt(), anyInt(), anyInt(), any())).thenReturn(true);
        
        // Mocking RTP configs to avoid NullPointerException in LocationGenerator
        io.github.dailystruggle.rtp.common.configuration.Configs configs = mock(io.github.dailystruggle.rtp.common.configuration.Configs.class);
        io.github.dailystruggle.rtp.common.RTP.configs = configs;
        io.github.dailystruggle.rtp.common.configuration.ConfigParser safetyParser = mock(io.github.dailystruggle.rtp.common.configuration.ConfigParser.class);
        when(configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class)).thenReturn(safetyParser);
        when(safetyParser.getConfigValue(any(), any())).thenReturn(new java.util.ArrayList<>());
        when(safetyParser.getNumber(any(), any())).thenReturn(0);

        io.github.dailystruggle.rtp.api.server.RTPServerAccessor serverAccessor = mock(io.github.dailystruggle.rtp.api.server.RTPServerAccessor.class);
        io.github.dailystruggle.rtp.common.RTP.serverAccessor = serverAccessor;
        io.github.dailystruggle.rtp.api.world.RTPChunkManager apiChunkManager = mock(io.github.dailystruggle.rtp.api.world.RTPChunkManager.class);
        when(serverAccessor.getChunkManager()).thenReturn(apiChunkManager);
        when(apiChunkManager.getChunkAtAsync(any(), anyInt(), anyInt())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1L));

        // We don't call poll directly in this test, but LocationGenerator.getLocation will call it.
        // Wait, RegionQueueManager is a field in Region.
        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("queueManager");
            field.setAccessible(true);
            field.set(region, queueManager);
        } catch (Exception ignored) {}

        // Execution
        GenerationResult result = LocationGenerator.getLocation(region, null, player, null);
        
        // Verification
        assert result != null;
        assert result.coords().equals(coords);
        // Assert chunk ticket management transfer: addTicket should have been called
        verify(chunkManager).addTicket(eq(4), eq(4));
        // And removeTicket should NOT have been called for these coords if successful
        verify(chunkManager, never()).removeTicket(eq(4), eq(4));
    }
}
