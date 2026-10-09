package io.github.dailystruggle.rtp.bukkitplatform.world;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.search.FuzzySearchEngine;
import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;

/**
 * Cached candidate resolver for Bukkit/Spigot/Paper/Folia materials using
 * typo-tolerant {@link FuzzySearchEngine} candidate resolution.
 */
public final class BukkitMaterialResolver {

  private static final Map<String, Material> MATERIAL_MAP;

  static {
    Map<String, Material> map = new LinkedHashMap<>();
    for (Material m : Material.values()) {
      map.put(m.name(), m);
    }
    MATERIAL_MAP = Collections.unmodifiableMap(map);
  }

  private BukkitMaterialResolver() {}

  /**
   * Resolves a raw material name against known Bukkit materials.
   *
   * @param rawName raw input name
   * @return fuzzy lookup result
   */
  @NotNull
  public static FuzzySearchEngine.FuzzyLookupResult<Material> resolve(@Nullable String rawName) {
    if (rawName == null || rawName.isBlank()) {
      return new FuzzySearchEngine.FuzzyLookupResult<>(
          FuzzySearchEngine.LookupStatus.IMPERCEPTIBLE,
          null,
          "",
          rawName == null ? "" : rawName,
          -1,
          MATERIAL_MAP.keySet().stream().sorted().toList()
      );
    }
    return FuzzySearchEngine.resolveCandidate(rawName.trim(), MATERIAL_MAP);
  }

  /**
   * Resolves a material name with logging on typo/fallback, returning the matched material or fallback.
   *
   * @param rawName raw input string (e.g. "GLAS", "GLASS", "compass")
   * @param fallback default fallback material (e.g. Material.GLASS or Material.COMPASS)
   * @param contextLabel context prefix for logs (e.g. "Platform material" or "Menu icon")
   * @return resolved Material or fallback
   */
  @NotNull
  public static Material resolve(@Nullable String rawName, @NotNull Material fallback, @NotNull String contextLabel) {
    FuzzySearchEngine.FuzzyLookupResult<Material> lookup = resolve(rawName);
    if (lookup.isExact() && lookup.match() != null) {
      return lookup.match();
    }
    if (lookup.isPerceptible() && lookup.match() != null) {
      RTP.log(
          Level.WARNING,
          "[RTP] "
              + contextLabel
              + " '"
              + rawName
              + "' was not recognized, but closely matches '"
              + lookup.matchedKey()
              + "'. Autocorrecting to '"
              + lookup.matchedKey()
              + "'.");
      return lookup.match();
    }
    if (rawName != null && !rawName.isBlank()) {
      RTP.log(
          Level.WARNING,
          "[RTP] "
              + contextLabel
              + " '"
              + rawName
              + "' is unrecognized. Falling back to default '"
              + fallback.name()
              + "'.");
    }
    return fallback;
  }
}
