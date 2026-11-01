package io.github.dailystruggle.rtp.common.action;

/**
 * Process-wide slots for the scripted-action services (ADR-093, ADR-097; ADR-115 seam 4).
 *
 * <p>Replaces the former {@code ActionServices.actionManager()} / {@code ActionServices.cacheWarmTask()} statics.
 * LeafRTPActionAddon installs both when it loads; each slot is {@code volatile} and is
 * {@code null} while the addon is absent, so callers shall null-check.
 */
public final class ActionServices {
  private static volatile ActionManager actionManager;
  private static volatile ActionCacheWarmTask cacheWarmTask;

  private ActionServices() {
  }

  /** The installed action manager, or {@code null} when the action addon is not loaded. */
  public static ActionManager actionManager() {
    return actionManager;
  }

  /** Installs {@code manager}; {@code null} clears the slot. */
  public static void setActionManager(ActionManager manager) {
    actionManager = manager;
  }

  /** The installed cache pre-warm task, or {@code null} when the action addon is not loaded. */
  public static ActionCacheWarmTask cacheWarmTask() {
    return cacheWarmTask;
  }

  /** Installs {@code task}; {@code null} clears the slot. */
  public static void setCacheWarmTask(ActionCacheWarmTask task) {
    cacheWarmTask = task;
  }
}
