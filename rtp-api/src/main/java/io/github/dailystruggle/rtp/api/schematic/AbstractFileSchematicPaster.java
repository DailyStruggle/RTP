package io.github.dailystruggle.rtp.api.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base {@link SchematicPaster} for Sponge {@code .schem} files (ADR-058).
 * Handles off-tick async decoding and memory caching (S-004, S-005).
 * Subclasses implement {@link #paste} for platform-specific block placement.
 */
public abstract class AbstractFileSchematicPaster implements SchematicPaster {

  /** Decoded-schematic cache keyed by absolute normalized file path. */
  private static final Map<String, LoadedSchematic> CACHE = new ConcurrentHashMap<>();

  /**
   * Drops every cached schematic so the next {@link #load} re-reads from disk. Intended for
   * {@code /rtp reload} and tests that swap a schematic file in place.
   */
  public static void clearCache() {
    CACHE.clear();
  }

  @Override
  public CompletableFuture<LoadedSchematic> load(SchematicSource source) {
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
      // Decode failure is reported as a null result so it never aborts the teleport, but the
      // cause MUST be visible (S-004) - a silent null here is exactly the "no information around
      // its failure" gap. Log the real reason (bad magic, unsupported NBT tag, cell-count
      // mismatch, ...) via the API-level accessor when one is bound.
      io.github.dailystruggle.rtp.api.server.RTPServerAccessor accessor =
          io.github.dailystruggle.rtp.api.RTPAPI.serverAccessor;
      if (accessor != null) {
        accessor.log(java.util.logging.Level.WARNING,
            "[RTP] failed to decode schematic '"
                + (source != null ? String.valueOf(source.path()) : "null")
                + "' (" + (source != null ? source.formatHint() : "?") + "): "
                + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
      }
      return CompletableFuture.completedFuture(null);
    }
  }

  /**
   * @return a stable cache key (the absolute, normalized path string) for the given file, or
   *     {@code null} when the path cannot be normalized (in which case caching is skipped and
   *     the file is decoded directly).
   */
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

  @Override
  public boolean supports(SchematicSource source) {
    if (source == null) {
      return false;
    }
    String hint = source.formatHint().toLowerCase(Locale.ROOT);
    boolean spongeFormat = hint.isEmpty() || hint.equals("schem") || hint.equals("schematic");
    return spongeFormat && Files.isRegularFile(source.path());
  }
}
