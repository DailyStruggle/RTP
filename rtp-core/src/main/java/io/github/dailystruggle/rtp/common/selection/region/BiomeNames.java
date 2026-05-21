package io.github.dailystruggle.rtp.common.selection.region;

import java.util.Set;

/**
 * Biome-name equivalence helper.
 *
 * <p>Different Bukkit-family platforms expose biome ids in different shapes:
 * older releases where {@code org.bukkit.block.Biome} is a Java enum surface
 * the bare uppercase enum constant (e.g. {@code "PLAINS"}); newer releases
 * (1.21.5+, registry-keyed Biome) surface the namespaced form
 * (e.g. {@code "MINECRAFT:PLAINS"} after uppercasing). The same input typed
 * by a player into a command argument or written into {@code safety.yml}
 * may follow either convention.
 *
 * <p>This helper provides a single comparator that treats the vanilla
 * {@code minecraft:} namespace as optional on either side of the comparison
 * while preserving modded namespaces (e.g. {@code iris:overworld:plains},
 * {@code terralith:emerald_peaks}) verbatim. The contract:
 *
 * <ul>
 *   <li>{@code matches({"PLAINS"}, "MINECRAFT:PLAINS")} - {@code true}</li>
 *   <li>{@code matches({"MINECRAFT:PLAINS"}, "PLAINS")} - {@code true}</li>
 *   <li>{@code matches({"MINECRAFT:PLAINS"}, "MINECRAFT:PLAINS")} - {@code true}</li>
 *   <li>{@code matches({"PLAINS"}, "PLAINS")} - {@code true}</li>
 *   <li>{@code matches({"IRIS:OVERWORLD:PLAINS"}, "PLAINS")} - {@code false}</li>
 *   <li>{@code matches({"IRIS:OVERWORLD:PLAINS"}, "IRIS:OVERWORLD:PLAINS")} - {@code true}</li>
 * </ul>
 *
 * <p>Callers are expected to have already uppercased both sides for
 * case-insensitivity; this helper deals solely with namespace equivalence.
 * Inputs must be non-null.
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
}
