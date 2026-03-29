package io.github.dailystruggle.rtp.api.world;

import java.util.Objects;

/** Class representing a chunk in the world */
public abstract class RTPChunk<T> {
  protected final T chunk;

  protected RTPChunk(T chunk) {
    this.chunk = chunk;
  }

  public T chunk() {
    return chunk;
  }

  /**
   * Get the x coordinate of the chunk
   *
   * @return the x coordinate
   */
  public abstract int x();

  /**
   * Get the z coordinate of the chunk
   *
   * @return the z coordinate
   */
  public abstract int z();

  /**
   * Get the block at the specified coordinates within the chunk
   *
   * @param x the x coordinate (0-15)
   * @param y the y coordinate
   * @param z the z coordinate (0-15)
   * @return the block
   */
  public abstract RTPBlock<?> getBlockAt(int x, int y, int z);

  /**
   * Get the block at the specified location within the chunk
   *
   * @param location the location
   * @return the block
   */
  public RTPBlock<?> getBlockAt(RTPLocation location) {
    return getBlockAt(location.x(), location.y(), location.z());
  }

  /**
   * Get the world the chunk is in
   *
   * @return the world
   */
  public abstract RTPWorld<?> getWorld();

  /**
   * Check if the chunk is generated
   *
   * @return true if generated, false otherwise
   */
  public abstract boolean isGenerated();

  /**
   * Set whether the chunk should be kept loaded
   *
   * @param keep true to keep loaded, false otherwise
   */
  public abstract void keep(boolean keep);

  /** Unload the chunk */
  public abstract void unload();

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    RTPChunk<?> that = (RTPChunk<?>) obj;
    return Objects.equals(this.chunk, that.chunk);
  }

  @Override
  public int hashCode() {
    return Objects.hash(chunk);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + "[" + "chunk=" + chunk + ']';
  }
}
