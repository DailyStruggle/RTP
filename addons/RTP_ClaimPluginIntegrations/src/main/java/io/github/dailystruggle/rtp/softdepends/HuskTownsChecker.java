package io.github.dailystruggle.rtp.softdepends;

import me.william278.husktowns.HuskTownsAPI;

public class HuskTownsChecker {
    private static boolean exists = true;
    public static Boolean isInClaim(org.bukkit.Location location) {
        if(exists) {
            try {
                return !HuskTownsAPI.getInstance().isWilderness(location);
            } catch (Throwable t) {
                exists = false;
                t.printStackTrace();
            }
        }
        return false;
    }
}
