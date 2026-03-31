package io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling;

import io.github.dailystruggle.rtp.folia_v1_20_R1.scheduling.FoliaSchedulerImpl;
import io.papermc.paper.threadedregions.scheduler.AsyncScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class FoliaSchedulerConcurrencyTest {

  private JavaPlugin plugin;
  private GlobalRegionScheduler globalScheduler;
  private AsyncScheduler asyncScheduler;
  private RegionScheduler regionScheduler;
  private FoliaSchedulerImpl scheduler;

  @BeforeEach
  void setUp() {
    plugin = mock(JavaPlugin.class);
    globalScheduler = mock(GlobalRegionScheduler.class);
    asyncScheduler = mock(AsyncScheduler.class);
    regionScheduler = mock(RegionScheduler.class);
    scheduler = new FoliaSchedulerImpl(plugin);
  }

  @RepeatedTest(100)
  void testFoliaSchedulerStress() throws InterruptedException {
    int taskCount = 1000;
    int threadCount = 64;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch startLatch = new CountDownLatch(1);
    AtomicInteger actualCount = new AtomicInteger(0);

    try (MockedStatic<Bukkit> mockedBukkit = Mockito.mockStatic(Bukkit.class)) {
      mockedBukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(globalScheduler);
      mockedBukkit.when(Bukkit::getAsyncScheduler).thenReturn(asyncScheduler);
      mockedBukkit.when(Bukkit::getRegionScheduler).thenReturn(regionScheduler);

      // We need the mock to actually execute the task to verify the count.
      // GlobalRegionScheduler.run(plugin, Consumer<ScheduledTask>)
      doAnswer(invocation -> {
        Consumer<ScheduledTask> consumer = invocation.getArgument(1);
        consumer.accept(mock(ScheduledTask.class));
        return mock(ScheduledTask.class);
      }).when(globalScheduler).run(any(), any());

      // AsyncScheduler.runNow(plugin, Consumer<ScheduledTask>)
      doAnswer(invocation -> {
        Consumer<ScheduledTask> consumer = invocation.getArgument(1);
        consumer.accept(mock(ScheduledTask.class));
        return mock(ScheduledTask.class);
      }).when(asyncScheduler).runNow(any(), any());

      for (int i = 0; i < taskCount; i++) {
        final int index = i;
        executor.submit(() -> {
          try {
            startLatch.await();
            if (index % 2 == 0) {
              scheduler.runTask(actualCount::incrementAndGet);
            } else {
              scheduler.runTaskAsynchronously(actualCount::incrementAndGet);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
      }

      startLatch.countDown();
      executor.shutdown();
      boolean finished = executor.awaitTermination(10, TimeUnit.SECONDS);

      Assertions.assertTrue(finished, "Test timed out - potential deadlock or slow execution");
      Assertions.assertEquals(taskCount, actualCount.get(), "Count mismatch - potential race condition in task submission/execution");
    } finally {
      executor.shutdownNow();
    }
  }
}
