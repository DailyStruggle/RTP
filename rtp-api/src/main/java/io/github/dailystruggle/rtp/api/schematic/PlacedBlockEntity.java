package io.github.dailystruggle.rtp.api.schematic;

import java.util.Objects;

/**
 * A {@link BlockEntityData} resolved to absolute world coordinates by
 * {@link SchematicPlacementPlanner#planBlockEntities}. Platform pasters use this to restore a
 * block entity's payload (chest contents, sign text, ...) at the right place after the blocks
 * themselves have been written. See ADR-058.
 */
public final class PlacedBlockEntity {
  private final int x;
  private final int y;
  private final int z;
  private final BlockEntityData data;

  /**
   * @param x    absolute world X
   * @param y    absolute world Y
   * @param z    absolute world Z
   * @param data the decoded block-entity payload; never {@code null}
   */
  public PlacedBlockEntity(int x, int y, int z, BlockEntityData data) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.data = Objects.requireNonNull(data, "data");
  }

  /** @return absolute world X. */
  public int x() {
    return x;
  }

  /** @return absolute world Y. */
  public int y() {
    return y;
  }

  /** @return absolute world Z. */
  public int z() {
    return z;
  }

  /** @return the decoded block-entity payload (id + relative coords + NBT). */
  public BlockEntityData data() {
    return data;
  }

  @Override
  public String toString() {
    return "PlacedBlockEntity[" + data.id() + " @ " + x + "," + y + "," + z + ']';
  }
}
