package io.github.dailystruggle.rtp.softdepends;

import com.palmergames.bukkit.towny.TownyAPI;
import io.github.dailystruggle.rtp.common.RTP;
import org.bukkit.Location;

import java.util.logging.Level;

public class TownyAdvancedChecker {
    private static boolean exists = true;
    public static boolean isInClaim( Location location ) {
        if( exists ) {
            try {
                return !TownyAPI.getInstance().isWilderness( location );
            } catch ( Throwable t ) {
                exists = false;
                RTP.log( Level.WARNING, t.getMessage(), t );
            }
        }
        return false;
    }
}
