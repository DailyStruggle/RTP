package io.github.dailystruggle.rtp.common.anvil;

import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;

import java.util.OptionalInt;

/**
 * Platform-neutral adapter exposing an {@code rtp-anvil} {@link ColumnProbe} as
 * the {@link ChunkColumnProbe} consumed by {@code VerticalAdjustor.adjustFromProbe},
 * {@code PregenTask.evaluateProbe}, and {@code ScanTask.evaluateScanProbe}
 * (ADR-016 / BIOME_LOOKUP_PERF_PLAN).
 *
 * <p>Companion to {@code rtp-spigot-common}'s Material-based
 * {@code AnvilColumnProbeAdapter}. This variant has no {@code org.bukkit.Material}
 * enum to reconcile against, so the reconciler is the platform-neutral
 * {@link PaletteIdentifierNormalizer} (namespace-strip + upper-case) — exactly
 * the form {@code SafetyKeys.unsafeBlocks} entries are normalised into for
 * matching on non-Bukkit platforms. Modded ids round-trip unchanged beyond
 * namespace strip + case fold, matching the {@code isSafe(...)} comparison
 * shape of the string-based {@code RTPChunk} implementations.</p>
 *
 * <p>S-005: read-only, off-tick. The backing {@link ColumnProbe} retains
 * the entire decoded section palette, so per-Y lookups are O(1) palette-index
 * indexes — no chunk load, no main-thread work.</p>
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
   * Override the default {@link ChunkColumnProbe#isAirAt(int)} which compares
   * the path segment case-sensitively against lowercase {@code "air"} /
   * {@code "cave_air"} / {@code "void_air"}. {@link #blockAt(int)} above runs
   * every identifier through {@link PaletteIdentifierNormalizer#normalize(String)},
   * which folds the path to upper-case. Without this override, every air block
   * would be reported as non-air and the adjustor would reject every candidate
   * Y — routing 100% of probes back through the full live-load path (the same
   * regression mode previously caught on Spigot — see ADR-016 §10 and the
   * {@code AnvilColumnProbeAdapterIsAirAtTest} regression guard).
   *
   * <p>Tolerate both reconciled upper-case and the raw namespaced form so the
   * override stays correct if a future change loosens the reconciler.</p>
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
