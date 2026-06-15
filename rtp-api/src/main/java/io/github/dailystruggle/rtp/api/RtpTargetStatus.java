package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import java.util.Objects;

/**
 * Immutable, per-player status of a single {@link RtpTarget}, intended for
 * populating menu items in a third-party GUI without reaching into
 * {@code rtp-core} internals.
 *
 * <p>It answers the three questions a GUI needs to decorate a destination
 * button: <em>can this player use it right now</em> ({@link #availability()}),
 * <em>how long until they can</em> ({@link #remainingCooldownMillis()}), and
 * <em>what will it cost</em> ({@link #cost()}). The values are a point-in-time
 * read; they carry no history and are not refreshed after construction.
 *
 * <p>Obtain instances through {@link RTPAPI#getTargetStatus(java.util.UUID, RtpTarget)}.
 * This is a read-only DTO: it never causes a teleport, charges a player, or
 * mutates any RTP state. The teleport itself still runs through
 * {@link RTPAPI#teleport(java.util.UUID, RtpTarget)}, which re-enforces every
 * safety and permission check regardless of what this status reported.
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
   * Creates a status snapshot with no icon / environment hints (equivalent to
   * the richer constructor with both hint arguments {@code null}).
   *
   * @param availability            the availability verdict; must not be {@code null}
   * @param remainingCooldownMillis remaining cooldown in milliseconds; clamped to
   *     {@code >= 0}
   * @param cost                    the monetary cost of this teleport; clamped to
   *     {@code >= 0}
   * @throws IllegalArgumentException if {@code availability} is {@code null}
   */
  public RtpTargetStatus(Availability availability, long remainingCooldownMillis, double cost) {
    this(availability, remainingCooldownMillis, cost, null, null);
  }

  /**
   * Creates a status snapshot, including optional display hints a menu can use
   * to decorate the destination (especially for a cross-server target, whose
   * environment / representative block cannot be resolved locally).
   *
   * @param availability            the availability verdict; must not be {@code null}
   * @param remainingCooldownMillis remaining cooldown in milliseconds; clamped to
   *     {@code >= 0}
   * @param cost                    the monetary cost of this teleport; clamped to
   *     {@code >= 0}
   * @param iconBlock               a representative block material name advertised
   *     for this target (e.g. {@code "NETHERRACK"}), or {@code null} when none was
   *     advertised
   * @param environment             the destination world's environment string
   *     (e.g. {@code "NORMAL"}, {@code "NETHER"}, {@code "THE_END"}, or a custom
   *     dimension name), or {@code null} when unknown
   * @throws IllegalArgumentException if {@code availability} is {@code null}
   */
  public RtpTargetStatus(Availability availability, long remainingCooldownMillis, double cost,
      String iconBlock, String environment) {
    this(availability, remainingCooldownMillis, cost, iconBlock, environment, null);
  }

  /**
   * Creates a status snapshot, including optional display hints a menu can use
   * to decorate the destination, plus an optional cosmetic display label.
   *
   * @param availability            the availability verdict; must not be {@code null}
   * @param remainingCooldownMillis remaining cooldown in milliseconds; clamped to
   *     {@code >= 0}
   * @param cost                    the monetary cost of this teleport; clamped to
   *     {@code >= 0}
   * @param iconBlock               a representative block material name advertised
   *     for this target, or {@code null} when none was advertised
   * @param environment             the destination world's environment string, or
   *     {@code null} when unknown
   * @param label                   an operator-configured cosmetic display label
   *     for this target (may contain RTP color/gradient codes), or {@code null}
   *     when none was configured/advertised
   * @throws IllegalArgumentException if {@code availability} is {@code null}
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
   * Returns the operator-configured cosmetic display label for this target, if
   * any. For a local target this is the region's configured display name; for a
   * cross-server target it is the label the destination backend advertised. May
   * contain RTP color/gradient codes; a menu should render it through the usual
   * color path. When {@code null}, callers should fall back to the target's name.
   *
   * @return the display label, or {@code null} when none
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
