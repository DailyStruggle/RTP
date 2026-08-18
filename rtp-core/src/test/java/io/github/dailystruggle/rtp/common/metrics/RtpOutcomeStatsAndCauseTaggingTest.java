package io.github.dailystruggle.rtp.common.metrics;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the always-on location-generation outcome metrics and the per-run
 * rejection-cause tagging of {@code MemoryShape}'s bad-location cache.
 *
 * <p>The pipeline records every terminal attempt outcome into
 * {@link RtpOutcomeStats#GLOBAL} regardless of {@code verbose}, and persists one
 * rejection cause per coalesced bad-location run (first-cause-wins).
 */
public class RtpOutcomeStatsAndCauseTaggingTest {

    static {
        MockRTPServerAccessor accessor =
                new MockRTPServerAccessor(new java.io.File("target/test-data"));
        RTP.serverAccessor = accessor;
        io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor = accessor;
    }

    private static final byte BIOME = (byte) LocationGenerator.FailTypes.biome.ordinal();
    private static final byte SAFETY_EXTERNAL =
            (byte) LocationGenerator.FailTypes.safetyExternal.ordinal();
    private static final byte MISC = (byte) LocationGenerator.FailTypes.misc.ordinal();

    private Circle shape;

    @BeforeEach
    void setUp() {
        shape = new Circle();
        shape.set(GenericMemoryShapeParams.radius, 200L);
        shape.set(GenericMemoryShapeParams.centerRadius, 0L);
    }

    // ------------------------------------------------------------------------
    // RtpOutcomeStats accumulator
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("RtpOutcomeStats tracks success/failure counts, breakdown and rate")
    void outcomeStats_tracksCountsAndRate() {
        RtpOutcomeStats stats = new RtpOutcomeStats();
        stats.recordSuccess();
        stats.recordSuccess();
        stats.recordSuccess();
        stats.recordFailure(LocationGenerator.FailTypes.biome);
        stats.recordFailure(LocationGenerator.FailTypes.biome);
        stats.recordFailure(LocationGenerator.FailTypes.safetyExternal);
        stats.recordFailure(null); // no-op

        assertEquals(3L, stats.successCount());
        assertEquals(2L, stats.failureCount(LocationGenerator.FailTypes.biome));
        assertEquals(1L, stats.failureCount(LocationGenerator.FailTypes.safetyExternal));
        assertEquals(0L, stats.failureCount(LocationGenerator.FailTypes.timeout));
        assertEquals(3L, stats.totalFailures());
        assertEquals(0.5d, stats.successRate(), 1e-9, "3 successes of 6 outcomes = 0.5");

        assertEquals(2L,
                stats.failureBreakdown().get(LocationGenerator.FailTypes.biome).longValue());

        stats.reset();
        assertEquals(0L, stats.successCount());
        assertEquals(0L, stats.totalFailures());
        assertTrue(Double.isNaN(stats.successRate()), "rate is NaN with no outcomes");
    }

    // ------------------------------------------------------------------------
    // Per-run cause tagging through rebuild
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("rebuild keeps one cause per distinct bad-location run, aligned to keys")
    void rebuild_keepsCausePerRun() {
        shape.addBadLocation(10L, LocationGenerator.FailTypes.biome);
        shape.addBadLocation(1000L, LocationGenerator.FailTypes.safetyExternal);
        shape.flushAndRebuild(1L);

        long[] keys = shape.badKeysSnapshot();
        byte[] causes = shape.badCausesSnapshot();
        assertEquals(2, keys.length, "two well-separated runs expected");
        assertEquals(keys.length, causes.length, "cause array aligns 1:1 with keys");
        assertEquals(10L, keys[0]);
        assertEquals(1000L, keys[1]);
        assertEquals(BIOME, causes[0]);
        assertEquals(SAFETY_EXTERNAL, causes[1]);
    }

    @Test
    @DisplayName("untagged addBadLocation defaults the run cause to misc")
    void rebuild_defaultCauseIsMisc() {
        shape.addBadLocation(5L);
        shape.flushAndRebuild(1L);

        byte[] causes = shape.badCausesSnapshot();
        assertEquals(1, causes.length);
        assertEquals(MISC, causes[0]);
    }

    @Test
    @DisplayName("coalesced runs keep the first run's cause (first-cause-wins)")
    void rebuild_coalescedRunKeepsFirstCause() {
        shape.addBadLocation(10L, LocationGenerator.FailTypes.biome);
        shape.addBadLocation(11L, LocationGenerator.FailTypes.safety);
        // spatialResolution 5 coalesces 10 and 11 into a single run.
        shape.flushAndRebuild(5L);

        long[] keys = shape.badKeysSnapshot();
        byte[] causes = shape.badCausesSnapshot();
        assertEquals(1, keys.length, "the two adjacent indices coalesce into one run");
        assertEquals(BIOME, causes[0], "first-cause-wins keeps biome over safety");
    }

    // ------------------------------------------------------------------------
    // Versioned .bin round-trip
    // ------------------------------------------------------------------------

    @Test
    @DisplayName("save/load round-trips the per-run cause through the v2 .bin format")
    void binRoundTrip_preservesCause() {
        shape.addBadLocation(10L, LocationGenerator.FailTypes.biome);
        shape.addBadLocation(1000L, LocationGenerator.FailTypes.safetyExternal);
        shape.flushAndRebuild(1L);

        String file = "causeRoundTrip";
        String world = "causeWorld";
        shape.save(file, world);

        Circle reloaded = new Circle();
        reloaded.set(GenericMemoryShapeParams.radius, 200L);
        reloaded.set(GenericMemoryShapeParams.centerRadius, 0L);
        reloaded.load(file, world).join();

        long[] keys = reloaded.badKeysSnapshot();
        byte[] causes = reloaded.badCausesSnapshot();
        assertEquals(2, keys.length);
        assertEquals(keys.length, causes.length);
        assertEquals(BIOME, causes[0]);
        assertEquals(SAFETY_EXTERNAL, causes[1]);
    }
}
