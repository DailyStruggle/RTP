package io.github.dailystruggle.rtp.softdepends;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;

public class FactionsChecker {
    public static Boolean isInClaim(org.bukkit.Location location) {
        try {
            FLocation fLocation = new FLocation(location);
            return Board.getInstance().getFactionAt(fLocation).getOwnerList(fLocation).size()>0;
        } catch (NoClassDefFoundError | NullPointerException e) {
            return false;
        }
    }
}
