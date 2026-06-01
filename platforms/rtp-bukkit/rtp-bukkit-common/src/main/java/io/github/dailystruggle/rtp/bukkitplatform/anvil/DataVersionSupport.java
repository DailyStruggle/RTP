package io.github.dailystruggle.rtp.bukkitplatform.anvil;

/**
 * Spigot mirror of {@code rtp-anvil}'s DataVersionSupport (ADR-016 §8.2). Gate
 * is retired; constants below are fixture-parity anchors for
 * {@code AnvilFixtureParityTest}. Immutable, thread-safe; static helpers only.
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

  /** Always {@code true}: gate retired (ADR-016 §13.1). Decoder failures surface as {@link Verdict#UNKNOWN}. */
  public static boolean isSupported(int dataVersion) {
    return true;
  }
}
