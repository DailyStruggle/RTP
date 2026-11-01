package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.rtp.api.RTPAPI;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Base {@link SchematicPaster} for Sponge ({@code .schem}, {@code .schematic}) and Litematica
 * ({@code .litematic}) files (leafrtp-ADR-041).
 *
 * <p>Delegates off-tick decoding, normalized-path memory caching, and format checks to
 * {@link SchematicFileLoader} in {@code :schematics-api} (S-004, S-005).
 * Subclasses implement {@link #paste} for platform-specific block placement.
 */
public abstract class AbstractFileSchematicPaster implements SchematicPaster {

  /**
   * Drops every cached schematic so the next {@link #load} re-reads from disk. Intended for
   * {@code /rtp reload} and tests that swap a schematic file in place.
   */
  public static void clearCache() {
    SchematicFileLoader.clearCache();
  }

  @Override
  public CompletableFuture<LoadedSchematic> load(SchematicSource source) {
    return SchematicFileLoader.load(source, () -> RTPAPI.serverAccessor);
  }

  /**
   * Logs {@code message} at {@code level} via {@link SchematicFileLoader#logIfPossible}, falling
   * back to {@link RTPAPI#serverAccessor} when no standalone server accessor is registered.
   *
   * @param level log level
   * @param message message to log
   */
  protected static void logIfPossible(Level level, String message) {
    SchematicFileLoader.logIfPossible(level, message, () -> RTPAPI.serverAccessor);
  }

  /**
   * Logs {@code message} and {@code throwable} at {@code level} via
   * {@link SchematicFileLoader#logIfPossible}, falling back to {@link RTPAPI#serverAccessor} when
   * no standalone server accessor is registered.
   *
   * @param level log level
   * @param message message to log
   * @param throwable exception cause
   */
  protected static void logIfPossible(Level level, String message, Throwable throwable) {
    SchematicFileLoader.logIfPossible(level, message, throwable, () -> RTPAPI.serverAccessor);
  }

  @Override
  public boolean supports(SchematicSource source) {
    return SchematicFileLoader.supports(source);
  }
}
