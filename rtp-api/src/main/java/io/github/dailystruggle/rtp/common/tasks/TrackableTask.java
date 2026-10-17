package io.github.dailystruggle.rtp.common.tasks;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;

/**
 * Tier 1 contract (ADR-115 section 1) letting {@code tools.MemoryTracker} observe and cancel live
 * teleport tasks without importing {@code tasks.teleport}. Implementations must be thread-safe.
 *
 * @see RTPCancellable
 */
public interface TrackableTask extends RTPCancellable, Runnable {
  /**
   * Returns the player this task teleports.
   *
   * @return owning player, or {@code null} if the task has no player context
   */
  RTPPlayer player();

  /**
   * Execution phase name or identifier for diagnostics and placeholder rendering.
   *
   * @return pipeline phase name, or empty string if unspecified
   */
  default String phaseName() {
    return "";
  }
}
