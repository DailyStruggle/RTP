package io.github.dailystruggle.server.api;

import java.util.Objects;

/**
 * Platform-neutral context for a chunk load event (ADR-049, ADR-115).
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 */
public final class ChunkLoadContext {
  private final String worldName;
  private final int chunkX;
  private final int chunkZ;
  private final boolean newChunk;
  private final long worldSeed;

  /**
   * Constructs a platform-neutral chunk load context.
   *
   * @param worldName canonical world name; must not be {@code null}
   * @param chunkX chunk X coordinate
   * @param chunkZ chunk Z coordinate
   * @param newChunk {@code true} if the chunk was newly generated
   * @param worldSeed world seed
   */
  public ChunkLoadContext(
      String worldName, int chunkX, int chunkZ, boolean newChunk, long worldSeed) {
    this.worldName = Objects.requireNonNull(worldName, "worldName");
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.newChunk = newChunk;
    this.worldSeed = worldSeed;
  }

  public String worldName() {
    return worldName;
  }

  public int chunkX() {
    return chunkX;
  }

  public int chunkZ() {
    return chunkZ;
  }

  public boolean newChunk() {
    return newChunk;
  }

  public long worldSeed() {
    return worldSeed;
  }
}
