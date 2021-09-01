package leafcraft.rtp.tools.softdepends;

import me.william278.husktowns.HuskTowns;
import me.william278.husktowns.HuskTownsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class HuskTownsChecker {
    private static HuskTownsAPI getHuskTowns() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("HuskTowns");

        // HuskTowns may not be loaded
        if (!(plugin instanceof HuskTowns)) {
            return null; // Maybe you want throw an exception instead
        }
        return HuskTownsAPI.getInstance();
    }

    public static Boolean isInClaim(org.bukkit.Location location) {
        if(getHuskTowns() == null) return false;
        return !HuskTownsAPI.getInstance().isWilderness(location);
    }
}
