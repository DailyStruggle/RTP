package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Objects;

/**
 * Immutable selector describing <em>where</em> an {@link RTPAPI#teleport(java.util.UUID, RtpTarget)}
 * call should send a player, without exposing any {@code rtp-core} region internals.
 *
 * <p>A target resolves, in the core, to a single teleport region:
 * <ul>
 *   <li>{@link Kind#DEFAULT} - the server's default region (same as a bare {@code /rtp}).</li>
 *   <li>{@link Kind#REGION} - a region looked up by its configured name.</li>
 *   <li>{@link Kind#WORLD} - the region configured as the target for a named world.</li>
 * </ul>
 *
 * <p>Instances are created through the static factory methods and are safe to share
 * across threads. Use {@link #defaultRegion()} when you just want "an RTP, anywhere
 * sensible".
 */
public final class RtpTarget {

  /** Discriminator describing how {@link #name()} should be interpreted. */
  public enum Kind {
    /** Resolve to the server's default region; {@link #name()} is {@code null}. */
    DEFAULT,
    /** Resolve to a region by its configured name; {@link #name()} is the region name. */
    REGION,
    /** Resolve to the target region of a world; {@link #name()} is the world name. */
    WORLD
  }

  private static final RtpTarget DEFAULT = new RtpTarget(Kind.DEFAULT, null);

  private final Kind kind;
  private final String name;

  private RtpTarget(Kind kind, String name) {
    this.kind = kind;
    this.name = name;
  }

  /**
   * The default-region target, equivalent to a player typing {@code /rtp} with no
   * arguments.
   *
   * @return the shared default target; never {@code null}
   */
  public static RtpTarget defaultRegion() {
    return DEFAULT;
  }

  /**
   * Target a region by its configured name.
   *
   * @param regionName the region name as defined in the region config; must not be
   *     {@code null} or blank
   * @return a region-kind target
   * @throws IllegalArgumentException if {@code regionName} is {@code null} or blank
   */
  public static RtpTarget region(String regionName) {
    if (regionName == null || regionName.trim().isEmpty()) {
      throw new IllegalArgumentException("regionName must not be null or blank");
    }
    return new RtpTarget(Kind.REGION, regionName);
  }

  /**
   * Target the region configured for a named world.
   *
   * @param worldName the world name; must not be {@code null} or blank
   * @return a world-kind target
   * @throws IllegalArgumentException if {@code worldName} is {@code null} or blank
   */
  public static RtpTarget world(String worldName) {
    if (worldName == null || worldName.trim().isEmpty()) {
      throw new IllegalArgumentException("worldName must not be null or blank");
    }
    return new RtpTarget(Kind.WORLD, worldName);
  }

  /**
   * Target the region configured for the given world.
   *
   * @param world the world; must not be {@code null}
   * @return a world-kind target
   * @throws IllegalArgumentException if {@code world} is {@code null}
   */
  public static RtpTarget world(RTPWorld<?> world) {
    if (world == null) {
      throw new IllegalArgumentException("world must not be null");
    }
    return world(world.name());
  }

  /**
   * Returns how this target should be resolved.
   *
   * @return the target kind; never {@code null}
   */
  public Kind kind() {
    return kind;
  }

  /**
   * Returns the region or world name carried by this target.
   *
   * @return the name, or {@code null} for {@link Kind#DEFAULT}
   */
  public String name() {
    return name;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RtpTarget)) return false;
    RtpTarget that = (RtpTarget) o;
    return kind == that.kind && Objects.equals(name, that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, name);
  }

  @Override
  public String toString() {
    return "RtpTarget[" + kind + (name == null ? "" : ":" + name) + ']';
  }
}
