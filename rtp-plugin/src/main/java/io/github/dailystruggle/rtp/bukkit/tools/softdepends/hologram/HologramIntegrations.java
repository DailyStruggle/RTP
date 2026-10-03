package io.github.dailystruggle.rtp.bukkit.tools.softdepends.hologram;

import io.github.dailystruggle.effectsapi.common.hologram.HologramProvider;
import io.github.dailystruggle.effectsapi.common.hologram.HologramRegistry;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * Coordinates external hologram plugin hooks and vanilla TextDisplay fallback on Bukkit / Paper / Folia.
 */
public final class HologramIntegrations {
    private HologramIntegrations() {}

    /**
     * Probes available hologram systems in priority order:
     * 1. DecentHolograms
     * 2. HolographicDisplays
     * 3. Native TextDisplay entities (Paper/Folia 1.19.4+)
     */
    public static void setup(JavaPlugin plugin) {
        // 1. DecentHolograms
        if (DecentHologramsChecker.isAvailable()) {
            bind("DecentHolograms", new DecentHologramsChecker());
            return;
        }

        // 2. HolographicDisplays
        if (HolographicDisplaysChecker.isAvailable()) {
            bind("HolographicDisplays", new HolographicDisplaysChecker(plugin));
            return;
        }

        // 3. Native vanilla TextDisplay (Paper / Folia 1.19.4+)
        if (BukkitTextDisplayHologramProvider.isAvailable()) {
            bind("Paper TextDisplay", new BukkitTextDisplayHologramProvider());
            return;
        }

        RTP.log(Level.FINER, "[RTP] No external hologram plugin or native TextDisplay support detected; using virtual fallback.");
    }

    private static void bind(String name, HologramProvider provider) {
        try {
            HologramRegistry.register(provider);
            RTP.log(Level.INFO, "[RTP] Bound hologram provider '" + name + "' for floating countdowns.");
        } catch (Throwable t) {
            RTP.log(Level.WARNING, "[RTP] Failed to bind hologram provider '" + name + "': " + t.getMessage(), t);
        }
    }
}
