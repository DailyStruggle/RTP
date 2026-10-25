package io.github.dailystruggle.effectsapi.common.spi;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

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
 * be a pure function of {@code (type, raw)} - no I/O, no chunk loads
 * (S-005), no exceptions on rejection.
 *
 * <p><b>S-004 (no silent failures):</b> {@link #parse(TypeKey, String)} must
 * either return a non-null result or throw {@link IllegalArgumentException}
 * with a human-readable diagnostic. It must not return {@code null}.
 */
public interface ValueCoercer {

    /** Canonical named color tokens accepted by {@link #DEFAULT}. */
    Set<String> NAMED_COLORS = Set.of(
            "WHITE", "SILVER", "GRAY", "BLACK", "RED", "MAROON",
            "YELLOW", "OLIVE", "LIME", "GREEN", "AQUA", "TEAL",
            "BLUE", "NAVY", "FUCHSIA", "PURPLE", "ORANGE"
    );

    /**
     * Platform-neutral {@link ValueCoercer} handling primitive, string, color, and enum tokens
     * without requiring a platform jar on the classpath (ADR-116 Decision 2).
     */
    ValueCoercer DEFAULT = new ValueCoercer() {
        @Override
        public TypeKey classify(Object def) {
            if (def == null || def instanceof String) return TypeKey.STRING;
            if (def instanceof Boolean) return TypeKey.BOOLEAN;
            if (def instanceof Long) return TypeKey.LONG;
            if (def instanceof Integer || def instanceof Short || def instanceof Byte) return TypeKey.INT;
            if (def instanceof Double) return TypeKey.DOUBLE;
            if (def instanceof Float) return TypeKey.FLOAT;
            return TypeKey.UNKNOWN;
        }

        @Override
        public boolean canParse(TypeKey type, String raw) {
            if (raw == null) return false;
            switch (type) {
                case STRING:
                    return true;
                case BOOLEAN: {
                    String upper = raw.toUpperCase(Locale.ROOT);
                    return upper.equals("TRUE") || upper.equals("FALSE");
                }
                case INT:
                case LONG:
                    try {
                        Long.parseLong(raw);
                        return true;
                    } catch (NumberFormatException nfe) {
                        return false;
                    }
                case DOUBLE:
                case FLOAT:
                    try {
                        float f = Float.parseFloat(raw);
                        return !Float.isNaN(f) && !Float.isInfinite(f);
                    } catch (NumberFormatException nfe) {
                        return false;
                    }
                case COLOR: {
                    String upper = raw.toUpperCase(Locale.ROOT);
                    if (NAMED_COLORS.contains(upper)) return true;
                    String hex = raw.startsWith("#") ? raw.substring(1) : raw;
                    if (hex.length() != 6) return false;
                    try {
                        Integer.parseInt(hex, 16);
                        return true;
                    } catch (NumberFormatException nfe) {
                        return false;
                    }
                }
                case SOUND:
                case PARTICLE:
                case POTION_EFFECT:
                case MATERIAL:
                    return !raw.isBlank();
                case WORLD:
                case UNKNOWN:
                default:
                    return false;
            }
        }

        @Override
        public Object parse(TypeKey type, String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("[effects-api] null token");
            }
            switch (type) {
                case STRING:
                    return raw.toUpperCase(Locale.ROOT);
                case BOOLEAN:
                    return Boolean.parseBoolean(raw.toUpperCase(Locale.ROOT));
                case INT:
                case LONG:
                    try {
                        return Integer.parseInt(raw.toUpperCase(Locale.ROOT));
                    } catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException(
                                "[effects-api] expected integer but got \"" + raw + "\"", nfe);
                    }
                case DOUBLE:
                case FLOAT:
                    try {
                        return Float.parseFloat(raw.toUpperCase(Locale.ROOT)) / 100f;
                    } catch (NumberFormatException nfe) {
                        throw new IllegalArgumentException(
                                "[effects-api] expected float but got \"" + raw + "\"", nfe);
                    }
                case COLOR: {
                    String upper = raw.toUpperCase(Locale.ROOT);
                    if (NAMED_COLORS.contains(upper)) return upper;
                    String hex = upper.startsWith("#") ? upper.substring(1) : upper;
                    if (hex.length() == 6) {
                        try {
                            Integer.parseInt(hex, 16);
                            return hex;
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    throw new IllegalArgumentException(
                            "[effects-api] expected Color (named constant or 6-digit hex RRGGBB) but got \""
                                    + raw + "\"");
                }
                case SOUND:
                case PARTICLE:
                case POTION_EFFECT:
                case MATERIAL:
                    if (raw.isBlank()) {
                        throw new IllegalArgumentException("[effects-api] empty token for " + type);
                    }
                    return raw.toUpperCase(Locale.ROOT);
                case WORLD:
                case UNKNOWN:
                default:
                    throw new IllegalArgumentException(
                            "[effects-api] cannot parse \"" + raw + "\" — type "
                                    + type + " has no neutral parser");
            }
        }

        @Override
        public Object resolveReflective(Class<?> targetType, String raw) {
            if (targetType == null || raw == null) return null;
            try {
                return targetType.getMethod("valueOf", String.class)
                        .invoke(null, raw.toUpperCase(Locale.ROOT));
            } catch (Throwable ignored) {
                // fall through
            }
            try {
                return targetType.getMethod("getByName", String.class).invoke(null, raw);
            } catch (Throwable ignored) {
                return null;
            }
        }

        @Override
        public List<TypeKey> readingOrder() {
            return Arrays.asList(
                    TypeKey.STRING, TypeKey.BOOLEAN, TypeKey.INT, TypeKey.LONG,
                    TypeKey.DOUBLE, TypeKey.FLOAT,
                    TypeKey.COLOR, TypeKey.SOUND, TypeKey.PARTICLE,
                    TypeKey.POTION_EFFECT, TypeKey.MATERIAL, TypeKey.WORLD);
        }
    };

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
     * {@link #classify(Object)} returns {@link TypeKey#UNKNOWN} - the default
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
