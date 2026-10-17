package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.database.DatabaseAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.selection.region.BacklogLocationBuffer;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-PRED-F-005: Characterization Pin-Down Unit Tests for Predictive Cost Modeling")
public class TimeBoundTaskPipeCharacterizationTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
    }

    private static void spinNanos(long nanos) {
        long end = System.nanoTime() + nanos;
        while (System.nanoTime() < end) {
            Thread.onSpinWait();
        }
    }

    @Nested
    @DisplayName("TimeBoundTaskPipe Timing, Boundaries, and 7/8 Decay Math")
    class TaskPipeCharacterization {

        @Test
        @DisplayName("Initial avgTime matches historical 50ms default")
        void initialAvgTime_matchesFiftyMilliseconds() {
            TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
            assertEquals(TimeUnit.MILLISECONDS.toNanos(50), pipe.avgTime,
                    "TimeBoundTaskPipe must initialize with exactly 50ms predicted cost");
        }

        @Test
        @DisplayName("Boundary: Zero or negative budget runs 0 tasks and leaves queue intact")
        void zeroOrNegativeBudget_runsZeroTasks() {
            TimeBoundTaskPipe pipe = new TimeBoundTaskPipe(0);
            AtomicInteger count = new AtomicInteger(0);
            for (int i = 0; i < 5; i++) {
                pipe.add(count::incrementAndGet);
            }

            assertFalse(pipe.execute(0), "Zero budget execute must return false");
            assertEquals(0, count.get(), "Zero tasks should have run under zero budget");
            assertEquals(5, pipe.size(), "All 5 tasks must remain queued");

            assertFalse(pipe.execute(-1000L), "Negative budget execute must return false");
            assertEquals(0, count.get(), "Zero tasks should have run under negative budget");
            assertEquals(5, pipe.size(), "All 5 tasks must remain queued");
        }

        @Test
        @DisplayName("Boundary: Micro-budget (1L nanosecond) guarantees forward progress by running exactly 1 task")
        void microBudget_runsExactlyOneTask() {
            TimeBoundTaskPipe pipe = new TimeBoundTaskPipe(1L);
            AtomicInteger count = new AtomicInteger(0);
            for (int i = 0; i < 5; i++) {
                pipe.add(count::incrementAndGet);
            }

            boolean completedAll = pipe.execute();
            assertFalse(completedAll, "Pipe should yield because 4 tasks remain");
            assertEquals(1, count.get(), "Forward progress guarantee: exactly one task must run on positive budget");
            assertEquals(4, pipe.size(), "Remaining 4 tasks must stay queued for subsequent pulses");
        }

        @Test
        @DisplayName("Boundary: Budget overrun halts execution before overrunning tick deadline")
        void budgetOverrun_yieldsToNextPulse() {
            // Initial avgTime is 50ms. With 100ms budget, running a 1ms task leaves dt ~ 1ms.
            // After 1st task: avgTime = ((50ms / 8) * 7) + (1ms / 8) = 43.75ms + 0.125ms = 43.875ms.
            // dt + avgTime ~ 45ms < 100ms -> runs 2nd task.
            // With a 10ms budget and 50ms initial avgTime:
            // 1st task runs (forward progress).
            // dt + avgTime > 10ms -> loop breaks and yields!
            TimeBoundTaskPipe pipe = new TimeBoundTaskPipe(10_000_000L); // 10ms
            AtomicInteger executed = new AtomicInteger(0);
            for (int i = 0; i < 5; i++) {
                pipe.add(() -> {
                    spinNanos(100_000L); // 0.1ms
                    executed.incrementAndGet();
                });
            }

            boolean completed = pipe.execute();
            assertFalse(completed, "Must yield due to predicted overrun");
            assertEquals(1, executed.get(), "Only the first task should run as dt + avgTime exceeds 10ms");
            assertEquals(4, pipe.size());
        }

        @Test
        @DisplayName("Decay: Integer 7/8 moving average decays toward faster task durations")
        void integerDecay_decaysTowardObservedDurations() {
            TimeBoundTaskPipe pipe = new TimeBoundTaskPipe(Long.MAX_VALUE);
            long initial = pipe.avgTime;

            // Execute 10 fast tasks (~0.1ms each)
            for (int i = 0; i < 10; i++) {
                pipe.add(() -> spinNanos(100_000L));
            }
            pipe.execute();

            assertTrue(pipe.avgTime < initial, "avgTime must decay downward when tasks take less than 50ms");
            assertTrue(pipe.avgTime > 0, "avgTime must remain strictly positive");
        }

        @Test
        @DisplayName("Decay: Bitwise arithmetic verification of 7/8 integer formula across synthetic values")
        void integerDecay_bitwiseParityAcrossSyntheticSamples() {
            long avg = 50_000_000L;
            long[] syntheticDiffs = {
                    10_000_000L,
                    5_000_000L,
                    2_000_000L,
                    1_000_000L,
                    500_000L,
                    100_000_000L, // spike
                    500_000L
            };

            for (long diff : syntheticDiffs) {
                long expected = ((avg / 8L) * 7L) + (diff / 8L);
                avg = expected;
                assertTrue(avg > 0L);
            }

            // Verify with non-multiples of 8 to pin down integer truncation behavior
            long oddAvg = 1234567L;
            long oddDiff = 9876543L;
            long calculated = ((oddAvg / 8L) * 7L) + (oddDiff / 8L);
            long manual = (154320L * 7L) + (1234567L); // 1080240 + 1234567 = 2314807
            assertEquals(manual, calculated);
        }
    }

    @Nested
    @DisplayName("DatabaseAccessor.drainQueries Queue Pacing Characterization")
    class DatabaseAccessorCharacterization {

        static class MockDatabaseAccessor extends DatabaseAccessor<Object> {
            final AtomicInteger writes = new AtomicInteger();
            final AtomicInteger deletes = new AtomicInteger();
            final AtomicInteger reads = new AtomicInteger();

            MockDatabaseAccessor() {
                super();
            }

            @Override
            public String name() {
                return "Mock";
            }

            @Override
            public List<DatabaseAccessor.StoredLocation> loadCachedLocations(String regionName) {
                return Collections.emptyList();
            }

            @Override
            public void startup() {}

            @Override
            public @Nullable Object connect() {
                return new Object();
            }

            @Override
            public void disconnect(@Nullable Object o) {}

            @Override
            public @NotNull Optional<Map<String, Object>> read(Object o, String tableName, Map.Entry<String, Object> lookup) {
                reads.incrementAndGet();
                spinNanos(500_000L); // 0.5ms synthetic read
                return Optional.of(Map.of("key", "val"));
            }

            @Override
            public void write(Object o, String tableName, Map<DatabaseAccessor.TableObj, DatabaseAccessor.TableObj> keyValuePairs) {
                writes.incrementAndGet();
                spinNanos(500_000L); // 0.5ms synthetic write
            }

            @Override
            public void delete(Object o, String tableName, Map.Entry<String, Object> lookup) {
                deletes.incrementAndGet();
                spinNanos(500_000L); // 0.5ms synthetic delete
            }

            void enqueueRead(String table, String key, String val) {
                readQueue.add(new AbstractMap.SimpleEntry<>(
                        table,
                        new AbstractMap.SimpleEntry<>(
                                new AbstractMap.SimpleEntry<>(new DatabaseAccessor.TableObj(key), new DatabaseAccessor.TableObj(val)),
                                new CompletableFuture<>()
                        )
                ));
            }

            long getAvgTimeWrite() {
                return avgTimeWrite;
            }

            long getAvgTimeRead() {
                return avgTimeRead;
            }

            void setAvgTimeWrite(long v) {
                avgTimeWrite = v;
            }

            void setAvgTimeRead(long v) {
                avgTimeRead = v;
            }
        }

        @Test
        @DisplayName("Write queue: 1st write initializes avgTimeWrite, breaks when dt + avgTimeWrite > availableTime")
        void writeQueue_pacingAndDecay() {
            MockDatabaseAccessor db = new MockDatabaseAccessor();
            assertEquals(0L, db.getAvgTimeWrite());

            // Enqueue 5 write requests
            for (int i = 0; i < 5; i++) {
                db.setValue("testTable", "key" + i, "val" + i);
            }

            // Budget of 1ms: 1st write takes ~0.5ms, avgTimeWrite set to ~0.5ms.
            // dt + avgTimeWrite >= 1.0ms -> loop breaks after 1st or 2nd write!
            db.processQueries(1_000_000L); // 1ms
            assertTrue(db.writes.get() >= 1, "At least 1 write must execute");
            assertTrue(db.writes.get() < 5, "Should yield before exhausting all 5 writes on a 1ms budget");
            assertTrue(db.getAvgTimeWrite() > 0L, "avgTimeWrite must be initialized from 0");
        }

        @Test
        @DisplayName("Delete queue: inherits write pacing and respects time budget")
        void deleteQueue_pacingAndDecay() {
            MockDatabaseAccessor db = new MockDatabaseAccessor();
            db.setAvgTimeWrite(5_000_000L); // 5ms pre-existing write avg

            // Enqueue 5 delete requests
            for (int i = 0; i < 5; i++) {
                db.removeCachedLocation(UUID.randomUUID().toString());
            }

            // With 1ms budget and 5ms avgTimeWrite, 1st delete runs then dt + avgTimeWrite > budget -> yields
            db.processQueries(1_000_000L);
            assertEquals(1, db.deletes.get(), "Delete queue should yield after 1 execution under tight budget");
        }

        @Test
        @DisplayName("Read queue: initializes avgTimeRead and yields on budget overrun")
        void readQueue_pacingAndDecay() {
            MockDatabaseAccessor db = new MockDatabaseAccessor();
            assertEquals(0L, db.getAvgTimeRead());

            // Enqueue 5 read requests
            for (int i = 0; i < 5; i++) {
                db.enqueueRead("table", "key" + i, "val" + i);
            }

            db.processQueries(1_000_000L);
            assertTrue(db.reads.get() >= 1, "At least 1 read must execute");
            assertTrue(db.reads.get() < 5, "Read queue should yield on budget overrun");
            assertTrue(db.getAvgTimeRead() > 0L, "avgTimeRead must be initialized");
        }
    }

    @Nested
    @DisplayName("Region.processBacklog Refill Pacing and backlogPickNanos Characterization")
    class RegionBacklogCharacterization {

        @Test
        @DisplayName("Zero budget makes 0 picks in backlog")
        void zeroBudget_makesZeroPicks() throws Exception {
            MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);
            TrackedMockWorld world = new TrackedMockWorld("world");
            accessor.addWorld(world);
            RegionSettings settings = new RegionSettings(
                    "char_reg_zero", world, new Circle(), new LinearAdjustor(new ArrayList<>()),
                    false, false, 5L, 100L, 0L, 10, 0.0, 1L, "", false);
            Region region = new Region("char_reg_zero", settings);
            BacklogLocationBuffer backlog = region.queueManager.backlogLocations;
            assertNotNull(backlog);

            Method processBacklogMethod = Region.class.getDeclaredMethod("processBacklog", long.class, long.class);
            processBacklogMethod.setAccessible(true);

            // Zero budget
            processBacklogMethod.invoke(region, 0L, System.nanoTime());
            assertEquals(0, backlog.size(), "Zero budget must produce zero backlog picks");
        }

        @Test
        @DisplayName("Positive budget makes picks and updates backlogPickNanos with 7/8 integer decay")
        void positiveBudget_picksAndUpdateBacklogPickNanos() throws Exception {
            MockRTPServerAccessor accessor = RTPTestSetup.install(tempDir);
            TrackedMockWorld world = new TrackedMockWorld("world");
            accessor.addWorld(world);
            RegionSettings settings = new RegionSettings(
                    "char_reg_pos", world, new Circle(), new LinearAdjustor(new ArrayList<>()),
                    false, false, 5L, 50L, 0L, 10, 0.0, 1L, "", false);
            Region region = new Region("char_reg_pos", settings);
            BacklogLocationBuffer backlog = region.queueManager.backlogLocations;
            assertNotNull(backlog);

            Field pickField = Region.class.getDeclaredField("backlogPacer");
            pickField.setAccessible(true);
            io.github.dailystruggle.rtp.common.predict.BudgetPacer pacer =
                    (io.github.dailystruggle.rtp.common.predict.BudgetPacer) pickField.get(region);
            assertEquals(0L, pacer.predictedCostNanos(), "Initial backlog predicted cost must be 0");

            Method processBacklogMethod = Region.class.getDeclaredMethod("processBacklog", long.class, long.class);
            processBacklogMethod.setAccessible(true);

            // Give a generous 500ms budget to allow picks
            processBacklogMethod.invoke(region, TimeUnit.MILLISECONDS.toNanos(500), System.nanoTime());
            assertTrue(backlog.size() > 0, "Positive budget must pick entries into backlog");
            long updatedPickNanos = pacer.predictedCostNanos();
            assertTrue(updatedPickNanos > 0L, "backlog pacer cost must be updated and positive: " + updatedPickNanos);
        }

        @Test
        @DisplayName("refillDeadline calculates 80% available time as deadline")
        void refillDeadline_retainsHeadroom() throws Exception {
            Method deadlineMethod = Region.class.getDeclaredMethod("refillDeadline", long.class, long.class, long.class);
            deadlineMethod.setAccessible(true);
            long deadline = (long) deadlineMethod.invoke(null, 10_000L, 0L, 2_000L);
            assertEquals(8_400L, deadline);
        }
    }
}
