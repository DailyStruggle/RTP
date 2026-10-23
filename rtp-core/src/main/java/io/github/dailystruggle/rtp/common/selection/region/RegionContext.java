package io.github.dailystruggle.rtp.common.selection.region;

import io.github.dailystruggle.rtp.api.world.RTPWorld;

/**
 * Thread-local region and world context for the selection that is running on the current thread
 * (ADR-115 seam 4). Replaces the former {@code RegionContext.region()} / {@code RegionContext.world()}
 * statics; the {@link ThreadLocal} instances are process-wide and never replaced.
 */
public final class RegionContext {
  private static final ThreadLocal<Region> REGION = new ThreadLocal<>();
  private static final ThreadLocal<RTPWorld> WORLD = new ThreadLocal<>();

  private RegionContext() {
  }

  public static void clear() {
    REGION.remove();
    WORLD.remove();
  }

  /** Region being selected on this thread; callers set and clear it around selection work. */
  public static ThreadLocal<Region> region() {
    return REGION;
  }

  /** World being selected on this thread; callers set and clear it around selection work. */
  public static ThreadLocal<RTPWorld> world() {
    return WORLD;
  }
}
