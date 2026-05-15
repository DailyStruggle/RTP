package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
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

  // -----------------------------------------------------------------
  // addOnEmptyListener / removeOnEmptyListener — these are the hooks
  // TestFullCmd uses to chain shipped subcommands without parking an
  // async-pool worker between dispatches. Without these tests, a
  // regression in fire-once semantics would silently strand the umbrella
  // sweep (S-004 would NOT fire because the sweep would simply never
  // advance), which is why each contract is asserted explicitly below.
  // -----------------------------------------------------------------

  @Test
  @DisplayName("addOnEmptyListener fires inline when the owner is already drained")
  void addOnEmptyListenerFiresInlineWhenAlreadyDrained() {
    UUID owner = UUID.randomUUID();
    AtomicInteger fires = new AtomicInteger();

    // No jobs ever registered for this owner → already drained.
    ActiveTestJobs.addOnEmptyListener(owner, fires::incrementAndGet);

    assertEquals(1, fires.get(), "listener must fire synchronously when owner is already empty");
  }

  @Test
  @DisplayName("addOnEmptyListener fires exactly once when the last job unregisters")
  void addOnEmptyListenerFiresOnTransitionToEmpty() {
    UUID owner = UUID.randomUUID();
    AtomicInteger fires = new AtomicInteger();

    Runnable unreg1 =
        ActiveTestJobs.register(owner, new ActiveTestJobs.Job("a", () -> {}));
    Runnable unreg2 =
        ActiveTestJobs.register(owner, new ActiveTestJobs.Job("b", () -> {}));

    ActiveTestJobs.addOnEmptyListener(owner, fires::incrementAndGet);

    assertEquals(0, fires.get(), "listener must not fire while jobs remain");
    unreg1.run();
    assertEquals(0, fires.get(), "listener must not fire while one job remains");
    unreg2.run();
    assertEquals(1, fires.get(), "listener must fire on transition to empty");

    // Re-arming jobs and draining again must NOT re-fire the original
    // listener — it was a one-shot and was removed at fire time.
    Runnable unreg3 =
        ActiveTestJobs.register(owner, new ActiveTestJobs.Job("c", () -> {}));
    unreg3.run();
    assertEquals(1, fires.get(), "drain listener must be one-shot");
  }

  @Test
  @DisplayName("removeOnEmptyListener prevents a parked listener from firing")
  void removeOnEmptyListenerCancelsBeforeFire() {
    UUID owner = UUID.randomUUID();
    AtomicInteger fires = new AtomicInteger();
    Runnable listener = fires::incrementAndGet;

    Runnable unreg =
        ActiveTestJobs.register(owner, new ActiveTestJobs.Job("a", () -> {}));
    ActiveTestJobs.addOnEmptyListener(owner, listener);
    assertEquals(0, fires.get());

    // Caller (e.g. TestFullCmd's watchdog) un-arms the listener before
    // the owner drains. The subsequent drain must not fire it.
    ActiveTestJobs.removeOnEmptyListener(owner, listener);
    unreg.run();

    assertEquals(0, fires.get(), "removed listener must not fire on drain");
  }

  @Test
  @DisplayName("cancelOwned fires drain listeners so a chained sweep can advance")
  void cancelOwnedFiresOnEmptyListeners() {
    UUID owner = UUID.randomUUID();
    AtomicInteger fires = new AtomicInteger();

    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("a", () -> {}));
    ActiveTestJobs.addOnEmptyListener(owner, fires::incrementAndGet);
    assertEquals(0, fires.get());

    int cancelled = ActiveTestJobs.cancelOwned(owner);

    assertEquals(1, cancelled);
    assertEquals(
        1,
        fires.get(),
        "cancelOwned must fire drain listeners — otherwise `rtp test cancel` "
            + "mid-sweep would strand the umbrella chain in TestFullCmd.");
  }

  @Test
  @DisplayName("a throwing drain listener does not block sibling listeners")
  void onEmptyListenerThrowableIsSwallowed() {
    UUID owner = UUID.randomUUID();
    AtomicInteger goodFires = new AtomicInteger();

    // Owner is already empty → both listeners fire inline at registration.
    // The bad listener throws; the good one must still observe its fire.
    ActiveTestJobs.addOnEmptyListener(owner, () -> { throw new RuntimeException("boom"); });
    ActiveTestJobs.addOnEmptyListener(owner, goodFires::incrementAndGet);

    assertEquals(1, goodFires.get(), "throwing listener must not block siblings");
  }
}
