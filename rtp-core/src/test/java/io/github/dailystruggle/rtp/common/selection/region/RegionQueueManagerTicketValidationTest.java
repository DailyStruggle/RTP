package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.world.ChunkReservation;
import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("REQ-RTP-S-002: RegionQueueManager ticket validation sweep")
class RegionQueueManagerTicketValidationTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor serverAccessor;
    private MockRTPWorld world;
    private RegionQueueManager queueManager;
    private int nextCoordinate;

    @BeforeEach
    void setUp() {
        GenerationBudget.server().resetForTesting();
        GenerationBudget.configureServerAdmission(true);
        GenerationBudget.configureServerCap(null);
        serverAccessor = RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("ticket_validation_world");
        serverAccessor.addWorld(world);

        RegionSettings settings = new RegionSettings(
                "ticket_validation_region", world,
                new Circle(), new LinearAdjustor(new ArrayList<>()),
                false, false,
                16L, 1000L, 0L, 8,
                0.0, 1L, "", false);
        queueManager = new Region("ticket_validation_region", settings).queueManager;
        nextCoordinate = 16;
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
    @DisplayName("null load future logs and releases its generation slot")
    void nullLoadFutureReleasesSlot() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        doReturn(null).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(reservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("load returned no future");
    }

    @Test
    @DisplayName("null chunk set logs and releases its generation slot")
    void nullChunkSetReleasesSlot() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        doReturn(CompletableFuture.completedFuture(null))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(reservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("load returned no completion");
    }

    @Test
    @DisplayName("null completion future logs and releases its generation slot")
    void nullCompletionFutureReleasesSlot() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = mock(ChunkSet.class);
        when(chunkSet.complete()).thenReturn(null);
        doReturn(CompletableFuture.completedFuture(chunkSet))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(reservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("load returned no completion");
    }

    @Test
    @DisplayName("throwing completion logs and releases its generation slot")
    void throwingCompletionReleasesSlot() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = mock(ChunkSet.class);
        IllegalStateException failure = new IllegalStateException("completion unavailable");
        doThrow(failure).when(chunkSet).complete();
        doReturn(CompletableFuture.completedFuture(chunkSet))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(reservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("Failed to recover chunk ticket");
    }

    @Test
    @DisplayName("exceptional and false completion results do not refresh tickets")
    void failedCompletionsDoNotRefreshTickets() {
        ChunkReservation exceptionalReservation = enqueueReservation();
        ChunkReservation falseReservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        ChunkSet exceptionalChunkSet = mock(ChunkSet.class);
        ChunkSet falseChunkSet = mock(ChunkSet.class);
        CompletableFuture<Boolean> exceptionalCompletion = new CompletableFuture<>();
        exceptionalCompletion.completeExceptionally(new IllegalStateException("chunk unavailable"));
        when(exceptionalChunkSet.complete()).thenReturn(exceptionalCompletion);
        when(falseChunkSet.complete()).thenReturn(CompletableFuture.completedFuture(false));
        doReturn(
                CompletableFuture.completedFuture(exceptionalChunkSet),
                CompletableFuture.completedFuture(falseChunkSet))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(exceptionalReservation, never()).refresh();
        verify(falseReservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("Failed to recover chunk ticket");
        assertWarningLogged("chunk did not complete");
    }

    @Test
    @DisplayName("throwing ticket refresh is logged and releases its generation slot")
    void throwingRefreshReleasesSlot() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        ChunkSet chunkSet = successfulChunkSet();
        doThrow(new IllegalStateException("ticket refresh failed")).when(reservation).refresh();
        doReturn(CompletableFuture.completedFuture(chunkSet))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(reservation).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("Failed to recover chunk ticket");
    }

    @Test
    @DisplayName("throwing async chunk load is logged and releases its generation slot")
    void throwingChunkLoadReleasesSlot() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        doThrow(new IllegalStateException("load unavailable"))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(reservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("Failed to recover chunk ticket");
    }

    @Test
    @DisplayName("exceptional chunk load future is logged and releases its generation slot")
    void exceptionalChunkLoadFutureReleasesSlot() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        CompletableFuture<ChunkSet> load = new CompletableFuture<>();
        load.completeExceptionally(new IllegalStateException("async load unavailable"));
        doReturn(load).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);

        verify(reservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
        assertWarningLogged("Failed to recover chunk ticket");
    }

    @Test
    @DisplayName("same-world re-entry schedules the active sweep instead of starting another load")
    void sameWorldReentryDoesNotStartAnotherLoad() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        CompletableFuture<ChunkSet> load = new CompletableFuture<>();
        doReturn(load).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);
        queueManager.validateTickets(spyWorld);
        serverAccessor.getMockScheduler().tick(1L);

        verify(spyWorld, times(1)).getChunkAtAsync(anyInt(), anyInt());
        assertEquals(1, GenerationBudget.server().inFlight());
        load.complete(successfulChunkSet());
        verify(reservation).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
    }

    @Test
    @DisplayName("different-world re-entry cancels the active sweep")
    void differentWorldReentryCancelsActiveSweep() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        CompletableFuture<ChunkSet> load = new CompletableFuture<>();
        doReturn(load).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());
        MockRTPWorld otherWorld = new MockRTPWorld("ticket_validation_other_world");
        serverAccessor.addWorld(otherWorld);

        queueManager.validateTickets(spyWorld);
        queueManager.validateTickets(otherWorld);
        load.complete(successfulChunkSet());

        verify(reservation, never()).refresh();
        assertEquals(0, GenerationBudget.server().inFlight());
    }

    @Test
    @DisplayName("clearCaches cancels in-flight ticket validation")
    void clearCachesCancelsInFlightSweep() {
        ChunkReservation reservation = enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        CompletableFuture<ChunkSet> load = new CompletableFuture<>();
        doReturn(load).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        queueManager.validateTickets(spyWorld);
        queueManager.clearCaches();
        load.complete(successfulChunkSet());

        verify(reservation, never()).refresh();
        assertEquals(0, queueManager.keptLocations.size());
        assertEquals(0, GenerationBudget.server().inFlight());
    }

    @Test
    @DisplayName("ticket validation waits when capacity is full and no scheduler is installed")
    void fullCapacityWithoutSchedulerDoesNotLoad() {
        enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        GenerationBudget budget = GenerationBudget.server();
        GenerationBudget.configureServerCap(() -> 1);
        GenerationBudget.Reservation occupied = budget.reserve();
        RTPRuntime.setScheduler(null);

        try {
            queueManager.validateTickets(spyWorld);

            verify(spyWorld, never()).getChunkAtAsync(anyInt(), anyInt());
            assertEquals(1, budget.inFlight());
        } finally {
            occupied.close();
            queueManager.clearCaches();
        }
    }

    @Test
    @DisplayName("ticket validation waits when capacity is full and scheduling throws")
    void fullCapacityWithThrowingSchedulerDoesNotLoad() {
        enqueueReservation();
        RTPWorld<?> spyWorld = spy(world);
        GenerationBudget budget = GenerationBudget.server();
        GenerationBudget.configureServerCap(() -> 1);
        GenerationBudget.Reservation occupied = budget.reserve();
        RTPScheduler scheduler = mock(RTPScheduler.class);
        doThrow(new IllegalStateException("scheduler unavailable"))
                .when(scheduler).runTaskLater(any(Runnable.class), anyLong());
        RTPRuntime.setScheduler(scheduler);

        try {
            queueManager.validateTickets(spyWorld);

            verify(scheduler).runTaskLater(any(Runnable.class), anyLong());
            verify(spyWorld, never()).getChunkAtAsync(anyInt(), anyInt());
            assertEquals(1, budget.inFlight());
            assertWarningLogged("Ticket validation sweep could not be scheduled");
        } finally {
            occupied.close();
            queueManager.clearCaches();
        }
    }

    private ChunkReservation enqueueReservation() {
        ChunkReservation reservation = mock(ChunkReservation.class);
        queueManager.keptLocations.offer(new RTPLocation(
                new RTPCoords(world.name(), nextCoordinate, 64, nextCoordinate), 1L, reservation));
        nextCoordinate += 16;
        return reservation;
    }

    private ChunkSet successfulChunkSet() {
        ChunkSet chunkSet = mock(ChunkSet.class);
        when(chunkSet.complete()).thenReturn(CompletableFuture.completedFuture(true));
        return chunkSet;
    }

    private void assertWarningLogged(String text) {
        assertTrue(serverAccessor.logMessages.stream().anyMatch(message -> message.contains(text)),
                () -> "Expected warning containing: " + text + "; got " + serverAccessor.logMessages);
    }
}
