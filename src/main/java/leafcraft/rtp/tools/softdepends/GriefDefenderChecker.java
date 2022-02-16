package leafcraft.rtp.tools.softdepends;

import com.griefdefender.api.GriefDefender;

import java.util.Objects;

public class GriefDefenderChecker {
    public static boolean isInClaim(org.bukkit.Location location) {
        try {
            return Objects.requireNonNull(GriefDefender.getCore().getClaimAt(location)).isWilderness();
        }
        catch (NullPointerException | NoClassDefFoundError e) {
            return true;
        }
    }
}
