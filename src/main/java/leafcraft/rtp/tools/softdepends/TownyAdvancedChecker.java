package leafcraft.rtp.tools.softdepends;

import com.palmergames.bukkit.towny.TownyAPI;
import org.bukkit.Location;

public class TownyAdvancedChecker {
    public static boolean isInClaim(Location location) {
        try {
            return !TownyAPI.getInstance().isWilderness(location);
        } catch (Error | Exception e) {
            return false;
        }
    }
}
