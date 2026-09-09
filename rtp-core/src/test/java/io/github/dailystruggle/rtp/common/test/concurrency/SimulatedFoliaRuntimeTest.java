package io.github.dailystruggle.rtp.common.test.concurrency;

import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SimulatedFoliaRuntime Tests (ADR-090, ADR-087)")
class SimulatedFoliaRuntimeTest {

    @Test
    @DisplayName("Regional tasks execute on bounded worker pool without exceeding thread capacity")
    void testRegionalExecutionBounded() throws InterruptedException {
        int workerThreads = 4;
        try (SimulatedFoliaRuntime runtime = new SimulatedFoliaRuntime(workerThreads)) {
            MockRTPWorld world = new MockRTPWorld();
            int taskCount = 20;
            CountDownLatch latch = new CountDownLatch(taskCount);
            AtomicInteger completedTasks = new AtomicInteger(0);

            for (int i = 0; i < taskCount; i++) {
                final int cx = i;
                final int cz = i * 2;
                runtime.runTask(world, cx, cz, () -> {
                    try {
                        Thread.sleep(10);
                        completedTasks.incrementAndGet();
                    } catch (InterruptedException ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(5, TimeUnit.SECONDS), "All regional tasks should complete");
            assertEquals(taskCount, completedTasks.get());
            assertTrue(runtime.getMaxObservedConcurrentTasks() <= workerThreads,
                    "Observed concurrent tasks (" + runtime.getMaxObservedConcurrentTasks() + ") should not exceed worker capacity (" + workerThreads + ")");
        }
    }

    @Test
    @DisplayName("MSPT simulation tracks degraded tick budgets correctly")
    void testMsptSimulation() {
        try (SimulatedFoliaRuntime runtime = new SimulatedFoliaRuntime(2)) {
            assertEquals(15, runtime.getSimulatedMspt());
            runtime.setSimulatedMspt(48);
            assertEquals(48, runtime.getSimulatedMspt());
        }
    }
}
