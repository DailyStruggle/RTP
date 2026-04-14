package io.github.dailystruggle.rtp.api.world;

import java.util.Objects;

public final class RTPLocation implements Cloneable {
  private final RTPWorld<?> world;
  private final int x;
  private final int y;
  private final int z;
  private ChunkReservation reservation;

  public RTPLocation(RTPWorld<?> world, int x, int y, int z) {
    this(world, x, y, z, null);
  }

  public RTPLocation(RTPWorld<?> world, int x, int y, int z, ChunkReservation reservation) {
    this.world = world;
    this.x = x;
    this.y = y;
    this.z = z;
    this.reservation = reservation;
  }

  public int getBlockX() {
    return x;
  }

  public int getBlockY() {
    return y;
  }

  public int getBlockZ() {
    return z;
  }

  public ChunkReservation getReservation() {
    return reservation;
  }

  public void setReservation(ChunkReservation reservation) {
    this.reservation = reservation;
  }

  public long distanceSquared(RTPLocation that) {
    if (!this.world.equals(that.world)) return Long.MAX_VALUE;
    long dx = this.x - that.x;
    long dy = this.y - that.y;
    long dz = this.z - that.z;
    return (long) (Math.pow(dx, 2) + Math.pow(dy, 2) + Math.pow(dz, 2));
  }

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

  public RTPWorld<?> world() {
    return world;
  }

  public int x() {
    return x;
  }

  public int y() {
    return y;
  }

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
