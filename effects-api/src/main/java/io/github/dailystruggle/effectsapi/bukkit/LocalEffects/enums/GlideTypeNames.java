package io.github.dailystruggle.effectsapi.bukkit.LocalEffects.enums;

/**
 * Positional / type-driven keys for {@code GlideEffect}.
 *
 * <p>Order is the legacy positional order consumed by
 * {@code Effect#setData(String...)}; type-driven adaptive parsing
 * (effects-api-ADR-002) further allows skipped earlier keys to be filled by
 * later tokens.
 */
public enum GlideTypeNames {
    /** Block used to synthesize an emergency platform on shutdown when no safe block is below the player. */
    SHUTDOWNPLATFORMMATERIAL,
    /** Blocks above the player's current Y to lift them when glide starts. */
    RELATIVE,
    /** Hard ceiling on the lift target Y. */
    MAX,
    /** Maximum duration of the glide in ticks before forced landing. */
    LANDINGTIMEOUT,
    /** If false, firework rockets are suppressed for the duration of the glide. */
    ALLOWFIREWORKS,
    /** If true, on shutdown still-gliding players are placed at the highest safe block below them. */
    PLACEONSHUTDOWN,
    /** World allow-list sentinel ("*" or a specific world name). */
    WORLD
}
