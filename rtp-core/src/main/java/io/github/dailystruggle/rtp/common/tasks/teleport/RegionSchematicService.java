package io.github.dailystruggle.rtp.common.tasks.teleport;

import io.github.dailystruggle.rtp.api.schematic.SchematicSource;
import io.github.dailystruggle.rtp.common.RTP;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves region-specific arrival schematic files by presence in {@code advanced/schematics/} (ADR-058, ADR-076).
 */
public final class RegionSchematicService {

  /**
   * Sub-directory of the plugin data folder holding region schematic files. ADR-076 relocates
   * this under the {@code advanced/} door alongside the other rarely-hand-edited content.
   */
  public static final String SCHEMATICS_DIRNAME = "advanced/schematics";

  /** Recognised file extensions, in resolution-precedence order (Sponge {@code .schem} first). */
  private static final String[] EXTENSIONS = {"schem", "schematic"};

  /**
   * Presence answers (hits and misses) live this long. The lookup runs on every teleport's
   * main-thread path and costs up to three file-system stats; a hand-dropped file is seen
   * within one TTL, and {@code PrefabSchematicInstaller} writes invalidate immediately.
   */
  static final long CACHE_TTL_NANOS = 1_000_000_000L;

  /** Bound on distinct (directory, region) keys; past it lookups go uncached. */
  private static final int CACHE_MAX = 1024;

  private record Entry(SchematicSource source, long expiresAtNanos) {
  }

  private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

  private RegionSchematicService() {
  }

  /**
   * Resolves the schematic source for a region by file presence.
   *
   * @param regionName the region's name; may be {@code null}
   * @return a {@link SchematicSource} when a matching file exists, or {@code null} when there
   *     is no schematics directory, no plugin directory, or no file for this region
   */
  public static SchematicSource resolveSource(String regionName) {
    if (regionName == null || regionName.isEmpty()) {
      return null;
    }
    if (RTP.serverAccessor == null) {
      return null;
    }
    File pluginDir = RTP.serverAccessor.getPluginDirectory();
    if (pluginDir == null) {
      return null;
    }
    Path base = pluginDir.toPath().resolve(SCHEMATICS_DIRNAME);
    String key = base + "\u0000" + regionName;
    long now = System.nanoTime();
    Entry cached = CACHE.get(key);
    if (cached != null && now - cached.expiresAtNanos() < 0L) {
      return cached.source();
    }
    SchematicSource resolved = probe(base, regionName);
    if (cached != null || CACHE.size() < CACHE_MAX) {
      CACHE.put(key, new Entry(resolved, now + CACHE_TTL_NANOS));
    }
    return resolved;
  }

  /** Drops every cached presence answer; call after writing or deleting schematic files. */
  public static void invalidateCache() {
    CACHE.clear();
  }

  private static SchematicSource probe(Path base, String regionName) {
    if (!Files.isDirectory(base)) {
      return null;
    }
    for (String ext : EXTENSIONS) {
      Path candidate = base.resolve(regionName + "." + ext);
      if (Files.isRegularFile(candidate)) {
        return new SchematicSource(regionName, candidate, ext);
      }
    }
    return null;
  }
}
