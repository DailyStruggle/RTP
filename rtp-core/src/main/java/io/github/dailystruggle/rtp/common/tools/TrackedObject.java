package io.github.dailystruggle.rtp.common.tools;

import java.lang.ref.WeakReference;

public class TrackedObject {
  private final WeakReference<Object> reference;
  private final String label;
  private final long maxLifespan;
  private long startTime;

  public TrackedObject(Object target, String label, long maxLifespan) {
    this.reference = new WeakReference<>(target);
    this.label = label;
    this.maxLifespan = maxLifespan;
    this.startTime = System.currentTimeMillis();
  }

  public void reset() {
    this.startTime = System.currentTimeMillis();
  }

  public boolean isCollected() {
    return reference.get() == null;
  }

  public boolean isLeaking() {
    return !isCollected() && (System.currentTimeMillis() - startTime > maxLifespan);
  }

  public long getLeakDuration() {
    return System.currentTimeMillis() - (startTime + maxLifespan);
  }

  public String getLabel() {
    return label;
  }

  public Object getTarget() {
    return reference.get();
  }

  public boolean matches(Object target) {
    Object current = reference.get();
    return current != null && current == target;
  }
}
