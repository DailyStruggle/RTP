package io.github.dailystruggle.rtp.fabric.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricSchedulerTimerTest {

    @Test
    @DisplayName("RTP-3: async timer advances via wall clock even when tick is not called (paused server)")
    void asyncTimerAdvancesWithoutServerTick() throws InterruptedException {
        FabricScheduler scheduler = new FabricScheduler();
        AtomicInteger count = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        // Schedule timer: 0 tick delay, 1 tick period (50 ms)
        Object task = scheduler.runTaskTimerAsynchronously(() -> {
            count.incrementAndGet();
            latch.countDown();
        }, 0L, 1L);

        // Do NOT call scheduler.tick() - simulates empty server paused by vanilla 1.21.2+
        boolean completed = latch.await(2, TimeUnit.SECONDS);
        scheduler.cancelTask(task);

        assertTrue(completed, "Async timer must advance via wall-clock time even when server ticks are paused");
        assertTrue(count.get() >= 3, "Task must have executed at least 3 times");
    }
}
