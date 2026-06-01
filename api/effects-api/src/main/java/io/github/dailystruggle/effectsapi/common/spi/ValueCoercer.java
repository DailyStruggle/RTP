package io.github.dailystruggle.effectsapi.common.spi;

import java.util.List;

/**
 * Per-platform leaf operations of the <em>effects-api-ADR-002</em> adaptive
 * reading order. Introduced by <em>effects-api-ADR-004</em> so that
 * {@code effectsapi.common.Effect} no longer has to import
 * {@code org.bukkit.*}.
 *
 * <p>Implementations are bound at platform-init time via
 * {@code EffectFactory.setCoercer(ValueCoercer)}:
 * <ul>
 *   <li>Bukkit-family: {@code BukkitEffectsInitializer.registerAll()} →
 *       {@code BukkitValueCoercer}</li>
 *   <li>Fabric: {@code FabricEffectsInitializer.registerAll()} →
 *       {@code FabricValueCoercer}</li>
 * </ul>
 *
 * <p><b>Determinism (contractual):</b> {@link #canParse(TypeKey, String)} must
 * be a pure function of {@code (type, raw)} — no I/O, no chunk loads
 * (S-005), no exceptions on rejection.
 *
 * <p><b>S-004 (no silent failures):</b> {@link #parse(TypeKey, String)} must
 * either return a non-null result or throw {@link IllegalArgumentException}
 * with a human-readable diagnostic. It must not return {@code null}.
 */
public interface ValueCoercer {

    /**
     * Map a defaults-map value to its logical {@link TypeKey}. Implementations
     * should switch on {@link Object#getClass()} of {@code def} (and its
     * fully-qualified class name when the platform type is registry-backed
     * and not directly importable here).
     *
     * @param def the default value, possibly {@code null}
     * @return the logical type, never {@code null}; returns {@link TypeKey#STRING}
     *         for {@code null} (legacy "permissive" behaviour) and
     *         {@link TypeKey#UNKNOWN} for anything the coercer doesn't
     *         specifically understand
     */
    TypeKey classify(Object def);

    /**
     * Cheapest, side-effect-free check: can the raw token be read as the given
     * logical type? Must never throw.
     */
    boolean canParse(TypeKey type, String raw);

    /**
     * Parse the raw token into the platform-native object the concrete effect
     * consumes. Throws {@link IllegalArgumentException} on parse failure
     * (never returns {@code null}). Never performs chunk I/O (S-005).
     */
    Object parse(TypeKey type, String raw);

    /**
     * Reflective fallback used by {@code Effect#fixData} when
     * {@link #classify(Object)} returns {@link TypeKey#UNKNOWN} — the default
     * value's runtime class is not one of the known logical types and the
     * coercer should try {@code valueOf(String)} / {@code getByName(String)}
     * / a registry lookup keyed by {@code targetType}.
     *
     * @return the resolved object, or {@code null} when no resolver matches
     *         (caller surfaces an {@link IllegalArgumentException} per S-004)
     */
    Object resolveReflective(Class<?> targetType, String raw);

    /**
     * Declared reading order for this platform. ADR-002's ladder iterates
     * {@code defaults} in declaration order; the platform's preferred order
     * is informational and reserved for future cache-promotion tuning.
     */
    List<TypeKey> readingOrder();
}
