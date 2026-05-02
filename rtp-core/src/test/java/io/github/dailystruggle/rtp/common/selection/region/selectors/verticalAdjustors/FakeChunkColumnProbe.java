package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

/**
 * <b>Test-only</b> in-memory {@link ChunkColumnProbe} used to exercise
 * {@code adjustFromProbe} without a real Anvil fixture. Defaults every Y to
 * solid-safe {@code minecraft:stone}; tests opt specific Ys into air or into a
 * named unsafe block via the fluent {@code withBlock(...)} / {@code setAir(...)}
 * / {@code setSolid(...)} helpers.
 *
 * <p><b>Not a production probe.</b> The production {@link ChunkColumnProbe} and
 * its concrete implementation ({@code io.github.dailystruggle.rtp.anvil.ColumnProbe})
 * are read-only views over decoded NBT / live-chunk data and expose no mutation
 * surface. The {@code with*} methods on this class are test-fixture staging only
 * — they populate an in-memory map that backs {@link #blockAt(int)} / etc. They
 * do <b>not</b>, and cannot, write blocks into any world. Do not copy this class
 * (or its API shape) into {@code src/main}.
 */
public final class FakeChunkColumnProbe implements ChunkColumnProbe {
  private final int chunkX;
  private final int chunkZ;
  private final int minY;
  private final int maxY;
  private final Map<Integer, String> blockOverrides = new HashMap<>();
  private final Map<Integer, String> biomeOverrides = new HashMap<>();
  // Per-column overrides keyed by ((localX << 8) | localZ) → (Y → block id).
  // Allows tests covering the multi-column probe sweep (testCoords) to make a
  // chunk where the center column has no foothold but an off-center column
  // does — exercising the {@link ChunkColumnProbe#blockAt(int, int, int)}
  // entry-point that the production AnvilColumnProbeAdapter overrides.
  // Lookup falls back to the Y-only blockOverrides map (uniform-column
  // behaviour) when no per-column entry exists, preserving back-compat for
  // every existing test that doesn't opt in.
  private final Map<Integer, Map<Integer, String>> perColumnBlockOverrides = new HashMap<>();
  private String defaultBlock = "minecraft:stone";
  private String defaultBiome = "minecraft:plains";
  private OptionalInt heightmapTopY = OptionalInt.empty();

  public FakeChunkColumnProbe(int chunkX, int chunkZ, int minY, int maxY) {
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
    this.minY = minY;
    this.maxY = maxY;
  }

  public FakeChunkColumnProbe withBlock(int y, String identifier) {
    blockOverrides.put(y, identifier);
    return this;
  }

  public FakeChunkColumnProbe setAir(int y) {
    return withBlock(y, "minecraft:air");
  }

  public FakeChunkColumnProbe setAirRange(int fromY, int toY) {
    for (int y = fromY; y <= toY; y++) setAir(y);
    return this;
  }

  public FakeChunkColumnProbe setSolid(int y) {
    return withBlock(y, "minecraft:stone");
  }

  public FakeChunkColumnProbe setSolidRange(int fromY, int toY) {
    for (int y = fromY; y <= toY; y++) setSolid(y);
    return this;
  }

  public FakeChunkColumnProbe setBiome(int y, String identifier) {
    biomeOverrides.put(y, identifier);
    return this;
  }

  public FakeChunkColumnProbe withDefaultBlock(String identifier) {
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

  /**
   * Stage a per-column block override. Subsequent
   * {@link #blockAt(int, int, int)} reads at {@code (localX, localZ, y)}
   * return {@code identifier}; reads at any other column at the same Y
   * (or via the legacy {@link #blockAt(int)} entry-point) still return the
   * Y-keyed default.
   */
  public FakeChunkColumnProbe withColumnBlock(int localX, int localZ, int y, String identifier) {
    int key = (localX << 8) | localZ;
    perColumnBlockOverrides.computeIfAbsent(key, k -> new HashMap<>()).put(y, identifier);
    return this;
  }

  /** Convenience for {@link #withColumnBlock(int, int, int, String)} with {@code minecraft:air}. */
  public FakeChunkColumnProbe setColumnAir(int localX, int localZ, int y) {
    return withColumnBlock(localX, localZ, y, "minecraft:air");
  }

  /** Convenience for {@link #withColumnBlock(int, int, int, String)} with {@code minecraft:stone}. */
  public FakeChunkColumnProbe setColumnSolid(int localX, int localZ, int y) {
    return withColumnBlock(localX, localZ, y, "minecraft:stone");
  }

  @Override
  public String blockAt(int localX, int localZ, int y) {
    if (y < minY || y > maxY) return null;
    Map<Integer, String> col = perColumnBlockOverrides.get((localX << 8) | localZ);
    if (col != null) {
      String v = col.get(y);
      if (v != null) return v;
    }
    return blockOverrides.getOrDefault(y, defaultBlock);
  }

  @Override
  public String biomeAt(int y) {
    if (y < minY || y > maxY) return null;
    return biomeOverrides.getOrDefault(y, defaultBiome);
  }

}
