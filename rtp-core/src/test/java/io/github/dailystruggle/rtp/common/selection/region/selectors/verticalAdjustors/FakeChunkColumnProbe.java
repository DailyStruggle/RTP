package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * In-memory {@link ChunkColumnProbe} used to exercise {@code adjustFromProbe} without
 * a real Anvil fixture. Defaults every Y to solid-safe {@code minecraft:stone}; tests
 * opt specific Ys into air or into a named unsafe block.
 */
public final class FakeChunkColumnProbe implements ChunkColumnProbe {
  private final int chunkX;
  private final int chunkZ;
  private final int minY;
  private final int maxY;
  private final Map<Integer, String> blockOverrides = new HashMap<>();
  private final Map<Integer, String> biomeOverrides = new HashMap<>();
  private final Map<Integer, Integer> skyLightOverrides = new HashMap<>();
  private String defaultBlock = "minecraft:stone";
  private String defaultBiome = "minecraft:plains";
  private OptionalInt heightmapTopY = OptionalInt.empty();
  private int defaultSkyLight = 15;
  private boolean isLightOn = true;

  public FakeChunkColumnProbe(int chunkX, int chunkZ, int minY, int maxY) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.minY = minY;
    this.maxY = maxY;
  }

  public FakeChunkColumnProbe setBlock(int y, String identifier) {
    blockOverrides.put(y, identifier);
    return this;
  }

  public FakeChunkColumnProbe setAir(int y) {
    return setBlock(y, "minecraft:air");
  }

  public FakeChunkColumnProbe setAirRange(int fromY, int toY) {
    for (int y = fromY; y <= toY; y++) setAir(y);
    return this;
  }

  public FakeChunkColumnProbe setSolid(int y) {
    return setBlock(y, "minecraft:stone");
  }

  public FakeChunkColumnProbe setSolidRange(int fromY, int toY) {
    for (int y = fromY; y <= toY; y++) setSolid(y);
    return this;
  }

  public FakeChunkColumnProbe setBiome(int y, String identifier) {
    biomeOverrides.put(y, identifier);
    return this;
  }

  public FakeChunkColumnProbe setDefaultBlock(String identifier) {
    this.defaultBlock = identifier;
    return this;
  }

  public FakeChunkColumnProbe setDefaultBiome(String identifier) {
    this.defaultBiome = identifier;
    return this;
  }

  public FakeChunkColumnProbe setHeightmapTop(int y) {
    this.heightmapTopY = OptionalInt.of(y);
    return this;
  }

  @Override
  public int chunkX() {
    return chunkX;
  }

  @Override
  public int chunkZ() {
    return chunkZ;
  }

  @Override
  public int minY() {
    return minY;
  }

  @Override
  public int maxY() {
    return maxY;
  }

  @Override
  public OptionalInt heightmapTopY() {
    return heightmapTopY;
  }

  @Override
  public String blockAt(int y) {
    if (y < minY || y > maxY) return null;
    return blockOverrides.getOrDefault(y, defaultBlock);
  }

  @Override
  public String biomeAt(int y) {
    if (y < minY || y > maxY) return null;
    return biomeOverrides.getOrDefault(y, defaultBiome);
  }

  public FakeChunkColumnProbe setSkyLight(int y, int level) {
    skyLightOverrides.put(y, level);
    return this;
  }

  public FakeChunkColumnProbe setDefaultSkyLight(int level) {
    this.defaultSkyLight = level;
    return this;
  }

  public FakeChunkColumnProbe setLightOn(boolean on) {
    this.isLightOn = on;
    return this;
  }

  @Override
  public int skyLightAt(int y) {
    if (y < minY || y > maxY) return 15;
    return skyLightOverrides.getOrDefault(y, defaultSkyLight);
  }

  @Override
  public boolean isLightOn() {
    return isLightOn;
  }
}
