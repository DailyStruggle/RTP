package io.github.dailystruggle.effectsapi.common.spi;

import io.github.dailystruggle.server.api.ServerAccessors;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Retired handle registry bridge forwarding all calls to {@link ServerAccessors}
 * and {@link EffectTarget} (ADR-116 Decision 2).
 *
 * @deprecated Retired in favor of {@link ServerAccessors} and {@link EffectTarget}
 *             static resolution methods (ADR-116 Decision 2).
 */
@Deprecated(since = "3.3.1")
@SuppressWarnings("DeprecatedIsStillUsed")
public final class HandleRegistry {
    private HandleRegistry() {}

    public static void setProvider(@NotNull HandleProvider provider) {
        ServerAccessors.setEffectAccessor(provider);
    }

    public static boolean hasProvider() {
        return ServerAccessors.hasEffectAccessor();
    }

    @Nullable
    public static PlayerHandle wrapPlayer(@Nullable Object player) {
        return EffectTarget.wrapPlayer(player);
    }

    @Nullable
    public static LocationHandle wrapLocation(@Nullable Object location) {
        return EffectTarget.wrapLocation(location);
    }

    @Nullable
    public static PlayerHandle playerAt(@NotNull LocationHandle location) {
        return EffectTarget.playerAt(location);
    }

    public static void dispatchConsoleCommand(@NotNull String command) {
        EffectTarget.dispatchConsoleCommand(command);
    }

    public static void dispatchPlayerCommand(@NotNull PlayerHandle player, @NotNull String command) {
        EffectTarget.dispatchPlayerCommand(player, command);
    }
}
