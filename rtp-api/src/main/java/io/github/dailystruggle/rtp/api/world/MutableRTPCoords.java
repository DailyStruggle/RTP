package io.github.dailystruggle.rtp.api.world;

/**
 * Mutable block-coordinate value used during location generation before freezing.
 *
 * <p>Public fields allow low-overhead mutation in the generator loop.
 * Thread safety: not thread-safe; confine to generation thread until {@link #toImmutable()}.
 *
 * @see RTPCoords
 */
public class MutableRTPCoords {
    public String worldName;
    public int x;
    public int y;
    public int z;

  /**
   * Creates a horizontal-only coordinate with empty world name and y=0.
   *
   * @param x block X coordinate
   * @param z block Z coordinate
   */
    public MutableRTPCoords(int x, int z) {
        this.worldName = "";
        this.x = x;
        this.y = 0;
        this.z = z;
    }

  /**
   * Creates a fully-specified mutable coordinate.
   *
   * @param worldName canonical world name; must not be {@code null}
   * @param x         block X coordinate
   * @param y         block Y coordinate
   * @param z         block Z coordinate
   */
    public MutableRTPCoords(String worldName, int x, int y, int z) {
        this.worldName = worldName;
        this.x = x;
        this.y = y;
        this.z = z;
    }

  /**
   * Updates the X and Z coordinates in a single call, avoiding two separate
   * field assignments in the hot generation loop.
   *
   * @param x new block X coordinate
   * @param z new block Z coordinate
   */
    public void setXZ(int x, int z) {
        this.x = x;
        this.z = z;
    }

  /**
   * Sets the Y (vertical) coordinate, typically after a surface or cave scan.
   *
   * @param y new block Y coordinate
   */
    public void setY(int y) {
        this.y = y;
    }

  /**
   * Sets the world name, typically once the target world is determined.
   *
   * @param worldName canonical world name; must not be {@code null}
   */
    public void setWorldName(String worldName) {
        this.worldName = worldName;
    }

  /**
   * Creates an immutable {@link RTPCoords} snapshot of current field values.
   *
   * @return new immutable coordinate snapshot
   */
    public RTPCoords toImmutable() {
        return new RTPCoords(worldName, x, y, z);
    }
}
