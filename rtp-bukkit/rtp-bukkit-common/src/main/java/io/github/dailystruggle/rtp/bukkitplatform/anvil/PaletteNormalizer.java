package io.github.dailystruggle.rtp.bukkitplatform.anvil;

import io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.Material;

/**
 * Spigot-side reconciler layered on top of {@link PaletteIdentifierNormalizer} (ADR-016 §8.1).
 *
 * <p>Pure-string canonicalisation (namespace-strip + {@code Locale.ROOT} upper-case) lives
 * in {@code rtp-api}; reconciliation against the live Bukkit {@link Material} registry
 * lives here. Both sides must run through the same reconciler so that a palette entry
 * and the {@code unsafe-blocks} set look up identically. Modded / unknown identifiers
 * fall through to the pure-string path. Stateless and thread-safe.
 */
public final class PaletteNormalizer {

  private PaletteNormalizer() {
    // Utility class.
  }

  /**
   * Reconcile a single identifier to its canonical lookup form. Vanilla identifiers
   * resolve to {@link Material#name()}; modded / unknown identifiers fall back to
   * {@link PaletteIdentifierNormalizer#normalize(String)}. Returns {@code null} iff
   * {@code raw} is {@code null}.
   */
  public static String reconcile(String raw) {
    if (raw == null) return null;
    try {
      Material material = Material.matchMaterial(raw);
      if (material != null) return material.name();
    } catch (Throwable ignored) {
      // matchMaterial is documented as null-tolerant but we guard against any adapter
      // that throws on malformed input; fall back to the pure-string canonical form.
    }
    return PaletteIdentifierNormalizer.normalize(raw);
  }

  /** Reconcile every non-null entry into an insertion-ordered unmodifiable set. */
  public static Set<String> reconcileAll(Collection<String> raw) {
    if (raw == null || raw.isEmpty()) return Collections.emptySet();
    Set<String> out = new LinkedHashSet<>(raw.size());
    for (String s : raw) {
      if (s == null) continue;
      String n = reconcile(s);
      if (n != null && !n.isEmpty()) out.add(n);
    }
    return Collections.unmodifiableSet(out);
  }

  /** True iff the reconciled form of {@code rawPaletteId} is in {@code reconciledUnsafe}. */
  public static boolean matches(String rawPaletteId, Set<String> reconciledUnsafe) {
    if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return false;
    String n = reconcile(rawPaletteId);
    return n != null && !n.isEmpty() && reconciledUnsafe.contains(n);
  }
}
