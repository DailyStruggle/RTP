package io.github.dailystruggle.rtp.common.tools;

import java.lang.ref.WeakReference;

/** Class for tracking objects with a weak reference and an expected lifespan. */
public class TrackedObject {
  private final WeakReference<Object> weakReference;
  private final String label;
  private final long maxExpectedLifespan;
  private final long creationTime;

  /**
   * @param target the object to track
   * @param label a descriptive name or ID
   * @param maxExpectedLifespan the expected maximum lifespan in milliseconds
   */
  public TrackedObject(Object target, String label, long maxExpectedLifespan) {
    this.weakReference = new WeakReference<>(target);
    this.label = label;
    this.maxExpectedLifespan = maxExpectedLifespan;
    this.creationTime = System.currentTimeMillis();
  }

  /**
   * @return true if the object has exceeded its expected lifespan and is still reachable
   */
  public boolean isLeaking() {
    return (System.currentTimeMillis() - creationTime > maxExpectedLifespan)
        && (weakReference.get() != null);
  }

  /**
   * @return true if the object has been garbage collected
   */
  public boolean isCollected() {
    return weakReference.get() == null;
  }

  /**
   * @return how long the object has been alive past its expected lifespan in milliseconds, or 0 if
   *     it has not exceeded its expected lifespan.
   */
  public long getLeakDuration() {
    long delta = System.currentTimeMillis() - creationTime;
    if (delta > maxExpectedLifespan) {
      return delta - maxExpectedLifespan;
    }
    return 0;
  }

  /**
   * @return the descriptive name or ID of the tracked object
   */
  public String getLabel() {
    return label;
  }
}
