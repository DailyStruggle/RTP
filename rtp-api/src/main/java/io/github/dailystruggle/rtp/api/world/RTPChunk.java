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
   * Check if the block at the specified coordinates within the chunk is an air block
   *
   * @param x the x coordinate (0-15)
   * @param y the y coordinate
   * @param z the z coordinate (0-15)
   * @return true if air, false otherwise
   */
  public abstract boolean isAir(int x, int y, int z);

  /**
   * Get the sky light level at the specified coordinates within the chunk
   *
   * @param x the x coordinate (0-15)
   * @param y the y coordinate
   * @param z the z coordinate (0-15)
   * @return the sky light level
   */
  public abstract int getSkyLight(int x, int y, int z);

  /**
   * Get the highest solid block Y coordinate at the specified coordinates within the chunk
   *
   * @param x the x coordinate (0-15)
   * @param z the z coordinate (0-15)
   * @return the highest solid block Y coordinate
   */
  public abstract int getSurfaceHeight(int x, int z);

  /**
   * Check if the block at the specified coordinates within the chunk is safe
   *
   * @param x            the x coordinate (0-15)
   * @param y            the y coordinate
   * @param z            the z coordinate (0-15)
   * @param unsafeBlocks the set of unsafe blocks
   * @return true if safe, false otherwise
   */
  public abstract boolean isSafe(int x, int y, int z, java.util.Set<String> unsafeBlocks);

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
   * Check if the chunk is loaded
   *
   * @return true if loaded, false otherwise
   */
  public abstract boolean isLoaded();

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
