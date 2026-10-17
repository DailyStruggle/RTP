package io.github.dailystruggle.rtp.common.world;

import io.github.dailystruggle.rtp.api.world.RTPLocation;

import java.util.function.Function;

/**
 * Shared per-location biome resolver installed by addons via
 * {@code RTPServerAccessor#setBiomeGetter}. Fabric and NeoForge world adapters
 * consult it before the vanilla registry lookup, mirroring the Bukkit/Folia
 * static getter. Platform-neutral: no Minecraft types.
 */
public final class BiomeOverride {

    private static volatile Function<RTPLocation, String> getter;

    private BiomeOverride() {}

    /**
     * Installs the addon resolver. A {@code null} value clears it.
     *
     * @param resolver resolver to install, or {@code null} to clear
     */
    public static void set(Function<RTPLocation, String> resolver) {
        getter = resolver;
    }

    /**
     * Returns the installed resolver, or {@code null} when none is set.
     *
     * @return the resolver, or {@code null}
     */
    public static Function<RTPLocation, String> get() {
        return getter;
    }
}
