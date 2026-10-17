package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.common.tools.PlaceholderProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("REQ-RTP-F-013 - TeleportPipelineTask arrival distance placeholder tests")
class TeleportPipelineTaskDistanceTest {

    @TempDir
    File tempDir;

    private TrackedMockWorld world;
    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        world = new TrackedMockWorld("test_world");
        accessor = (MockRTPServerAccessor) RTP.serverAccessor;
        accessor.addWorld(world);
        RTPRunnable.scheduler = accessor.getMockScheduler();
        TeleportPipelineTask.ConfigCache.reload();
        RTP.getInstance().latestTeleportData.clear();
        RTP.getInstance().processingPlayers.clear();
    }

    @AfterEach
    void tearDown() {
        RTP.getInstance().latestTeleportData.clear();
        RTP.getInstance().processingPlayers.clear();
    }

    @Test
    @DisplayName("Assert placeholder replacement produces expected formatted distance and distance_blocks")
    void testDistancePlaceholderReplacement() {
        UUID playerId = UUID.randomUUID();
        // Origin at (100, 64, 200)
        RTPLocation origin = new RTPLocation(world, 100, 64, 200);
        MockRTPPlayer player = new MockRTPPlayer(playerId, "TestPlayer", origin);
        accessor.addPlayer(player);

        GenerationContext ctx = new GenerationContext(player, player, null);

        // Region with center at chunk (0, 0) -> block (0, 0)
        Circle circle = new Circle("test_circle");
        circle.getData().put(GenericMemoryShapeParams.centerX, 0);
        circle.getData().put(GenericMemoryShapeParams.centerZ, 0);
        RegionSettings settings = new RegionSettings(
                "test_region",
                world,
                circle,
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region region = new Region("test_region", settings);

        // Destination at (400, 64, 600)
        // dx = 400 - 100 = 300, dz = 600 - 200 = 400
        // distance = hypot(300, 400) = 500.0
        RTPCoords destination = new RTPCoords("test_world", 400, 64, 600);

        TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, destination);
        assertEquals(500.0, task.getDistance(), 0.001);

        // Distance from region center (0, 0): hypot(400, 600) = 721.110255...
        double expectedCenterDist = Math.hypot(400, 600);
        assertEquals(expectedCenterDist, task.getDistanceFromCenter(), 0.001);

        // Verify placeholders in task.getPlaceholderMap()
        assertEquals("500.0", task.getPlaceholderMap().get("<distance>"));
        assertEquals("500", task.getPlaceholderMap().get("<distance_blocks>"));

        // Verify placeholder replacement in message string
        String template = "Arrived at <distance> blocks (<distance_blocks> rounded)! Center: <distance_center>";
        String replaced = task.formatWithPlaceholders(template, playerId);
        assertTrue(replaced.contains("500.0 blocks (500 rounded)!"));
        assertTrue(replaced.contains("721.1"));

        // Store teleport data to verify PlaceholderProvider global replacement
        TeleportData data = new TeleportData();
        data.distance = task.getDistance();
        data.distanceFromCenter = task.getDistanceFromCenter();
        RTP.getInstance().latestTeleportData.put(playerId, data);

        // Verify PlaceholderProvider replacement as well
        String globalTemplate = "Global: <distance> blocks, %distance% / [distance] | <distance_blocks>";
        String globalReplaced = PlaceholderProvider.fillPlaceholders(globalTemplate, playerId);
        assertEquals("Global: 500.0 blocks, 500.0 / 500.0 | 500", globalReplaced);
    }

    @Test
    @DisplayName("Assert decimal distance formatting with 1 decimal place")
    void testFractionalDistanceFormatting() {
        UUID playerId = UUID.randomUUID();
        // Origin at (0, 64, 0)
        RTPLocation origin = new RTPLocation(world, 0, 64, 0);
        MockRTPPlayer player = new MockRTPPlayer(playerId, "FractionalPlayer", origin);
        accessor.addPlayer(player);

        GenerationContext ctx = new GenerationContext(player, player, null);
        Circle circle = new Circle("frac_circle");
        RegionSettings settings = new RegionSettings(
                "frac_region",
                world,
                circle,
                new LinearAdjustor(new ArrayList<>()),
                false,
                false,
                10L,
                100L,
                0L,
                5,
                0.0,
                1L,
                "",
                false);
        Region region = new Region("frac_region", settings);

        // Destination at (1000, 64, 1009)
        // hypot(1000, 1009) = 1420.5988... -> 1420.6 rounded to 1 decimal place, 1421 blocks rounded
        RTPCoords destination = new RTPCoords("test_world", 1000, 64, 1009);

        TeleportPipelineTask task = new TeleportPipelineTask(ctx, region, destination);

        assertEquals("1420.6", task.getPlaceholderMap().get("<distance>"));
        assertEquals("1421", task.getPlaceholderMap().get("<distance_blocks>"));

        String msg = "Teleported <distance> blocks away (<distance_blocks>)";
        String formatted = task.formatWithPlaceholders(msg, playerId);
        assertEquals("Teleported 1420.6 blocks away (1421)", formatted);
    }
}
