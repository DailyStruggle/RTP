package io.github.dailystruggle.rtp.neoforge.scheduling;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeSchedulerBudgetTest {

    @Test
    @DisplayName("async pool is bounded to max(2, cores/2) daemon threads; excess work queues")
    void asyncPoolIsBounded() throws InterruptedException {
        int expected = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        ThreadPoolExecutor pool = NeoForgeScheduler.asyncExecutor();
        assertEquals(NeoForgeScheduler.ASYNC_MAX_THREADS, expected);
        assertEquals(expected, pool.getMaximumPoolSize());
        assertEquals(expected, pool.getCorePoolSize());
        assertTrue(pool.allowsCoreThreadTimeOut(), "idle workers must time out");

        int submitted = expected * 3;
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(submitted);
        Set<Thread> workers = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < submitted; i++) {
            pool.execute(() -> {
                workers.add(Thread.currentThread());
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (pool.getActiveCount() < expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(pool.getPoolSize() <= expected, "pool grew past its bound: " + pool.getPoolSize());
        assertTrue(pool.getQueue().size() >= submitted - expected, "excess work must queue");
        release.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "queued work must still complete");
        assertTrue(pool.getLargestPoolSize() <= expected, "largest pool size exceeded bound");
        assertTrue(workers.size() <= expected);
        for (Thread t : workers) {
            assertTrue(t.isDaemon());
            assertTrue(t.getName().startsWith("RTP-NeoForge-Async-"), t.getName());
        }
    }

    @Test
    @DisplayName("repeating async timer skips firings while the previous one is still running")
    void asyncTimerDoesNotOverlap() throws InterruptedException {
        NeoForgeScheduler scheduler = new NeoForgeScheduler();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxActive = new AtomicInteger();
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch twoRuns = new CountDownLatch(2);
        Object task = scheduler.runTaskTimerAsynchronously(() -> {
            int now = active.incrementAndGet();
            maxActive.accumulateAndGet(now, Math::max);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                runs.incrementAndGet();
                twoRuns.countDown();
            }
        }, 0L, 1L);
        boolean completed = twoRuns.await(5, TimeUnit.SECONDS);
        Thread.sleep(300);
        scheduler.cancelTask(task);

        assertTrue(completed, "timer must keep firing after a slow callback completes");
        assertEquals(1, maxActive.get(), "a timer must never run concurrently with itself");
        assertTrue(runs.get() < 10, "50 ms period with 200 ms callback must skip firings, ran " + runs.get());
    }

    @Test
    @DisplayName("sync loop runs at least one callback per tick and carries deferred ones over in FIFO order")
    void syncLoopCarriesOverDeferredInOrder() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        scheduler.runTaskLater(() -> order.add("A"), 1L);
        scheduler.runTaskLater(() -> order.add("B"), 1L);
        scheduler.runTaskLater(() -> order.add("C"), 1L);
        scheduler.runTaskLater(() -> order.add("D"), 2L);

        scheduler.drainDue(1L);
        assertEquals(List.of("A"), order);
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "B"), order, "deferred callbacks run before newly due ones");
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "B", "C"), order);
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "B", "C", "D"), order);
        scheduler.drainDue(1L);
        assertEquals(4, order.size(), "one-shot callbacks run exactly once");
    }

    @Test
    @DisplayName("deferred periodic callback keeps its due slot and its period afterwards")
    void deferredPeriodicTaskIsNotResetOrDropped() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler();
        List<String> order = new ArrayList<>();
        scheduler.runTaskLater(() -> order.add("A"), 1L);
        Object p = scheduler.runTaskTimer(() -> order.add("P"), 1L, 2L);

        scheduler.drainDue(1L);
        assertEquals(List.of("A"), order);
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "P"), order, "deferred periodic must run on the next tick");
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "P"), order);
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "P", "P"), order, "period counts from the actual run");
        scheduler.cancelTask(p);
        scheduler.drainDue(1L);
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "P", "P"), order);
    }

    @Test
    @DisplayName("cancelled deferred callback is skipped and does not consume the tick")
    void cancelledDeferredTaskIsSkipped() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler();
        List<String> order = new ArrayList<>();
        scheduler.runTaskLater(() -> order.add("A"), 1L);
        Object b = scheduler.runTaskTimer(() -> order.add("B"), 1L, 0L);
        scheduler.runTaskLater(() -> order.add("C"), 1L);

        scheduler.drainDue(1L);
        scheduler.cancelTask(b);
        scheduler.drainDue(1L);
        assertEquals(List.of("A", "C"), order);
    }

    @Test
    @DisplayName("sync loop stops at the configured time budget and drains the rest on later ticks")
    void syncLoopStopsAtBudget() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler();
        AtomicInteger ran = new AtomicInteger();
        int total = 10;
        for (int i = 0; i < total; i++) {
            scheduler.runTaskLater(() -> {
                long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(3);
                while (System.nanoTime() < until) {
                    Thread.onSpinWait();
                }
                ran.incrementAndGet();
            }, 1L);
        }
        // Default TickBudget sync budget is 5 ms with no config loaded.
        scheduler.drainDueWithinTickBudget();
        int first = ran.get();
        assertTrue(first >= 1 && first < total, "first tick must stop at the budget, ran " + first);
        for (int i = 0; i < total && ran.get() < total; i++) {
            scheduler.drainDueWithinTickBudget();
        }
        assertEquals(total, ran.get(), "every due callback must eventually run");
    }

    @Test
    @DisplayName("generous budget runs every due callback in one tick")
    void generousBudgetRunsAll() {
        NeoForgeScheduler scheduler = new NeoForgeScheduler();
        AtomicInteger ran = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            scheduler.runTaskLater(ran::incrementAndGet, 1L);
        }
        scheduler.drainDue(Long.MAX_VALUE);
        assertEquals(20, ran.get());
    }
}
