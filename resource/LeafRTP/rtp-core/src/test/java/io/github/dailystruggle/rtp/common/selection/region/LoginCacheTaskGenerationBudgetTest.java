package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

@DisplayName("RTP-80: login promotions release budget reservations on dispatch failure")
class LoginCacheTaskGenerationBudgetTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor serverAccessor;
    private MockRTPWorld world;

    @BeforeEach
    void setUp() {
        GenerationBudget.server().resetForTesting();
        GenerationBudget.configureServerAdmission(true);
        GenerationBudget.configureServerCap(null);
        serverAccessor = RTPTestSetup.install(tempDir.toFile());
        world = new MockRTPWorld("login_budget_world");
        serverAccessor.addWorld(world);
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
    @DisplayName("RTP-80: failed login-load dispatch returns the cold entry and frees the slot")
    void dispatchFailureReleasesReservationAndRequeuesLocation() {
        GenerationBudget budget = GenerationBudget.server();
        GenerationBudget.configureServerCap(() -> 1);
        Region region = createRegion();
        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);
        RTPLocation coldLocation = new RTPLocation(
                new RTPCoords(world.name(), 16, 0, 16), 1L, null);
        region.queueManager.unkeptLocations.offer(coldLocation);

        RTPWorld<?> spyWorld = spy(world);
        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();
        doThrow(new IllegalStateException("simulated dispatch failure"))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        new LoginCacheTask(spyRegion).promoteUpTo(1);

        verify(spyWorld).getChunkAtAsync(anyInt(), anyInt());
        assertEquals(1, region.queueManager.unkeptLocations.size());
        assertEquals(0, region.inFlightCalculations.get());
        assertEquals(0, budget.inFlight());
    }

    @Test
    @DisplayName("RTP-80: null login-load future releases the reservation and requeues the location")
    void nullLoadFutureReleasesReservationAndRequeuesLocation() {
        GenerationBudget budget = GenerationBudget.server();
        Region region = createRegionWithColdLocation();
        RTPWorld<?> spyWorld = spy(world);
        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();
        doReturn(null).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        new LoginCacheTask(spyRegion).promoteUpTo(1);

        assertFailedPromotionReleased(region, budget);
    }

    @Test
    @DisplayName("RTP-80: null login-load result releases the reservation and requeues the location")
    void nullChunkSetReleasesReservationAndRequeuesLocation() {
        GenerationBudget budget = GenerationBudget.server();
        Region region = createRegionWithColdLocation();
        RTPWorld<?> spyWorld = spy(world);
        Region spyRegion = spy(region);
        doReturn(spyWorld).when(spyRegion).getWorld();
        CompletableFuture<ChunkSet> loadFuture = CompletableFuture.completedFuture(null);
        doReturn(loadFuture).when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        new LoginCacheTask(spyRegion).promoteUpTo(1);

        assertFailedPromotionReleased(region, budget);
    }

    @Test
    @DisplayName("RTP-80: null completion future releases the reservation and requeues the location")
    void nullCompletionReleasesReservationAndRequeuesLocation() {
        GenerationBudget budget = GenerationBudget.server();
        Region region = createRegionWithColdLocation();
        RTPWorld<?> spyWorld = spy(world);
        Region spyRegion = spy(region);
        ChunkSet chunkSet = mock(ChunkSet.class);
        doReturn(null).when(chunkSet).complete();
        doReturn(spyWorld).when(spyRegion).getWorld();
        doReturn(CompletableFuture.completedFuture(chunkSet))
                .when(spyWorld).getChunkAtAsync(anyInt(), anyInt());

        new LoginCacheTask(spyRegion).promoteUpTo(1);

        assertFailedPromotionReleased(region, budget);
    }

    @Test
    @DisplayName("RTP-80: empty login promotion releases its reservation without entering flight")
    void emptyUnkeptQueueReleasesReservation() {
        GenerationBudget budget = GenerationBudget.server();
        Region region = createRegion();
        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);

        new LoginCacheTask(region).promoteUpTo(1);

        assertEquals(0, region.inFlightCalculations.get());
        assertEquals(0, budget.inFlight());
        assertEquals(0, region.queueManager.unkeptLocations.size());
    }

    private Region createRegionWithColdLocation() {
        Region region = createRegion();
        region.queueManager.loginLocations = new LockFreeLocationBuffer(10);
        region.queueManager.unkeptLocations.offer(new RTPLocation(
                new RTPCoords(world.name(), 16, 0, 16), 1L, null));
        return region;
    }

    private void assertFailedPromotionReleased(Region region, GenerationBudget budget) {
        assertEquals(1, region.queueManager.unkeptLocations.size());
        assertEquals(0, region.inFlightCalculations.get());
        assertEquals(0, budget.inFlight());
    }

    private Region createRegion() {
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        RegionSettings settings = new RegionSettings(
                "login_budget_region",
                world,
                square,
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                1000L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        return new Region("login_budget_region", settings);
    }
}
