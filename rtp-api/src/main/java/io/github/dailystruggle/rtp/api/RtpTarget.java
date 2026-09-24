package io.github.dailystruggle.rtp.api;

import io.github.dailystruggle.rtp.api.annotations.PublicApi;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.Objects;

/**
 * Immutable selector describing destination for {@link RTPAPI#teleport(java.util.UUID, RtpTarget)}.
 * Thread-safe. Use {@link #defaultRegion()} for default region.
 */
@PublicApi
public final class RtpTarget {

  /** Discriminator describing how {@link #name()} should be interpreted. */
  public enum Kind {
    /** Resolve to the server's default region; {@link #name()} is {@code null}. */
    DEFAULT,
    /** Resolve to a region by its configured name; {@link #name()} is the region name. */
    REGION,
    /** Resolve to the target region of a world; {@link #name()} is the world name. */
    WORLD,
    /**
     * Resolve to a region advertised by a peer backend across the network.
     * Dispatched across the cross-server wait queue.
     */
    NETWORK,
    /**
     * Resolve to an exact coordinate on a local or remote server (e.g. for {@code /rtp back}).
     */
    COORDINATE
  }

  private static final RtpTarget DEFAULT = new RtpTarget(Kind.DEFAULT, null, null, null, 0, 0, 0);

  private final Kind kind;
  private final String name;
  private final String serverId;
  private final String worldName;
  private final int x;
  private final int y;
  private final int z;

  private RtpTarget(Kind kind, String name, String serverId) {
    this(kind, name, serverId, null, 0, 0, 0);
  }

  private RtpTarget(Kind kind, String name, String serverId, String worldName, int x, int y, int z) {
    this.kind = kind;
    this.name = name;
    this.serverId = serverId;
    this.worldName = worldName;
    this.x = x;
    this.y = y;
    this.z = z;
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
    if (regionName == null || regionName.isBlank()) {
      throw new IllegalArgumentException("regionName must not be null or blank");
    }
    return new RtpTarget(Kind.REGION, regionName, null);
  }

  /**
   * Target a region advertised by a specific peer backend across the network.
   *
   * @param serverId destination backend network id; must not be null/blank
   * @param regionName region name as advertised by destination backend; must not be null/blank
   * @return network-kind target
   * @throws IllegalArgumentException if either argument is null or blank
   */
  public static RtpTarget network(String serverId, String regionName) {
    if (serverId == null || serverId.isBlank()) {
      throw new IllegalArgumentException("serverId must not be null or blank");
    }
    if (regionName == null || regionName.isBlank()) {
      throw new IllegalArgumentException("regionName must not be null or blank");
    }
    return new RtpTarget(Kind.NETWORK, regionName, serverId);
  }

  /**
   * Target the region configured for a named world.
   *
   * @param worldName the world name; must not be {@code null} or blank
   * @return a world-kind target
   * @throws IllegalArgumentException if {@code worldName} is {@code null} or blank
   */
  public static RtpTarget world(String worldName) {
    if (worldName == null || worldName.isBlank()) {
      throw new IllegalArgumentException("worldName must not be null or blank");
    }
    return new RtpTarget(Kind.WORLD, worldName, null);
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
   * Target an exact coordinate on a local or remote server.
   *
   * @param serverId destination backend network id (nullable/empty for local)
   * @param worldName world name; must not be null or blank
   * @param x x coordinate
   * @param y y coordinate
   * @param z z coordinate
   * @return coordinate-kind target
   */
  public static RtpTarget coordinate(String serverId, String worldName, int x, int y, int z) {
    if (worldName == null || worldName.isBlank()) {
      throw new IllegalArgumentException("worldName must not be null or blank");
    }
    String sId = (serverId == null || serverId.isBlank()) ? null : serverId;
    return new RtpTarget(Kind.COORDINATE, worldName + ":" + x + "," + y + "," + z, sId, worldName, x, y, z);
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

  /**
   * Returns the destination backend's network id for a {@link Kind#NETWORK} or remote {@link Kind#COORDINATE}
   * target.
   *
   * @return the server id, or {@code null} for any non-network / local target
   */
  public String serverId() {
    return serverId;
  }

  /**
   * Returns the destination world name for a {@link Kind#COORDINATE} target.
   *
   * @return world name, or {@code null} if not a coordinate target
   */
  public String worldName() {
    return worldName;
  }

  /**
   * Returns the X coordinate for a {@link Kind#COORDINATE} target.
   */
  public int x() {
    return x;
  }

  /**
   * Returns the Y coordinate for a {@link Kind#COORDINATE} target.
   */
  public int y() {
    return y;
  }

  /**
   * Returns the Z coordinate for a {@link Kind#COORDINATE} target.
   */
  public int z() {
    return z;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof RtpTarget)) return false;
    RtpTarget that = (RtpTarget) o;
    return kind == that.kind
        && x == that.x
        && y == that.y
        && z == that.z
        && Objects.equals(name, that.name)
        && Objects.equals(serverId, that.serverId)
        && Objects.equals(worldName, that.worldName);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, name, serverId, worldName, x, y, z);
  }

  @Override
  public String toString() {
    return "RtpTarget[" + kind
        + (serverId == null ? "" : ":" + serverId)
        + (worldName == null ? "" : ":" + worldName)
        + (kind == Kind.COORDINATE ? "(" + x + "," + y + "," + z + ")" : (name == null ? "" : ":" + name)) + ']';
  }
}
