package io.github.dailystruggle.rtp.bukkit.commands.test;

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
 * <p>Rationale (see {@code RUNTIME_TEST_SUITE_PLAN.md §4 &mdash; cancel}):
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
final class ActiveTestJobs {

  /** Description of a running test job and the hook used to cancel it. */
  static final class Job {
    final String subcommand; // e.g. "stress"
    final long startedAtNanos;
    final Runnable canceller;

    Job(String subcommand, Runnable canceller) {
      this.subcommand = subcommand;
      this.startedAtNanos = System.nanoTime();
      this.canceller = canceller;
    }
  }

  private static final Map<UUID, CopyOnWriteArrayList<Job>> JOBS = new ConcurrentHashMap<>();

  private ActiveTestJobs() {}

  /** Registers a job owned by {@code owner}. Returns an unregister hook. */
  static Runnable register(UUID owner, Job job) {
    JOBS.computeIfAbsent(owner, k -> new CopyOnWriteArrayList<>()).add(job);
    return () -> {
      CopyOnWriteArrayList<Job> list = JOBS.get(owner);
      if (list != null) {
        list.remove(job);
        if (list.isEmpty()) JOBS.remove(owner, list);
      }
    };
  }

  /**
   * Cancels every job owned by {@code owner} and returns the number
   * cancelled. Each {@code canceller} is invoked inside a try/catch so
   * one misbehaving subcommand cannot block others from being stopped.
   */
  static int cancelOwned(UUID owner) {
    CopyOnWriteArrayList<Job> list = JOBS.remove(owner);
    if (list == null) return 0;
    int n = 0;
    for (Job j : list) {
      try {
        j.canceller.run();
        n++;
      } catch (Throwable ignored) {
        // Intentionally swallowed: cancel must be best-effort.
      }
    }
    return n;
  }

  /** Cancels every job across every owner and returns the count. */
  static int cancelAll() {
    int n = 0;
    for (UUID owner : JOBS.keySet().toArray(new UUID[0])) {
      n += cancelOwned(owner);
    }
    return n;
  }

  /** Read-only snapshot of all active jobs, for {@code rtp test cancel} reporting. */
  static Map<UUID, Collection<Job>> snapshot() {
    Map<UUID, Collection<Job>> out = new java.util.HashMap<>();
    for (Map.Entry<UUID, CopyOnWriteArrayList<Job>> e : JOBS.entrySet()) {
      out.put(e.getKey(), Collections.unmodifiableList(new java.util.ArrayList<>(e.getValue())));
    }
    return out;
  }
}
