package io.github.dailystruggle.rtp.api.configuration;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Pure-string, platform-neutral identifier normalizer shared by RTP's safety configuration
 * and by the Spigot-only Anvil read-only pre-filter (see
 * {@code docs/adr/ADR-016-anvil-subsystem.md} and
 * {@code docs/dev/ANVIL_PREFILTER_PLAN.md} §8.1).
 *
 * <p>The normalizer intentionally has <strong>zero Bukkit dependencies</strong> so it can
 * live in {@code rtp-api} and be called from both {@code rtp-core} (during
 * {@code SafetyKeys.unsafeBlocks} load) and {@code rtp-spigot-common} (when a region-file
 * palette entry is matched against the unsafe set).</p>
 *
 * <p>The canonical form is produced by the following deterministic pipeline:</p>
 * <ol>
 *   <li>{@code null} → {@code null} (callers decide whether to skip or reject).</li>
 *   <li>Trim surrounding whitespace.</li>
 *   <li>Strip the first {@code namespace:} prefix, if any. Multiple colons are preserved
 *       after the first split so that pathological inputs like {@code mod:ns:block} round-trip
 *       to {@code NS:BLOCK} rather than being lossily truncated.</li>
 *   <li>Upper-case under {@link Locale#ROOT} to avoid the Turkish-i trap.</li>
 *   <li>Empty result after trimming and stripping → empty string (never {@code null}).</li>
 * </ol>
 *
 * <p>Examples (all three collapse to the same canonical form {@code LAVA}):</p>
 * <ul>
 *   <li>{@code "minecraft:lava"} → {@code "LAVA"}</li>
 *   <li>{@code "LAVA"} → {@code "LAVA"}</li>
 *   <li>{@code "MINECRAFT:LAVA"} → {@code "LAVA"}</li>
 * </ul>
 *
 * <p>Modded identifiers pass through with their namespace stripped and their path
 * upper-cased (e.g. {@code "create:crushing_wheel"} → {@code "CRUSHING_WHEEL"}). Reconciling
 * such identifiers against the running platform's material registry is an adapter concern and
 * is handled on top of this helper (see {@code PaletteNormalizer} in
 * {@code io.github.dailystruggle.rtp.spigot.anvil}).</p>
 *
 * <p>This class is immutable and thread-safe; it exposes only static helpers.</p>
 */
public final class PaletteIdentifierNormalizer {

  private PaletteIdentifierNormalizer() {
    // Utility class.
  }

  /**
   * Normalize a single identifier to its canonical {@code PATH} form.
   *
   * @param raw the raw identifier as read from config or from a region-file palette. May be
   *     {@code null}.
   * @return the normalized canonical form, or {@code null} if {@code raw} is {@code null}.
   *     Returns an empty string if {@code raw} was non-null but contained only whitespace
   *     or only a namespace separator.
   */
  public static String normalize(String raw) {
    if (raw == null) return null;
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return "";
    int colon = trimmed.indexOf(':');
    String path = (colon < 0) ? trimmed : trimmed.substring(colon + 1);
    return path.toUpperCase(Locale.ROOT);
  }

  /**
   * Normalize every non-null entry of the supplied collection into a new, insertion-ordered,
   * unmodifiable {@link Set}. {@code null} entries are silently skipped. Duplicates after
   * normalization are coalesced.
   *
   * @param raw the raw identifiers; may be {@code null} (treated as empty).
   * @return an unmodifiable set of normalized identifiers, never {@code null}.
   */
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

  /**
   * Convenience: test whether a raw palette identifier, once normalized, is present in an
   * already-normalized lookup set. The set is expected to have been produced by
   * {@link #normalizeAll(Collection)} or an equivalent pipeline.
   *
   * @param rawPaletteId the palette identifier to test (will be normalized on the fly).
   * @param normalizedUnsafe the already-normalized lookup set (case-sensitive equality on
   *     canonical form).
   * @return {@code true} if the normalized form of {@code rawPaletteId} is present in
   *     {@code normalizedUnsafe}.
   */
  public static boolean matches(String rawPaletteId, Set<String> normalizedUnsafe) {
    Objects.requireNonNull(normalizedUnsafe, "normalizedUnsafe");
    String n = normalize(rawPaletteId);
    return n != null && !n.isEmpty() && normalizedUnsafe.contains(n);
  }
}
