package io.github.dailystruggle.rtp.softdepends;

import io.github.dailystruggle.rtp.RTPClaimPluginIntegrations;
import io.github.dailystruggle.rtp.common.RTP;
import me.angeschossen.lands.api.integration.LandsIntegration;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

/**
 * Checker for Lands claims
 */
public class LandsChecker {
    private static LandsIntegration landsIntegration = null;
    private static boolean exists = true;

    /**
     * Setup Lands integration
     *
     * @param yourPlugin the plugin instance
     */
    public static void landsSetup(Plugin yourPlugin) {
        try {
            landsIntegration = new LandsIntegration(yourPlugin);
        } catch (NullPointerException | NoClassDefFoundError ignored) {

        }
    }

    /**
     * Check if a location is within a Lands claim
     *
     * @param location the location to check
     * @return true if in a claim, false otherwise
     */
    public static Boolean isInClaim(org.bukkit.Location location) {
        if (exists) {
            try {
                int x = location.getBlockX();
                int z = location.getBlockZ();
                int chunkX = (x > 0) ? x / 16 : x / 16 - 1;
                int chunkZ = (z > 0) ? z / 16 : z / 16 - 1;
                if (landsIntegration == null) landsSetup(JavaPlugin.getPlugin(RTPClaimPluginIntegrations.class));
                return landsIntegration.isClaimed(Objects.requireNonNull(location.getWorld()), chunkX, chunkZ);
            } catch (Throwable t) {
                exists = false;
                RTP.log(Level.WARNING, t.getMessage(), t);
            }
        }
        return false;
    }
}


