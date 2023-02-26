package io.github.dailystruggle.rtp.softdepends;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;

public class FactionsChecker {
    private static boolean exists = true;

    public static Boolean isInClaim(org.bukkit.Location location) {
        if(exists) {
            try {
                FLocation fLocation = new FLocation(location);
                return Board.getInstance().getFactionAt(fLocation).getOwnerList(fLocation).size() > 0;
            } catch (Throwable t) {
                t.printStackTrace();
                exists = false;
            }
        }
        return false;
    }
}
