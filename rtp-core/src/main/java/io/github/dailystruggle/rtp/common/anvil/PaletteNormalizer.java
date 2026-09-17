package io.github.dailystruggle.rtp.common.anvil;

import io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer;
import java.util.Collection;
import java.util.Set;

/**
 * Platform-neutral palette-identifier reconciler for anvil pre-filtering (ADR-016 section 8.1).
 *
 * @deprecated Replaced by {@link io.github.dailystruggle.rtp.anvil.PaletteIdentifierNormalizer} in {@code anvil-api}.
 */
@Deprecated
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
        return PaletteIdentifierNormalizer.normalizeAll(raw);
    }

    /** True iff the reconciled form of {@code rawPaletteId} is in {@code reconciledUnsafe}. */
    public static boolean matches(String rawPaletteId, Set<String> reconciledUnsafe) {
        if (reconciledUnsafe == null || reconciledUnsafe.isEmpty()) return false;
        return PaletteIdentifierNormalizer.matches(rawPaletteId, reconciledUnsafe);
    }
}
