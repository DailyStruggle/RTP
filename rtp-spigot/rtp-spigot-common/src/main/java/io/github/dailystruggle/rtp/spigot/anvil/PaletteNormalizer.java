package io.github.dailystruggle.rtp.spigot.anvil;

import io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.bukkit.Material;

/**
 * Spigot-side reconciler layered on top of {@link PaletteIdentifierNormalizer}.
 *
 * <p>Per {@code ADR-016} §8.1, identifier normalization is deliberately
 * split in two:</p>
 *
 * <ul>
 *   <li>The pure-string canonical form (namespace-strip + {@code Locale.ROOT} upper-case)
 *       lives in {@code rtp-api} ({@link PaletteIdentifierNormalizer}) so that
 *       {@code rtp-core} can invoke it during {@code SafetyKeys.unsafeBlocks} load without
 *       taking any Bukkit dependency.</li>
 *   <li>Reconciliation against the live Bukkit {@link Material} registry lives here, in
 *       {@code rtp-spigot-common}. It converts a palette identifier shaped like
 *       {@code minecraft:lava} to the canonical {@link Material#name()} form ({@code "LAVA"}),
 *       and falls back to the pure-string canonical form when the identifier is not a
 *       known vanilla material (i.e. modded namespaces).</li>
 * </ul>
 *
 * <p>Why the split matters: when the Anvil reader produces a palette entry, we want a
 * single lookup against the unsafe set. If the unsafe set was populated only by
 * {@code rtp-api}'s pure-string path while the palette side used {@code Material.name()},
 * a custom-registered material whose {@link Material#name()} differs from its path segment
 * would silently miss a {@code REJECT} it should have produced. Running both sides through
 * the same reconciler eliminates that class of drift.</p>
 *
 * <p>Modded identifiers (unknown to {@link Material#matchMaterial(String)}) are
 * <strong>not</strong> treated as errors; they pass through the pure-string path and, if
 * the user listed them in {@code unsafe-blocks}, will be matched. Unknown modded
 * identifiers that are <em>not</em> in the unsafe set simply fall through to the live
 * {@code chunk.isSafe(...)} re-check, which remains the source of truth. See ADR-016
 * Decision §3 for the authoritative contract.</p>
 *
 * <p>This class is thread-safe; it exposes only stateless static helpers. It is also not
 * used by the pre-filter in Phase 1 — callers appear in Phase 3 when
 * {@code BukkitRTPWorld.getChunkAt} is wired in.</p>
 */
public final class PaletteNormalizer {

  private PaletteNormalizer() {
    // Utility class.
  }

  /**
   * Reconcile a single identifier to its canonical lookup form.
   *
   * <p>Pipeline:</p>
   * <ol>
   *   <li>If {@code raw} is {@code null}, return {@code null}.</li>
   *   <li>If {@link Material#matchMaterial(String)} resolves to a non-null
   *       {@link Material}, return its {@link Material#name()} — guaranteed equal to the
   *       pure-string canonical form for vanilla identifiers, but definitive when Bukkit
   *       has registered a material whose name does not trivially derive from its path
   *       (historical edge cases across MC updates).</li>
   *   <li>Otherwise, fall back to {@link PaletteIdentifierNormalizer#normalize(String)}
   *       (namespace-strip + {@code Locale.ROOT} upper-case). Modded / unknown identifiers
   *       follow this path.</li>
   * </ol>
   *
   * @param raw the raw identifier as read from config or from a region-file palette. May
   *     be {@code null}.
   * @return the reconciled canonical form, or {@code null} if {@code raw} is {@code null}.
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

  /**
   * Reconcile every non-null entry of the supplied collection into an insertion-ordered,
   * unmodifiable {@link Set}. {@code null} entries are silently skipped; duplicates after
   * reconciliation are coalesced.
   *
   * @param raw the raw identifiers; may be {@code null} (treated as empty).
   * @return an unmodifiable set of reconciled identifiers, never {@code null}.
   */
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

  /**
   * Convenience: test whether a raw palette identifier, once reconciled, is present in an
   * already-reconciled lookup set. The set is expected to have been produced by
   * {@link #reconcileAll(Collection)} (Spigot side) or
   * {@link PaletteIdentifierNormalizer#normalizeAll(Collection)} (core side) — both produce
   * equivalent canonical forms for vanilla identifiers.
   *
   * @param rawPaletteId the palette identifier to test (will be reconciled on the fly).
   * @param reconciledUnsafe the already-reconciled lookup set.
   * @return {@code true} if the reconciled form of {@code rawPaletteId} is present in
   *     {@code reconciledUnsafe}.
   */
  public static boolean matches(String rawPaletteId, Set<String> reconciledUnsafe) {
    if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return false;
    String n = reconcile(rawPaletteId);
    return n != null && !n.isEmpty() && reconciledUnsafe.contains(n);
  }
}
