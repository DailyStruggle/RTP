package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPScheduler;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tools.GenerationBudget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RTP-80: kept-ticket re-pins respect the shared generation budget")
class RegionQueueManagerTicketPacingTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor serverAccessor;
    private MockRTPWorld world;
    private RegionQueueManager queueManager;

    @BeforeEach
    void setUp() {
        GenerationBudget.server().resetForTesting();
        GenerationBudget.configureServerAdmission(true);
        GenerationBudget.configureServerCap(null);
        serverAccessor = RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("ticket_pacing_world");
        serverAccessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "ticket_pacing_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 1000L, 0L, 8,
                0.0, 1L, "", false);
        queueManager = new Region("ticket_pacing_region", settings).queueManager;
    }

    @AfterEach
    void tearDown() {
        RTPRuntime.setServerAccessor(null);
        RTPRuntime.setScheduler(null);
        GenerationBudget.server().resetForTesting();
        GenerationBudget.configureServerAdmission(true);
        GenerationBudget.configureServerCap(null);
    }

    @Test
    @DisplayName("RTP-80: re-pin sweep defers at capacity and resumes after a slot frees")
    void rePinSweepDefersAtCapacityAndResumes() {
        GenerationBudget budget = GenerationBudget.server();
        GenerationBudget.configureServerCap(() -> 1);
        GenerationBudget.Reservation occupied = budget.reserve();

        RTPWorld<?> spyWorld = spy(world);
        ChunkReservation firstReservation = mock(ChunkReservation.class);
        ChunkReservation secondReservation = mock(ChunkReservation.class);
        queueManager.keptLocations.offer(new RTPLocation(
                new RTPCoords(world.name(), 16, 64, 16), 1L, firstReservation));
        queueManager.keptLocations.offer(new RTPLocation(
                new RTPCoords(world.name(), 32, 64, 32), 1L, secondReservation));

        CompletableFuture<ChunkSet> firstLoad = new CompletableFuture<>();
        CompletableFuture<ChunkSet> secondLoad = new CompletableFuture<>();
        doReturn(firstLoad, secondLoad).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());
        MockRTPScheduler scheduler = serverAccessor.getMockScheduler();

        try {
            queueManager.validateTickets(spyWorld);
            scheduler.tick(1L);
            verify(spyWorld, never()).getChunkAtAsync(anyInt(), anyInt());
            assertEquals(2, queueManager.keptLocations.size());

            occupied.close();
            scheduler.tick(20L);
            verify(spyWorld, times(1)).getChunkAtAsync(anyInt(), anyInt());
            assertEquals(1, budget.inFlight());

            ChunkSet firstChunkSet = mock(ChunkSet.class);
            when(firstChunkSet.complete()).thenReturn(CompletableFuture.completedFuture(true));
            firstLoad.complete(firstChunkSet);
            verify(firstReservation).refresh();
            assertEquals(0, budget.inFlight());

            scheduler.tick(20L);
            verify(spyWorld, times(2)).getChunkAtAsync(anyInt(), anyInt());
            assertEquals(1, budget.inFlight());

            ChunkSet secondChunkSet = mock(ChunkSet.class);
            when(secondChunkSet.complete()).thenReturn(CompletableFuture.completedFuture(true));
            secondLoad.complete(secondChunkSet);
            verify(secondReservation).refresh();
            assertEquals(0, budget.inFlight());
        } finally {
            occupied.close();
        }
    }

    @Test
    @DisplayName("RTP-80: failed re-pin load releases its reservation and retains the kept entry")
    void failedRePinLoadReleasesReservation() {
        GenerationBudget budget = GenerationBudget.server();
        GenerationBudget.configureServerCap(() -> 1);
        RTPWorld<?> spyWorld = spy(world);
        ChunkReservation reservation = mock(ChunkReservation.class);
        queueManager.keptLocations.offer(new RTPLocation(
                new RTPCoords(world.name(), 48, 64, 48), 1L, reservation));
        CompletableFuture<ChunkSet> failedLoad = new CompletableFuture<>();
        doReturn(failedLoad).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);
        assertEquals(1, budget.inFlight());
        failedLoad.completeExceptionally(new IllegalStateException("simulated chunk failure"));

        verify(reservation, never()).refresh();
        assertEquals(1, queueManager.keptLocations.size());
        assertEquals(0, budget.inFlight());
    }
}
