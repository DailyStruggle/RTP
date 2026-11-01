package io.github.dailystruggle.rtp.common.selection.region;

import java.util.Set;

/**
 * Material / block-name equivalence helper.
 *
 * <p>Treats vanilla {@code minecraft:} prefix as optional while preserving custom namespaces.
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
