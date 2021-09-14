package leafcraft.rtp.tools.softdepends;

import me.william278.husktowns.HuskTownsAPI;

public class HuskTownsChecker {
    public static Boolean isInClaim(org.bukkit.Location location) {
        try {
            return !HuskTownsAPI.getInstance().isWilderness(location);
        } catch (Error | Exception e) {
            return false;
        }
    }
}
