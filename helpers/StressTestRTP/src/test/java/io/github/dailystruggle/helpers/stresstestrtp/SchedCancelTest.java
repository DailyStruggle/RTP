package io.github.dailystruggle.helpers.stresstestrtp;

import io.papermc.paper.threadedregions.FoliaStyleTasks;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Folia hands out a non-public task class behind the public ScheduledTask interface. */
class SchedCancelTest {

    private final List<LogRecord> logged = new ArrayList<>();

    @BeforeEach
    void reset() {
        Sched.resetForTest();
        logged.clear();
        Sched.logger().addHandler(new Handler() {
            @Override public void publish(LogRecord r) { logged.add(r); }
            @Override public void flush() { }
            @Override public void close() { }
        });
    }

    @Test
    @DisplayName("the old getClass().getMethod(\"cancel\").invoke() path fails on a non-public handle class")
    void oldApproachThrowsIllegalAccess() throws Exception {
        Object t = FoliaStyleTasks.task();
        var m = t.getClass().getMethod("cancel");
        assertThrows(IllegalAccessException.class, () -> m.invoke(t));
        assertEquals(0, FoliaStyleTasks.cancels(t));
    }

    @Test
    @DisplayName("Sched.cancel cancels a non-public Folia-style handle through the public interface")
    void cancelsNonPublicHandle() {
        Object t = FoliaStyleTasks.task();
        Sched.cancel(t);
        assertEquals(1, FoliaStyleTasks.cancels(t));
        assertEquals(0, Sched.timerStats(Sched.Kind.SERVICE).cancelFailures());
        assertTrue(logged.isEmpty());
    }

    @Test
    @DisplayName("Sched.cancel falls back to setAccessible when no public interface exists")
    void cancelsViaSetAccessible() {
        Object t = FoliaStyleTasks.opaque();
        Sched.cancel(t);
        assertEquals(1, FoliaStyleTasks.cancels(t));
    }

    @Test
    @DisplayName("a failing cancel is counted and logged exactly once")
    void failureCountedAndLoggedOnce() {
        Object t = FoliaStyleTasks.broken();
        Sched.cancel(t);
        Sched.cancel(t);
        Sched.cancel(t);
        assertEquals(3, Sched.timerStats(Sched.Kind.SERVICE).cancelFailures());
        assertEquals(1, logged.size());
    }

    @Test
    @DisplayName("TimerHandle cancels once, stops running the body, and live count returns to 0")
    void timerHandleLifecycle() {
        Sched.TimerStats before = Sched.timerStats(Sched.Kind.PLAYER);
        Object task = FoliaStyleTasks.task();
        AtomicInteger bodyRuns = new AtomicInteger();
        Sched.TimerHandle h = new Sched.TimerHandle(Sched.Kind.PLAYER, 0L);
        h.attach(task);
        assertEquals(1, Sched.timerStats(Sched.Kind.PLAYER).minus(before).live());

        h.fire(task, () -> { bodyRuns.incrementAndGet(); return true; });
        h.fire(task, () -> { bodyRuns.incrementAndGet(); return false; }); // asks to stop
        h.fire(task, () -> { bodyRuns.incrementAndGet(); return true; });  // leaked fire: no body work
        Sched.cancel(h);
        Sched.cancel(h);

        assertEquals(2, bodyRuns.get());
        assertEquals(1, FoliaStyleTasks.cancels(task), "cancel attempted exactly once");
        assertTrue(h.isStopped());
        assertEquals(0, Sched.timerStats(Sched.Kind.PLAYER).minus(before).live());
    }

    @Test
    @DisplayName("TimerHandle stops itself at the fire bound even when cancel() fails")
    void fireBoundStopsLeakedTask() {
        Sched.TimerStats before = Sched.timerStats(Sched.Kind.PLAYER);
        Object task = FoliaStyleTasks.broken();
        AtomicInteger bodyRuns = new AtomicInteger();
        Sched.TimerHandle h = new Sched.TimerHandle(Sched.Kind.PLAYER, 5L);
        h.attach(task);
        for (int i = 0; i < 1000; i++) h.fire(task, () -> { bodyRuns.incrementAndGet(); return true; });

        assertEquals(5, bodyRuns.get(), "body never runs past the bound");
        assertEquals(6, h.fires(), "fires stop being counted once stopped: no repeated work");
        Sched.TimerStats d = Sched.timerStats(Sched.Kind.PLAYER).minus(before);
        assertEquals(1, d.cancelFailures(), "a single cancel attempt, not one per tick");
        assertEquals(1, d.live(), "the failed cancel stays visible in the live count");
        assertFalse(logged.isEmpty());
    }

    @Test
    @DisplayName("a task retired by the scheduler is counted as cancelled; stop() before attach cancels on attach")
    void retiredAndStopBeforeAttach() {
        Sched.TimerStats before = Sched.timerStats(Sched.Kind.PLAYER);
        Sched.TimerHandle retired = new Sched.TimerHandle(Sched.Kind.PLAYER, 0L);
        retired.retired();
        retired.stop();

        Object task = FoliaStyleTasks.task();
        Sched.TimerHandle early = new Sched.TimerHandle(Sched.Kind.PLAYER, 0L);
        early.stop();
        early.attach(task);

        assertEquals(1, FoliaStyleTasks.cancels(task));
        assertEquals(0, Sched.timerStats(Sched.Kind.PLAYER).minus(before).live());
    }

    @Test
    @DisplayName("pinned watch tick bound derives from the attempt timeout unless configured")
    void pinnedWatchBound() {
        assertEquals(40L + 100L, Runner.pinnedWatchMaxTicks(5000L, 0L));
        assertEquals(40L, Runner.pinnedWatchMaxTicks(0L, 0L));
        assertEquals(7L, Runner.pinnedWatchMaxTicks(5000L, 7L));
    }
}
