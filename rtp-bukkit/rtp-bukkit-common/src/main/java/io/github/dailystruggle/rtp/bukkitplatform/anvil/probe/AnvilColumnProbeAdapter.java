package io.github.dailystruggle.rtp.bukkitplatform.anvil.probe;

import io.github.dailystruggle.rtp.anvil.ColumnProbe;
import io.github.dailystruggle.rtp.api.world.ChunkColumnProbe;
import io.github.dailystruggle.rtp.bukkitplatform.anvil.PaletteNormalizer;

import java.util.OptionalInt;
import java.util.function.UnaryOperator;

/**
 * Adapter that exposes an {@code rtp-anvil} {@link ColumnProbe} as the
 * platform-neutral {@link ChunkColumnProbe} consumed by
 * {@code VerticalAdjustor.adjustFromProbe} and {@code PregenTask.evaluateProbe}
 * (see {@code docs/dev/BIOME_LOOKUP_PERF_PLAN.md}).
 *
 * <p>Lives in {@code rtp-spigot-common} because {@code rtp-anvil} is deliberately
 * kept platform-neutral (no dependency on {@code rtp-api}), while {@code rtp-core}
 * does not depend on {@code rtp-anvil}. Both {@code BukkitRTPWorld} and
 * {@code FoliaRTPWorld} (via the {@code rtp-paper-common → rtp-spigot-common}
 * transitive chain) can reach this class and use it to satisfy
 * {@code RTPWorld.probeChunkColumn}.</p>
 *
 * <p>Identifier reconciliation mirrors the existing {@code AnvilPrefilter} path:
 * palette identifiers from disk are normalised via {@link PaletteNormalizer#reconcile}
 * so that downstream consumers (e.g. biome-name matching in {@code PregenTask}) see
 * the same canonical form they would see when reading a live chunk.</p>
 */
public final class AnvilColumnProbeAdapter implements ChunkColumnProbe {

  private static final UnaryOperator<String> RECONCILER = PaletteNormalizer::reconcile;

  private final ColumnProbe probe;
  private final int chunkX;
  private final int chunkZ;

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

  /**
   * Off-center column read. The backing {@link ColumnProbe} already retains
   * the entire decoded section palette, so this is an O(1) palette-index
   * lookup with the same {@link PaletteNormalizer#reconcile reconciliation}
   * applied as the center-column entry point — keeping safety / air-set
   * comparisons consistent across all five {@code testCoords} columns the
   * adjustor probe path now scans.
   */
  @Override
  public String blockAt(int localX, int localZ, int y) {
    return reconcile(probe.blockAt(localX, localZ, y));
  }

  @Override
  public String biomeAt(int y) {
    return reconcile(probe.biomeAt(y));
  }

  /**
   * Override the default {@link ChunkColumnProbe#isAirAt(int)} implementation, which
   * case-sensitively compares the path segment against lowercase {@code "air"} /
   * {@code "cave_air"} / {@code "void_air"}. {@link #blockAt(int)} runs every identifier
   * through {@link PaletteNormalizer#reconcile(String)} so that downstream
   * {@code unsafeBlocks.contains(...)} lookups (which are populated from config as
   * {@link org.bukkit.Material#name()} upper-case tokens like {@code "LAVA"}) match.
   * As a side effect, reconciled air returns as {@code "AIR"} / {@code "CAVE_AIR"} /
   * {@code "VOID_AIR"} — the default {@code isAirAt} would then report every air block
   * as non-air, which in turn makes {@code JumpAdjustor.acceptProbeY} and
   * {@code LinearAdjustor.acceptY} reject every candidate Y. That routed 100% of
   * probes back through the full-load path (observed as {@code adjustNull=activeChecks}
   * on the ScanTask concurrency gauge).
   *
   * <p>Accept both the reconciled upper-case form and the raw namespaced form; the
   * latter keeps the override correct for modded identifiers that
   * {@link org.bukkit.Material#matchMaterial(String)} does not resolve and that
   * therefore fall back to {@link io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer#normalize(String)}
   * (namespace-stripped upper-case).</p>
   */
  @Override
  public boolean isAirAt(int y) {
    String b = blockAt(y);
    if (b == null) return false;
    // Reconciled vanilla air: "AIR" / "CAVE_AIR" / "VOID_AIR".
    if (b.equals("AIR") || b.equals("CAVE_AIR") || b.equals("VOID_AIR")) return true;
    // Fall back to the default case-sensitive path-segment check for the rare
    // case where reconcile() was a no-op (null input guarded above; raw pass-through
    // shape preserved for forward compatibility).
    int colon = b.indexOf(':');
    String path = (colon >= 0) ? b.substring(colon + 1) : b;
    return path.equals("air") || path.equals("cave_air") || path.equals("void_air")
        || path.equals("AIR") || path.equals("CAVE_AIR") || path.equals("VOID_AIR");
  }

  /**
   * Off-center air check. Mirrors the center-column override: tolerate both
   * the reconciled upper-case form ({@code "AIR"} / {@code "CAVE_AIR"} /
   * {@code "VOID_AIR"}) and the raw namespaced form, so the multi-column
   * probe sweep matches the live-path tolerance regardless of which producer
   * pipeline reconciled the identifier.
   */
  @Override
  public boolean isAirAt(int localX, int localZ, int y) {
    String b = blockAt(localX, localZ, y);
    if (b == null) return false;
    if (b.equals("AIR") || b.equals("CAVE_AIR") || b.equals("VOID_AIR")) return true;
    int colon = b.indexOf(':');
    String path = (colon >= 0) ? b.substring(colon + 1) : b;
    return path.equals("air") || path.equals("cave_air") || path.equals("void_air")
        || path.equals("AIR") || path.equals("CAVE_AIR") || path.equals("VOID_AIR");
  }

  private static String reconcile(String raw) {
    if (raw == null) return null;
    String n = RECONCILER.apply(raw);
    return (n != null && !n.isEmpty()) ? n : raw;
  }
}
