package io.github.dailystruggle.rtp.folia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

  @Test
  @DisplayName("ADR-123: Cell-group resolution, split detection, round-robin merge probing, and global span")
  void cellGroupResolutionSplitMergeAndGlobalSpan() {
    UUID worldId = UUID.randomUUID();
    AtomicBoolean autoTune = new AtomicBoolean(true);
    Map<String, Long> shares = new ConcurrentHashMap<>();
    Map<String, Long> loans = new ConcurrentHashMap<>();

    RegionThreadBudget governed = new RegionThreadBudget(
        clock::get,
        () -> BUDGET,
        autoTune::get,
        g -> shares.getOrDefault(g, -1L),
        g -> loans.getOrDefault(g, 0L),
        null);

    // 1. Two distant cells (0,0) and (80,80) start in separate tick regions -> 2 live groups
    String g1 = governed.beginSliceGroup(worldId, 0, 0, (cx, cz) -> cx < 32);
    governed.endSliceGroup();
    String g2 = governed.beginSliceGroup(worldId, 80, 80, (cx, cz) -> cx >= 32);
    governed.endSliceGroup();
    assertEquals(2, governed.liveGroupCount());

    // 2. Nearby cell (8,0) in same region joins g1
    String g1Near = governed.beginSliceGroup(worldId, 8, 0, (cx, cz) -> cx < 32);
    governed.endSliceGroup();
    assertEquals(g1, g1Near);
    assertEquals(2, governed.liveGroupCount());

    // 3. Folia merges the two regions so both representatives now return true on isOwnedByCurrentRegion
    //    Round-robin merge probe detects the merge on the next slice and collapses to 1 live group
    String merged = governed.beginSliceGroup(worldId, 0, 0, (cx, cz) -> true);
    governed.endSliceGroup();
    assertEquals(1, governed.liveGroupCount(), "Merge probe must collapse merged groups into one");
    assertEquals(merged, governed.groupForCell(worldId, 80, 80));

    // 4. Global region span charges k = "global"
    long gSpan = governed.beginGlobal();
    clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(2));
    governed.endGlobal(gSpan);
    assertEquals(TimeUnit.MILLISECONDS.toNanos(2), governed.globalChargedNanos());
  }

  @Test
  @DisplayName("ADR-123 Invariant I1: Group bucket inside thread bucket caps shared threads at S_cfg and allows exclusive loan up to min(2*S_cfg, 25ms)")
  void invariantI1GroupAndExclusiveThreadLoanEnforcement() {
    UUID worldId = UUID.randomUUID();
    Map<String, Long> shares = new ConcurrentHashMap<>();
    Map<String, Long> loans = new ConcurrentHashMap<>();

    RegionThreadBudget governed = new RegionThreadBudget(
        clock::get,
        () -> BUDGET, // S_cfg = 5 ms
        () -> true,
        g -> shares.getOrDefault(g, BUDGET),
        g -> loans.getOrDefault(g, 0L),
        null);

    String gBorrower = governed.beginSliceGroup(worldId, 0, 0, (cx, cz) -> cx == 0 && cz == 0);
    shares.put(gBorrower, BUDGET);     // S_k = 5 ms
    loans.put(gBorrower, BUDGET);      // e_k = 5 ms -> S_k + e_k = 10 ms

    // On an exclusive thread (only gBorrower has run on this thread in the last 1 s),
    // outer thread ceiling rises to min(2 * 5 ms, 25 ms) = 10 ms
    governed.charge(gBorrower, TimeUnit.MILLISECONDS.toNanos(7), false);
    assertTrue(governed.hasBudget(gBorrower),
        "Exclusive borrower thread with 5 ms share + 5 ms loan must still have budget after 7 ms");
    governed.charge(gBorrower, TimeUnit.MILLISECONDS.toNanos(3), false);
    assertFalse(governed.hasBudget(gBorrower),
        "10 ms exhausts the 5 ms share + 5 ms loan on the exclusive borrower thread");
    governed.endSliceGroup();

    // Advance 1 tick (50 ms) and run a second group on the SAME thread within 1 s:
    // thread is no longer exclusive to gBorrower -> outer ceiling drops immediately to S_cfg = 5 ms!
    clock.addAndGet(TICK);
    String gOther = governed.beginSliceGroup(worldId, 160, 160, (cx, cz) -> cx == 160 && cz == 160);
    governed.charge(gOther, TimeUnit.MILLISECONDS.toNanos(1), false);
    governed.endSliceGroup();

    governed.beginSliceGroup(worldId, 0, 0, (cx, cz) -> cx == 0 && cz == 0);
    governed.charge(gBorrower, TimeUnit.MILLISECONDS.toNanos(4), false);
    assertFalse(governed.hasBudget(gBorrower),
        "Once a thread ticks a second group within 1 s, its outer thread bucket ceiling must drop to S_cfg (5 ms)");
    governed.endSliceGroup();
  }
}
