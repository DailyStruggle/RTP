package io.github.dailystruggle.rtp.api.schematic;

import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Platform-neutral file loader and normalized-path memory cache for Sponge ({@code .schem},
 * {@code .schematic}) and Litematica ({@code .litematic}) schematics (leafrtp-ADR-041).
 *
 * <p>Handles off-tick decoding and failure auditing (S-004, S-005) without depending on {@code rtp-api}.
 *
 * @governedBy ADR-115
 * @governedBy leafrtp-ADR-041
 * @trace REQ-RTP-S-004
 * @trace REQ-RTP-S-005
 */
public final class SchematicFileLoader {

  /** Decoded-schematic cache keyed by absolute normalized file path. */
  private static final Map<String, LoadedSchematic> CACHE = new ConcurrentHashMap<>();

  private SchematicFileLoader() {}

  /**
   * Drops every cached schematic so the next {@link #load} re-reads from disk.
   */
  public static void clearCache() {
    CACHE.clear();
  }

  /**
   * Decodes or serves from cache the schematic described by {@code source}.
   *
   * @param source schematic source descriptor; may be {@code null}
   * @return completed future carrying the decoded schematic, or {@code null} on failure
   */
  public static CompletableFuture<LoadedSchematic> load(SchematicSource source) {
    return load(source, null);
  }

  /**
   * Decodes or serves from cache the schematic described by {@code source}, using
   * {@code fallbackAccessor} when {@link ServerAccessors#get()} is not installed.
   *
   * @param source           schematic source descriptor; may be {@code null}
   * @param fallbackAccessor optional fallback server accessor supplier for logging
   * @return completed future carrying the decoded schematic, or {@code null} on failure
   */
  public static CompletableFuture<LoadedSchematic> load(
      SchematicSource source, Supplier<ServerAccessor> fallbackAccessor) {
    if (source == null) {
      return CompletableFuture.completedFuture(null);
    }
    String key = cacheKey(source.path());
    if (key != null) {
      LoadedSchematic cached = CACHE.get(key);
      if (cached != null) {
        return CompletableFuture.completedFuture(cached);
      }
    }
    try {
      LoadedSchematic loaded = SpongeSchematicDecoder.decode(source);
      if (loaded != null && key != null) {
        CACHE.put(key, loaded);
      }
      return CompletableFuture.completedFuture(loaded);
    } catch (IOException | RuntimeException e) {
      logIfPossible(
          Level.WARNING,
          "[RTP] failed to decode schematic '"
              + source.path()
              + "' ("
              + source.formatHint()
              + "): "
              + e.getClass().getSimpleName()
              + ": "
              + e.getMessage(),
          e,
          fallbackAccessor);
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * Returns whether {@code source} names an existing regular file with a supported schematic
   * format hint ({@code ""}, {@code "schem"}, {@code "schematic"}, or {@code "litematic"}).
   *
   * @param source schematic source descriptor
   * @return {@code true} if supported and present on disk
   */
  public static boolean supports(SchematicSource source) {
    if (source == null) {
      return false;
    }
    String hint = source.formatHint().toLowerCase(Locale.ROOT);
    boolean supportedFormat =
        hint.isEmpty()
            || hint.equals("schem")
            || hint.equals("schematic")
            || hint.equals("litematic");
    return supportedFormat && Files.isRegularFile(source.path());
  }

  /**
   * Logs {@code message} at {@code level} via {@link ServerAccessors#get()} or
   * {@code fallbackAccessor}.
   *
   * @param level            log level
   * @param message          message to log
   * @param fallbackAccessor optional fallback accessor supplier
   */
  public static void logIfPossible(
      Level level, String message, Supplier<ServerAccessor> fallbackAccessor) {
    ServerAccessor accessor = resolveAccessor(fallbackAccessor);
    if (accessor != null) {
      accessor.log(level, message);
    }
  }

  /**
   * Logs {@code message} and {@code throwable} at {@code level} via {@link ServerAccessors#get()}
   * or {@code fallbackAccessor}.
   *
   * @param level            log level
   * @param message          message to log
   * @param throwable        exception cause
   * @param fallbackAccessor optional fallback accessor supplier
   */
  public static void logIfPossible(
      Level level,
      String message,
      Throwable throwable,
      Supplier<ServerAccessor> fallbackAccessor) {
    ServerAccessor accessor = resolveAccessor(fallbackAccessor);
    if (accessor != null) {
      accessor.log(level, message, throwable);
    }
  }

  private static ServerAccessor resolveAccessor(Supplier<ServerAccessor> fallbackAccessor) {
    ServerAccessor accessor = ServerAccessors.get();
    if (accessor != null) {
      return accessor;
    }
    return fallbackAccessor != null ? fallbackAccessor.get() : null;
  }

  private static String cacheKey(Path path) {
    if (path == null) {
      return null;
    }
    try {
      return path.toAbsolutePath().normalize().toString();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
