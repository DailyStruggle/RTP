package io.github.dailystruggle.softdepends;

import me.angeschossen.lands.api.integration.LandsIntegration;
import org.bukkit.plugin.Plugin;

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
            return landsIntegration.isClaimed(location);
        }
        catch (NullPointerException | NoClassDefFoundError e) {
            return false;
        }
    }
}
