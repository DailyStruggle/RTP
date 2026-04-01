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
import io.github.dailystruggle.rtp.common.selection.region.CachedLocation;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;
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
    private RegionChunkManager chunkManager;
    private RegionQueueManager queueManager;

    @BeforeEach
    void setUp() throws NoSuchFieldException, IllegalAccessException {
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
        chunkManager = mock(RegionChunkManager.class);
        queueManager = new RegionQueueManager(region);

        when(region.getSettings()).thenReturn(settings);
        when(region.getShape()).thenReturn((Shape) shape);
        when(region.getVert()).thenReturn((VerticalAdjustor) vert);
        when(region.getWorld()).thenReturn(world);
        when(region.name).thenReturn("testRegion");

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
        when(border.isInside()).thenReturn(loc -> true);

        when(configs.getParser(PerformanceKeys.class)).thenReturn(performance);
        when(configs.getParser(SafetyKeys.class)).thenReturn(safety);
        doReturn(false).when(performance).getConfigValue(any(), any());
        doReturn(false).when(safety).getConfigValue(eq(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.biomeWhitelist), any());
        doReturn(new java.util.ArrayList<String>()).when(safety).getConfigValue(eq(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.biomes), any());
        doReturn(new java.util.ArrayList<String>()).when(safety).getConfigValue(eq(io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys.unsafeBlocks), any());
        doReturn(0).when(safety).getNumber(any(), any());

        when(shape.getRange()).thenReturn(1000.0);
        when(settings.spatialResolution()).thenReturn(1L);

        // RTP singleton setup
        RTP rtp = new RTP() {
            {
                serverAccessor = FillTaskTest.this.serverAccessor;
                scheduler = FillTaskTest.this.scheduler;
                configs = FillTaskTest.this.configs;
            }
        };
        RTP.serverAccessor = serverAccessor;
        RTP.scheduler = scheduler;
        RTP.configs = configs;

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
            queueManager.locationQueue.offer(mock(CachedLocation.class));
        }

        FillTask fillTask = new FillTask(region, 0);

        // Mock fillIncrement to a large value to try and fill more than cacheCap
        try {
            Field fiField = FillTask.class.getDeclaredField("fillIncrement");
            fiField.setAccessible(true);
            ((AtomicLong) fiField.get(fillTask)).set(100);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Run the task
        fillTask.run();

        // Assert that locationQueue size does not exceed cacheCap
        assertTrue(queueManager.locationQueue.size() <= cacheCap,
            "locationQueue size (" + queueManager.locationQueue.size() + ") exceeded cacheCap (" + cacheCap + ")");
    }

    @Test
    void testFillTaskRespectsActiveChunkCap() {
        int cacheCap = 10;
        int activeChunkCap = 2;
        when(settings.cacheCap()).thenReturn((long) cacheCap);
        when(settings.activeChunkCap()).thenReturn(activeChunkCap);

        for (int i = 0; i < activeChunkCap; i++) {
            chunkManager.locAssChunks.put((long) i, mock(ChunkSet.class));
        }

        FillTask fillTask = new FillTask(region, 0);

        // Mock fillIncrement to a large value to try and fill more than activeChunkCap
        try {
            Field fiField = FillTask.class.getDeclaredField("fillIncrement");
            fiField.setAccessible(true);
            ((AtomicLong) fiField.get(fillTask)).set(100);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // Run the task
        fillTask.run();

        // Assert that active chunk tickets in RegionChunkManager does not exceed activeChunkCap
        assertTrue(chunkManager.locAssChunks.size() <= activeChunkCap,
                "Active chunks in RegionChunkManager (" + chunkManager.locAssChunks.size() + ") exceeded activeChunkCap (" + activeChunkCap + ")");
    }
}
