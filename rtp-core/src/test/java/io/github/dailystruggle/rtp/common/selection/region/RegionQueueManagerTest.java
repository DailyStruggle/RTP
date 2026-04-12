package io.github.dailystruggle.rtp.common.selection.region;

import static org.mockito.Mockito.*;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.Assertions;
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

        RTPServerAccessor serverAccessor = mock(RTPServerAccessor.class);
        RTP.serverAccessor = serverAccessor;
        ILocationGenerator locationGenerator = mock(ILocationGenerator.class);
        when(serverAccessor.getLocationGenerator()).thenReturn(locationGenerator);

        RTP rtp = mock(RTP.class);
        try {
            java.lang.reflect.Field field;
            field = RTP.class.getDeclaredField("queuedPlayers");
            field.setAccessible(true);
            field.set(rtp, new java.util.concurrent.ConcurrentHashMap<java.util.UUID, Boolean>().keySet(true));

            field = RTP.class.getDeclaredField("invulnerablePlayers");
            field.setAccessible(true);
            field.set(rtp, new java.util.concurrent.ConcurrentHashMap<>());

            field = RTP.class.getDeclaredField("processingPlayers");
            field.setAccessible(true);
            field.set(rtp, new java.util.concurrent.ConcurrentSkipListSet<>());

            field = RTP.class.getDeclaredField("latestTeleportData");
            field.setAccessible(true);
            field.set(rtp, new java.util.concurrent.ConcurrentHashMap<>());

            java.lang.reflect.Field instanceField = RTP.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, rtp);
        } catch (Exception ignored) {}

        when(region.getSettings()).thenReturn(settings);
        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            field.set(region, chunkManager);
        } catch (Exception ignored) {}

        queueManager = new RegionQueueManager(region);
    }

    @Test
    void testPriorityPolling() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        RTPCoords fastLoc = new RTPCoords("world", 1, 1, 1);
        RTPCoords playerLoc = new RTPCoords("world", 2, 2, 2);
        RTPCoords globalLoc = new RTPCoords("world", 3, 3, 3);

        java.util.concurrent.CompletableFuture<RTPLocation> fastFuture = new java.util.concurrent.CompletableFuture<>();
        queueManager.fastLocations.put(uuid, fastFuture);

        java.util.concurrent.ConcurrentLinkedQueue<RTPLocation> pQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
        pQueue.add(new RTPLocation(playerLoc, 1L));
        queueManager.perPlayerLocationQueue.put(uuid, pQueue);

        queueManager.keptLocations.add(new RTPLocation(globalLoc, 1L));

        // 1. fastLocations prioritized
        java.util.concurrent.CompletableFuture<RTPLocation> poll1 = queueManager.poll(uuid);
        Assertions.assertNotNull(poll1, "fastLocation future should not be null");
        Assertions.assertSame(fastFuture, poll1);
        Assertions.assertFalse(poll1.isDone());
        fastFuture.complete(new RTPLocation(fastLoc, 1L));

        try {
            Assertions.assertEquals(fastLoc, poll1.get().coords());
        } catch (Exception e) {
            Assertions.fail(e);
        }

        // 2. perPlayerLocationQueue prioritized next
        java.util.concurrent.CompletableFuture<RTPLocation> poll2 = queueManager.poll(uuid);
        Assertions.assertNotNull(poll2, "playerQueue future should not be null");
        try {
            Assertions.assertEquals(playerLoc, poll2.get().coords());
        } catch (Exception e) {
            Assertions.fail(e);
        }

        // 3. global locationQueue as fallback
        java.util.concurrent.CompletableFuture<RTPLocation> poll3 = queueManager.poll(uuid);
        Assertions.assertNotNull(poll3, "locationQueue future should not be null");
        try {
            Assertions.assertEquals(globalLoc, poll3.get().coords());
        } catch (Exception e) {
            Assertions.fail(e);
        }

        // 4. null if all empty
        Assertions.assertNull(queueManager.poll(uuid));
    }

    @Test
    void testChunkTicketTransfer() {
        GlobalRegionVerifiers.clearGlobalRegionVerifiers();

        io.github.dailystruggle.rtp.api.entity.RTPPlayer player = mock(io.github.dailystruggle.rtp.api.entity.RTPPlayer.class);
        java.util.UUID uuid = java.util.UUID.randomUUID();
        when(player.uuid()).thenReturn(uuid);

        RTPCoords coords = new RTPCoords("world", 64, 64, 64);
        queueManager.keptLocations.add(new RTPLocation(coords, 1L));

        when(region.getWorld()).thenReturn(mock(io.github.dailystruggle.rtp.api.world.RTPWorld.class));

        java.util.concurrent.CompletableFuture<Long> chunkFuture = java.util.concurrent.CompletableFuture.completedFuture(1L);
        java.util.List<java.util.concurrent.CompletableFuture<Long>> chunks = java.util.Collections.singletonList(chunkFuture);
        ChunkSet mockChunkSet = new ChunkSet(chunks, new java.util.concurrent.CompletableFuture<>());

        io.github.dailystruggle.rtp.api.selection.GenerationContext context = new io.github.dailystruggle.rtp.api.selection.GenerationContext(null, player, null);
        when(chunkManager.addTicket(anyInt(), anyInt())).thenReturn(mockChunkSet);
        when(chunkManager.getChunkSet(any())).thenReturn(mockChunkSet);
        io.github.dailystruggle.rtp.api.world.RTPChunk mockChunk = mock(io.github.dailystruggle.rtp.api.world.RTPChunk.class);
        when(region.getWorld().getCachedChunk(anyLong())).thenReturn(mockChunk);
        when(mockChunk.x()).thenReturn(4);
        when(mockChunk.z()).thenReturn(4);
        when(mockChunk.isSafe(anyInt(), anyInt(), anyInt(), any())).thenReturn(true);

        io.github.dailystruggle.rtp.common.configuration.Configs configs = mock(io.github.dailystruggle.rtp.common.configuration.Configs.class);
        io.github.dailystruggle.rtp.common.RTP.configs = configs;
        io.github.dailystruggle.rtp.common.configuration.ConfigParser safetyParser = mock(io.github.dailystruggle.rtp.common.configuration.ConfigParser.class);
        when(configs.getParser(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.class)).thenReturn(safetyParser);
        doReturn(new java.util.ArrayList<>()).when(safetyParser).getConfigValue(any(), any());
        doReturn(0).when(safetyParser).getNumber(any(), any());

        io.github.dailystruggle.rtp.api.server.RTPServerAccessor serverAccessor = RTP.serverAccessor;
        io.github.dailystruggle.rtp.api.world.RTPChunkManager apiChunkManager = mock(io.github.dailystruggle.rtp.api.world.RTPChunkManager.class);
        when(serverAccessor.getChunkManager()).thenReturn(apiChunkManager);
        when(apiChunkManager.getChunkAtAsync(any(), anyInt(), anyInt())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(1L));

        ILocationGenerator locationGenerator = serverAccessor.getLocationGenerator();
        when(locationGenerator.getLocation(any(), any(), any(), any())).thenReturn(java.util.concurrent.CompletableFuture.completedFuture(new GenerationResult(coords, 1L, mockChunkSet)));

        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("queueManager");
            field.setAccessible(true);
            field.set(region, queueManager);
        } catch (Exception ignored) {}

        GenerationResult result = LocationGenerator.getLocation(region, context);

        Assertions.assertNotNull(result, "GenerationResult should not be null");
        Assertions.assertEquals(coords, result.coords());
        verify(chunkManager).addTicket(eq(4), eq(4));
        verify(chunkManager, atMostOnce()).removeTicket(eq(4), eq(4));
    }
}
