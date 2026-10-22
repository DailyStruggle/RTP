package io.github.dailystruggle.rtp.common.selection.region;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
