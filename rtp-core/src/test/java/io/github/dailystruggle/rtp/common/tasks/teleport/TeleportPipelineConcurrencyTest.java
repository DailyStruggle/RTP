package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.*;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.VerticalAdjustor;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.*;

public class TeleportPipelineConcurrencyTest {
    private Region mockRegion;
    private RTPScheduler mockScheduler;
    private RTPServerAccessor mockServerAccessor;
    private RTP rtp;

    @BeforeEach
    public void setUp() {
        mockScheduler = mock(RTPScheduler.class);
        mockServerAccessor = mock(RTPServerAccessor.class);
        when(mockServerAccessor.getPluginDirectory()).thenReturn(new File("target/test-data"));
        when(mockServerAccessor.createTaskPipe()).thenReturn(mock(io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe.class));

        RTP.scheduler = mockScheduler;
        RTP.serverAccessor = mockServerAccessor;
        RTP.selectionAPI = new io.github.dailystruggle.rtp.common.selection.SelectionAPI();
        rtp = new RTP();
        mockRegion = mock(Region.class);
        RegionSettings settings = new RegionSettings(
                "default",
                mock(RTPWorld.class),
                mock(Shape.class),
                mock(VerticalAdjustor.class),
                false,
                false,
                10,
                3,
                0.0D,
                3,
                "default",
                true
        );
        mockRegion.setSettings(settings);

        when(mockRegion.getShape()).thenReturn(mock(Shape.class));

        // Inject dependencies into mockRegion to prevent NPEs during task.run()
        RegionChunkManager mockChunkManager = mock(RegionChunkManager.class);
        ChunkSet mockChunkSet = new ChunkSet(Collections.emptyList(), CompletableFuture.completedFuture(true));
        when(mockChunkManager.getChunkSet(any())).thenReturn(mockChunkSet);

        try {
            java.lang.reflect.Field field = Region.class.getDeclaredField("chunkManager");
            field.setAccessible(true);
            field.set(mockRegion, mockChunkManager);

            java.lang.reflect.Field ifcField = Region.class.getDeclaredField("inFlightCalculations");
            ifcField.setAccessible(true);
            ifcField.set(mockRegion, new AtomicInteger(0));
        } catch (Exception ignored) {}

        // Progress the pipeline state automatically to prevent thread hangs
        doAnswer(invocation -> {
            TeleportPipelineTask task = invocation.getArgument(1);
            task.setPhase(TeleportPipelineTask.Phase.CLEANUP);
            task.run();
            return null;
        }).when(mockScheduler).scheduleTeleport(any(), any(), anyLong());
    }

    @RepeatedTest(50)
    public void testSimultaneousTeleportRequests() throws InterruptedException {
        int playerCount = 100;
        int threadCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(playerCount);

        RegionQueueManager queueManager = new RegionQueueManager(mockRegion);

        List<RTPCoords> expectedCoords = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            RTPCoords coords = new RTPCoords("world", i, 64, i);
            expectedCoords.add(coords);
            queueManager.keptLocations.add(new RTPLocation(coords, 1));
        }

        List<RTPPlayer> players = new ArrayList<>();
        ConcurrentMap<UUID, RTPCoords> assignedCoords = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < playerCount; i++) {
            RTPPlayer mockPlayer = mock(RTPPlayer.class);
            UUID uuid = UUID.randomUUID();
            when(mockPlayer.uuid()).thenReturn(uuid);
            doReturn(true).when(mockPlayer).isOnline();
            io.github.dailystruggle.rtp.api.world.RTPLocation mockLoc = mock(io.github.dailystruggle.rtp.api.world.RTPLocation.class);
            RTPWorld mockWorld = mock(RTPWorld.class);
            when(mockWorld.name()).thenReturn("world");
            doReturn(java.util.UUID.randomUUID()).when(mockWorld).id();
            when(mockLoc.world()).thenReturn(mockWorld);
            when(mockPlayer.getLocation()).thenReturn(mockLoc);
            players.add(mockPlayer);
        }

        for (int i = 0; i < playerCount; i++) {
            final RTPPlayer player = players.get(i);
            executor.submit(() -> {
                try {
                    startLatch.await();
                    GenerationContext context = new GenerationContext(player, player, null);
                    CompletableFuture<RTPLocation> pollFuture = queueManager.poll(player.uuid());

                    if (pollFuture != null) {
                        RTPLocation loc = pollFuture.get();
                        if (loc != null) {
                            assignedCoords.put(player.uuid(), loc.coords());
                            TeleportPipelineTask task = new TeleportPipelineTask(context, mockRegion, loc.coords());
                            task.run(); // Execute the pipeline
                        } else {
                            failures.incrementAndGet();
                        }
                    } else {
                        failures.incrementAndGet();
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdownNow();

        Assertions.assertEquals(0, failures.get(), "There were failures during concurrent polling");
        Assertions.assertEquals(playerCount, assignedCoords.size(), "Not all players were assigned a coordinate");
        Assertions.assertEquals(new HashSet<>(expectedCoords), new HashSet<>(assignedCoords.values()), "Assigned coordinates are not unique");
        Assertions.assertEquals(0, queueManager.keptLocations.size(), "Queue was not fully emptied");
    }
}
