package io.github.dailystruggle.rtp.common.commands.test;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide registry of in-flight {@code rtp test *} jobs, so that
 * {@code rtp test cancel} can interrupt timer loops started by
 * {@link TestStressCmd} (and future subcommands) without each subcommand
 * needing its own bespoke cancellation channel.
 *
 * <p>Rationale (see {@code RUNTIME_TEST_SUITE_PLAN.md Â§4 &mdash; cancel}):
 * today a mistyped {@code iterations:1000 intervalTicks:10} invocation has
 * no in-band stop switch. A single registry keeps the cancel semantics
 * uniform across subcommands and avoids a proliferation of static fields.
 *
 * <p>Thread-safety: backed by a {@link ConcurrentHashMap} of
 * {@link CopyOnWriteArrayList}s, so jobs may be registered from any
 * thread and cancelled from any thread without external locking. This is
 * important because timer callbacks run on the async scheduler while
 * {@code rtp test cancel} is typically invoked from a command thread.
 */
public final class ActiveTestJobs {

  /** Description of a running test job and the hook used to cancel it. */
  public static final class Job {
    public final String subcommand; // e.g. "stress"
    public final long startedAtNanos;
    public final Runnable canceller;

    public Job(String subcommand, Runnable canceller) {
      this.subcommand = subcommand;
      this.startedAtNanos = System.nanoTime();
      this.canceller = canceller;
    }
  }

  private static final Map<UUID, CopyOnWriteArrayList<Job>> JOBS = new ConcurrentHashMap<>();

  /**
   * One-shot "owner is now drained" listeners, used by {@link TestFullCmd}
   * to chain shipped subcommands without parking an async-pool worker on
   * a polling drain loop. Each listener fires at most once: either when
   * the owner's job list transitions to empty (last unregister hook), or
   * synchronously at registration time if the owner already has no jobs.
   *
   * <p>Listeners are removed from the map at fire time, and any throwable
   * is swallowed â€” drain notification must be best-effort, mirroring the
   * cancel semantics in {@link #cancelOwned(UUID)}.
   */
  private static final Map<UUID, CopyOnWriteArrayList<Runnable>> ON_EMPTY_LISTENERS =
      new ConcurrentHashMap<>();

  private ActiveTestJobs() {}

  /** Registers a job owned by {@code owner}. Returns an unregister hook. */
  public static Runnable register(UUID owner, Job job) {
    JOBS.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>()).add(job);
    return () -> {
      CopyOnWriteArrayList<Job> list = JOBS.get(owner);
      boolean nowEmpty = false;
      if (list != null) {
        list.remove(job);
        if (list.isEmpty()) {
          JOBS.remove(owner, list);
          nowEmpty = true;
        }
      }
      if (nowEmpty) fireOnEmptyListeners(owner);
    };
  }

  /**
   * Registers a one-shot listener that fires when {@code owner} has no
   * outstanding jobs. If the owner is already drained the listener fires
   * inline on the calling thread; otherwise it fires on whatever thread
   * removes the owner's last job. The listener is removed after firing
   * regardless of outcome, so it cannot fire twice.
   *
   * <p>This exists so {@link TestFullCmd} can convert its cross-subcommand
   * drain wait from a parked-thread polling loop into an event-driven
   * chain that releases its async-pool slot between subcommands. Without
   * this hook, the umbrella sweep occupies one async worker for its full
   * wall-clock duration, starving other server async work.
   */
  public static void addOnEmptyListener(UUID owner, Runnable listener) {
    if (listener == null) return;
    // Fast path: already drained â†’ fire inline. We still take the listener
    // through the registration map briefly to avoid racing with a concurrent
    // register() that observes JOBS empty between the two checks below.
    CopyOnWriteArrayList<Runnable> bucket =
        ON_EMPTY_LISTENERS.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>());
    bucket.add(listener);
    // Re-check after registration: if no jobs are present, fire now and
    // drop. This closes the race where the owner drained between the
    // caller's last snapshot() check and our add() above.
    CopyOnWriteArrayList<Job> jobs = JOBS.get(owner);
    if (jobs == null || jobs.isEmpty()) {
      fireOnEmptyListeners(owner);
    }
  }

  private static void fireOnEmptyListeners(UUID owner) {
    CopyOnWriteArrayList<Runnable> bucket = ON_EMPTY_LISTENERS.remove(owner);
    if (bucket == null) return;
    for (Runnable r : bucket) {
      try {
        r.run();
      } catch (Throwable ignored) {
        // Intentionally swallowed: drain notification is best-effort.
      }
    }
  }

  /**
   * Removes a previously-registered drain listener without firing it.
   * Used by the umbrella sweep's timeout watchdog to ensure the listener
   * cannot race the watchdog and double-advance the chain.
   */
  public static void removeOnEmptyListener(UUID owner, Runnable listener) {
    if (listener == null) return;
    CopyOnWriteArrayList<Runnable> bucket = ON_EMPTY_LISTENERS.get(owner);
    if (bucket == null) return;
    bucket.remove(listener);
    if (bucket.isEmpty()) ON_EMPTY_LISTENERS.remove(owner, bucket);
  }

  /**
   * Cancels every job owned by {@code owner} and returns the number
   * cancelled. Each {@code canceller} is invoked inside a try/catch so
   * one misbehaving subcommand cannot block others from being stopped.
   */
  public static int cancelOwned(UUID owner) {
    CopyOnWriteArrayList<Job> list = JOBS.remove(owner);
    int n = 0;
    if (list != null) {
      for (Job j : list) {
        try {
          j.canceller.run();
          n++;
        } catch (Throwable ignored) {
          // Intentionally swallowed: cancel must be best-effort.
        }
      }
    }
    // Owner is now drained as a result of cancellation. The per-job
    // unregister hooks ran inside j.canceller.run() and saw JOBS.get(owner)
    // == null (we removed the list above), so they did not fire the
    // drain listeners themselves. Fire them once here so the umbrella
    // sweep's chain advances when an operator runs `rtp test cancel`
    // mid-sweep. Also release the per-caller test permit
    // ({@link TestSemaphore}) so a queued retry of the same caller's
    // command is not stranded behind a stuck holder.
    fireOnEmptyListeners(owner);
    TestSemaphore.releaseOwned(owner);
    return n;
  }

  /** Cancels every job across every owner and returns the count. */
  public static int cancelAll() {
    int n = 0;
    for (UUID owner : JOBS.keySet().toArray(new UUID[0])) {
      n += cancelOwned(owner);
    }
    return n;
  }

  /** Read-only snapshot of all active jobs, for {@code rtp test cancel} reporting. */
  public static Map<UUID, Collection<Job>> snapshot() {
    Map<UUID, Collection<Job>> out = new java.util.HashMap<>();
    for (Map.Entry<UUID, CopyOnWriteArrayList<Job>> e : JOBS.entrySet()) {
      out.put(e.getKey(), Collections.unmodifiableList(new java.util.ArrayList<>(e.getValue())));
    }
    return out;
  }
}
