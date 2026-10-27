package io.github.dailystruggle.server.api;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Platform-neutral material registry, live block-tag snapshot, and on-disk palette reconciliation
 * contract.
 *
 * @governedBy ADR-017
 * @governedBy ADR-115
 */
public interface PaletteTagAccessor {
  /**
   * Returns the set of all material names known to the server.
   *
   * @return set of material name strings in the platform's canonical format; never {@code null}
   */
  default Set<String> materials() {
    return Collections.emptySet();
  }

  /**
   * Returns immutable snapshot of live block tags for safety parsing (ADR-017).
   *
   * <p>Keyed by lowercase {@code namespace:path}. Values are sets of uppercase material names.
   *
   * @return immutable tag map; never {@code null}
   */
  default Map<String, Set<String>> blockTagSnapshot() {
    return Collections.emptyMap();
  }

  /**
   * Instructs the accessor to rebuild its block-tag snapshot on the next call to {@link
   * #blockTagSnapshot()}.
   */
  default void rebuildBlockTagSnapshot() {}

  /**
   * Reconciles a raw on-disk block or biome palette identifier against the platform registry.
   *
   * <p>The default implementation strips any leading {@code namespace:} prefix and normalizes to
   * uppercase under {@link Locale#ROOT}.
   *
   * @param raw raw identifier from disk or config (e.g. {@code "minecraft:stone"}, {@code "stone"})
   * @return canonical identifier for lookups, or {@code null} if {@code raw} is {@code null}
   */
  default String reconcilePaletteIdentifier(String raw) {
    if (raw == null) {
      return null;
    }
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) {
      return "";
    }
    int colon = trimmed.indexOf(':');
    String path = (colon < 0) ? trimmed : trimmed.substring(colon + 1);
    return path.toUpperCase(Locale.ROOT);
  }

  /**
   * Reconciles a collection of raw identifiers into an unmodifiable set of canonical identifiers.
   *
   * @param raw collection of raw identifiers
   * @return unmodifiable set of canonical identifiers
   */
  default Set<String> reconcilePaletteIdentifiers(Collection<String> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptySet();
    Set<String> out = new LinkedHashSet<>(raw.size());
    for (String s : raw) {
      if (s == null) continue;
      String n = reconcilePaletteIdentifier(s);
      if (n != null && !n.isEmpty()) out.add(n);
    }
    return Collections.unmodifiableSet(out);
  }

  /**
   * Checks whether the reconciled form of a raw palette identifier matches any entry in the
   * reconciled unsafe set.
   *
   * @param rawPaletteId raw block palette identifier
   * @param reconciledUnsafe set of reconciled unsafe block identifiers
   * @return {@code true} if matches, {@code false} otherwise
   */
  default boolean matchesPaletteIdentifier(String rawPaletteId, Set<String> reconciledUnsafe) {
    if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return false;
    String n = reconcilePaletteIdentifier(rawPaletteId);
    return n != null && !n.isEmpty() && reconciledUnsafe.contains(n);
  }
}
