package io.github.dailystruggle.rtp.api.configuration;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-string, platform-neutral identifier normalizer (ADR-016 §8.1). Trim,
 * strip first {@code namespace:} prefix, upper-case under {@link Locale#ROOT}.
 * {@code null} → {@code null}; empty/whitespace-only → {@code ""}. Modded ids
 * pass through with namespace stripped (registry reconciliation is an adapter
 * concern; see {@code PaletteNormalizer} in {@code rtp-spigot-common}).
 * Stateless and thread-safe.
 */
public final class PaletteIdentifierNormalizer {

  private PaletteIdentifierNormalizer() {
    // Utility class.
  }

  /** Normalize one identifier to canonical {@code PATH} form; {@code null} → {@code null}. */
  public static String normalize(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return "";
    int colon = trimmed.indexOf(':');
    String path = (colon < 0) ? trimmed : trimmed.substring(colon + 1);
    return path.toUpperCase(Locale.ROOT);
  }

  /** Normalize every non-null entry into an insertion-ordered unmodifiable set. */
  public static Set<String> normalizeAll(Collection<String> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptySet();
    Set<String> out = new LinkedHashSet<>(raw.size());
    for (String s : raw) {
      if (s == null) continue;
      String n = normalize(s);
      if (n != null && !n.isEmpty()) out.add(n);
    }
    return Collections.unmodifiableSet(out);
  }

  /** True iff the normalized form of {@code rawPaletteId} is in {@code normalizedUnsafe}. */
  public static boolean matches(String rawPaletteId, Set<String> normalizedUnsafe) {
    Objects.requireNonNull(normalizedUnsafe, "normalizedUnsafe");
    String n = normalize(rawPaletteId);
    return n != null && !n.isEmpty() && normalizedUnsafe.contains(n);
  }
}
