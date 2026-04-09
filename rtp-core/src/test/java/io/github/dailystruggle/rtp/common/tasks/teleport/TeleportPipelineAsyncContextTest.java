package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.common.configuration.enums.PerformanceKeys;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunkManager;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionChunkManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.doReturn;

public class TeleportPipelineAsyncContextTest {
    private RTP rtp;
    private RTPScheduler scheduler;
    private RTPServerAccessor serverAccessor;
    private Region region;
    private RegionChunkManager regionChunkManager;
    private RTPPlayer player;
    private RTPWorld world;
    private Configs configs;
    private Thread mainThread;

    @BeforeEach
    void setUp() {
        mainThread = Thread.currentThread();
        scheduler = mock(RTPScheduler.class);
        serverAccessor = mock(RTPServerAccessor.class);
        RTPChunkManager chunkManager = mock(RTPChunkManager.class);
        when(serverAccessor.getChunkManager()).thenReturn(chunkManager);
        doCallRealMethod().when(chunkManager).whenComplete(any(), any());
        RTP.scheduler = scheduler;
        RTP.serverAccessor = serverAccessor;

        when(serverAccessor.createTaskPipe()).thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));
        when(serverAccessor.getPluginDirectory()).thenReturn(new java.io.File("."));

        rtp = new RTP();
        rtp.databaseAccessor = mock(DatabaseAccessor.class);

        region = mock(Region.class);
        regionChunkManager = mock(RegionChunkManager.class);
        player = mock(RTPPlayer.class);
        world = mock(RTPWorld.class);
        configs = mock(Configs.class);
        RTP.configs = configs;

        UUID playerId = UUID.randomUUID();
        when(player.uuid()).thenReturn(playerId);
        doReturn(true).when(player).isOnline();
        when(player.name()).thenReturn("TestPlayer");
        when(player.delay()).thenReturn(0L);
        when(world.name()).thenReturn("world");
        doReturn(java.util.UUID.randomUUID()).when(world).id();
        when(serverAccessor.getRTPWorld("world")).thenReturn(world);
        when(region.getWorld()).thenReturn(world);

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

        ConfigParser<PerformanceKeys> performanceConfig = mock(ConfigParser.class);
        ConfigParser<MessagesKeys> messagesConfig = mock(ConfigParser.class);
        when(configs.getParser(PerformanceKeys.class)).thenReturn(performanceConfig);
        when(configs.getParser(MessagesKeys.class)).thenReturn(messagesConfig);
        doReturn(0L).when(performanceConfig).getNumber(any(), any());
        doReturn("").when(messagesConfig).getConfigValue(any(), any());

        // Mock getLocation to throw exception if not on main thread
        when(player.getLocation()).thenAnswer(invocation -> {
            if (Thread.currentThread() != mainThread) {
                throw new IllegalStateException("Async catch");
            }
            return new RTPLocation(world, 0, 64, 0);
        });
    }

    @Test
    void testAsyncPipelineExecutionWithPreFetchedLocation() throws InterruptedException, ExecutionException {
        try (MockedStatic<RTP> rtpStatic = mockStatic(RTP.class)) {
            rtpStatic.when(RTP::getInstance).thenReturn(rtp);

            RTPCoords preSelectedCoords = new RTPCoords("world", 100, 64, 100);
            GenerationContext context = new GenerationContext(player, player, null);

            // 3. Pre-fetch the location via the synchronous command thread logic
            // This simulates what happens in a command dispatch before the pipeline is started
            TeleportData teleportData = new TeleportData();
            teleportData.sender = player;
            teleportData.time = System.currentTimeMillis();
            teleportData.delay = player.delay();
            teleportData.targetRegion = region;
            // Pre-fetching location on the main thread (where we are now)
            RTPLocation loc = player.getLocation();
            teleportData.originalCoords = new RTPCoords(loc.world().name(), loc.x(), loc.y(), loc.z());

            rtp.latestTeleportData.put(player.uuid(), teleportData);

            // 1. Instantiate a TeleportPipelineTask using the standard constructor (simulating a command dispatch).
            // Actually, the instruction says "passing it into the pipeline state",
            // and point 3 says "Pre-fetch... passing it into the pipeline state".
            // Constructor 3: TeleportPipelineTask(context, region, preSelectedCoords)
            TeleportPipelineTask task = new TeleportPipelineTask(context, region, preSelectedCoords);

            // 4. Execute runLoad() and runTeleport() via a separate ExecutorService thread.
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<?> future = executor.submit(() -> {
                    // Start from LOAD phase as set by constructor
                    assertEquals(TeleportPipelineTask.Phase.LOAD, task.getPhase());

                    // Mock chunkSet for runLoad
                    CompletableFuture<Boolean> chunkFuture = CompletableFuture.completedFuture(true);
                    ChunkSet chunkSet = new ChunkSet(new ArrayList<>(), chunkFuture);
                    when(regionChunkManager.getChunkSet(preSelectedCoords)).thenReturn(chunkSet);

                    task.run(); // Should execute runLoad()

                    // After runLoad, it should have scheduled teleport.
                    // In our mock, we should check if scheduleTeleport was called or manually advance.
                    // The task logic calls RTP.scheduler.scheduleTeleport(player(), this, toTicks);
                    // which we can't easily block on, so we'll manually advance phase for testing runTeleport
                    task.setPhase(TeleportPipelineTask.Phase.TELEPORT);

                    when(player.setLocation(any())).thenReturn(CompletableFuture.completedFuture(true));
                    task.run(); // Should execute runTeleport()
                });

                future.get(); // This will throw ExecutionException if IllegalStateException("Async catch") occurred

                // Assert that the pipeline transitions to Phase.CLEANUP
                // Based on runTeleport code, it sets phase to CLEANUP after player.setLocation is called.
                assertEquals(TeleportPipelineTask.Phase.CLEANUP, task.getPhase());
            } finally {
                executor.shutdown();
            }
        }
    }
}
