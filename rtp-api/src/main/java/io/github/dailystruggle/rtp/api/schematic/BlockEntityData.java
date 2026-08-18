package io.github.dailystruggle.rtp.api.schematic;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-neutral description of a single block entity decoded from a schematic (ADR-058).
 * Coordinates are relative to the schematic origin.
 */
public final class BlockEntityData {
  private final int x;
  private final int y;
  private final int z;
  private final String id;
  private final Map<String, Object> nbt;

  /**
   * @param x   relative X within the schematic
   * @param y   relative Y within the schematic
   * @param z   relative Z within the schematic
   * @param id  the block-entity id (e.g. {@code minecraft:chest}); never {@code null}
   * @param nbt the raw NBT payload; never {@code null} (may be empty)
   */
  public BlockEntityData(int x, int y, int z, String id, Map<String, Object> nbt) {
    this.x = x;
    this.y = y;
    this.z = z;
    this.id = Objects.requireNonNull(id, "id");
    this.nbt = Collections.unmodifiableMap(Objects.requireNonNull(nbt, "nbt"));
  }

  /** @return relative X within the schematic. */
  public int x() {
    return x;
  }

  /** @return relative Y within the schematic. */
  public int y() {
    return y;
  }

  /** @return relative Z within the schematic. */
  public int z() {
    return z;
  }

  /** @return the block-entity id, e.g. {@code minecraft:chest}. */
  public String id() {
    return id;
  }

  /** @return the raw, unmodifiable NBT payload tree (may be empty). */
  public Map<String, Object> nbt() {
    return nbt;
  }

  @Override
  public String toString() {
    return "BlockEntityData[" + id + " @ " + x + "," + y + "," + z + ']';
  }
}
