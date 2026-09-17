package io.github.dailystruggle.rtp.api.configuration;

import java.util.Collection;
import java.util.Set;

/**
 * Pure-string, platform-neutral identifier normalizer (ADR-016 section 8.1).
 *
 * @deprecated Replaced by {@link io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer} in {@code anvil-api}.
 *             Retained for backwards compatibility.
 */
@Deprecated
public final class PaletteIdentifierNormalizer {

  private PaletteIdentifierNormalizer() {
    // Utility class.
  }

  /** Normalize one identifier to canonical {@code PATH} form; {@code null} → {@code null}. */
  public static String normalize(String raw) {
    return io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer.normalize(raw);
  }

  /** Normalize every non-null entry into an insertion-ordered unmodifiable set. */
  public static Set<String> normalizeAll(Collection<String> raw) {
    return io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer.normalizeAll(raw);
  }

  /** True iff the normalized form of {@code rawPaletteId} is in {@code normalizedUnsafe}. */
  public static boolean matches(String rawPaletteId, Set<String> normalizedUnsafe) {
    return io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer.matches(rawPaletteId, normalizedUnsafe);
  }
}
