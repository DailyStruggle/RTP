package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.common.selection.region.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class FillTaskTest {
    private Region region;
    private RegionSettings settings;
    private MemoryShape<?> shape;
    private VerticalAdjustor<?> vert;
    private RTPWorld world;
    private RTPServerAccessor serverAccessor;
    private RTPScheduler scheduler;
    private Configs configs;
    private ConfigParser<PerformanceKeys> performance;
    private ConfigParser<SafetyKeys> safety;
    private ConfigParser<io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys> messages;
    private RegionChunkManager chunkManager;
    private RegionQueueManager queueManager;

    @BeforeEach
    void setUp() throws Exception {
        region = mock(Region.class);
        settings = mock(RegionSettings.class);
        shape = mock(MemoryShape.class);
        vert = mock(VerticalAdjustor.class);
        world = mock(RTPWorld.class);
        serverAccessor = mock(RTPServerAccessor.class);
        scheduler = mock(RTPScheduler.class);
        configs = mock(Configs.class);
        performance = mock(ConfigParser.class);
        safety = mock(ConfigParser.class);
        messages = mock(ConfigParser.class);
        chunkManager = new RegionChunkManager(region);
        queueManager = new RegionQueueManager(region);

        when(region.getSettings()).thenReturn(settings);
        when(region.getShape()).thenReturn((Shape) shape);
        when(region.getVert()).thenReturn((VerticalAdjustor) vert);
        when(region.getWorld()).thenReturn(world);
        region.name = "testRegion";

        // Set final field chunkManager and queueManager for testing
        Field cmField = Region.class.getDeclaredField("chunkManager");
        cmField.setAccessible(true);
        cmField.set(region, chunkManager);

        Field qmField = Region.class.getDeclaredField("queueManager");
        qmField.setAccessible(true);
        qmField.set(region, queueManager);

        when(world.name()).thenReturn("testWorld");
        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn("plains");

        when(serverAccessor.getChunkManager()).thenReturn(mock(io.github.dailystruggle.rtp.api.world.RTPChunkManager.class));
        when(serverAccessor.getChunkManager().getChunkAtAsync(any(), anyInt(), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(1L));
        RTPChunk chunk = mock(RTPChunk.class);
        when(world.getCachedChunk(anyLong())).thenReturn(chunk);
        when(chunk.isSafe(anyInt(), anyInt(), anyInt(), any())).thenReturn(true);
        when(vert.adjust(any(), any())).thenReturn(true);
        when(vert.minY()).thenReturn(0);
        when(vert.maxY()).thenReturn(255);

        WorldBorder border = mock(WorldBorder.class);
        when(serverAccessor.getWorldBorder(anyString())).thenReturn(border);
        when(serverAccessor.getBiomes(any())).thenReturn(new java.util.HashSet<>());
        when(serverAccessor.getPluginDirectory()).thenReturn(new java.io.File("target/test-data"));
        when(border.isInside()).thenReturn(loc -> true);

        when(configs.getParser(PerformanceKeys.class)).thenReturn(performance);
        when(configs.getParser(SafetyKeys.class)).thenReturn(safety);
        when(configs.getParser(io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys.class)).thenReturn(messages);

        // Minimal, robust stubbing to prevent NPEs in FillTask.run()
        // Using thenAnswer to return the provided default value (second argument)
        when(performance.getConfigValue(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(safety.getConfigValue(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(safety.getNumber(any(), any())).thenAnswer(inv -> inv.getArgument(1));
        when(messages.getConfigValue(any(), any())).thenAnswer(inv -> inv.getArgument(1));

        when(shape.getRange()).thenReturn(1000L);
        when(settings.spatialResolution()).thenReturn(1L);

        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;
        RTP.configs = configs;

        // Mock RTP instance instead of creating it to avoid initialization issues
        RTP rtp = mock(RTP.class);

        // Manually initialize fields used by FillTask
        Field ftField = RTP.class.getDeclaredField("fillTasks");
        ftField.setAccessible(true);
        ftField.set(rtp, new java.util.concurrent.ConcurrentHashMap<String, FillTask>());

        Field ltdField = RTP.class.getDeclaredField("latestTeleportData");
        ltdField.setAccessible(true);
        ltdField.set(rtp, new java.util.concurrent.ConcurrentHashMap<java.util.UUID, io.github.dailystruggle.rtp.common.playerData.TeleportData>());

        Field ppField = RTP.class.getDeclaredField("processingPlayers");
        ppField.setAccessible(true);
        ppField.set(rtp, new java.util.concurrent.ConcurrentSkipListSet<java.util.UUID>());

        Field instanceField = RTP.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, rtp);
    }

    @Test
    void testFillTaskRespectsCacheCap() {
        int cacheCap = 10;
        when(settings.cacheCap()).thenReturn((long) cacheCap);
        when(settings.activeChunkCap()).thenReturn(2);
        for (int i = 0; i < cacheCap; i++) {
            queueManager.keptLocations.offer(mock(RTPLocation.class));
        }

        FillTask fillTask = new FillTask(region, 0);

        // Mock fillIncrement to a large value to try and fill more than cacheCap
        FillTask.fillIncrement.set(100);

        // Run the task
        fillTask.run();

        // Assert that locationQueue size does not exceed cacheCap
        assertTrue(queueManager.keptLocations.size() <= cacheCap,
            "locationQueue size (" + queueManager.keptLocations.size() + ") exceeded cacheCap (" + cacheCap + ")");
    }

    @Test
    void testFillTaskRespectsActiveChunkCap() {
        int cacheCap = 10;
        int activeChunkCap = 2;
        when(settings.cacheCap()).thenReturn((long) cacheCap);
        when(settings.activeChunkCap()).thenReturn(activeChunkCap);

        for (int i = 0; i < activeChunkCap; i++) {
            chunkManager.locAssChunks.put((long) i, mock(io.github.dailystruggle.rtp.api.world.ChunkReservation.class));
        }

        FillTask fillTask = new FillTask(region, 0);

        // Mock fillIncrement to a large value to try and fill more than activeChunkCap
        FillTask.fillIncrement.set(100);

        // Run the task
        fillTask.run();

        // Assert that active chunk tickets in RegionChunkManager does not exceed activeChunkCap
        assertTrue(chunkManager.locAssChunks.size() <= activeChunkCap,
                "Active chunks in RegionChunkManager (" + chunkManager.locAssChunks.size() + ") exceeded activeChunkCap (" + activeChunkCap + ")");
    }
}
