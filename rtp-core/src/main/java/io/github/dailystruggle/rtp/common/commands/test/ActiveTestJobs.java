package io.github.dailystruggle.rtp.common.commands.test;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide thread-safe registry of in-flight test jobs for cancellation and completion tracking.
 */
public final class ActiveTestJobs {

  /** Description of a running test job and the hook used to cancel it. */
  public static final class Job {
    /** Subcommand name, e.g. {@code "stress"}. */
    public final String subcommand;
    /** Wall-clock start time in nanoseconds. */
    public final long startedAtNanos;
    /** Hook invoked by {@link ActiveTestJobs#cancelOwned(UUID)} to stop this job. */
    public final Runnable canceller;

    /**
     * Constructs a job descriptor.
     *
     * @param subcommand name of the test subcommand that created this job
     * @param canceller  runnable that stops the job when invoked
     */
    public Job(String subcommand, Runnable canceller) {
      this.subcommand = subcommand;
      this.startedAtNanos = System.nanoTime();
      this.canceller = canceller;
    }
  }

  private static final Map<UUID, CopyOnWriteArrayList<Job>> JOBS = new ConcurrentHashMap<>();

  /**
   * One-shot listeners fired when an owner's active job list transitions to empty.
   */
  private static final Map<UUID, CopyOnWriteArrayList<Runnable>> ON_EMPTY_LISTENERS =
      new ConcurrentHashMap<>();

  private ActiveTestJobs() {}

  /**
   * Registers a job owned by {@code owner}.
   *
   * @param owner the player or console UUID that owns the job
   * @param job   the job to register
   * @return an unregister hook that removes the job and fires drain listeners if the owner is now empty
   */
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
   * Registers a one-shot listener that fires when {@code owner} has no outstanding jobs.
   * Fires immediately if the owner is already drained.
   *
   * @param owner    the player or console UUID to watch
   * @param listener the one-shot callback to fire when the owner is drained
   */
  public static void addOnEmptyListener(UUID owner, Runnable listener) {
    if (listener == null) return;
    // Fast path: already drained → fire inline. We still take the listener
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
   *
   * @param owner    the player or console UUID whose listener to remove
   * @param listener the listener to remove
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
   *
   * @param owner the player or console UUID whose jobs to cancel
   * @return the number of jobs cancelled
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

  /**
   * Cancels every job across every owner.
   *
   * @return the total number of jobs cancelled
   */
  public static int cancelAll() {
    int n = 0;
    for (UUID owner : JOBS.keySet().toArray(new UUID[0])) {
      n += cancelOwned(owner);
    }
    return n;
  }

  /**
   * Read-only snapshot of all active jobs, for {@code rtp test cancel} reporting.
   *
   * @return an unmodifiable map from owner UUID to their active jobs
   */
  public static Map<UUID, Collection<Job>> snapshot() {
    Map<UUID, Collection<Job>> out = new java.util.HashMap<>();
    for (Map.Entry<UUID, CopyOnWriteArrayList<Job>> e : JOBS.entrySet()) {
      out.put(e.getKey(), Collections.unmodifiableList(new java.util.ArrayList<>(e.getValue())));
    }
    return out;
  }
}
