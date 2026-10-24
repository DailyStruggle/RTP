package io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors;

import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import java.util.OptionalInt;
import java.util.function.IntBinaryOperator;
import java.util.function.BiPredicate;

/**
 * Test-only probe over a height field: stone below the floor (feet-Y {@code floor(wx, wz)}), air
 * from it up. {@link #neighbour} and {@link #square()} answer the chunks {@code available} admits,
 * so a test can stand in for a region file's clipping (ADR-120).
 */
public final class TerrainTestProbe implements ChunkColumnProbe {
  private final int cx;
  private final int cz;
  private final int minY;
  private final int maxY;
  private final IntBinaryOperator floor;
  private final BiPredicate<Integer, Integer> available;
  /** Neighbour fetches through {@link #square()}. */
  public final java.util.concurrent.atomic.AtomicInteger fetches = new java.util.concurrent.atomic.AtomicInteger();

  public TerrainTestProbe(int cx, int cz, int minY, int maxY, IntBinaryOperator floor,
      BiPredicate<Integer, Integer> available) {
    this.cx = cx;
    this.cz = cz;
    this.minY = minY;
    this.maxY = maxY;
    this.floor = floor;
    this.available = available;
  }

  @Override
  public int chunkX() {
    return cx;
  }

  @Override
  public int chunkZ() {
    return cz;
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
    return OptionalInt.empty();
  }

  @Override
  public String blockAt(int y) {
    return blockAt(8, 8, y);
  }

  @Override
  public String blockAt(int localX, int localZ, int y) {
    if (y < minY || y > maxY) return null;
    return y >= floor.applyAsInt(cx * 16 + localX, cz * 16 + localZ) ? "minecraft:air" : "minecraft:stone";
  }

  @Override
  public String biomeAt(int y) {
    return "minecraft:plains";
  }

  /** This probe's square: counts fetches in {@link #fetches}. */
  public io.github.dailystruggle.rtp.anvil.ChunkSquare square() {
    return (dx, dz) -> {
      fetches.incrementAndGet();
      return neighbour(dx, dz);
    };
  }

  public io.github.dailystruggle.rtp.anvil.ChunkColumnProbe neighbour(int dx, int dz) {
    if (dx == 0 && dz == 0) return this;
    if (Math.abs(dx) > 2 || Math.abs(dz) > 2 || !available.test(cx + dx, cz + dz)) return null;
    return new TerrainTestProbe(cx + dx, cz + dz, minY, maxY, floor, available);
  }
}
