package io.github.dailystruggle.rtp.bukkitplatform.world;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.World;

/**
 * Resolves the on-disk region subdirectory of a Bukkit-family world relative to
 * {@link World#getWorldFolder()} (ADR-016, ADR-077).
 *
 * <p>Paper/Folia 26.1+ store every dimension under {@code dimensions/<ns>/<key>/region/};
 * older builds and Spigot use {@code region/}, {@code DIM-1/region/} or {@code DIM1/region/}.
 * The first candidate whose {@code region/} directory exists wins. Only hits are cached, so a
 * directory created by a later save is still found; with no hit the legacy subpath is returned
 * and probes miss as for ungenerated land. Blocking stat: off the tick thread (S-005).
 */
public final class BukkitRegionPaths {

  private static final ConcurrentHashMap<UUID, String> RESOLVED = new ConcurrentHashMap<>();

  private BukkitRegionPaths() {}

  /** Region subpath for {@code world}; {@code ""} when {@code world} is null. */
  public static String dimensionRegionSubpath(World world) {
    if (world == null) return "";
    UUID id;
    try {
      id = world.getUID();
    } catch (Throwable t) {
      id = null;
    }
    if (id != null) {
      String hit = RESOLVED.get(id);
      if (hit != null) return hit;
    }
    Path worldFolder;
    try {
      worldFolder = world.getWorldFolder().toPath();
    } catch (Throwable t) {
      return legacySubpath(world);
    }
    String legacy = legacySubpath(world);
    String resolved = resolveExisting(worldFolder, namespacedKey(world), legacy);
    if (resolved == null) return legacy;
    if (id != null) RESOLVED.put(id, resolved);
    return resolved;
  }

  /**
   * First candidate in {@link #candidates} whose {@code region/} directory exists under
   * {@code worldFolder}, or {@code null} when none does.
   */
  static String resolveExisting(Path worldFolder, String namespacedKey, String legacy) {
    if (worldFolder == null) return null;
    for (String c : candidates(namespacedKey, legacy)) {
      Path dir = c.isEmpty() ? worldFolder.resolve("region") : worldFolder.resolve(c).resolve("region");
      try {
        if (Files.isDirectory(dir)) return c;
      } catch (Throwable ignored) {
        // Unreadable candidate: try the next one.
      }
    }
    return null;
  }

  /**
   * Candidates in priority order: unified 26.1+ layout, legacy environment subpath, then
   * {@code ""} for builds whose world folder is already the dimension folder.
   */
  static List<String> candidates(String namespacedKey, String legacy) {
    List<String> out = new ArrayList<>(3);
    if (namespacedKey != null) {
      int colon = namespacedKey.indexOf(':');
      String ns = colon > 0 ? namespacedKey.substring(0, colon) : "minecraft";
      String path = colon >= 0 ? namespacedKey.substring(colon + 1) : namespacedKey;
      if (!path.isEmpty()) out.add("dimensions/" + ns + "/" + path);
    }
    String l = legacy == null ? "" : legacy;
    if (!out.contains(l)) out.add(l);
    if (!out.contains("")) out.add("");
    return out;
  }

  /** Clears resolved subpaths. Test hook. */
  static void invalidate() {
    RESOLVED.clear();
  }

  /** Vanilla pre-26 subpath for the world's environment. */
  private static String legacySubpath(World world) {
    try {
      switch (world.getEnvironment()) {
        case NETHER:
          return "DIM-1";
        case THE_END:
          return "DIM1";
        case NORMAL:
        default:
          return "";
      }
    } catch (Throwable ignored) {
      return "";
    }
  }

  /** {@code World#getKey()} as {@code ns:path}; reflective because Spigot 1.20.1 lacks it. */
  private static String namespacedKey(World world) {
    try {
      Object key = world.getClass().getMethod("getKey").invoke(world);
      return key == null ? null : key.toString().toLowerCase(Locale.ROOT);
    } catch (Throwable ignored) {
      return null;
    }
  }
}
