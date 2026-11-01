package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.selection.GenerationResult;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.RTPCoords;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Public cold fill is paced by its measured time to completion, not by a single-flight flag
 * (ADR-110). A fill's cost is dispatch to release; the cache pipe's pacer must see that cost so
 * slow or stalled fills slow the start of new ones.
 */
@DisplayName("ADR-110 cold fill paced by completion cost, not single-flight")
class RegionCacheFillPacingTest {

    private static final long PULSE_BUDGET = TimeUnit.MILLISECONDS.toNanos(25);
    private static final long SLOW_FILL_MS = 100;

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;
    private Region region;
    private ScheduledExecutorService completer;
    private volatile Supplier<CompletableFuture<GenerationResult>> nextResult;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
        MockRTPWorld world = new MockRTPWorld("fill_pacing_world");
        accessor.addWorld(world);
        Circle circle = new Circle();
        circle.setRng(new Random(7L));
        RegionSettings settings = new RegionSettings(
                "fill_pacing_region", world, circle, new LinearAdjustor(new ArrayList<>()),
                false, false, 1000L, 1000L, 0L, 5, 0.0, 1L, "", false);
        region = new Region("fill_pacing_region", settings);
        completer = Executors.newSingleThreadScheduledExecutor();
        accessor.setLocationGenerator(new SuppliedGenerator());
    }

    @AfterEach
    void tearDown() {
        completer.shutdownNow();
    }

    private CompletableFuture<GenerationResult> done() {
        return CompletableFuture.completedFuture(
                new GenerationResult(new RTPCoords("fill_pacing_world", 16, 64, 16), 1, null));
    }

    private CompletableFuture<GenerationResult> slow() {
        CompletableFuture<GenerationResult> f = new CompletableFuture<>();
        completer.schedule(() -> f.complete(
                new GenerationResult(new RTPCoords("fill_pacing_world", 32, 64, 32), 1, null)),
                SLOW_FILL_MS, TimeUnit.MILLISECONDS);
        return f;
    }

    private int pulse(TimeBoundTaskPipe pipe, int tasks) {
        int before = region.inFlightCalculations.get();
        for (int i = 0; i < tasks; i++) pipe.add(new RegionCacheTask(region, PULSE_BUDGET));
        pipe.execute(PULSE_BUDGET);
        pipe.clear();
        return region.inFlightCalculations.get() - before;
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Pending public fills do not block further fills in the same region")
    void pendingFillDoesNotSerialiseRegion() {
        CompletableFuture<GenerationResult> pending = new CompletableFuture<>();
        nextResult = () -> pending;
        new RegionCacheTask(region, PULSE_BUDGET).run();
        new RegionCacheTask(region, PULSE_BUDGET).run();
        assertEquals(2, region.inFlightCalculations.get(), "two fills must be in flight at once");
        pending.complete(null);
        assertEquals(0, region.inFlightCalculations.get(), "both fills release on completion");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("Fill wall time to completion, not dispatch time, reaches the cache pipe pacer")
    void completionWallTimeFeedsPacer() throws Exception {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        long initial = pipe.pacer().predictedCostNanos();
        nextResult = this::slow;
        assertEquals(1, pulse(pipe, 1));
        Thread.sleep(SLOW_FILL_MS * 3);
        assertEquals(0, region.inFlightCalculations.get(), "slow fill must have completed");
        long expectedFloor = initial * 7 / 8 + TimeUnit.MILLISECONDS.toNanos(SLOW_FILL_MS) / 8;
        assertTrue(pipe.pacer().predictedCostNanos() >= expectedFloor,
                "pacer must learn the ~" + SLOW_FILL_MS + " ms completion cost; predicted="
                        + pipe.pacer().predictedCostNanos() + " floor=" + expectedFloor);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @DisplayName("Fast fills start many per pulse; once fills turn slow the pacer starts fewer")
    void slowFillsSlowNewStarts() throws Exception {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        nextResult = this::done;
        for (int i = 0; i < 40; i++) pulse(pipe, 1);
        assertTrue(pipe.pacer().predictedCostNanos() < PULSE_BUDGET / 5,
                "fast fills must drive the predicted cost well under the pulse budget");

        nextResult = this::slow;
        assertEquals(5, pulse(pipe, 5), "cheap predicted cost admits the whole deficit");
        Thread.sleep(SLOW_FILL_MS * 3);
        assertEquals(0, region.inFlightCalculations.get(), "slow fills must have completed");

        assertEquals(1, pulse(pipe, 5),
                "after slow completions the pacer admits one fill per pulse (forward progress only)");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("A fill that never completes releases its slot at the fill deadline and charges the pacer")
    void neverCompletingFillReleasesAtDeadline() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        long initial = pipe.pacer().predictedCostNanos();
        CompletableFuture<GenerationResult> never = new CompletableFuture<>();
        nextResult = () -> never;
        assertEquals(1, pulse(pipe, 1));
        accessor.getMockScheduler().tick(RegionCacheTask.FILL_DEADLINE_TICKS - 1);
        assertEquals(1, region.inFlightCalculations.get(), "slot held until the deadline");
        accessor.getMockScheduler().tick(1);
        assertEquals(0, region.inFlightCalculations.get(), "deadline must release the slot");
        assertTrue(pipe.pacer().predictedCostNanos() > initial,
                "the timed-out fill must be charged to the pacer");
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    @DisplayName("A result arriving after the fill deadline is dropped, not queued")
    void lateResultAfterDeadlineIsDropped() {
        CompletableFuture<GenerationResult> late = new CompletableFuture<>();
        nextResult = () -> late;
        new RegionCacheTask(region, PULSE_BUDGET).run();
        accessor.getMockScheduler().tick(RegionCacheTask.FILL_DEADLINE_TICKS);
        long coldBefore = region.queueManager.unkeptLocations.size();
        late.complete(new GenerationResult(new RTPCoords("fill_pacing_world", 48, 64, 48), 1, null));
        assertEquals(coldBefore, (long) region.queueManager.unkeptLocations.size(),
                "late result must not enter cold after its slot was released");
        assertEquals(0, region.inFlightCalculations.get(), "no double release");
    }

    private final class SuppliedGenerator implements ILocationGenerator {
        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, GenerationContext context) {
            return nextResult.get();
        }

        @Override
        public CompletableFuture<GenerationResult> generateLocation(Object region, GenerationContext context) {
            return nextResult.get();
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(
                Object region, RTPCommandSender sender, RTPPlayer player, Set<String> biomeNames) {
            return nextResult.get();
        }

        @Override
        public CompletableFuture<GenerationResult> getLocation(Object region, Set<String> biomeNames) {
            return nextResult.get();
        }
    }
}
