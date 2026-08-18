package io.github.dailystruggle.rtp.api.world;

import java.util.Objects;

/**
 * Immutable block position tied to an {@link RTPWorld}, optionally holding a {@link ChunkReservation}.
 *
 * <p>Equality and hash code depend solely on world and (x, y, z) coordinates.
 *
 * @see RTPCoords
 * @see ChunkReservation
 */
public final class RTPLocation implements Cloneable {
  private final RTPWorld<?> world;
  private final int x;
  private final int y;
  private final int z;
  private ChunkReservation reservation;

  /**
   * Creates a location without a chunk reservation.
   *
   * @param world the world this location belongs to; must not be {@code null}
   * @param x     block X coordinate
   * @param y     block Y coordinate
   * @param z     block Z coordinate
   */
  public RTPLocation(RTPWorld<?> world, int x, int y, int z) {
    this(world, x, y, z, null);
  }

  /**
   * Creates a location with a pre-attached chunk reservation.
   *
   * @param world       world this location belongs to; must not be {@code null}
   * @param x           block X coordinate
   * @param y           block Y coordinate
   * @param z           block Z coordinate
   * @param reservation chunk reservation keeping chunks loaded, or {@code null}
   */
  public RTPLocation(RTPWorld<?> world, int x, int y, int z, ChunkReservation reservation) {
    this.world = world;
    this.x = x;
    this.y = y;
    this.z = z;
    this.reservation = reservation;
  }

  /**
   * Returns the block X coordinate.
   *
   * @return block X
   */
  public int getBlockX() {
    return x;
  }

  /**
   * Returns the block Y coordinate.
   *
   * @return block Y
   */
  public int getBlockY() {
    return y;
  }

  /**
   * Returns the block Z coordinate.
   *
   * @return block Z
   */
  public int getBlockZ() {
    return z;
  }

  /**
   * Returns the {@link ChunkReservation} keeping this location's chunks loaded, or
   * {@code null} if no reservation is held.
   *
   * @return the chunk reservation, or {@code null}
   */
  public ChunkReservation getReservation() {
    return reservation;
  }

  /**
   * Attaches a chunk reservation to this location.
   *
   * @param reservation reservation to attach; may be {@code null}
   */
  public void setReservation(ChunkReservation reservation) {
    this.reservation = reservation;
  }

  /**
   * Returns squared 3D Euclidean distance in blocks, or {@link Long#MAX_VALUE} if worlds differ.
   *
   * @param that other location; must not be {@code null}
   * @return squared distance in blocks, or {@link Long#MAX_VALUE}
   */
  public long distanceSquared(RTPLocation that) {
    if (!this.world.equals(that.world)) return Long.MAX_VALUE;
    long dx = this.x - that.x;
    long dy = this.y - that.y;
    long dz = this.z - that.z;
    return (long) (Math.pow(dx, 2) + Math.pow(dy, 2) + Math.pow(dz, 2));
  }

  /**
   * Returns squared horizontal (XZ-plane) distance in blocks, or {@link Long#MAX_VALUE} if worlds differ.
   *
   * @param that other location; must not be {@code null}
   * @return squared XZ distance in blocks, or {@link Long#MAX_VALUE}
   */
  public long distanceSquaredXZ(RTPLocation that) {
    if (!this.world.equals(that.world)) return Long.MAX_VALUE;
    long dx = this.x - that.x;
    long dz = this.z - that.z;
    return (long) (Math.pow(dx, 2) + Math.pow(dz, 2));
  }

  @Override
  public RTPLocation clone() {
    try {
      RTPLocation clone = (RTPLocation) super.clone();
      clone.reservation = this.reservation;
      return clone;
    } catch (CloneNotSupportedException e) {
      return new RTPLocation(world, x, y, z, reservation);
    }
  }

  /**
   * Returns the world this location belongs to.
   *
   * @return the world; never {@code null}
   */
  public RTPWorld<?> world() {
    return world;
  }

  /**
   * Returns the block X coordinate (record-style accessor).
   *
   * @return block X
   */
  public int x() {
    return x;
  }

  /**
   * Returns the block Y coordinate (record-style accessor).
   *
   * @return block Y
   */
  public int y() {
    return y;
  }

  /**
   * Returns the block Z coordinate (record-style accessor).
   *
   * @return block Z
   */
  public int z() {
    return z;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    RTPLocation that = (RTPLocation) obj;
    return Objects.equals(this.world, that.world)
        && this.x == that.x
        && this.y == that.y
        && this.z == that.z;
  }

  @Override
  public int hashCode() {
    return Objects.hash(world, x, y, z);
  }

  @Override
  public String toString() {
    return "RTPLocation["
        + "world="
        + world.name()
        + ", "
        + "x="
        + x
        + ", "
        + "y="
        + y
        + ", "
        + "z="
        + z
        + ']';
  }
}
