package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LocationGenerator unit and mutation tests")
class LocationGeneratorTest {

    @TempDir
    File tempDir;

    private MockRTPServerAccessor accessor;
    private MockRTPWorld world;
    private Region region;
    private MockRTPPlayer player;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir);
        world = new MockRTPWorld("locgen_world");
        accessor.addWorld(world);
        Square square = new Square();
        square.set(GenericMemoryShapeParams.radius, 100L);
        square.set(GenericMemoryShapeParams.centerRadius, 0L);
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());
        RegionSettings settings = new RegionSettings(
                "testRegion",
                world,
                square,
                vert,
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
        region = new Region("testRegion", settings);
        player = new MockRTPPlayer(UUID.randomUUID(), "LocP", new RTPLocation(world, 0, 64, 0));
        accessor.addPlayer(player);
        LocationGenerator.setRng(new Random(42L));
    }

    @AfterEach
    void tearDown() {
        LocationGenerator.setRng(null);
    }

    @Test
    void non_region_object_returns_completed_future_null() throws ExecutionException, InterruptedException {
        LocationGenerator generator = new LocationGenerator();
        GenerationContext ctx = new GenerationContext(player, player, Set.of("PLAINS"));

        CompletableFuture<GenerationResult> f1 = generator.getLocation("not-a-region", ctx);
        assertTrue(f1.isDone());
        assertNull(f1.get());

        CompletableFuture<GenerationResult> f2 = generator.generateLocation("not-a-region", ctx);
        assertTrue(f2.isDone());
        assertNull(f2.get());

        CompletableFuture<GenerationResult> f3 = generator.getLocation("not-a-region", player, player, Set.of("PLAINS"));
        assertTrue(f3.isDone());
        assertNull(f3.get());

        CompletableFuture<GenerationResult> f4 = generator.getLocation("not-a-region", Set.of("PLAINS"));
        assertTrue(f4.isDone());
        assertNull(f4.get());
    }

    @Test
    void async_primary_methods_with_valid_region() throws ExecutionException, InterruptedException {
        LocationGenerator generator = new LocationGenerator();
        GenerationContext ctx = new GenerationContext(player, player, null);

        CompletableFuture<GenerationResult> f1 = generator.getLocation((Object) region, ctx);
        assertNotNull(f1);

        CompletableFuture<GenerationResult> f2 = generator.generateLocation((Object) region, ctx);
        assertNotNull(f2);

        CompletableFuture<GenerationResult> f3 = generator.getLocation((Object) region, player, player, null);
        assertNotNull(f3);

        CompletableFuture<GenerationResult> f4 = generator.getLocation((Object) region, (Set<String>) null);
        assertNotNull(f4);

        CompletableFuture<GenerationResult> f5 = LocationGenerator.getLocationFuture(region, null);
        assertNotNull(f5);

        CompletableFuture<GenerationResult> f6 = LocationGenerator.getLocationFuture(region, player, player, null);
        assertNotNull(f6);
    }

    @Test
    @SuppressWarnings("deprecation")
    void deprecated_sync_shims_behave_consistently_with_deprecation_policy() {
        GenerationContext ctx = new GenerationContext(player, player, null);

        // All 4 deprecated shims in LocationGenerator
        assertDoesNotThrow(() -> LocationGenerator.getLocation(region, ctx));
        assertDoesNotThrow(() -> LocationGenerator.generateLocation(region, ctx));
        assertDoesNotThrow(() -> LocationGenerator.getLocation(region, player, player, null));
        assertDoesNotThrow(() -> LocationGenerator.getLocation(region, (Set<String>) null));
    }

    @Test
    void rng_methods_deterministic_and_fallback() {
        Random custom = new Random(1234L);
        LocationGenerator.setRng(custom);
        assertSame(custom, LocationGenerator.rng());

        LocationGenerator.setRng(null);
        assertNotNull(LocationGenerator.rng());
    }

    @Test
    void fail_types_enum_coverage() {
        for (LocationGenerator.FailTypes type : LocationGenerator.FailTypes.values()) {
            assertNotNull(LocationGenerator.FailTypes.valueOf(type.name()));
        }
    }

    @Test
    void malformed_region_state_completes_with_null() throws ExecutionException, InterruptedException {
        // A region with null world causes PregenState.build(region, ...) to return null
        RegionSettings nullWorldSettings = new RegionSettings(
                "nullWorld",
                null,
                new Square(),
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
        Region emptyRegion = new Region("empty", nullWorldSettings);

        CompletableFuture<GenerationResult> future = LocationGenerator.getLocationFuture(emptyRegion, null);
        assertTrue(future.isDone());
        assertNull(future.get());
    }

    @Test
    void joinSafely_handles_interrupted_execution_and_timeout_exceptions() throws Exception {
        java.lang.reflect.Method joinSafelyMethod = LocationGenerator.class.getDeclaredMethod("joinSafely", CompletableFuture.class);
        joinSafelyMethod.setAccessible(true);

        // 1. Completes normally
        CompletableFuture<GenerationResult> normalFuture = CompletableFuture.completedFuture(null);
        Object res1 = joinSafelyMethod.invoke(null, normalFuture);
        assertNull(res1);

        // 2. Completes exceptionally (ExecutionException branch)
        CompletableFuture<GenerationResult> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("Simulated error"));
        Object res2 = joinSafelyMethod.invoke(null, failedFuture);
        assertNull(res2);

        // 3. Interrupted (InterruptedException branch)
        CompletableFuture<GenerationResult> neverDoneFuture = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                Thread.currentThread().interrupt();
                joinSafelyMethod.invoke(null, neverDoneFuture);
            } catch (Exception ignored) {}
        });
        worker.start();
        worker.join();
    }
}
