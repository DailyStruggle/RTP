package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Region-center resolution and distance bookkeeping behind the {@code <distance_center>} and
 * {@code <distance>} placeholders: chunk-unit centers on memory shapes, block-unit centers on plain
 * shapes, accepted key spellings, and non-numeric values falling back to the origin.
 */
@DisplayName("REQ-RTP-F-013 - TeleportPipelineTask region center and distance bookkeeping")
class TeleportPipelineTaskRegionCenterTest {

    @TempDir
    File tempDir;

    private TrackedMockWorld world;
    private MockRTPServerAccessor accessor;

    enum UnderscoreKeys { center_x, center_z, radius }

    enum ShortKeys { cx, cz, radius }

    /** Minimal non-memory shape: centers are taken as block coordinates. */
    static final class PlainShape<E extends Enum<E>> extends Shape<E> {
        PlainShape(Class<E> eClass, EnumMap<E, Object> data) {
            super(eClass, "plain_" + eClass.getSimpleName(), data);
        }

        @Override
        public int[] select() {
            return new int[] {0, 0};
        }

        @Override
        public Map<String, CommandParameter> getParameters() {
            return Map.of();
        }

        @Override
        public boolean contains(int x, int z) {
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
        world = new TrackedMockWorld("center_world");
        accessor = (MockRTPServerAccessor) RTPRuntime.serverAccessor();
        accessor.addWorld(world);
        RTPRunnable.scheduler = accessor.getMockScheduler();
        TeleportPipelineTask.ConfigCache.reload();
        TeleportSessions.current().latestTeleportData.clear();
        TeleportSessions.current().processingPlayers.clear();
    }

    @AfterEach
    void tearDown() {
        TeleportSessions.current().latestTeleportData.clear();
        TeleportSessions.current().processingPlayers.clear();
    }

    private RegionSettings settings(String name) {
        Circle circle = new Circle(name + "_circle");
        return new RegionSettings(name, world, circle, new LinearAdjustor(new ArrayList<>()),
                false, false, 10L, 100L, 0L, 5, 0.0, 1L, "", false);
    }

    // Parameter must not be named `shape`: Region's inherited field would shadow it in the subclass.
    private Region regionWithShape(String name, Shape<?> forcedShape) {
        return new Region(name, settings(name)) {
            @Override
            public Shape<?> getShape() {
                return forcedShape;
            }
        };
    }

    private static <E extends Enum<E>> EnumMap<E, Object> data(Class<E> type, Object x, Object z) {
        EnumMap<E, Object> map = new EnumMap<>(type);
        E[] keys = type.getEnumConstants();
        map.put(keys[0], x);
        map.put(keys[1], z);
        map.put(keys[2], 512);
        return map;
    }

    @Test
    @DisplayName("Null region and null shape resolve to the origin")
    void nullRegionAndShapeResolveToOrigin() {
        assertArrayEquals(new double[] {0.0, 0.0}, TeleportPipelineTask.getRegionCenter(null));
        assertArrayEquals(new double[] {0.0, 0.0},
                TeleportPipelineTask.getRegionCenter(regionWithShape("no_shape", null)));
    }

    @Test
    @DisplayName("Memory shape center is in chunks and converted to blocks")
    void memoryShapeCenterIsScaledToBlocks() {
        Circle circle = new Circle("chunk_center");
        circle.set(GenericMemoryShapeParams.centerX, 3);
        circle.set(GenericMemoryShapeParams.centerZ, -5);
        circle.set(GenericMemoryShapeParams.radius, 64);
        assertArrayEquals(new double[] {48.0, -80.0},
                TeleportPipelineTask.getRegionCenter(regionWithShape("chunk_center", circle)), 1e-9);
    }

    @Test
    @DisplayName("Plain shape accepts center_x / center_z in block units")
    void plainShapeUnderscoreKeys() {
        Shape<?> shape = new PlainShape<>(UnderscoreKeys.class, data(UnderscoreKeys.class, 7, 9));
        assertArrayEquals(new double[] {7.0, 9.0},
                TeleportPipelineTask.getRegionCenter(regionWithShape("underscore", shape)), 1e-9);
    }

    @Test
    @DisplayName("Plain shape accepts cx / cz in block units")
    void plainShapeShortKeys() {
        Shape<?> shape = new PlainShape<>(ShortKeys.class, data(ShortKeys.class, -2, 11.5));
        assertArrayEquals(new double[] {-2.0, 11.5},
                TeleportPipelineTask.getRegionCenter(regionWithShape("short", shape)), 1e-9);
    }

    @Test
    @DisplayName("A non-numeric center coordinate falls back to the origin")
    void nonNumericCenterFallsBackToOrigin() {
        Shape<?> zText = new PlainShape<>(ShortKeys.class, data(ShortKeys.class, 4, "north"));
        assertArrayEquals(new double[] {0.0, 0.0},
                TeleportPipelineTask.getRegionCenter(regionWithShape("z_text", zText)), 1e-9);

        Shape<?> xText = new PlainShape<>(UnderscoreKeys.class, data(UnderscoreKeys.class, "east", 6));
        assertArrayEquals(new double[] {0.0, 0.0},
                TeleportPipelineTask.getRegionCenter(regionWithShape("x_text", xText)), 1e-9);
    }

    @Test
    @DisplayName("distance_center is measured from a non-zero memory-shape center")
    void distanceFromCenterUsesScaledCenter() {
        Circle circle = new Circle("offset_center");
        circle.set(GenericMemoryShapeParams.centerX, 10);
        circle.set(GenericMemoryShapeParams.centerZ, 20);
        circle.set(GenericMemoryShapeParams.radius, 64);
        Region region = regionWithShape("offset_center", circle);

        MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "CenterPlayer",
                new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);
        // Center (160, 320); destination (460, 64, 720): hypot(300, 400) = 500.
        TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(player, player, null),
                region, new RTPCoords("center_world", 460, 64, 720));

        assertEquals(500.0, task.getDistanceFromCenter(), 1e-9);
        assertEquals("500.0", task.getPlaceholderMap().get("<distance_center>"));
    }

    @Test
    @DisplayName("Unknown destination world falls back to the region's world")
    void unknownDestinationWorldFallsBackToRegionWorld() {
        Region region = new Region("fallback_world", settings("fallback_world"));
        MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "WorldPlayer",
                new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);

        TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(player, player, null),
                region, new RTPCoords("not_loaded_world", 30, 64, 40));

        RTPLocation destination = task.getDestination();
        assertNotNull(destination);
        assertSame(world, destination.world());
        assertEquals(50.0, task.getDistance(), 1e-9);
    }

    @Test
    @DisplayName("Latest teleport data receives the computed distances")
    void latestTeleportDataReceivesDistances() {
        UUID playerId = UUID.randomUUID();
        MockRTPPlayer player = new MockRTPPlayer(playerId, "DataPlayer", new RTPLocation(world, 100, 64, 200));
        accessor.addPlayer(player);
        TeleportData data = new TeleportData();
        TeleportSessions.current().latestTeleportData.put(playerId, data);

        TeleportPipelineTask task = new TeleportPipelineTask(new GenerationContext(player, player, null),
                new Region("data_region", settings("data_region")), new RTPCoords("center_world", 400, 64, 600));

        assertEquals(500.0, task.getDistance(), 1e-9);
        assertEquals(500.0, data.distance, 1e-9);
        assertEquals(Math.hypot(400, 600), data.distanceFromCenter, 1e-9);
    }
}
