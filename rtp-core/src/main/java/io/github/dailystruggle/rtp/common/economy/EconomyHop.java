package io.github.dailystruggle.rtp.common.economy;

import io.github.dailystruggle.rtp.common.RTP;

/**
 * Routes economy write calls onto the platform's global/main scheduler via {@link RTP#scheduler}.
 *
 * <p>Folia-safe: Vault calls dispatch to the Global Region Scheduler to avoid {@code ThreadAccessException}.
 * Fire-and-forget without blocking joins (S-005). Falls back to inline execution if scheduler is unwired.
 */
public final class EconomyHop {
  private EconomyHop() {}

  /**
   * Fire-and-forget hop for write-side economy calls (e.g. {@code take}, {@code give}).
   *
   * @param task the economy write task to dispatch; never {@code null}
   */
  public static void run(Runnable task) {
    if (RTP.scheduler == null) {
      task.run();
      return;
    }
    RTP.scheduler.runTask(task);
  }
}
