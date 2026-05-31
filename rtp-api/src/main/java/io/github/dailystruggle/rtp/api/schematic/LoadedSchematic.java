package io.github.dailystruggle.rtp.api.schematic;

/**
 * Opaque, platform-neutral handle to a decoded schematic payload plus its
 * bounding-box dimensions. The dimensions let {@code rtp-core} compute the paste
 * footprint for the claim check (S-003) <em>before</em> any block is written, without
 * the core ever touching a platform schematic type.
 *
 * <p>A {@code LoadedSchematic} holds no live world references and is safe to cache and
 * to hand back to {@link SchematicPaster#paste}. See ADR-058 §1.
 */
public interface LoadedSchematic {
  /** @return the source this schematic was decoded from. */
  SchematicSource source();

  /** @return bounding-box extent along X, in blocks (&gt;= 0). */
  int width();

  /** @return bounding-box extent along Y, in blocks (&gt;= 0). */
  int height();

  /** @return bounding-box extent along Z, in blocks (&gt;= 0). */
  int length();

  /**
   * The block-state palette: index {@code i} maps to the canonical block-state string
   * (e.g. {@code minecraft:oak_log[axis=y]}) referenced by {@link #paletteIndexAt}.
   *
   * <p>Default {@code []} keeps pre-Amendment-1 opaque handles (and test doubles) valid.
   *
   * @return the immutable palette; never {@code null}
   */
  default java.util.List<String> palette() {
    return java.util.Collections.emptyList();
  }

  /**
   * @param x relative X (0 .. {@code width()-1})
   * @param y relative Y (0 .. {@code height()-1})
   * @param z relative Z (0 .. {@code length()-1})
   * @return the {@link #palette()} index at the cell, or {@code -1} when out of bounds or
   *     when this handle does not expose decoded blocks
   */
  default int paletteIndexAt(int x, int y, int z) {
    return -1;
  }

  /** @return the decoded block entities (chests, signs, ...); never {@code null}. */
  default java.util.List<BlockEntityData> blockEntities() {
    return java.util.Collections.emptyList();
  }

  /** @return the schematic origin offset along X (Sponge {@code Offset[0]}). */
  default int offsetX() {
    return 0;
  }

  /** @return the schematic origin offset along Y (Sponge {@code Offset[1]}). */
  default int offsetY() {
    return 0;
  }

  /** @return the schematic origin offset along Z (Sponge {@code Offset[2]}). */
  default int offsetZ() {
    return 0;
  }
}
