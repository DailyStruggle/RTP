package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TimeBoundTaskPipeTest {

    @TempDir
    File tempDir;

    @BeforeEach
    void setUp() {
        RTPTestSetup.install(tempDir);
    }

    @Test
    void execute_emptyPipe_returnsTrue() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        assertTrue(pipe.execute());
    }

    @Test
    void execute_singleTask_runsAndReturnsTrue() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        AtomicBoolean ran = new AtomicBoolean(false);
        pipe.add(() -> ran.set(true));
        assertTrue(pipe.execute());
        assertTrue(ran.get());
    }

    @Test
    void execute_multipleTasksInOrder() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        List<Integer> order = new ArrayList<>();
        pipe.add(() -> order.add(1));
        pipe.add(() -> order.add(2));
        pipe.add(() -> order.add(3));
        assertTrue(pipe.execute());
        assertEquals(List.of(1, 2, 3), order);
    }

    @Test
    void execute_withSufficientTime_completesAll() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe(Long.MAX_VALUE);
        AtomicInteger count = new AtomicInteger(0);
        for (int i = 0; i < 10; i++) {
            pipe.add(count::incrementAndGet);
        }
        assertTrue(pipe.execute());
        assertEquals(10, count.get());
    }

    @Test
    void execute_withZeroTime_yieldsAfterFirstTask() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe(0);
        AtomicInteger count = new AtomicInteger(0);
        for (int i = 0; i < 5; i++) {
            pipe.add(count::incrementAndGet);
        }
        // With 0 available time, should yield (return false) after first task
        boolean completed = pipe.execute();
        // At least one task ran, but not necessarily all
        assertTrue(count.get() >= 1);
        // If not all ran, result is false
        if (count.get() < 5) assertFalse(completed);
    }

    @Test
    void stop_preventsExecution() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        AtomicBoolean ran = new AtomicBoolean(false);
        pipe.add(() -> ran.set(true));
        pipe.stop();
        assertTrue(pipe.execute()); // stopped pipe returns true immediately
        assertFalse(ran.get());
    }

    @Test
    void start_afterStop_allowsExecution() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        pipe.stop();
        pipe.start();
        AtomicBoolean ran = new AtomicBoolean(false);
        pipe.add(() -> ran.set(true));
        pipe.execute();
        assertTrue(ran.get());
    }

    @Test
    void clear_removesAllTasks() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        pipe.add(() -> {});
        pipe.add(() -> {});
        assertEquals(2, pipe.size());
        pipe.clear();
        assertEquals(0, pipe.size());
        assertTrue(pipe.execute());
    }

    @Test
    void size_reflectsQueuedTasks() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        assertEquals(0, pipe.size());
        pipe.add(() -> {});
        assertEquals(1, pipe.size());
        pipe.add(() -> {});
        assertEquals(2, pipe.size());
    }

    @Test
    void execute_throwingTask_continuesWithNext() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        AtomicBoolean secondRan = new AtomicBoolean(false);
        pipe.add(() -> { throw new RuntimeException("intentional"); });
        pipe.add(() -> secondRan.set(true));
        // Should not throw, and second task should run
        assertDoesNotThrow(() -> pipe.execute());
        assertTrue(secondRan.get());
    }

    @Test
    void getAndSetAvailableTime() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe(12345L);
        assertEquals(12345L, pipe.getAvailableTime());
        pipe.setAvailableTime(99999L);
        assertEquals(99999L, pipe.getAvailableTime());
    }

    @Test
    void execute_withExplicitTime_usesProvidedTime() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        AtomicInteger count = new AtomicInteger(0);
        for (int i = 0; i < 5; i++) {
            pipe.add(count::incrementAndGet);
        }
        pipe.execute(Long.MAX_VALUE);
        assertEquals(5, count.get());
    }

    @Test
    void cancelledRTPRunnable_isSkipped() {
        TimeBoundTaskPipe pipe = new TimeBoundTaskPipe();
        AtomicBoolean ran = new AtomicBoolean(false);

        // Create a cancellable runnable that is already cancelled
        RTPRunnable cancelled = new RTPRunnable() {
            { setCancelled(true); }
            @Override
            public void run() { ran.set(true); }
        };

        pipe.add(cancelled);
        pipe.execute();
        assertFalse(ran.get());
    }
}
