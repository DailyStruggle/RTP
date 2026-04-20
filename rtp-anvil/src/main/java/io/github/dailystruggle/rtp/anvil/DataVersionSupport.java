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
 * evidence per {@code ANVIL_PREFILTER_PLAN.md} §8.2.</p>
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

  private DataVersionSupport() {
    // Utility class.
  }

  /**
   * Check whether the Anvil reader has been parity-validated for chunks at the given
   * {@code DataVersion}. The whitelist currently matches the exact integers observed
   * in the committed fixtures. Adjacent patch versions that share the same chunk NBT
   * layout would be trivial to extend once new fixtures land, but are intentionally
   * excluded until evidence exists — a {@code DataVersion} that is not whitelisted
   * falls through to the live-load path (the caller converts to {@link Verdict#UNKNOWN}).
   *
   * @param dataVersion the {@code DataVersion} NBT field read from the chunk root tag.
   * @return {@code true} if the format is whitelisted; {@code false} otherwise.
   */
  public static boolean isSupported(int dataVersion) {
    return dataVersion == MC_1_20_DATA_VERSION
        || dataVersion == MC_1_21_DATA_VERSION
        || dataVersion == MC_26_1_DATA_VERSION;
  }
}
