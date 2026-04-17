package io.github.dailystruggle.rtp.api.world;

/**
 * Mutable block-coordinate value used during the location generation pipeline
 * before a candidate position is confirmed and frozen as a {@link RTPCoords}.
 *
 * <p>Fields are intentionally {@code public} for low-overhead sequential mutation
 * inside the generation loop. Once generation is complete, call {@link #toImmutable()}
 * to obtain a thread-safe snapshot suitable for passing across the API boundary.
 *
 * <p><b>Thread safety:</b> This class is <em>not</em> thread-safe. Instances must
 * be confined to a single thread during the generation phase.
 *
 * @see RTPCoords
 */
public class MutableRTPCoords {
    public String worldName;
    public int x;
    public int y;
    public int z;

  /**
   * Creates a horizontal-only coordinate with {@code worldName} set to an empty
   * string and {@code y} set to {@code 0}.
   *
   * <p>Use this constructor when only the XZ plane is known at the time of
   * candidate selection; call {@link #setY(int)} and {@link #setWorldName(String)}
   * once the surface Y and world are resolved.
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
   * Creates an immutable snapshot of the current state.
   *
   * <p>This is a cheap field-copy operation and does not validate the coordinate
   * values. Callers are responsible for ensuring the coordinate represents a
   * valid, verified location before sharing the result across threads.
   *
   * @return a new {@link RTPCoords} with the current field values
   */
    public RTPCoords toImmutable() {
        return new RTPCoords(worldName, x, y, z);
    }
}
