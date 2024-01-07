package io.github.dailystruggle.rtp.softdepends;

import com.griefdefender.api.GriefDefender;
import io.github.dailystruggle.rtp.common.RTP;

import java.util.Objects;
import java.util.logging.Level;

public class GriefDefenderChecker {
    private static boolean exists = true;
    public static Boolean isInClaim( org.bukkit.Location location ) {
        if( exists ) {
            try {
                return !Objects.requireNonNull( GriefDefender.getCore().getClaimAt( location) ).isWilderness();
            }
            catch ( Throwable t ) {
                exists = false;
                RTP.log( Level.WARNING, t.getMessage(), t );
            }
        }
        return false;
    }
}
