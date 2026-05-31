package io.github.dailystruggle.rtp.api.schematic;

import java.util.Objects;

/**
 * A single resolved block write: an absolute world coordinate plus the canonical
 * block-state string to place there. Produced by {@link SchematicPlacementPlanner} and
 * consumed by a platform paster, which converts {@link #blockState()} via its native
 * block parser (e.g. {@code Bukkit.createBlockData(String)}). Platform-neutral; carries
 * no world references.
 */
public final class BlockPlacement {
  private final int x;
  private final int y;
  private final int z;
  private final String blockState;

  /**
   * @param x          absolute world X
   * @param y          absolute world Y
   * @param z          absolute world Z
   * @param blockState canonical block-state string; never {@code null}
   */
  public BlockPlacement(int x, int y, int z, String blockState) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.blockState = Objects.requireNonNull(blockState, "blockState");
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

  /** @return the canonical block-state string for the platform parser. */
  public String blockState() {
    return blockState;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BlockPlacement)) return false;
    BlockPlacement that = (BlockPlacement) o;
    return x == that.x && y == that.y && z == that.z && blockState.equals(that.blockState);
  }

  @Override
  public int hashCode() {
    return Objects.hash(x, y, z, blockState);
  }

  @Override
  public String toString() {
    return "BlockPlacement[" + x + "," + y + "," + z + "=" + blockState + ']';
  }
}
