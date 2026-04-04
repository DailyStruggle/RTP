package io.github.dailystruggle.rtp.common.tools;

import java.lang.ref.WeakReference;

/** Class for tracking objects with a weak reference and an expected lifespan. */
public class TrackedObject {
  private final WeakReference<Object> targetRef;
  private final String label;
  private final long maxExpectedLifespan;
  private long lastActivityTime; // Modified from final creationTime

  public TrackedObject(Object target, String label, long maxExpectedLifespan) {
    this.targetRef = new WeakReference<>(target);
    this.label = label;
    this.maxExpectedLifespan = maxExpectedLifespan;
    this.lastActivityTime = System.currentTimeMillis();
  }

  /** Refreshes the lifespan timer */
  public void reset() {
    this.lastActivityTime = System.currentTimeMillis();
  }

  public boolean isLeaking() {
    return (System.currentTimeMillis() - lastActivityTime > maxExpectedLifespan)
            && (targetRef.get() != null);
  }

  public boolean isCollected() {
    return targetRef.get() == null;
  }

  public boolean matches(Object target) {
    Object ref = targetRef.get();
    return ref != null && ref == target;
  }

  public long getLeakDuration() {
    long delta = System.currentTimeMillis() - lastActivityTime;
    if (delta > maxExpectedLifespan) {
      return delta - maxExpectedLifespan;
    }
    return 0;
  }

  public String getLabel() {
    return label;
  }
}
