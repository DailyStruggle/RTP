package io.github.dailystruggle.rtp.api.platform;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One enrolled emergency-platform restore job: the footprint's original blocks plus the
 * countdown state needed to put them back. Carries no live world references. See ADR-060.
 *
 * <p>The countdown is gated on the footprint's primary chunk being loaded: the reaper
 * decrements {@link #remainingSeconds()} only on pulses where {@code (cx, cz)} is already
 * loaded, and never force-loads a chunk to run the timer (S-005).
 */
public final class PendingPlatformRestore {
  private final UUID id;
  private final String worldName;
  private final int cx;
  private final int cz;
  private final List<BlockDelta> blocks;
  private int remainingSeconds;

  /**
   * @param id               stable identity (DB primary key); never {@code null}
   * @param worldName        the world the footprint lives in; never {@code null}
   * @param cx               countdown-gate chunk X ({@code blockX >> 4})
   * @param cz               countdown-gate chunk Z ({@code blockZ >> 4})
   * @param blocks           the recorded original blocks to write back; never {@code null}
   * @param remainingSeconds remaining chunk-loaded seconds before restore (may be {@code 0})
   */
  public PendingPlatformRestore(
      UUID id, String worldName, int cx, int cz, List<BlockDelta> blocks, int remainingSeconds) {
    this.id = Objects.requireNonNull(id, "id");
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.cx = cx;
    this.cz = cz;
    this.blocks = Collections.unmodifiableList(Objects.requireNonNull(blocks, "blocks"));
    this.remainingSeconds = remainingSeconds;
  }

  /** @return stable identity. */
  public UUID id() {
    return id;
  }

  /** @return the footprint's world name. */
  public String worldName() {
    return worldName;
  }

  /** @return countdown-gate chunk X. */
  public int cx() {
    return cx;
  }

  /** @return countdown-gate chunk Z. */
  public int cz() {
    return cz;
  }

  /** @return the recorded original blocks (immutable). */
  public List<BlockDelta> blocks() {
    return blocks;
  }

  /** @return remaining chunk-loaded seconds before restore. */
  public int remainingSeconds() {
    return remainingSeconds;
  }

  /**
   * Decrements the countdown by one second, floored at zero.
   *
   * @return the new remaining seconds
   */
  public int decrement() {
    if (remainingSeconds > 0) {
      remainingSeconds--;
    }
    return remainingSeconds;
  }

  @Override
  public String toString() {
    return "PendingPlatformRestore[id=" + id + ", world=" + worldName + ", cx=" + cx
        + ", cz=" + cz + ", blocks=" + blocks.size() + ", remaining=" + remainingSeconds + ']';
  }
}
