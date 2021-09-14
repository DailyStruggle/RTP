package leafcraft.rtp.tools.softdepends;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import com.massivecraft.factions.Factions;
import com.massivecraft.factions.FactionsPlugin;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Set;

public class SaberFactionsChecker {
    public static Boolean isInClaim(org.bukkit.Location location) {
        try {
            FLocation fLocation = new FLocation(location.getWorld().getName(),location.getBlockX(),location.getBlockZ());
            return Board.getInstance().getFactionAt(fLocation).getOwnerList(fLocation).size()>0;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }
}
