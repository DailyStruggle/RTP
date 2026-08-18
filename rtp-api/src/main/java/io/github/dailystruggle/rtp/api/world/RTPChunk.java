package io.github.dailystruggle.rtp.api.world;

import io.github.dailystruggle.rtp.api.safety.CompiledUnsafeSet;
import java.util.Objects;
import java.util.Set;

/**
 * Platform-agnostic representation of a world chunk.
 *
 * @param <T> underlying platform-specific chunk object type
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
   * Checks whether block at chunk-relative (x, y, z) is safe for teleportation.
   *
   * @param x            chunk-local X (0-15)
   * @param y            world Y
   * @param z            chunk-local Z (0-15)
   * @param unsafeBlocks set of unsafe material names
   * @return true if safe for teleportation
   */
  public abstract boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks);

  /**
   * Compiled-form overload (ADR-017). Adapters with native tag/property access
   * should override and evaluate against the {@link CompiledUnsafeSet} directly;
   * the default delegates to {@link #isSafe(int, int, int, Set)} using the plain
   * materials bucket only - tag/state predicates are inert until overridden.
   * Pass {@link CompiledUnsafeSet#EMPTY} for "nothing unsafe".
   */
  public boolean isSafe(int x, int y, int z, CompiledUnsafeSet unsafeBlocks) {
    Objects.requireNonNull(unsafeBlocks, "unsafeBlocks");
    return isSafe(x, y, z, unsafeBlocks.plainMaterials());
  }

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
   * Returns biome identifier at world (x, y, z) from this chunk's backing (ADR-016).
   *
   * @param x absolute world X
   * @param y absolute world Y
   * @param z absolute world Z
   * @return biome identifier, or null if unresolvable
   */
  public String getBiome(int x, int y, int z) {
    RTPWorld<?> w = getWorld();
    return (w != null) ? w.getBiome(x, y, z) : null;
  }

  /**
   * Returns whether block queries run without requiring a loaded live chunk (ADR-015, ADR-016).
   *
   * @return true if queries never trigger live chunk I/O
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
