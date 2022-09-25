package io.github.dailystruggle.softdepends;

import com.griefdefender.api.GriefDefender;

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
