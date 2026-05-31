package io.github.dailystruggle.rtp.api.schematic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Base {@link SchematicPaster} for platforms that read the in-repo Sponge {@code .schem}
 * format (ADR-058 Amendment 1). It implements the platform-neutral half of the contract:
 *
 * <ul>
 *   <li>{@link #load} decodes the file with {@link SpongeSchematicDecoder} and wraps the
 *       result in a completed future. The decode is blocking I/O, so {@code load} MUST be
 *       called off any tick / region thread (S-005); a missing or malformed file yields a
 *       {@code null}-completed future (the caller maps that to a {@code MISSING_SOURCE} /
 *       {@code DECODE_ERROR} audit, never an exception that aborts the teleport).
 *   <li>{@link #supports} accepts {@code .schem} / {@code .schematic} sources whose file
 *       exists.
 * </ul>
 *
 * <p>Subclasses implement only {@link #paste}, applying the planned
 * {@link BlockPlacement}s (see {@link SchematicPlacementPlanner}) through the platform's
 * native block parser on the region-owning thread.
 */
public abstract class AbstractFileSchematicPaster implements SchematicPaster {

  /**
   * Decoded-schematic cache keyed by the absolute, normalized file path. Decoding a
   * {@code .schem} file is blocking disk I/O plus NBT parsing; since RTP teleports can resolve
   * the same region schematic many times per second, we decode each file only on its first
   * {@link #load} and serve subsequent loads from memory (the issue's "we can't do file
   * operations so often" requirement). {@link LoadedSchematic} is immutable so the cached
   * instance is safe to share across threads and teleports. Entries are dropped (and the file
   * re-read) whenever the file is missing/unreadable at lookup time, so deleting or replacing a
   * schematic and reloading still picks up the change.
   */
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
      // cause MUST be visible (S-004) — a silent null here is exactly the "no information around
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
