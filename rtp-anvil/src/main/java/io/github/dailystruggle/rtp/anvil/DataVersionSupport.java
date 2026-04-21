package io.github.dailystruggle.rtp.anvil;

/**
 * Whitelist of Minecraft {@code DataVersion} ranges whose region-file layout the Anvil
 * read-only pre-filter is known to parse correctly. A chunk's {@code DataVersion} is the
 * canonical identifier for its on-disk format (see
 * <a href="https://minecraft.wiki/w/Data_version">Minecraft wiki — Data version</a>);
 * adjacent values within the same minor release usually share the same layout, but breaking
 * changes between major releases (section Y-range shift in 1.18, biome storage move from 2D
 * to 3D palette in 1.18, heightmap bit-width changes, 1.20.2 LZ4 compression mode) require
 * an explicit opt-in per range.
 *
 * <p>The whitelist is deliberately conservative: a {@code DataVersion} not covered here
 * yields {@link Verdict#UNKNOWN} and falls through to the existing live-load path. New
 * Minecraft releases opt in by extending the whitelist and adding fixture-backed parity
 * evidence per {@code ADR-016} §8.2.</p>
 *
 * <p>This class is immutable and thread-safe; it exposes only static helpers.</p>
 *
 * <h3>Whitelist semantics</h3>
 *
 * <p>"Supported" here means "the structural NBT walk performed by
 * {@link AnvilReader} has been parity-validated against a real server-produced
 * {@code r.X.Z.mca} for this {@code DataVersion}" — i.e. the root compound can be
 * decoded, {@code DataVersion} and {@code Heightmaps.MOTION_BLOCKING_NO_LEAVES} are
 * reachable, and the chunk section list parses. It does <em>not</em> yet imply the
 * verdict layer (Phase 2 {@code AnvilPrefilter}) can produce a {@link Verdict#REJECT}
 * from this format — that verdict-level opt-in requires additional palette-semantics
 * evidence and will be gated separately when {@code AnvilPrefilter} lands.
 */
public final class DataVersionSupport {

  /**
   * Parity-validated against {@code rtp-spigot-common/src/test/resources/anvil/real/1_20_R1/r.0.0.mca}.
   * Produced by Minecraft 1.20.4 / Spigot v1_20_R3; covered by {@code AnvilFixtureParityTest}.
   */
  public static final int MC_1_20_DATA_VERSION = 3465;

  /**
   * Parity-validated against {@code rtp-spigot-common/src/test/resources/anvil/real/1_21_R1/r.0.0.mca}.
   * Produced by Minecraft 1.21.5; covered by {@code AnvilFixtureParityTest}.
   */
  public static final int MC_1_21_DATA_VERSION = 4671;

  /**
   * Parity-validated against {@code rtp-spigot-common/src/test/resources/anvil/real/26_1_R1/r.0.0.mca}.
   * Produced by Minecraft 26.1; covered by {@code AnvilFixtureParityTest}.
   */
  public static final int MC_26_1_DATA_VERSION = 4788;

  /**
   * Inclusive lower bound of the supported {@code DataVersion} range.
   *
   * <p>Set to {@code 3454} — Minecraft 1.20 release candidate / final (1.20.0 ships
   * at 3463). From 1.20 onward the chunk NBT layout that {@link AnvilReader} walks
   * (section Y-range, 3D biome palette introduced in 1.18 and stable since, heightmap
   * bit width, compression modes 1–4 including LZ4 from 1.20.2) is sufficiently stable
   * that adjacent patch versions produce binary-compatible region files for our purposes.
   *
   * <p>Prior to 1.20, biome-palette semantics and heightmap bit width differed enough
   * that parity was never validated. Operators on 1.19 and below fall through to the
   * live-load path (verdict {@link Verdict#UNKNOWN}), which is the safe default.
   */
  public static final int MIN_SUPPORTED_DATA_VERSION = 3454;

  /**
   * Inclusive upper bound of the supported {@code DataVersion} range.
   *
   * <p>Set to {@code 5000} — a generous ceiling above the highest fixture-validated
   * constant ({@link #MC_26_1_DATA_VERSION} = 4788). Future Minecraft releases that
   * break the chunk NBT layout (section Y-range shift, heightmap bit-width change,
   * new compression mode, etc.) shall tighten this ceiling and add a new fixture
   * per {@code ADR-016} §8.2.
   *
   * <p>Ratcheting the ceiling up without fixture evidence is only safe when no
   * breaking format change has shipped; errant parsing failures in the probe path
   * are still attributed to {@code FailTypes.nullChunk} telemetry via the
   * {@code try/catch} in {@link AnvilPrefilter#probeSyncDetailed}, so a corrupt
   * read never produces an unsafe teleport — only a retry.
   */
  public static final int MAX_SUPPORTED_DATA_VERSION = 5000;

  private DataVersionSupport() {
    // Utility class.
  }

  /**
   * Check whether the Anvil reader has been parity-validated for chunks at the given
   * {@code DataVersion}. The check is range-based over
   * {@code [MIN_SUPPORTED_DATA_VERSION, MAX_SUPPORTED_DATA_VERSION]}: every MC
   * release in the 1.20.x / 1.21.x / 26.x families (and future patch releases
   * below the ceiling) is admitted because their chunk NBT layout is structurally
   * equivalent to the fixture-validated constants {@link #MC_1_20_DATA_VERSION},
   * {@link #MC_1_21_DATA_VERSION}, {@link #MC_26_1_DATA_VERSION}.
   *
   * <p>Rationale (2026-04-20): the prior exact-value whitelist rejected every
   * real-world DataVersion other than the three fixture integers — e.g. an operator
   * running 1.21.1 (DataVersion 3955), 1.21.3 (4082), 1.21.4 (4189), 1.21.6 (4435),
   * 1.21.7 (4438), 1.21.8 (4440) observed zero Anvil probe hits because the gate
   * rejected every live chunk as {@link Verdict#UNKNOWN}. The range form repairs
   * coverage across the supported version matrix without weakening the
   * "unsupported → UNKNOWN → live load" fall-through contract for pre-1.20 and
   * far-future {@code DataVersion}s.
   *
   * @param dataVersion the {@code DataVersion} NBT field read from the chunk root tag.
   * @return {@code true} if the format is whitelisted; {@code false} otherwise.
   */
  public static boolean isSupported(int dataVersion) {
    // 2026-04-20 follow-up (ADR-016 §13.1 debug on Folia 1.21.11):
    // The DataVersion range gate has been retired so that the probe path
    // is attempted on every chunk regardless of version. The decoder itself
    // (`AnvilReader` → `AnvilChunkView`) remains the real correctness
    // boundary: any NBT-layout incompatibility surfaces as an
    // IOException/RuntimeException in `AnvilPrefilter.probeSyncDetailed`,
    // which is caught and attributed to `Verdict.UNKNOWN` with a WARNING
    // log line. Admitting every DataVersion lets us distinguish "version
    // was out of range" (previous silent rejection) from "decoder could
    // not parse this chunk" (the genuine layout-drift case).
    //
    // The MIN_/MAX_SUPPORTED_DATA_VERSION constants are retained above as
    // historical parity anchors for `AnvilFixtureParityTest`; they no
    // longer gate the probe.
    return true;
  }
}
