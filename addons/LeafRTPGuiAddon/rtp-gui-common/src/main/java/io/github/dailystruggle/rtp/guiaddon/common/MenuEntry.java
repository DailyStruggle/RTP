package io.github.dailystruggle.rtp.guiaddon.common;

import io.github.dailystruggle.rtp.api.RtpTarget;
import io.github.dailystruggle.rtp.api.RtpTargetStatus;

/**
 * One destination row in a {@link MenuModel}: a fully-resolved, platform-neutral
 * description of a clickable target. The renderer consumes this verbatim and never
 * re-derives icon/label/status, so all presentation logic stays in {@code common}.
 */
public final class MenuEntry {

  private final RtpTarget target;
  private final RtpTargetStatus.Availability availability;
  private final String displayName;
  private final String iconName;
  private final long remainingCooldownMillis;
  private final double cost;
  private final boolean ready;
  private final long delayMillis;
  private final long combatRemainingMillis;

  MenuEntry(
      RtpTarget target,
      RtpTargetStatus.Availability availability,
      String displayName,
      String iconName,
      long remainingCooldownMillis,
      double cost) {
    this(target, availability, displayName, iconName, remainingCooldownMillis, cost, 0L, 0L);
  }

  MenuEntry(
      RtpTarget target,
      RtpTargetStatus.Availability availability,
      String displayName,
      String iconName,
      long remainingCooldownMillis,
      double cost,
      long delayMillis,
      long combatRemainingMillis) {
    this.target = target;
    this.availability = availability;
    this.displayName = displayName;
    this.iconName = iconName;
    this.remainingCooldownMillis = remainingCooldownMillis;
    this.cost = cost;
    this.ready = availability == RtpTargetStatus.Availability.READY;
    this.delayMillis = Math.max(0L, delayMillis);
    this.combatRemainingMillis = Math.max(0L, combatRemainingMillis);
  }

  /** The target this row teleports to when clicked. */
  public RtpTarget target() {
    return target;
  }

  public RtpTargetStatus.Availability availability() {
    return availability;
  }

  public String displayName() {
    return displayName;
  }

  /** Platform-neutral icon material name (the renderer maps it to its item type). */
  public String iconName() {
    return iconName;
  }

  public long remainingCooldownMillis() {
    return remainingCooldownMillis;
  }

  public double cost() {
    return cost;
  }

  public boolean ready() {
    return ready;
  }

  public long delayMillis() {
    return delayMillis;
  }

  public long combatRemainingMillis() {
    return combatRemainingMillis;
  }
}
