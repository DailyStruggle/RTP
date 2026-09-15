package io.github.dailystruggle.rtp.neoforge.commands.test;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NeoForgeTestUmbrellaSchedulerTest {

  private RTPScheduler previousScheduler;

  @BeforeEach
  void setUp() {
    previousScheduler = RTP.scheduler;
  }

  @AfterEach
  void tearDown() {
    RTP.scheduler = previousScheduler;
  }

  @Test
  @DisplayName("NeoForgeTestUmbrellaScheduler implements TestUmbrellaScheduler")
  void implementsInterface() {
    NeoForgeTestUmbrellaScheduler scheduler = new NeoForgeTestUmbrellaScheduler();
    assertTrue(scheduler instanceof TestUmbrellaScheduler);
  }

  @Test
  @DisplayName("runLater throws IllegalArgumentException when task is null")
  void runLaterNullTask() {
    NeoForgeTestUmbrellaScheduler scheduler = new NeoForgeTestUmbrellaScheduler();
    assertThrows(IllegalArgumentException.class, () -> scheduler.runLater(100L, null));
  }

  @Test
  @DisplayName("runLater delegates to RTP.scheduler.runTaskTimerAsynchronously and cancels handle on fire")
  void runLaterWithScheduler() {
    RTPScheduler mockScheduler = mock(RTPScheduler.class);
    RTP.scheduler = mockScheduler;

    Object handleToken = new Object();
    ArgumentCaptor<Runnable> runnableCaptor = ArgumentCaptor.forClass(Runnable.class);
    when(mockScheduler.runTaskTimerAsynchronously(runnableCaptor.capture(), eq(2L), eq(2L)))
        .thenReturn(handleToken);

    NeoForgeTestUmbrellaScheduler scheduler = new NeoForgeTestUmbrellaScheduler();
    AtomicBoolean executed = new AtomicBoolean(false);
    scheduler.runLater(100L, () -> executed.set(true));

    verify(mockScheduler).runTaskTimerAsynchronously(any(Runnable.class), eq(2L), eq(2L));

    // Fire the captured scheduled task
    Runnable scheduledTask = runnableCaptor.getValue();
    assertNotNull(scheduledTask);
    scheduledTask.run();

    assertTrue(executed.get());
    verify(mockScheduler).cancelTask(handleToken);
  }

  @Test
  @DisplayName("runLater fallback executes inline when RTP.scheduler is null")
  void runLaterFallbackNullScheduler() {
    RTP.scheduler = null;
    NeoForgeTestUmbrellaScheduler scheduler = new NeoForgeTestUmbrellaScheduler();
    AtomicBoolean executed = new AtomicBoolean(false);

    scheduler.runLater(0L, () -> executed.set(true));
    assertTrue(executed.get());
  }

  @Test
  @DisplayName("runLater fallback executes inline when RTP.scheduler throws")
  void runLaterFallbackSchedulerThrows() {
    RTPScheduler mockScheduler = mock(RTPScheduler.class);
    when(mockScheduler.runTaskTimerAsynchronously(any(), anyLong(), anyLong()))
        .thenThrow(new RuntimeException("scheduler error"));
    RTP.scheduler = mockScheduler;

    NeoForgeTestUmbrellaScheduler scheduler = new NeoForgeTestUmbrellaScheduler();
    AtomicBoolean executed = new AtomicBoolean(false);

    scheduler.runLater(0L, () -> executed.set(true));
    assertTrue(executed.get());
  }

  @Test
  @DisplayName("runLater swallows task exceptions safely (S-004 log warning, no throw)")
  void runLaterSafeTaskException() {
    RTP.scheduler = null;
    NeoForgeTestUmbrellaScheduler scheduler = new NeoForgeTestUmbrellaScheduler();

    assertDoesNotThrow(() -> scheduler.runLater(0L, () -> {
      throw new RuntimeException("safe test failure");
    }));
  }
}
