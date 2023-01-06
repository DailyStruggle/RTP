package io.github.dailystruggle.rtp.softdepends;

import me.angeschossen.lands.api.integration.LandsIntegration;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public class LandsChecker {
    private static LandsIntegration landsIntegration = null;

    public static void landsSetup(Plugin yourPlugin) {
        try {
            landsIntegration = new LandsIntegration(yourPlugin);
        }
        catch (NullPointerException | NoClassDefFoundError ignored) {

        }
    }

    public static Boolean isInClaim(org.bukkit.Location location) {
        try {
            int x = location.getBlockX();
            int z = location.getBlockZ();
            int chunkX = (x>0) ? x/16 : x/16-1;
            int chunkZ = (z>0) ? z/16 : z/16-1;
            return landsIntegration.isClaimed(Objects.requireNonNull(location.getWorld()),chunkX,chunkZ);
        }
        catch (NullPointerException | NoClassDefFoundError e) {
            return false;
        }
    }
}
