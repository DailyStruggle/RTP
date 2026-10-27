package io.github.dailystruggle.effectsapi.common.spi;

import io.github.dailystruggle.server.api.ServerAccessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;

/**
 * Legacy SPI for creating platform-specific effect handles, folded into
 * {@link ServerAccessor} per ADR-116 Decision 2.
 *
 * @deprecated Implement {@link ServerAccessor#wrapEffectPlayer(Object)},
 *             {@link ServerAccessor#wrapEffectLocation(Object)},
 *             {@link ServerAccessor#resolveEffectPlayerAt(Object)}, and
 *             {@link ServerAccessor#dispatchConsoleCommand(String)} (or install via
 *             {@link io.github.dailystruggle.server.api.ServerAccessors#setEffectAccessor(ServerAccessor)}).
 * @governedBy ADR-034
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
@Deprecated(since = "3.3.1")
@SuppressWarnings("DeprecatedIsStillUsed")
public interface HandleProvider extends ServerAccessor {
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

    /**
     * Dispatch a console command platform-neutrally (effects-api-ADR-007).
     *
     * @param command command string to run
     */
    @Override
    default void dispatchConsoleCommand(@NotNull String command) {}

    /**
     * Dispatch a player command platform-neutrally (effects-api-ADR-007).
     *
     * @param player player to run command as
     * @param command command string to run
     */
    default void dispatchPlayerCommand(@NotNull PlayerHandle player, @NotNull String command) {
        player.performCommand(command);
    }

    @Override
    default Object wrapEffectPlayer(Object player) {
        return player == null ? null : wrapPlayer(player);
    }

    @Override
    default Object wrapEffectLocation(Object location) {
        return location == null ? null : wrapLocation(location);
    }

    @Override
    default Object resolveEffectPlayerAt(Object location) {
        return location instanceof LocationHandle lh ? playerAt(lh) : null;
    }

    @Override
    default String getServerVersion() {
        return "unknown";
    }

    @Override
    default String getPlatform() {
        return "unknown";
    }

    @Override
    default Integer getServerIntVersion() {
        return null;
    }

    @Override
    default void log(Level level, String message) {}

    @Override
    default void log(Level level, String message, Throwable throwable) {}
}
