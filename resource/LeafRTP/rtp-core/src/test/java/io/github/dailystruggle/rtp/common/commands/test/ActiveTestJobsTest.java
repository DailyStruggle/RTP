package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the process-wide {@link ActiveTestJobs} registry backing
 * {@code rtp test cancel}. Verifies registration, per-owner and global
 * cancellation, drain-listener fire/remove semantics, and snapshot isolation.
 */
class ActiveTestJobsTest {

  @AfterEach
  void drain() {
    ActiveTestJobs.cancelAll();
  }

  @Test
  void registerThenUnregister_firesDrainListenerOnEmpty() {
    UUID owner = UUID.randomUUID();
    AtomicInteger cancelled = new AtomicInteger();
    AtomicInteger drained = new AtomicInteger();

    Runnable unregister =
        ActiveTestJobs.register(owner, new ActiveTestJobs.Job("stress", cancelled::incrementAndGet));
    ActiveTestJobs.addOnEmptyListener(owner, drained::incrementAndGet);

    // Listener must not fire while a job is still outstanding.
    assertEquals(0, drained.get());

    unregister.run();
    // The canceller is NOT invoked by unregister (only by cancel*).
    assertEquals(0, cancelled.get());
    assertEquals(1, drained.get(), "drain listener fires once when owner empties");
  }

  @Test
  void addOnEmptyListener_firesImmediatelyWhenAlreadyDrained() {
    UUID owner = UUID.randomUUID();
    AtomicInteger drained = new AtomicInteger();
    ActiveTestJobs.addOnEmptyListener(owner, drained::incrementAndGet);
    assertEquals(1, drained.get());
  }

  @Test
  void removeOnEmptyListener_preventsFire() {
    UUID owner = UUID.randomUUID();
    AtomicInteger drained = new AtomicInteger();
    Runnable listener = drained::incrementAndGet;

    Runnable unregister =
        ActiveTestJobs.register(owner, new ActiveTestJobs.Job("scheduler", () -> {}));
    ActiveTestJobs.addOnEmptyListener(owner, listener);
    ActiveTestJobs.removeOnEmptyListener(owner, listener);

    unregister.run();
    assertEquals(0, drained.get(), "removed listener must not fire");
  }

  @Test
  void cancelOwned_runsCancellersAndReturnsCount() {
    UUID owner = UUID.randomUUID();
    AtomicInteger cancelled = new AtomicInteger();
    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("a", cancelled::incrementAndGet));
    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("b", cancelled::incrementAndGet));

    int n = ActiveTestJobs.cancelOwned(owner);
    assertEquals(2, n);
    assertEquals(2, cancelled.get());
    assertEquals(0, ActiveTestJobs.cancelOwned(owner), "owner is drained after cancel");
  }

  @Test
  void cancelOwned_swallowsMisbehavingCanceller() {
    UUID owner = UUID.randomUUID();
    AtomicInteger good = new AtomicInteger();
    ActiveTestJobs.register(
        owner,
        new ActiveTestJobs.Job(
            "boom",
            () -> {
              throw new RuntimeException("boom");
            }));
    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("ok", good::incrementAndGet));

    // A throwing canceller is counted as failed (not incremented) but must not
    // stop the sibling from running.
    int n = ActiveTestJobs.cancelOwned(owner);
    assertEquals(1, n, "only the successful canceller is counted");
    assertEquals(1, good.get());
  }

  @Test
  void cancelAll_spansEveryOwner() {
    UUID o1 = UUID.randomUUID();
    UUID o2 = UUID.randomUUID();
    AtomicInteger cancelled = new AtomicInteger();
    ActiveTestJobs.register(o1, new ActiveTestJobs.Job("x", cancelled::incrementAndGet));
    ActiveTestJobs.register(o2, new ActiveTestJobs.Job("y", cancelled::incrementAndGet));

    assertEquals(2, ActiveTestJobs.cancelAll());
    assertEquals(2, cancelled.get());
    assertTrue(ActiveTestJobs.snapshot().isEmpty());
  }

  @Test
  void snapshot_isImmutableAndReflectsLiveJobs() {
    UUID owner = UUID.randomUUID();
    ActiveTestJobs.register(owner, new ActiveTestJobs.Job("snap", () -> {}));

    Map<UUID, Collection<ActiveTestJobs.Job>> snap = ActiveTestJobs.snapshot();
    assertTrue(snap.containsKey(owner));
    Collection<ActiveTestJobs.Job> jobs = snap.get(owner);
    assertEquals(1, jobs.size());
    ActiveTestJobs.Job job = jobs.iterator().next();
    assertEquals("snap", job.subcommand);
    assertTrue(job.startedAtNanos > 0L);
  }

  @Test
  void nullListener_isIgnored() {
    UUID owner = UUID.randomUUID();
    // Must not throw.
    ActiveTestJobs.addOnEmptyListener(owner, null);
    ActiveTestJobs.removeOnEmptyListener(owner, null);
    assertFalse(ActiveTestJobs.snapshot().containsKey(owner));
  }
}
