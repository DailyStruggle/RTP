package io.github.dailystruggle.rtp.common.economy;

import io.github.dailystruggle.rtp.common.RTP;

/**
 * Thin hop helper that routes every Vault/economy <b>write</b> call onto the
 * platform's global scheduler via
 * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTask(Runnable)}.
 *
 * <p>On Folia, {@code RTP.scheduler.runTask(Runnable)} dispatches through the
 * Global Region Scheduler (the only valid sink for Vault/economy operations on
 * Folia; region threads throw {@code ThreadAccessException}). On Spigot/Paper
 * it hops to the main server thread. Either way, callers on async/command
 * threads can invoke {@link #run(Runnable)} without worrying about thread
 * context.
 *
 * <p>This helper is fire-and-forget by design (no blocking joins, S-005 and the
 * project's no-blocking-future-calls architecture rule are preserved). Read
 * operations on {@code RTPEconomy} (e.g. {@code bal}) are assumed atomic and
 * are NOT routed through this helper; only writes ({@code take}, {@code give},
 * future {@code deposit}/{@code withdraw}-style calls) must hop.
 *
 * <p>When {@code RTP.scheduler} is not yet wired (early bootstrap or unit
 * tests), the task is executed inline on the caller thread as a best-effort
 * fallback.
 */
public final class EconomyHop {
  private EconomyHop() {}

  /** Fire-and-forget hop for write-side economy calls (e.g. {@code take}, {@code give}). */
  public static void run(Runnable task) {
    if (RTP.scheduler == null) {
      task.run();
      return;
    }
    RTP.scheduler.runTask(task);
  }
}
