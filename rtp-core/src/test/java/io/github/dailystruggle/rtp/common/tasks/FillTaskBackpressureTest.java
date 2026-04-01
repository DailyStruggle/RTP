package io.github.dailystruggle.rtp.common.tasks;

import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.MutableRTPCoords;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.selection.region.*;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.MemoryShape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

public class FillTaskBackpressureTest {
    private Region region;
    private RegionSettings settings;
    private MemoryShape<?> shape;
    private RTPWorld world;
    private RTPServerAccessor serverAccessor;
    private RTPChunkManager apiChunkManager;
    private Configs configs;

    @BeforeEach
    void setUp() throws Exception {
        region = mock(Region.class);
        settings = mock(RegionSettings.class);
        shape = mock(MemoryShape.class);
        world = mock(RTPWorld.class);
        serverAccessor = mock(RTPServerAccessor.class);
        apiChunkManager = mock(RTPChunkManager.class);
        configs = mock(Configs.class);

        when(region.getSettings()).thenReturn(settings);
        when(region.getShape()).thenReturn((Shape) shape);
        when(region.getVert()).thenReturn(mock(VerticalAdjustor.class));
        when(region.getWorld()).thenReturn(world);

        Field nameField = FactoryValue.class.getDeclaredField("name");
        nameField.setAccessible(true);
        nameField.set(region, "testRegion");

        Field cmField = Region.class.getDeclaredField("chunkManager");
        cmField.setAccessible(true);
        cmField.set(region, mock(RegionChunkManager.class));

        Field qmField = Region.class.getDeclaredField("queueManager");
        qmField.setAccessible(true);
        qmField.set(region, mock(RegionQueueManager.class));

        when(world.name()).thenReturn("testWorld");
        doReturn(java.util.UUID.randomUUID()).when(world).id();
        when(serverAccessor.getChunkManager()).thenReturn(apiChunkManager);
        when(serverAccessor.createTaskPipe()).thenReturn(mock(RTPTaskPipe.class));
        when(serverAccessor.getPluginDirectory()).thenReturn(new java.io.File("."));

        WorldBorder border = mock(WorldBorder.class);
        when(serverAccessor.getWorldBorder(anyString())).thenReturn(border);
        when(border.isInside()).thenReturn(loc -> true);

        when(configs.getParser(PerformanceKeys.class)).thenReturn(mock(ConfigParser.class));
        when(configs.getParser(SafetyKeys.class)).thenReturn(mock(ConfigParser.class));
        when(configs.getParser(MessagesKeys.class)).thenReturn(mock(ConfigParser.class));
        ConfigParser<PerformanceKeys> performance = (ConfigParser<PerformanceKeys>) configs.getParser(PerformanceKeys.class);
        ConfigParser<SafetyKeys> safety = (ConfigParser<SafetyKeys>) configs.getParser(SafetyKeys.class);
        ConfigParser<MessagesKeys> messages = (ConfigParser<MessagesKeys>) configs.getParser(MessagesKeys.class);

        doReturn(false).when(performance).getConfigValue(any(), any());
        doReturn(true).when(safety).getConfigValue(eq(SafetyKeys.biomeWhitelist), any());
        doReturn(java.util.Collections.singletonList("PLAINS")).when(safety).getConfigValue(eq(SafetyKeys.biomes), any());
        doReturn(new java.util.ArrayList<String>()).when(safety).getConfigValue(eq(SafetyKeys.unsafeBlocks), any());
        doReturn(0).when(safety).getNumber(any(), any());
        doReturn("").when(messages).getConfigValue(any(), any());

        when(world.getBiome(anyInt(), anyInt(), anyInt())).thenReturn("plains");
        when(shape.getRange()).thenReturn(1000.0);
        doAnswer(invocation -> {
            long l = invocation.getArgument(0);
            MutableRTPCoords c = invocation.getArgument(1);
            c.x = (int) (l * 16);
            c.z = (int) (l * 16);
            return null;
        }).when(shape).locationToXZ(anyLong(), any(MutableRTPCoords.class));
        when(settings.spatialResolution()).thenReturn(1L);
        when(settings.activeChunkCap()).thenReturn(50);
        when(settings.cacheCap()).thenReturn(1000L);

        RTP.serverAccessor = serverAccessor;
        RTP.configs = configs;
        RTPScheduler scheduler = mock(RTPScheduler.class);
        RTP.scheduler = scheduler;

        // Mock RTP singleton instance
        RTP rtp = new RTP() {};
        RTP.configs = configs; // RTP constructor overwrites configs, so we re-assign it
    }

    @Test
    void testFillTaskBackpressure() throws Exception {
        // 1. Mock getChunkAtAsync to return uncompleted futures
        // We use a custom Answer to return uncompleted futures
        when(apiChunkManager.getChunkAtAsync(any(), anyInt(), anyInt())).thenAnswer(invocation -> new CompletableFuture<Long>());

        FillTask fillTask = new FillTask(region, 0);

        // Mock fillIncrement to a large value
        Field fiField = FillTask.class.getDeclaredField("fillIncrement");
        fiField.setAccessible(true);
        ((AtomicLong) fiField.get(fillTask)).set(1000);

        // 2. Execute FillTask.run()
        // It should terminate when pendingChunks reaches 50
        fillTask.run();

        // 3. Assert that pendingChunks reaches exactly 50
        Field pcField = FillTask.class.getDeclaredField("pendingChunks");
        pcField.setAccessible(true);
        AtomicLong pendingChunks = (AtomicLong) pcField.get(fillTask);

        assert pendingChunks.get() == 50 : "Expected pendingChunks to be exactly 50, but was " + pendingChunks.get();

        // Also verify that getChunkAtAsync was called 50 times
        verify(apiChunkManager, times(50)).getChunkAtAsync(any(), anyInt(), anyInt());
    }
}
