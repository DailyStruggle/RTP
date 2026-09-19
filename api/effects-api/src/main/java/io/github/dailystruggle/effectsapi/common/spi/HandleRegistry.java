package io.github.dailystruggle.effectsapi.common.spi;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry for the active {@link HandleProvider}.
 */
public final class HandleRegistry {
    private static final AtomicReference<HandleProvider> PROVIDER = new AtomicReference<>();

    private HandleRegistry() {}

    public static void setProvider(@NotNull HandleProvider provider) {
        PROVIDER.set(provider);
    }

    @Nullable
    public static PlayerHandle wrapPlayer(@NotNull Object player) {
        if (player instanceof PlayerHandle) return (PlayerHandle) player;
        HandleProvider p = PROVIDER.get();
        return p != null ? p.wrapPlayer(player) : null;
    }

    @Nullable
    public static LocationHandle wrapLocation(@NotNull Object location) {
        if (location instanceof LocationHandle) return (LocationHandle) location;
        HandleProvider p = PROVIDER.get();
        return p != null ? p.wrapLocation(location) : null;
    }

    @Nullable
    public static PlayerHandle playerAt(@NotNull LocationHandle location) {
        HandleProvider p = PROVIDER.get();
        return p != null ? p.playerAt(location) : null;
    }
}
