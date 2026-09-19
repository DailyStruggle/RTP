package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Objects;

/**
 * Immutable point-in-time status snapshot of a single {@link RtpTarget} for a player.
 *
 * <p>Exposes availability, remaining cooldown, cost, and display hints for GUI rendering.
 *
 * <p>Obtain instances via {@link RTPAPI#getTargetStatus(java.util.UUID, RtpTarget)}.
 */
@PublicApi
public final class RtpTargetStatus {

  /**
   * Why a target is or is not usable by a player right now.
   *
   * <p><b>Forward compatibility:</b> new reasons may be added in future
   * versions. Callers that switch on this enum should treat any unrecognised
   * value (including {@link #UNKNOWN}) as "not usable" rather than assuming the
   * set is closed.
   */
  public enum Availability {
    /** The player may teleport to this target now. */
    READY,
    /** The player is within their teleport cooldown window. */
    ON_COOLDOWN,
    /** The player lacks the permission required for this target. */
    NO_PERMISSION,
    /** An economy is configured and the player cannot afford {@link #cost()}. */
    NO_FUNDS,
    /** The target is disabled or could not be resolved to a usable region. */
    DISABLED,
    /** Status could not be determined (e.g. player offline). */
    UNKNOWN
  }

  private final Availability availability;
  private final long remainingCooldownMillis;
  private final double cost;
  private final String iconBlock;
  private final String environment;
  private final String label;

  /**
   * Creates a status snapshot with default null display hints.
   *
   * @param availability            availability verdict; must not be {@code null}
   * @param remainingCooldownMillis remaining cooldown in milliseconds (>= 0)
   * @param cost                    teleport monetary cost (>= 0)
   */
  public RtpTargetStatus(Availability availability, long remainingCooldownMillis, double cost) {
    this(availability, remainingCooldownMillis, cost, null, null);
  }

  /**
   * Creates a status snapshot with optional icon and environment display hints.
   *
   * @param availability            availability verdict; must not be {@code null}
   * @param remainingCooldownMillis remaining cooldown in milliseconds (>= 0)
   * @param cost                    teleport monetary cost (>= 0)
   * @param iconBlock               advertised block material name hint, or {@code null}
   * @param environment             destination dimension environment hint, or {@code null}
   */
  public RtpTargetStatus(Availability availability, long remainingCooldownMillis, double cost,
      String iconBlock, String environment) {
    this(availability, remainingCooldownMillis, cost, iconBlock, environment, null);
  }

  /**
   * Creates a status snapshot with display hints and cosmetic label.
   *
   * @param availability            availability verdict; must not be {@code null}
   * @param remainingCooldownMillis remaining cooldown in milliseconds (>= 0)
   * @param cost                    teleport monetary cost (>= 0)
   * @param iconBlock               advertised block material name hint, or {@code null}
   * @param environment             destination dimension environment hint, or {@code null}
   * @param label                   cosmetic display label, or {@code null}
   */
  public RtpTargetStatus(Availability availability, long remainingCooldownMillis, double cost,
      String iconBlock, String environment, String label) {
    if (availability == null) {
      throw new IllegalArgumentException("availability must not be null");
    }
    this.availability = availability;
    this.remainingCooldownMillis = Math.max(0L, remainingCooldownMillis);
    this.cost = (cost < 0.0 || Double.isNaN(cost)) ? 0.0 : cost;
    this.iconBlock = (iconBlock == null || iconBlock.trim().isEmpty()) ? null : iconBlock.trim();
    this.environment = (environment == null || environment.trim().isEmpty()) ? null : environment.trim();
    this.label = (label == null || label.trim().isEmpty()) ? null : label.trim();
  }

  /**
   * Returns the availability verdict for this player/target pair.
   *
   * @return the availability; never {@code null}
   */
  public Availability availability() {
    return availability;
  }

  /**
   * Returns {@code true} if the player can teleport to this target right now.
   *
   * @return whether {@link #availability()} is {@link Availability#READY}
   */
  public boolean isReady() {
    return availability == Availability.READY;
  }

  /**
   * Returns the remaining teleport cooldown for this player, in milliseconds.
   *
   * @return remaining cooldown in milliseconds ({@code >= 0}); {@code 0} when not
   *     on cooldown
   */
  public long remainingCooldownMillis() {
    return remainingCooldownMillis;
  }

  /**
   * Returns the monetary cost of teleporting to this target for this player.
   *
   * @return the cost ({@code >= 0}); {@code 0} when no economy is configured or
   *     the player teleports for free
   */
  public double cost() {
    return cost;
  }

  /**
   * Returns a representative block material name advertised for this target, if
   * any. Typically set only for a cross-server target whose destination backend
   * advertised a block; a menu may use it as the destination's icon.
   *
   * @return the advertised block material name, or {@code null} when none
   */
  public String iconBlock() {
    return iconBlock;
  }

  /**
   * Returns the destination world's environment string (e.g. {@code "NORMAL"},
   * {@code "NETHER"}, {@code "THE_END"}, or a custom dimension name), if known.
   * A menu may translate this to a representative block locally (useful for
   * custom dimensions a producing backend could not map).
   *
   * @return the environment string, or {@code null} when unknown
   */
  public String environment() {
    return environment;
  }

  /**
   * Returns the operator-configured cosmetic display label for this target, if any.
   *
   * @return display label (possibly with color codes), or {@code null} for default name
   */
  public String label() {
    return label;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RtpTargetStatus)) return false;
    RtpTargetStatus that = (RtpTargetStatus) o;
    return remainingCooldownMillis == that.remainingCooldownMillis
        && Double.compare(that.cost, cost) == 0
        && availability == that.availability
        && Objects.equals(iconBlock, that.iconBlock)
        && Objects.equals(environment, that.environment)
        && Objects.equals(label, that.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(availability, remainingCooldownMillis, cost, iconBlock, environment, label);
  }

  @Override
  public String toString() {
    return "RtpTargetStatus["
        + availability
        + ", cooldownMs=" + remainingCooldownMillis
        + ", cost=" + cost
        + (iconBlock == null ? "" : ", iconBlock=" + iconBlock)
        + (environment == null ? "" : ", environment=" + environment)
        + (label == null ? "" : ", label=" + label)
        + ']';
  }
}
