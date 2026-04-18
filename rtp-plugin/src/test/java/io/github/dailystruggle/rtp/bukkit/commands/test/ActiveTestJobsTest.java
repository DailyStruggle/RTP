package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActiveTestJobs}, the shared registry used by
 * {@code rtp test cancel}. These tests are what makes {@code cancel}
 * safe to rely on: without them the registry's behaviour under mixed
 * owners, duplicate jobs, and misbehaving cancellers is unverified.
 *
 * <p>Traces REQ-RTP-S-004 (explicit failure channel for long-running
 * test jobs) via the cancel command that depends on this registry.
 */
class ActiveTestJobsTest {

  @Test
  @DisplayName("cancelOwned runs every canceller for the owner and clears them")
  void cancelOwnedRunsCancellers() {
    UUID owner = UUID.randomUUID();
    AtomicInteger counter = new AtomicInteger();
    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("stress", counter::incrementAndGet));
    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("reload-safety", counter::incrementAndGet));

    int cancelled = ActiveTestJobs.cancelOwned(owner);

    assertEquals(2, cancelled);
    assertEquals(2, counter.get());
    // Second cancel is a no-op: the registry is cleared after the first.
    assertEquals(0, ActiveTestJobs.cancelOwned(owner));
  }

  @Test
  @DisplayName("cancelOwned on one owner does not affect another owner's jobs")
  void cancelOwnedIsolatesByOwner() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    AtomicInteger aRuns = new AtomicInteger();
    AtomicInteger bRuns = new AtomicInteger();
    ActiveTestJobs.register(a, new ActiveTestJobs.Job("stress", aRuns::incrementAndGet));
    ActiveTestJobs.register(b, new ActiveTestJobs.Job("stress", bRuns::incrementAndGet));

    assertEquals(1, ActiveTestJobs.cancelOwned(a));
    assertEquals(1, aRuns.get());
    assertEquals(0, bRuns.get());

    assertEquals(1, ActiveTestJobs.cancelOwned(b));
    assertEquals(1, bRuns.get());
  }

  @Test
  @DisplayName("a canceller that throws does not prevent sibling cancellers from running")
  void cancelOwnedSwallowsThrowingCanceller() {
    UUID owner = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    ActiveTestJobs.register(
        owner,
        new ActiveTestJobs.Job(
            "bad",
            () -> {
              throw new RuntimeException("boom");
            }));
    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("good", runs::incrementAndGet));

    int cancelled = ActiveTestJobs.cancelOwned(owner);

    // One succeeds, one throws → count reflects only the successful one,
    // but the good canceller still ran despite the bad sibling.
    assertEquals(1, cancelled);
    assertEquals(1, runs.get());
  }

  @Test
  @DisplayName("register returns an unregister hook that removes the job without running it")
  void unregisterHookRemovesJob() {
    UUID owner = UUID.randomUUID();
    AtomicInteger runs = new AtomicInteger();
    Runnable unreg =
        ActiveTestJobs.register(owner, new ActiveTestJobs.Job("stress", runs::incrementAndGet));

    unreg.run();

    // After unregistering, cancelOwned has nothing to cancel, and the
    // canceller must NOT have fired (unregister is a silent remove).
    assertEquals(0, ActiveTestJobs.cancelOwned(owner));
    assertEquals(0, runs.get());
  }

  @Test
  @DisplayName("cancelAll cancels jobs across every owner")
  void cancelAllSweeps() {
    AtomicInteger total = new AtomicInteger();
    for (int i = 0; i < 4; i++) {
      ActiveTestJobs.register(
          UUID.randomUUID(), new ActiveTestJobs.Job("stress", total::incrementAndGet));
    }

    int cancelled = ActiveTestJobs.cancelAll();

    assertTrue(cancelled >= 4, "cancelAll should cancel at least the jobs we just registered");
    assertTrue(total.get() >= 4);
  }
}
