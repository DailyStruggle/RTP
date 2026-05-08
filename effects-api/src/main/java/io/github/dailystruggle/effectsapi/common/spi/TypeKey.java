package io.github.dailystruggle.effectsapi.common.spi;

/**
 * Closed enumeration of logical value types understood by the
 * {@link ValueCoercer} SPI.
 *
 * <p>Per <em>effects-api-ADR-004</em>, this is the seam that lets
 * <em>effects-api-ADR-002</em>'s adaptive reading order keep working on each
 * platform without {@code effectsapi.common} importing {@code org.bukkit.*}.
 * The ladder in {@code Effect#canParse} / {@code Effect#str2Obj} /
 * {@code Effect#fixData} stops switching on Bukkit class literals and instead
 * asks {@link ValueCoercer#classify(Object)} for a {@code TypeKey}, then
 * delegates {@link ValueCoercer#canParse} / {@link ValueCoercer#parse}.
 *
 * <p>Sealed-by-convention: addons must not extend this. Adding a new logical
 * type requires an ADR amendment.
 */
public enum TypeKey {
    /** Plain {@link String}. */
    STRING,
    /** {@link Boolean}. */
    BOOLEAN,
    /** {@link Integer} or {@link Long}. */
    INT,
    /** {@link Long}. Distinguished from {@link #INT} for fixData round-trips. */
    LONG,
    /** {@link Double}. Stored value uses the legacy ADR-002 {@code /100} coercion. */
    DOUBLE,
    /** {@link Float}. Stored value uses the legacy ADR-002 {@code /100} coercion. */
    FLOAT,
    /** Platform-native colour (e.g. {@code org.bukkit.Color}). */
    COLOR,
    /** Platform-native sound (e.g. {@code org.bukkit.Sound}). */
    SOUND,
    /** Platform-native particle (e.g. {@code org.bukkit.Particle}). */
    PARTICLE,
    /** Platform-native potion-effect (e.g. {@code org.bukkit.potion.PotionEffectType}). */
    POTION_EFFECT,
    /** Platform-native material (e.g. {@code org.bukkit.Material}). */
    MATERIAL,
    /** Platform-native world handle. */
    WORLD,
    /**
     * Reflective fallback. The coercer should attempt
     * {@code valueOf(String)} / {@code getByName(String)} / a registry lookup
     * keyed by the default's runtime class. Used by {@code fixData} when the
     * default's class isn't one of the known logical types above.
     */
    UNKNOWN
}
