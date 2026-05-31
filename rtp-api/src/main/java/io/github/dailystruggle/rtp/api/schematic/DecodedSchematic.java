package io.github.dailystruggle.rtp.api.schematic;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Concrete, in-memory {@link LoadedSchematic} produced by {@link SpongeSchematicDecoder}.
 * Holds the palette, a dense per-cell palette-index grid (Sponge ordering
 * {@code index = (y * length + z) * width + x}), the decoded block entities, and the
 * origin offset. Carries no live world references and is safe to cache (ADR-058 §1,
 * Amendment 1).
 */
public final class DecodedSchematic implements LoadedSchematic {
  private final SchematicSource source;
  private final int width;
  private final int height;
  private final int length;
  private final List<String> palette;
  private final int[] indices;
  private final List<BlockEntityData> blockEntities;
  private final int offsetX;
  private final int offsetY;
  private final int offsetZ;

  /**
   * @param source        the source this was decoded from; never {@code null}
   * @param width         X extent (&gt;= 0)
   * @param height        Y extent (&gt;= 0)
   * @param length        Z extent (&gt;= 0)
   * @param palette       palette index -&gt; block-state string; never {@code null}
   * @param indices       dense {@code width*height*length} palette indices in Sponge order
   * @param blockEntities decoded block entities; never {@code null}
   * @param offset        origin offset {@code [x, y, z]}; never {@code null}, length 3
   */
  public DecodedSchematic(SchematicSource source, int width, int height, int length,
                          List<String> palette, int[] indices,
                          List<BlockEntityData> blockEntities, int[] offset) {
    this.source = Objects.requireNonNull(source, "source");
    this.width = width;
    this.height = height;
    this.length = length;
    this.palette = Collections.unmodifiableList(Objects.requireNonNull(palette, "palette"));
    this.indices = Objects.requireNonNull(indices, "indices").clone();
    this.blockEntities =
        Collections.unmodifiableList(Objects.requireNonNull(blockEntities, "blockEntities"));
    Objects.requireNonNull(offset, "offset");
    if (offset.length != 3) {
      throw new IllegalArgumentException("offset must have length 3");
    }
    if (indices.length != width * height * length) {
      throw new IllegalArgumentException(
          "indices length " + indices.length + " != width*height*length "
              + (width * height * length));
    }
    this.offsetX = offset[0];
    this.offsetY = offset[1];
    this.offsetZ = offset[2];
  }

  @Override
  public SchematicSource source() {
    return source;
  }

  @Override
  public int width() {
    return width;
  }

  @Override
  public int height() {
    return height;
  }

  @Override
  public int length() {
    return length;
  }

  @Override
  public List<String> palette() {
    return palette;
  }

  @Override
  public int paletteIndexAt(int x, int y, int z) {
    if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
      return -1;
    }
    return indices[(y * length + z) * width + x];
  }

  @Override
  public List<BlockEntityData> blockEntities() {
    return blockEntities;
  }

  @Override
  public int offsetX() {
    return offsetX;
  }

  @Override
  public int offsetY() {
    return offsetY;
  }

  @Override
  public int offsetZ() {
    return offsetZ;
  }

  @Override
  public String toString() {
    return "DecodedSchematic[" + source.name() + " " + width + "x" + height + "x" + length
        + ", palette=" + palette.size() + ", blockEntities=" + blockEntities.size() + ']';
  }
}
