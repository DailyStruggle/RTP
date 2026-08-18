package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.SafetyKeys;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-005 / ADR-015 - Stale-Chunk Guard for Count-Bound Pipes.
 *
 * <p>Asserts that unloaded center chunks bypass block evaluation to prevent sync chunk loading.
 */
@DisplayName("REQ-RTP-S-005 / ADR-015: stale-chunk guard bypasses block evaluation")
public class ReqRtpS005StaleChunkGuardTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private TrackedMockWorld world;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new TrackedMockWorld("stale_chunk_guard_world");
        accessor.addWorld(world);

        // Force safetyCheck loop to actually run by setting safetyRadius > 0.
        // ConfigParser.set writes through a backing YamlFile that tests do not
        // materialise; instead, mutate the FactoryValue.data EnumMap via reflection.
        // This is in-memory only and is exactly what we want for this test.
        @SuppressWarnings("unchecked")
        ConfigParser<SafetyKeys> safety =
                (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
        try {
            java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
            dataField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.EnumMap<SafetyKeys, Object> data =
                    (java.util.EnumMap<SafetyKeys, Object>) dataField.get(safety);
            data.put(SafetyKeys.safetyRadius, 1);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to override safetyRadius for test", e);
        }

        Circle circle = new Circle();
        circle.setRng(new Random(42L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        RegionSettings settings = new RegionSettings(
                "stale_chunk_guard_region",
                world,
                circle,
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

        region = new Region("stale_chunk_guard_region", settings);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("stale center chunk => safetyCheck is skipped (isSafe never invoked)")
    void stale_center_chunk_bypasses_block_evaluation() {
        LocationGenerator.setRng(new Random(42L));

        // Simulate Folia native chunk GC: every chunk is reported as unloaded from
        // the moment we query isChunkLoaded, even though getChunkAtAsync returned
        // a successful ChunkSet a moment earlier.
        world.isChunkLoadedPredicate = key -> false;

        GenerationResult result = LocationGenerator.getLocation(region, (Set<String>) null);

        // Guard must entirely bypass MockRTPChunk.isSafe - if even one call leaked
        // through, we would have been vulnerable to the synchronous-load crash on
        // a real Folia server.
        assertEquals(0, world.isSafeCallCount.get(),
                "Block evaluation (MockRTPChunk.isSafe) must never be invoked when the "
                        + "stale-chunk guard detects an unloaded center chunk. Any non-zero "
                        + "call count would indicate the guard failed to prevent the race.");

        // Sanity: the stale-chunk guard was consulted at least once (we exercised the
        // pipeline). ADR-016 section 11 probe-first ordering (2026-04-20) means
        // `LocationGenerator` can satisfy a candidate via `getChunkAt` (anvil-backed
        // or mock-cached) without ever reaching `getChunkAtAsync`, so
        // `chunkAsyncLoadCount` is no longer a reliable "pipeline ran" proxy. The
        // guard at `LocationGenerator` line ~710 always calls `isChunkLoaded(cx, cz)`
        // before `vert.adjust(chunk)`, so `isChunkLoadedCallCount` is the
        // order-independent sanity counter.
        assertTrue(world.isChunkLoadedCallCount.get() > 0,
                "Test harness sanity: the pipeline should have consulted the "
                        + "stale-chunk guard (isChunkLoaded) at least once. If zero, the "
                        + "test is not exercising the guarded path.");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("loaded chunk => safetyCheck runs normally (baseline)")
    void loaded_chunk_allows_block_evaluation() {
        LocationGenerator.setRng(new Random(42L));

        // Default predicate (always loaded): baseline - the safetyCheck loop must run
        // and therefore MockRTPChunk.isSafe must be called at least once.
        world.isChunkLoadedPredicate = key -> true;

        LocationGenerator.getLocation(region, (Set<String>) null);

        assertTrue(world.isSafeCallCount.get() > 0,
                "Baseline: with a loaded chunk, MockRTPChunk.isSafe must be invoked by "
                        + "the safetyCheck loop. If zero, the test fixture no longer "
                        + "exercises the guarded code path and the first test would pass "
                        + "vacuously.");
    }
}
