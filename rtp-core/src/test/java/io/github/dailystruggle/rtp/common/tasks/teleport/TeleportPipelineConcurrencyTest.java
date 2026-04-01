package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.CachedLocation;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionQueueManager;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        rtp = new RTP();
        mockRegion = mock(Region.class);

        // Mock region settings and methods needed by TeleportPipelineTask
        when(mockRegion.getShape()).thenReturn(mock(Shape.class));
    }

    @RepeatedTest(50)
    public void testSimultaneousTeleportRequests() throws InterruptedException {
        int playerCount = 100;
        int threadCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(playerCount);

        RegionQueueManager queueManager = new RegionQueueManager(mockRegion);
        // We can't set mockRegion.queueManager because it's final,
        // but we can make it so that anything using mockRegion uses this queueManager if we mock the right things.
        // Actually, for this test we are using queueManager directly in our worker threads.

        // Pre-populate queue with 100 unique locations
        List<RTPCoords> expectedCoords = new ArrayList<>();
        for (int i = 0; i < playerCount; i++) {
            RTPCoords coords = new RTPCoords("world", i, 64, i);
            expectedCoords.add(coords);
            queueManager.locationQueue.add(new CachedLocation(coords, 1));
        }

        List<RTPPlayer> players = new ArrayList<>();
        ConcurrentMap<UUID, RTPCoords> assignedCoords = new ConcurrentHashMap<>();
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < playerCount; i++) {
            RTPPlayer mockPlayer = mock(RTPPlayer.class);
            UUID uuid = UUID.randomUUID();
            when(mockPlayer.uuid()).thenReturn(uuid);
            RTPLocation mockLoc = mock(RTPLocation.class);
            RTPWorld mockWorld = mock(RTPWorld.class);
            when(mockWorld.name()).thenReturn("world");
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

                    CompletableFuture<CachedLocation> pollFuture = queueManager.poll(player.uuid());
                    if (pollFuture != null) {
                        CachedLocation loc = pollFuture.get();
                        if (loc != null) {
                            assignedCoords.put(player.uuid(), loc.getCoords());
                            // Simulate task instantiation
                            new TeleportPipelineTask(context, mockRegion, loc.getCoords());
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

        assertEquals(0, failures.get(), "There were failures during concurrent polling");
        assertEquals(playerCount, assignedCoords.size(), "Not all players were assigned a coordinate");
        assertEquals(new HashSet<>(expectedCoords), new HashSet<>(assignedCoords.values()), "Assigned coordinates are not unique or don't match expected");
        assertEquals(0, queueManager.locationQueue.size(), "Queue was not fully emptied");
    }
}
