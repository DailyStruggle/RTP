package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for the full region location-generation pipeline,
 * using only mock server components ({@link MockRTPWorld}, {@link MockRTPServerAccessor}).
 *
 * <p>All tests inject a seeded {@link java.util.Random} via
 * {@link LocationGenerator#setRng} and {@link Circle#setRng} so results are
 * fully deterministic across CI runs, eliminating RNG as a source of flakiness.
 *
 * <p>Requirements covered: REQ-RTP-F-001, REQ-RTP-F-006, REQ-CORE-F-003,
 * REQ-CORE-F-004, REQ-RTP-S-004.
 */
public class RegionPipelineTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;
    private Circle circle;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new MockRTPWorld("pipeline_world");
        accessor.addWorld(world);

        circle = new Circle();
        circle.setRng(new Random(42L));

        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "pipeline_region",
                world,
                circle,
                vert,
                false,
                false,
                10L,
                5,
                0.0,
                1L,
                "",
                false);
        region = new Region("pipeline_region", settings);
    }

    // -----------------------------------------------------------------------
    // Construction
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void region_constructs_with_correct_mock_components() {
        assertNotNull(region.getShape(), "shape must not be null");
        assertNotNull(region.getVert(), "vert must not be null");
        assertSame(world, region.getWorld(), "world must be the injected MockRTPWorld");
        assertEquals("pipeline_region", region.name);
    }

    // -----------------------------------------------------------------------
    // Queue mechanics
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 1, unit = TimeUnit.SECONDS)
    void hasLocation_false_before_any_locations_added() {
        assertFalse(region.hasLocation(null));
        assertFalse(region.hasLocation(UUID.randomUUID()));
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void unkept_location_counts_in_public_queue_length() {
        RTPCoords coords = new RTPCoords(world.name(), 8, 64, 8);
        region.queueManager.unkeptLocations.offer(new RTPLocation(coords, 1L, null));

        assertEquals(1L, region.getPublicQueueLength(),
                "unkept location must be visible in public queue length before execute()");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void execute_promotes_unkept_location_to_kept() {
        RTPCoords coords = new RTPCoords(world.name(), 8, 64, 8);
        region.queueManager.unkeptLocations.offer(new RTPLocation(coords, 1L, null));

        region.execute(Long.MAX_VALUE);

        assertFalse(region.queueManager.keptLocations.isEmpty(),
                "execute() must promote the unkept location to keptLocations");
        assertTrue(region.hasLocation(null),
                "hasLocation(null) must return true after promotion");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void public_queue_length_counts_both_kept_and_unkept() {
        region.queueManager.unkeptLocations.offer(
                new RTPLocation(new RTPCoords(world.name(), 8, 64, 8), 1L, null));
        region.queueManager.unkeptLocations.offer(
                new RTPLocation(new RTPCoords(world.name(), 32, 64, 32), 1L, null));

        assertEquals(2L, region.getPublicQueueLength(),
                "getPublicQueueLength() must count all items in unkeptLocations before execute()");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    void total_queue_length_includes_per_player_locations() {
        UUID pid = UUID.randomUUID();
        RTPCoords coords = new RTPCoords(world.name(), 8, 64, 8);

        region.queueManager.perPlayerLocationQueue
                .computeIfAbsent(pid, k -> new java.util.concurrent.ConcurrentLinkedQueue<>())
                .add(new RTPLocation(coords, 1L, null));

        assertEquals(1L, region.getPersonalQueueLength(pid),
                "personal queue length must reflect the directly-added location");
        assertEquals(1L, region.getTotalQueueLength(pid),
                "total queue length must include per-player locations");
    }

    // -----------------------------------------------------------------------
    // Full LocationGenerator pipeline — seeded RNG
    // -----------------------------------------------------------------------

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void full_pipeline_returns_non_null_coords() {
        LocationGenerator.setRng(new Random(42L));
        circle.setRng(new Random(42L));

        GenerationResult result = LocationGenerator.getLocation(region, (Set<String>) null);

        assertNotNull(result, "GenerationResult must not be null");
        assertNotNull(result.coords(),
                "result coords must not be null — pipeline must find a valid location with MockRTPWorld");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void full_pipeline_result_coords_lie_inside_circle_bounds() {
        LocationGenerator.setRng(new Random(42L));
        circle.setRng(new Random(42L));

        GenerationResult result = LocationGenerator.getLocation(region, (Set<String>) null);

        assertNotNull(result);
        RTPCoords coords = result.coords();
        assertNotNull(coords);

        int cx = coords.x() >> 4;
        int cz = coords.z() >> 4;
        assertTrue(circle.contains(cx, cz),
                "Generated chunk (" + cx + ", " + cz + ") must be inside the Circle shape bounds");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void full_pipeline_with_plains_biome_filter_succeeds() {
        LocationGenerator.setRng(new Random(42L));
        circle.setRng(new Random(42L));

        GenerationResult result = LocationGenerator.getLocation(region, Set.of("PLAINS"));

        assertNotNull(result);
        assertNotNull(result.coords(),
                "PLAINS filter must succeed: MockRTPWorld always reports PLAINS biome");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void full_pipeline_with_impossible_biome_returns_null_coords() {
        LocationGenerator.setRng(new Random(42L));
        circle.setRng(new Random(42L));

        GenerationResult result = LocationGenerator.getLocation(region, Set.of("DEEP_OCEAN"));

        assertTrue(result == null || result.coords() == null,
                "Impossible biome (MockRTPWorld only has PLAINS) must exhaust attempts and yield no location");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void same_seed_produces_identical_result() {
        LocationGenerator.setRng(new Random(99L));
        circle.setRng(new Random(99L));
        GenerationResult first = LocationGenerator.getLocation(region, (Set<String>) null);

        LocationGenerator.setRng(new Random(99L));
        circle.setRng(new Random(99L));
        GenerationResult second = LocationGenerator.getLocation(region, (Set<String>) null);

        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(first.coords());
        assertNotNull(second.coords());
        assertEquals(first.coords().x(), second.coords().x(),
                "same seed must produce same X coordinate");
        assertEquals(first.coords().z(), second.coords().z(),
                "same seed must produce same Z coordinate");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void different_seeds_produce_different_results() {
        LocationGenerator.setRng(new Random(1L));
        circle.setRng(new Random(1L));
        GenerationResult r1 = LocationGenerator.getLocation(region, (Set<String>) null);

        LocationGenerator.setRng(new Random(999_999L));
        circle.setRng(new Random(999_999L));
        GenerationResult r2 = LocationGenerator.getLocation(region, (Set<String>) null);

        assertNotNull(r1);
        assertNotNull(r2);
        assertNotNull(r1.coords());
        assertNotNull(r2.coords());
        assertFalse(
                r1.coords().x() == r2.coords().x() && r1.coords().z() == r2.coords().z(),
                "Different seeds should produce different locations");
    }
}
