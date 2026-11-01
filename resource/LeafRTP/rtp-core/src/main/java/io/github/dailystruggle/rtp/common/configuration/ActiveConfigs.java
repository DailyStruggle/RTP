package io.github.dailystruggle.rtp.common.configuration;

/**
 * Process-wide slot for the live {@link Configs} (ADR-115 seam 4).
 *
 * <p>Every {@link Configs} constructor installs itself here, so the most recently built instance
 * is current, as with the former {@code ActiveConfigs.get()} static. The slot is {@code volatile} and
 * starts {@code null}: a read before the composition root loads configuration returns
 * {@code null}. Callers that tolerate a missing configuration shall null-check the result.
 */
public final class ActiveConfigs {
  private static volatile Configs current;

  private ActiveConfigs() {
  }

  /** The live configuration, or {@code null} before the first {@link Configs} is built. */
  public static Configs get() {
    return current;
  }

  /** Installs {@code configs} as current; {@code null} clears the slot (tests, shutdown). */
  public static void set(Configs configs) {
    current = configs;
  }
}
