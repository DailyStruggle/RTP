package io.github.dailystruggle.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ManagedBlockingIoExecutorTest {

  @Test
  void shutdownAndDrainAwaitsInFlightWorkAndRejectsSubsequentSubmissions() throws Exception {
    ManagedBlockingIoExecutor executor = new ManagedBlockingIoExecutor("TestServer", 2, Thread.NORM_PRIORITY);
    CountDownLatch started = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean completed = new AtomicBoolean(false);
    AtomicReference<String> workerName = new AtomicReference<>();

    executor.execute(
        () -> {
          workerName.set(Thread.currentThread().getName());
          started.countDown();
          try {
            release.await(2, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          completed.set(true);
        });

    assertTrue(started.await(2, TimeUnit.SECONDS));
    assertTrue(workerName.get().startsWith("TestServer-IO-"));

    Thread releaser =
        new Thread(
            () -> {
              try {
                Thread.sleep(60L);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              release.countDown();
            });
    releaser.start();

    assertTrue(executor.shutdownAndDrain(Duration.ofSeconds(2)));
    assertTrue(completed.get(), "shutdownAndDrain must await in-flight I/O task");
    assertTrue(executor.isShutdown());
    assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));
  }

  @Test
  void reopenAllowsSubsequentExecutionAfterDrain() throws Exception {
    ManagedBlockingIoExecutor executor = new ManagedBlockingIoExecutor("ReloadServer", 1, Thread.NORM_PRIORITY);
    assertTrue(executor.shutdownAndDrain(Duration.ofSeconds(1)));
    assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> {}));

    executor.reopen();
    assertFalse(executor.isShutdown());
    CountDownLatch ran = new CountDownLatch(1);
    executor.execute(ran::countDown);
    assertTrue(ran.await(2, TimeUnit.SECONDS));
    assertEquals(true, executor.shutdownAndDrain(Duration.ofSeconds(1)));
  }
}
