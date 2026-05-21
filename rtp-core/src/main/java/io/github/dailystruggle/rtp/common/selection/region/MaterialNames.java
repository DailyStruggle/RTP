package io.github.dailystruggle.rtp.common.selection.region;

import java.util.Set;

/**
 * Material / block-name equivalence helper.
 *
 * <p>Direct analog of {@link BiomeNames} for {@code Material}-style block ids
 * in {@code safety.yml::unsafeBlocks} and equivalent block-name sets.
 * Different Bukkit-family platforms expose block ids in different shapes: enum-
 * based releases yield bare uppercase names like {@code "LAVA"}; once a release
 * line moves {@code Material} (or the equivalent block registry) to {@code Keyed},
 * the same accessor will yield the namespaced form like {@code "MINECRAFT:LAVA"}.
 * The same input typed by an operator into {@code safety.yml} may follow either
 * convention.
 *
 * <p>This helper provides a single comparator that treats the vanilla
 * {@code minecraft:} namespace as optional on either side of the comparison
 * while preserving modded namespaces (e.g. {@code createdeco:limestone},
 * {@code terralith:smouldering_woods}) verbatim. The contract mirrors
 * {@link BiomeNames#matches(Set, String)}:
 *
 * <ul>
 *   <li>{@code matches({"LAVA"}, "MINECRAFT:LAVA")} - {@code true}</li>
 *   <li>{@code matches({"MINECRAFT:LAVA"}, "LAVA")} - {@code true}</li>
 *   <li>{@code matches({"MINECRAFT:LAVA"}, "MINECRAFT:LAVA")} - {@code true}</li>
 *   <li>{@code matches({"LAVA"}, "LAVA")} - {@code true}</li>
 *   <li>{@code matches({"CREATEDECO:LIMESTONE"}, "LIMESTONE")} - {@code false}</li>
 *   <li>{@code matches({"CREATEDECO:LIMESTONE"}, "CREATEDECO:LIMESTONE")} - {@code true}</li>
 * </ul>
 *
 * <p>Callers are expected to have already uppercased both sides for
 * case-insensitivity; this helper deals solely with namespace equivalence.
 * Inputs must be non-null.
 */
public final class MaterialNames {

  /** Uppercase form of the vanilla namespace prefix, including the trailing colon. */
  private static final String VANILLA_PREFIX = "MINECRAFT:";

  private MaterialNames() {}

  /**
   * Returns {@code true} if {@code probed} is equivalent (under vanilla-namespace
   * optionality) to any entry in {@code filter}.
   *
   * @param filter the set of unsafe block names (already uppercased); must be non-null
   * @param probed the probed block name to test (already uppercased); must be non-null
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
