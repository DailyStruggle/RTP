package io.github.dailystruggle.rtp.common.selection.region;

import java.util.Set;

/**
 * Biome-name equivalence helper.
 * Treats vanilla {@code minecraft:} namespace as optional while preserving modded namespaces.
 * Requires caller to uppercase inputs for case-insensitivity.
 */
public final class BiomeNames {

  /** Uppercase form of the vanilla namespace prefix, including the trailing colon. */
  private static final String VANILLA_PREFIX = "MINECRAFT:";

  private BiomeNames() {}

  /**
   * Returns {@code true} if {@code probed} is equivalent (under vanilla-namespace
   * optionality) to any entry in {@code filter}.
   *
   * @param filter the set of allowed biome names (already uppercased); must be non-null
   * @param probed the probed biome name to test (already uppercased); must be non-null
   * @return {@code true} iff {@code probed} matches at least one filter entry
   */
  public static boolean matches(Set<String> filter, String probed) {
    if (filter.contains(probed)) return true;
    if (probed.startsWith(VANILLA_PREFIX)) {
      return filter.contains(probed.substring(VANILLA_PREFIX.length()));
    }
    return filter.contains(VANILLA_PREFIX + probed);
  }

  /**
   * Canonicalises biome id by stripping vanilla {@code MINECRAFT:} prefix and uppercasing.
   * Preserves modded namespaces verbatim.
   */
  public static String canonical(String name) {
    if (name == null) return null;
    String up = name.toUpperCase(java.util.Locale.ROOT);
    if (up.startsWith(VANILLA_PREFIX)) {
      return up.substring(VANILLA_PREFIX.length());
    }
    return up;
  }
}
