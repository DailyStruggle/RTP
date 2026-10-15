package io.github.dailystruggle.rtp.api.world;

import io.github.dailystruggle.rtp.api.RTPAPI;
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
   * Returns the upper-cased {@code namespace:path} or bare material/block name for the block at
   * the given chunk-local coordinates and absolute world Y.
   *
   * <p>Subclasses should implement this to provide the raw or normalized identifier of the block
   * at the given location (e.g. {@code "MINECRAFT:STONE"}, {@code "AIR"}). Defaults to {@code ""}
   * on unresolvable coordinates or missing chunk state.
   *
   * @param x chunk-local X (0-15)
   * @param y absolute world Y
   * @param z chunk-local Z (0-15)
   * @return upper-cased material or block name, never null
   */
  public String materialNameAt(int x, int y, int z) {
    return "";
  }

  /**
   * Checks if the block at the specified coordinates within this chunk is air.
   *
   * <p>Evaluates whether {@link #materialNameAt(int, int, int)} is air via {@link RTPAPI#isAir(String)}.
   *
   * @param x the block's X coordinate relative to the chunk (0-15)
   * @param y the block's Y coordinate
   * @param z the block's Z coordinate relative to the chunk (0-15)
   * @return {@code true} if the block is air, {@code false} otherwise
   */
  public boolean isAir(int x, int y, int z) {
    String name = materialNameAt(x, y, z);
    if (name == null || name.isEmpty()) return false;
    return RTPAPI.isAir(name);
  }

  /**
   * Returns the sky light level at the specified coordinates within this chunk.
   *
   * <p>Selection algorithms derive sky openness directly from block data (e.g.
   * {@code computeColumnSkyFloor}); this method returns a safe default.
   *
   * @param x the block's X coordinate relative to the chunk (0-15)
   * @param y the block's Y coordinate
   * @param z the block's Z coordinate relative to the chunk (0-15)
   * @return the sky light level
   */
  public int getSkyLight(int x, int y, int z) {
    return 15;
  }

  /**
   * Checks whether block at chunk-relative (x, y, z) is safe for teleportation.
   *
   * @param x            chunk-local X (0-15)
   * @param y            world Y
   * @param z            chunk-local Z (0-15)
   * @param unsafeBlocks set of unsafe material names
   * @return true if safe for teleportation
   */
  public boolean isSafe(int x, int y, int z, Set<String> unsafeBlocks) {
    if (unsafeBlocks == null || unsafeBlocks.isEmpty()) return true;
    String name = materialNameAt(x, y, z);
    // S-001: an unresolvable block id fails closed rather than passing the unsafe scan.
    if (name == null || name.isEmpty()) return false;
    if (unsafeBlocks.contains(name)) return false;
    int colon = name.indexOf(':');
    if (colon > 0 && unsafeBlocks.contains(name.substring(colon + 1))) return false;
    return true;
  }

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
  public boolean isGenerated() {
    if (isSelfContained()) return true;
    RTPWorld<?> world = getWorld();
    if (world != null) {
      return world.isChunkGenerated(x(), z());
    }
    return chunk != null;
  }

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
  public void unload() {
    keep(false);
  }

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
