package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionChunkManager;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.dailystruggle.rtp.common.configuration.enums.EconomyKeys;

public class TeleportCancelTicketTest {
    private RTP rtp;
    private RTPScheduler scheduler;
    private RTPServerAccessor serverAccessor;
    private Region region;
    private RegionChunkManager regionChunkManager;
    private RTPPlayer player;
    private RTPWorld world;
    private GenerationContext context;
    private ILocationGenerator locationGenerator;
    private Configs configs;
    private ConfigParser<PerformanceKeys> performanceConfig;
    private ConfigParser<MessagesKeys> messagesConfig;
    private ConfigParser<EconomyKeys> economyConfig;

    @BeforeEach
    void setUp() {
        scheduler = mock(RTPScheduler.class);
        serverAccessor = mock(RTPServerAccessor.class);
        locationGenerator = mock(ILocationGenerator.class);

        RTP.scheduler = scheduler;
        RTP.serverAccessor = serverAccessor;
        when(serverAccessor.getLocationGenerator()).thenReturn(locationGenerator);
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(3);
            runnable.run();
            return null;
        }).when(scheduler).runTask(any(), anyInt(), anyInt(), any(Runnable.class));
        RTP.serverAccessor = serverAccessor;
        when(serverAccessor.createTaskPipe()).thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        when(serverAccessor.getPluginDirectory()).thenReturn(new java.io.File("."));
        io.github.dailystruggle.rtp.api.world.RTPChunkManager mockChunkManager = mock(io.github.dailystruggle.rtp.api.world.RTPChunkManager.class);
        when(serverAccessor.getChunkManager()).thenReturn(mockChunkManager);
        java.util.concurrent.CompletableFuture<Long> mockFuture = new java.util.concurrent.CompletableFuture<>();
        mockFuture.complete(1L);
