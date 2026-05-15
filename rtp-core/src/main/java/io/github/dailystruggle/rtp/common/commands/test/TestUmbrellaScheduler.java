package io.github.dailystruggle.rtp.common.commands.test;

/**
 * Platform-agnostic deferred-execution sink for the {@code /rtp test ...}
 * umbrella so that the umbrella body in {@code rtp-core} can defer the
 * dispatch of individual sub-commands without importing
 * {@code org.bukkit.Bukkit#getScheduler()} or any other platform symbol.
 *
 * <p>This is the Phase 1 SPI extracted from the umbrella's existing use of
 * {@code Bukkit.getScheduler().runTaskLaterAsynchronously(...)} on
 * Spigot/Paper and {@code RTP.scheduler.runTaskTimerAsynchronously(...)} on
 * Folia. Fabric will route through the server-side executor plus
 * {@code RTP.scheduler.runTaskTimerAsynchronously} per S-005 (no
 * synchronous chunk I/O implied by the dispatch path itself; the leaves
 * remain responsible for their own threading contracts).
 *
 * <p>Implementations are wired through {@link TestUmbrellaContext}. See
 * {@code docs/dev/scratch/CHECKLIST-fabric-rtp-test-full.md} Phase 1.
 */
public interface TestUmbrellaScheduler {

  /**
   * Schedules {@code task} to run after at least {@code delayMillis}
   * milliseconds. The execution thread is implementation-defined:
   * Bukkit-family adapters run async by default; Folia routes through the
   * async scheduler; Fabric uses the server-side executor or async
   * scheduler.
   *
   * <p>Folia callers that subsequently touch entity / region state in
   * {@code task} must respect S-005 / region-ownership rules inside the
   * runnable itself &mdash; this SPI does not promise a particular
   * region context.
   *
   * @param delayMillis non-negative delay; values {@code < 0} are clamped
   *                    to {@code 0} by implementations.
   * @param task        the runnable to execute; must not be {@code null}.
   *                    Implementations must not swallow exceptions thrown
   *                    by {@code task} silently (S-004) &mdash; surface
   *                    them via {@code RTP.log(Level.WARNING, ..., t)}.
   */
  void runLater(long delayMillis, Runnable task);
}
