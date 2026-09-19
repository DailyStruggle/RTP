package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys;
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
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-004 / ADR-016 verification: nullChunk attribution in pregen diagnostics.
 * Asserts that rejected/null chunk loads are tracked in {@link LocationGenerator.FailTypes#nullChunk}
 * with {@code reason=asyncLoadNull} rather than silently dropped.
 */
@DisplayName("REQ-RTP-S-004 / ADR-016: null-chunk drops are attributed to FailTypes.nullChunk")
public class ReqRtpS004NullChunkAttributionTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private TrackedMockWorld world;
    private Region region;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        accessor.setLocationGenerator(new LocationGenerator());

        world = new TrackedMockWorld("null_chunk_attribution_world");
        accessor.addWorld(world);

        // Override config keys via in-memory reflection:
        // SafetyKeys.safetyRadius=0: skip neighbour-chunk safety loop.
        // LoggingKeys.selection_failure=true: enable verbose pregen summary log output.
        try {
            java.lang.reflect.Field dataField = FactoryValue.class.getDeclaredField("data");
            dataField.setAccessible(true);

            @SuppressWarnings("unchecked")
            ConfigParser<SafetyKeys> safety =
                    (ConfigParser<SafetyKeys>) RTP.configs.getParser(SafetyKeys.class);
            @SuppressWarnings("unchecked")
            java.util.EnumMap<SafetyKeys, Object> safetyData =
                    (java.util.EnumMap<SafetyKeys, Object>) dataField.get(safety);
            safetyData.put(SafetyKeys.safetyRadius, 0);

            @SuppressWarnings("unchecked")
            ConfigParser<LoggingKeys> logging =
                    (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
            @SuppressWarnings("unchecked")
            java.util.EnumMap<LoggingKeys, Object> loggingData =
                    (java.util.EnumMap<LoggingKeys, Object>) dataField.get(logging);
            loggingData.put(LoggingKeys.selection_failure, true);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to override config keys for test", e);
        }

        Circle circle = new Circle();
        circle.setRng(new Random(42L));
        LinearAdjustor vert = new LinearAdjustor(new ArrayList<>());

        // verbose=true (param 7) so the pregen summary line is emitted and we can
        // assert on cause=nullChunk attribution.
        RegionSettings settings = new RegionSettings(
                "null_chunk_attribution_region",
                world,
                circle,
                vert,
                false,
                true,  // <-- verbose
                10L,
                1000L,
                 0L,
                5,
                0.0,
                1L,
                "",
                false);

        region = new Region("null_chunk_attribution_region", settings);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("every candidate returns null key => bucketed as cause=nullChunk reason=asyncLoadNull")
    void null_key_candidates_are_attributed_to_nullChunk_bucket() {
        LocationGenerator.setRng(new Random(42L));

        // Simulate the ADR-016 Anvil pre-filter REJECT path: every chunk key resolves
        // to null, just like BukkitRTPWorld.getChunkAt does when AnvilPrefilter.probe()
        // returns Verdict.REJECT.
        world.nullChunkKeyPredicate = key -> true;
        accessor.logMessages.clear();

        GenerationResult result = LocationGenerator.getLocation(region, (Set<String>) null);

        // No location can be found if every candidate is null-chunked.
        assertNotNull(result, "Generator must return a GenerationResult envelope even on total failure.");
        assertNull(result.coords(), "Coords must be null when every candidate was null-chunked.");

        // The safetyCheck loop must never run - chunk is null before we reach it.
        assertEquals(0, world.isSafeCallCount.get(),
                "MockRTPChunk.isSafe must never be invoked when the async load resolves to null.");

        // REQ-RTP-S-004 attribution: the pregen summary must explicitly report the
        // drops under cause=nullChunk reason=asyncLoadNull. Before the fix, the
        // drops were invisible - this assertion is the regression guard.
        List<String> logs;
        synchronized (accessor.logMessages) {
            logs = new ArrayList<>(accessor.logMessages);
        }
        boolean sawNullChunkBucket = logs.stream().anyMatch(
                s -> s.contains("cause=nullChunk") && s.contains("reason=asyncLoadNull"));
        assertTrue(sawNullChunkBucket,
                "Pregen summary must attribute null-chunk drops to cause=nullChunk "
                        + "reason=asyncLoadNull. Captured log:\n  " + String.join("\n  ", logs));

        // Sanity: we actually exercised the async chunk load path.
        assertTrue(world.chunkAsyncLoadCount.get() > 0,
                "Test harness sanity: pipeline should have attempted at least one async load.");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("baseline: non-null keys => no nullChunk attribution emitted")
    void healthy_candidates_do_not_produce_nullChunk_attribution() {
        LocationGenerator.setRng(new Random(42L));

        // Default predicate: every chunk resolves to a real key.
        world.nullChunkKeyPredicate = key -> false;
        accessor.logMessages.clear();

        LocationGenerator.getLocation(region, (Set<String>) null);

        List<String> logs;
        synchronized (accessor.logMessages) {
            logs = new ArrayList<>(accessor.logMessages);
        }
        boolean sawNullChunkBucket = logs.stream().anyMatch(s -> s.contains("cause=nullChunk"));
        assertTrue(!sawNullChunkBucket,
                "Baseline: with healthy chunk loads, cause=nullChunk must not be reported. "
                        + "Captured log:\n  " + String.join("\n  ", logs));
    }
}
