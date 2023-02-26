package io.github.dailystruggle.rtp.softdepends;

import com.palmergames.bukkit.towny.TownyAPI;
import org.bukkit.Location;

public class TownyAdvancedChecker {
    private static boolean exists = true;
    public static boolean isInClaim(Location location) {
        if(exists) {
            try {
                return !TownyAPI.getInstance().isWilderness(location);
            } catch (Throwable t) {
                exists = false;
                t.printStackTrace();
            }
        }
        return false;
    }
}