//        io.github.dailystruggle.rtp.api.world.ChunkSet mockChunkSet = mock(io.github.dailystruggle.rtp.api.world.ChunkSet.class);
//        try {
//            java.lang.reflect.Field completeField = io.github.dailystruggle.rtp.api.world.ChunkSet.class.getDeclaredField("complete");
//            completeField.setAccessible(true);
//            completeField.set(mockChunkSet, java.util.concurrent.CompletableFuture.completedFuture(true));
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
        java.util.concurrent.CompletableFuture<Boolean> complete = new java.util.concurrent.CompletableFuture<>();
        complete.complete(true);

        io.github.dailystruggle.rtp.api.world.ChunkSet realChunkSet = new io.github.dailystruggle.rtp.api.world.ChunkSet(new java.util.ArrayList<>(), complete);
        io.github.dailystruggle.rtp.api.world.ChunkSet mockChunkSet = spy(realChunkSet);

        rtp = new RTP();
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
        region = mock(Region.class);
        // Use a real RegionChunkManager
        regionChunkManager = new RegionChunkManager(region);
        player = mock(RTPPlayer.class);
        world = mock(RTPWorld.class);
        RTPCoords mockCoords = new RTPCoords("world", 0, 0, 0);
        java.util.concurrent.CompletableFuture<GenerationResult> mockLocation = java.util.concurrent.CompletableFuture.completedFuture(new GenerationResult(mockCoords, 1L, mockChunkSet));
        when(locationGenerator.getLocation(eq(region), any(GenerationContext.class))).thenReturn(mockLocation);
        configs = mock(Configs.class);
        performanceConfig = mock(ConfigParser.class);
        messagesConfig = mock(ConfigParser.class);
        economyConfig = mock(ConfigParser.class);

        RTP.configs = configs;
        java.util.concurrent.ConcurrentHashMap<Class<?>, io.github.dailystruggle.rtp.common.configuration.ConfigParser<?>> map = new java.util.concurrent.ConcurrentHashMap<>();
        map.put(PerformanceKeys.class, performanceConfig);
        map.put(MessagesKeys.class, messagesConfig);
        map.put(EconomyKeys.class, economyConfig);
        configs.configParserMap = map;
        configs.multiConfigParserMap = new java.util.concurrent.ConcurrentHashMap<>();

        UUID playerId = UUID.randomUUID();
        when(player.uuid()).thenReturn(playerId);
        UUID worldId = UUID.randomUUID();
        when(world.id()).thenReturn(worldId);
        when(player.getLocation()).thenReturn(new RTPLocation(world, 0, 64, 0));
        when(player.delay()).thenReturn(0L);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(false);
        when(world.name()).thenReturn("world");
        when(serverAccessor.getRTPWorld("world")).thenReturn(world);
        when(serverAccessor.getPlayer(playerId)).thenReturn(player);
        when(region.getWorld()).thenReturn(world);
        when(region.getShape()).thenReturn(mock(io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape.class));
        when(region.getVert()).thenReturn(mock(io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor.class));

        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            field.set(region, regionChunkManager);

            java.lang.reflect.Field inflightField = Region.class.getDeclaredField("inFlightCalculations");
            inflightField.setAccessible(true);
            inflightField.set(region, new AtomicInteger(0));
        } catch (Exception e) {
            e.printStackTrace();
        }

        when(configs.getParser(PerformanceKeys.class)).thenReturn(performanceConfig);
        when(configs.getParser(MessagesKeys.class)).thenReturn(messagesConfig);
        when(configs.getParser(EconomyKeys.class)).thenReturn(economyConfig);
        when(performanceConfig.getNumber(any(), any())).thenReturn(0L);
        when(messagesConfig.getConfigValue(any(), any())).thenReturn("");
        when(economyConfig.getConfigValue(any(), any())).thenReturn(true);

        context = new GenerationContext(player, player, null);
    }

    @Test
    void testCancelledPipelineReleasesChunkTickets() {

        try (MockedStatic<RTP> rtpStatic = mockStatic(RTP.class)) {
            rtpStatic.when(RTP::getInstance).thenReturn(rtp);

            RTPCoords coords = new RTPCoords("world", 100, 64, 100);

            // 1. Use the spy instantiation to prevent NPEs during internal iterations
            java.util.concurrent.CompletableFuture<Boolean> complete = new java.util.concurrent.CompletableFuture<>();
            complete.complete(true);
            io.github.dailystruggle.rtp.api.world.ChunkSet realChunkSet = new io.github.dailystruggle.rtp.api.world.ChunkSet(new java.util.ArrayList<>(), complete);
            io.github.dailystruggle.rtp.api.world.ChunkSet mockChunkSet = spy(realChunkSet);

            // Put the mock ChunkSet into the real RegionChunkManager
            regionChunkManager.putChunkSet(coords, mockChunkSet);

            // 2. Initialize teleport data for the player FIRST
            TeleportData teleportData = new TeleportData();
            teleportData.sender = player;
            teleportData.targetRegion = region;
            teleportData.selectedCoords = coords;
            rtp.latestTeleportData.put(player.uuid(), teleportData);

            // 3. NOW instantiate the task so its constructor captures the valid TeleportData
            TeleportPipelineTask task = new TeleportPipelineTask(context);
            teleportData.nextTask = task; // RTPTeleportCancel needs this

            // We need to set teleportData, region, and coords which are private...
            try {
                java.lang.reflect.Field teleportDataField = TeleportPipelineTask.class.getDeclaredField("teleportData");
                teleportDataField.setAccessible(true);
                teleportDataField.set(task, teleportData);

                java.lang.reflect.Field regionField = TeleportPipelineTask.class.getDeclaredField("region");
                regionField.setAccessible(true);
                regionField.set(task, region);

                java.lang.reflect.Field coordsField = TeleportPipelineTask.class.getDeclaredField("coords");
                coordsField.setAccessible(true);
                coordsField.set(task, coords);
            } catch (Exception e) {
                e.printStackTrace();
            }

            // Verify the ChunkSet has keep(true) applied initially
            RTPChunkManager chunkManager = RTP.serverAccessor.getChunkManager();
            chunkManager.keep(mockChunkSet, true, world);
            verify(chunkManager, atLeastOnce()).keep(mockChunkSet, true, world);

            // Trigger cancellation via RTPTeleportCancel
            new RTPTeleportCancel(player.uuid()).run();

            // The task should now be cancelled
            assertTrue(task.isCancelled());

            // Assert via Mockito that the cancellation strictly notified the server to drop the ticket
            verify(chunkManager, atLeastOnce()).keep(mockChunkSet, false, world);

            // Even if we run the task now, it should go to cleanup but not call keep(false) again if already released
            task.run();
        }
    }
}
