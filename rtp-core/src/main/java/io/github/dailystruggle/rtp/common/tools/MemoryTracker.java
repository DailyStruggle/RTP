package io.github.dailystruggle.rtp.common.tools;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Utility class for tracking memory leaks in objects. */
public class MemoryTracker {
  private static final Logger LOGGER = Logger.getLogger(MemoryTracker.class.getName());
  private static final ConcurrentHashMap<UUID, TrackedObject> trackedObjects =
      new ConcurrentHashMap<>();

  private MemoryTracker() {
    // Private constructor for utility class
  }

  /**
   * Start tracking an object.
   *
   * @param target the object to track
   * @param label a descriptive name or ID
   * @param maxLifespan the expected maximum lifespan in milliseconds
   */
  public static void track(Object target, String label, long maxLifespan) {
    UUID uuid = UUID.randomUUID();
    TrackedObject trackedObject = new TrackedObject(target, label, maxLifespan);
    trackedObjects.put(uuid, trackedObject);
  }

  /** Run diagnostics to detect leaks and clean up collected objects. */
  public static void runDiagnostics() {
    trackedObjects
        .entrySet()
        .removeIf(
            entry -> {
              TrackedObject tracked = entry.getValue();
              if (tracked.isCollected()) {
                return true;
              }
              if (tracked.isLeaking()) {
                long leakDuration = tracked.getLeakDuration();
                LOGGER.log(
                    Level.SEVERE,
                    "[RTP] Memory leak detected for object: {0}. Alive {1}ms past its expected lifespan.",
                    new Object[] {tracked.getLabel(), leakDuration});
              }
              return false;
            });
  }
}
