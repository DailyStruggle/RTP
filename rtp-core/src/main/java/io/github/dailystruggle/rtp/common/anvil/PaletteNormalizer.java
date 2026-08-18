package io.github.dailystruggle.rtp.common.anvil;

import io.github.dailystruggle.rtp.api.configuration.PaletteIdentifierNormalizer;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Platform-neutral palette-identifier reconciler for anvil pre-filtering (ADR-016 §8.1).
 * Normalizes palette IDs to uppercase namespace-stripped strings for lookup matching.
 * Stateless and thread-safe.
 */
public final class PaletteNormalizer {

    private PaletteNormalizer() {
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
