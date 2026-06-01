package io.github.dailystruggle.rtp.fabric.anvil;

import io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Fabric-side reconciler companion to {@code rtp-spigot-common}'s
 * {@code PaletteNormalizer} (ADR-016 §8.1, rtp-fabric-ADR-005, this ADR's
 * follow-up that lifts the deferred full-anvil-{@code RTPChunk} item).
 *
 * <p>Unlike the Spigot side, Fabric has no {@code Material} enum to consult,
 * so reconciliation is entirely string-based: namespace-strip and {@code
 * Locale.ROOT} upper-case via {@link PaletteIdentifierNormalizer#normalize(String)}.
 * Because the Bukkit {@code Material#name()} form (e.g. {@code "LAVA"}) and
 * the namespace-stripped vanilla palette ID form (e.g. {@code "minecraft:lava"}
 * → {@code "LAVA"}) coincide for every vanilla block, user configs that were
 * authored against the Bukkit-family Material names continue to match here
 * without any additional alias map. Modded / unknown identifiers fall through
 * to the same pure-string path used by the Spigot reconciler.</p>
 *
 * <p>The output form is the canonical lookup form expected by
 * {@code rtp-anvil}'s {@code AnvilChunkView#isAir} and
 * {@code AnvilChunkView#isSafe}, so Fabric callers may pass the reconciled
 * set directly into the Anvil view without further translation.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class FabricPaletteNormalizer {

    private FabricPaletteNormalizer() {
        // Utility class.
    }

    /**
     * Reconcile a single identifier to its canonical lookup form. Returns
     * {@code null} iff {@code raw} is {@code null}.
     */
    public static String reconcile(String raw) {
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
