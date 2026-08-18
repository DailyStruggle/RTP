package io.github.dailystruggle.rtp.common.anvil;

import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;

import java.util.OptionalInt;

/**
 * Platform-neutral adapter exposing an {@code rtp-anvil} {@link ColumnProbe} as
 * {@link ChunkColumnProbe} for off-tick pre-filtering (ADR-016).
 *
 * <p>Uses {@link PaletteIdentifierNormalizer} to upper-case and strip namespaces
 * for non-Bukkit matching. S-005 compliant (no chunk loading or main-thread work).
 */
public final class AnvilColumnProbeAdapter implements ChunkColumnProbe {

  private final ColumnProbe probe;
  private final int chunkX;
  private final int chunkZ;

  /**
   * Constructs an adapter wrapping the given {@link ColumnProbe}.
   *
   * @param probe  the decoded anvil column probe; never {@code null}
   * @param chunkX chunk X coordinate in the world's chunk grid
   * @param chunkZ chunk Z coordinate in the world's chunk grid
   */
  public AnvilColumnProbeAdapter(ColumnProbe probe, int chunkX, int chunkZ) {
    if (probe == null) throw new NullPointerException("probe");
    this.probe = probe;
    this.chunkX = chunkX;
    this.chunkZ = chunkZ;
  }

  @Override public int chunkX() { return chunkX; }
  @Override public int chunkZ() { return chunkZ; }
  @Override public int minY()   { return probe.minY(); }
  @Override public int maxY()   { return probe.maxY(); }

  @Override
  public OptionalInt heightmapTopY() {
    return probe.hasHeightmap() ? OptionalInt.of(probe.heightmapTopY()) : OptionalInt.empty();
  }

  @Override
  public String blockAt(int y) {
    return reconcile(probe.blockAt(y));
  }

  @Override
  public String blockAt(int localX, int localZ, int y) {
    return reconcile(probe.blockAt(localX, localZ, y));
  }

  @Override
  public String biomeAt(int y) {
    return reconcile(probe.biomeAt(y));
  }

  /**
   * Overrides {@link ChunkColumnProbe#isAirAt(int)} to support upper-cased normalized identifiers.
   * Tolerates both normalized upper-case and raw namespaced air variants.
   */
  @Override
  public boolean isAirAt(int y) {
    String b = blockAt(y);
    return isAirIdentifier(b);
  }

  @Override
  public boolean isAirAt(int localX, int localZ, int y) {
    String b = blockAt(localX, localZ, y);
    return isAirIdentifier(b);
  }

  private static boolean isAirIdentifier(String b) {
    if (b == null) return false;
    if (b.equals("AIR") || b.equals("CAVE_AIR") || b.equals("VOID_AIR")) return true;
    int colon = b.indexOf(':');
    String path = (colon >= 0) ? b.substring(colon + 1) : b;
    return path.equals("air") || path.equals("cave_air") || path.equals("void_air")
        || path.equals("AIR") || path.equals("CAVE_AIR") || path.equals("VOID_AIR");
  }

  private static String reconcile(String raw) {
    if (raw == null) return null;
    String n = PaletteIdentifierNormalizer.normalize(raw);
    return (n != null && !n.isEmpty()) ? n : raw;
  }
}
