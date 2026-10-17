package io.github.dailystruggle.rtp.folia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR-112 per-region-thread budget: one syncAllottedTime of RTP work per region tick, no catch-up. */
class RegionThreadBudgetTest {
  private static final long BUDGET = TimeUnit.MILLISECONDS.toNanos(5);
  private static final long TICK = RegionThreadBudget.TICK_NANOS;

  private final AtomicLong clock = new AtomicLong(1_000_000_000L);
  private final RegionThreadBudget budget = new RegionThreadBudget(clock::get, () -> BUDGET);

  @Test
  @DisplayName("ADR-112: a thread starts with one full budget and spends it")
  void startsFullAndSpends() {
    assertEquals(BUDGET, budget.remaining());
    budget.charge(TimeUnit.MILLISECONDS.toNanos(3));
    assertTrue(budget.hasBudget());
    budget.charge(TimeUnit.MILLISECONDS.toNanos(3));
    assertFalse(budget.hasBudget(), "6 ms of work exhausts a 5 ms budget");
  }

  @Test
  @DisplayName("ADR-112: a long region tick earns no catch-up allowance beyond one budget")
  void refillIsCappedAtOneBudget() {
    budget.charge(BUDGET);
    clock.addAndGet(TICK * 14); // a 700 ms tick (1.4 TPS)
    assertEquals(BUDGET, budget.remaining(), "a slow region gets one budget, not fourteen");
  }

  @Test
  @DisplayName("ADR-112: tokens refill in proportion to elapsed time within a tick")
  void refillsProportionally() {
    budget.charge(BUDGET);
    clock.addAndGet(TICK / 2);
    assertEquals(BUDGET / 2, budget.remaining());
  }

  @Test
  @DisplayName("ADR-112: debt from one slow task is floored at one budget")
  void debtIsFloored() {
    budget.charge(TimeUnit.SECONDS.toNanos(2));
    assertEquals(-BUDGET, budget.remaining());
    clock.addAndGet(TICK);
    assertFalse(budget.hasBudget(), "one tick only clears the debt");
    clock.addAndGet(TICK);
    assertTrue(budget.hasBudget(), "the second tick restores work");
  }

  @Test
  @DisplayName("ADR-112: nested spans on one thread are charged once, by the outermost span")
  void nestedSpansChargeOnce() {
    long outer = budget.begin();
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(1));
    long inner = budget.begin();
    assertEquals(RegionThreadBudget.NESTED, inner);
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(2));
    budget.end(inner);
    assertEquals(BUDGET, budget.remaining(), "the inner span charged nothing");
    budget.end(outer);
    assertEquals(BUDGET - TimeUnit.MILLISECONDS.toNanos(3), budget.remaining(), "outer span covers both");
  }

  @Test
  @DisplayName("ADR-112: buckets are per thread, so one hot region thread never drains another's")
  void bucketsArePerThread() throws Exception {
    budget.charge(BUDGET * 2);
    assertFalse(budget.hasBudget());
    boolean[] other = new boolean[1];
    Thread t = new Thread(() -> other[0] = budget.hasBudget());
    t.start();
    t.join();
    assertTrue(other[0]);
  }

  @Test
  @DisplayName("ADR-112: release() drops the calling thread's bucket so the next use starts full")
  void releaseResetsCallingThread() {
    budget.charge(BUDGET * 2);
    assertFalse(budget.hasBudget());
    budget.release();
    assertEquals(BUDGET, budget.remaining(), "a released thread re-initialises with one full budget");
  }
}
