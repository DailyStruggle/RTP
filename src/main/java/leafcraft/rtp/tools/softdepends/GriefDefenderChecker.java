package leafcraft.rtp.tools.softdepends;

import com.griefdefender.api.GriefDefender;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.Objects;

public class GriefDefenderChecker {
    public static Boolean isInClaim(org.bukkit.Location location) {
        try {
            return !Objects.requireNonNull(GriefDefender.getCore().getClaimAt(location)).isWilderness();
        }
        catch (NullPointerException | NoClassDefFoundError e) {
            return false;
        }
    }
}
