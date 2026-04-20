package io.github.dailystruggle.rtp.anvil;

/**
 * Thrown by the Anvil reader when the region file exists and is reachable but cannot be
 * safely parsed by the current implementation — e.g. the chunk's {@code DataVersion} is
 * outside {@link DataVersionSupport}'s whitelist, the compression mode is unsupported
 * (LZ4 in phase 1), or the NBT layout diverges from the expected shape.
 *
 * <p>Callers in this package catch this exception and surface a {@link Verdict#UNKNOWN}
 * outcome to the pipeline, which then falls through to the existing live-load path.
 * This class is <strong>never</strong> allowed to leak outside the
 * {@code io.github.dailystruggle.rtp.anvil} package — the public pre-filter API
 * exposes only {@link Verdict} values, never exceptions.</p>
 */
public class UnsupportedAnvilFormatException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnsupportedAnvilFormatException(String message) {
    super(message);
  }

  public UnsupportedAnvilFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
