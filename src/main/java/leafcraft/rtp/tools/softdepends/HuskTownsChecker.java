package leafcraft.rtp.tools.softdepends;

import me.william278.husktowns.HuskTownsAPI;

public class HuskTownsChecker {
    public static boolean isInClaim(org.bukkit.Location location) {
        try {
            return HuskTownsAPI.getInstance().isWilderness(location);
        } catch (NoClassDefFoundError | NullPointerException e) {
            return false;
        }
    }
}
