package io.github.dailystruggle.server.api;

import java.util.Objects;

/**
 * Platform-neutral context for a world time-skip event (e.g. vanilla night skip) (ADR-049,
 * ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class WorldTimeSkipContext {
  private final String worldName;
  private final String skipReason;
  private final long skipAmountTicks;
  private final long targetTimeTicks;
  private boolean cancelled;

  /**
   * Constructs a platform-neutral world time-skip context.
   *
   * @param worldName canonical world name; must not be {@code null}
   * @param skipReason platform skip reason (e.g. {@code "NIGHT_SKIP"}, {@code "COMMAND"})
   * @param skipAmountTicks ticks being added to the world clock
   * @param targetTimeTicks resulting world time after the skip
   */
  public WorldTimeSkipContext(
      String worldName, String skipReason, long skipAmountTicks, long targetTimeTicks) {
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.skipReason = skipReason == null || skipReason.isBlank() ? "NIGHT_SKIP" : skipReason;
    this.skipAmountTicks = skipAmountTicks;
    this.targetTimeTicks = targetTimeTicks;
  }

  /**
   * Convenience constructor defaulting {@code skipReason} to {@code "NIGHT_SKIP"}.
   *
   * @param worldName canonical world name; must not be {@code null}
   * @param skipAmountTicks ticks being added to the world clock
   * @param targetTimeTicks resulting world time after the skip
   */
  public WorldTimeSkipContext(String worldName, long skipAmountTicks, long targetTimeTicks) {
    this(worldName, "NIGHT_SKIP", skipAmountTicks, targetTimeTicks);
  }

  public String worldName() {
    return worldName;
  }

  public String skipReason() {
    return skipReason;
  }

  public long skipAmountTicks() {
    return skipAmountTicks;
  }

  public long targetTimeTicks() {
    return targetTimeTicks;
  }

  /**
   * Returns whether this time skip was triggered by sleeping through the night.
   *
   * @return {@code true} if {@code skipReason} is {@code "NIGHT_SKIP"}
   */
  public boolean isNightSkip() {
    return "NIGHT_SKIP".equalsIgnoreCase(skipReason);
  }

  public void cancel() {
    this.cancelled = true;
  }

  public boolean isCancelled() {
    return cancelled;
  }
}
