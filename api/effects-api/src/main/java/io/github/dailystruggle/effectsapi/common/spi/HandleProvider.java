package io.github.dailystruggle.effectsapi.common.spi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * SPI for creating platform-specific handles.
 */
public interface HandleProvider {
    /**
     * Wrap a platform-specific player object.
     */
    @Nullable PlayerHandle wrapPlayer(@NotNull Object player);

    /**
     * Wrap a platform-specific location object.
     */
    @Nullable LocationHandle wrapLocation(@NotNull Object location);

    /**
     * Recover the player currently standing at the given location, if any.
     *
     * <p>Used by effects that only retain a location reference for their target
     * (e.g. the glide effect) and need to act on the player there without the
     * caller touching platform-specific player objects. Implementations that
     * cannot resolve a player from a location should return {@code null}.
     */
    default @Nullable PlayerHandle playerAt(@NotNull LocationHandle location) {
        return null;
    }
}
