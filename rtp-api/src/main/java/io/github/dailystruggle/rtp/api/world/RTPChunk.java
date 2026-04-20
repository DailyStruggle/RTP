package io.github.dailystruggle.rtp.api.world;

import java.util.Objects;
import java.util.Set;

/**
 * Platform-agnostic representation of a world chunk.
 *
 * <p>This abstract class provides a common interface for interacting with chunks
 * across different server implementations (e.g., Bukkit, Folia). It encapsulates
 * a platform-specific chunk object and delegates method calls to it.
 *
 * @param <T> the type of the underlying platform-specific chunk object
 */
public abstract class RTPChunk<T> {
  protected final T chunk;

  protected RTPChunk(T chunk) {
    this.chunk = chunk;
  }

  /**
   * Returns the underlying platform-specific chunk object.
   *
   * @return the platform chunk object
   */
  public T chunk() {
    return chunk;
  }

  /**
   * Returns the X coordinate of this chunk.
   *
   * @return the chunk's X coordinate
   */
  public abstract int x();

  /**
   * Returns the Z coordinate of this chunk.
   *
   * @return the chunk's Z coordinate
   */
  public abstract int z();

  /**
   * Checks if the block at the specified coordinates within this chunk is air.
   *
   * @param x the block's X coordinate relative to the chunk (0-15)
   * @param y the block's Y coordinate
   * @param z the block's Z coordinate relative to the chunk (0-15)
   * @return {@code true} if the block is air, {@code false} otherwise
   */
  public abstract boolean isAir(int x, int y, int z);

  /**
   * Returns the sky light level at the specified coordinates within this chunk.
   *
   * @param x the block's X coordinate relative to the chunk (0-15)
   * @param y the block's Y coordinate
   * @param z the block's Z coordinate relative to the chunk (0-15)
   * @return the sky light level
   */
  public abstract int getSkyLight(int x, int y, int z);

  /**
   * Returns the Y coordinate of the highest solid block at the specified
   * coordinates within this chunk.
   *
   * @param x the block's X coordinate relative to the chunk (0-15)
   * @param z the block's Z coordinate relative to the chunk (0-15)
   * @return the Y coordinate of the highest solid block
   */
  public abstract int getSurfaceHeight(int x, int z);

  /**
   * Checks if the block at the specified coordinates within this chunk is safe
   * for teleportation.
   *
   * @param x            the block's X coordinate relative to the chunk (0-15)
   * @param y            the block's Y coordinate
   * @param z            the block's Z coordinate relative to the chunk (0-15)
   * @param unsafeBlocks a set of material names considered unsafe
   * @return {@code true} if the block is safe, {@code false} otherwise
   */
  public abstract boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks);

  /**
   * Returns the world this chunk belongs to.
   *
   * @return the {@link RTPWorld} containing this chunk
   */
  public abstract RTPWorld<?> getWorld();

  /**
   * Checks if this chunk has been generated.
   *
   * @return {@code true} if the chunk is generated, {@code false} otherwise
   */
  public abstract boolean isGenerated();

  /**
   * Checks if this chunk is currently loaded in memory.
   *
   * @return {@code true} if the chunk is loaded, {@code false} otherwise
   */
  public abstract boolean isLoaded();

  /**
   * Sets whether this chunk should be kept loaded in memory.
   *
   * @param keep {@code true} to keep the chunk loaded, {@code false} to allow it to be unloaded
   */
  public abstract void keep(boolean keep);

  /**
   * Unloads this chunk from memory.
   */
  public abstract void unload();

  /**
   * Whether this chunk instance can answer block-data queries
   * ({@link #isAir}, {@link #isSafe}, {@link #getSkyLight}, {@link #getSurfaceHeight})
   * without requiring the world's live chunk to be loaded.
   *
   * <p>Adapters that carry an out-of-band data source (e.g. the Spigot Anvil-backed
   * {@code BukkitRTPChunk} per ADR-016) override this to {@code true} so
   * that callers performing a stale-chunk guard (ADR-015) can skip the guard
   * safely — the queries will not force a synchronous live-chunk load.</p>
   *
   * <p>Default: {@code false}, meaning the chunk relies on the live world state
   * and is subject to the stale-chunk guard.</p>
   *
   * @return true iff queries on this instance never trigger a live chunk load.
   */
  public boolean isSelfContained() {
    return false;
  }

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
