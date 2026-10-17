package io.github.dailystruggle.effectsapi.common.hologram;

import org.jetbrains.annotations.Nullable;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry and service locator for {@link HologramProvider}.
 */
public final class HologramRegistry {

    private static final AtomicReference<HologramProvider> PROVIDER = new AtomicReference<>(null);

    private HologramRegistry() {
    }

    /**
     * Registers the active platform hologram provider.
     *
     * @param provider provider instance, or null to unregister
     */
    public static void register(@Nullable HologramProvider provider) {
        PROVIDER.set(provider);
    }

    /**
     * @return registered hologram provider, or null if none registered
     */
    public static @Nullable HologramProvider getProvider() {
        return PROVIDER.get();
    }

    /**
     * @return true if a hologram provider is currently registered
     */
    public static boolean isAvailable() {
        return PROVIDER.get() != null;
    }
}
