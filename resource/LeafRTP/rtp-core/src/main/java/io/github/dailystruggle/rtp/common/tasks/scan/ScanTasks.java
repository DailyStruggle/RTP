package io.github.dailystruggle.rtp.common.tasks.scan;

import java.util.Map;

/**
 * Slot for the running {@link ScanTask}s keyed by region name (ADR-115 seam 4), formerly the
 * {@code RTP.scanTasks} instance field. The core instance installs its map when it becomes
 * current, so {@link #current()} is {@code null} exactly while no core instance exists.
 */
public final class ScanTasks {
  private static volatile Map<String, ScanTask> current;

  private ScanTasks() {
  }

  /** The installed map, or {@code null} before the first core instance is constructed. */
  public static Map<String, ScanTask> current() {
    return current;
  }

  /** Installs {@code tasks} as current; {@code null} clears the slot (tests). */
  public static void install(Map<String, ScanTask> tasks) {
    current = tasks;
  }
}
