package io.github.dailystruggle.rtp.anvil;

/**
 * DataVersion gate for the Anvil read-only pre-filter (ADR-016 §8.2). Currently
 * admits every chunk and lets the decoder be the correctness boundary; failures
 * surface as {@link Verdict#UNKNOWN} and fall through to live-load. Constants
 * below are fixture-parity anchors retained for {@code AnvilFixtureParityTest}.
 * Immutable, thread-safe; static helpers only.
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

  /** Lower bound of the historic supported range (1.20 RC; retained for parity tests). */
  public static final int MIN_SUPPORTED_DATA_VERSION = 3454;

  /** Upper bound of the historic supported range; tighten when chunk NBT layout breaks (ADR-016 §8.2). */
  public static final int MAX_SUPPORTED_DATA_VERSION = 5000;

  private DataVersionSupport() {
    // Utility class.
  }

  /**
   * Always {@code true}: the gate is retired (ADR-016 §13.1, 2026-04-20). The decoder
   * ({@code AnvilReader} → {@code AnvilChunkView}) is the correctness boundary; layout
   * drift surfaces as {@link Verdict#UNKNOWN} via the {@code try/catch} in
   * {@link AnvilPrefilter#probeSyncDetailed}.
   */
  public static boolean isSupported(int dataVersion) {
    return true;
  }
}
