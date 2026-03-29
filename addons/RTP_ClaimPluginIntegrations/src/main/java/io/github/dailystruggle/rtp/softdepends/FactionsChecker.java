package io.github.dailystruggle.rtp.softdepends;

import com.massivecraft.factions.Board;
import com.massivecraft.factions.FLocation;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.logging.Level;

/** Checker for Factions claims */
public class FactionsChecker {
  private static boolean exists = true;

  /**
   * Check if a location is within a Factions claim
   *
   * @param location the location to check
   * @return true if in a claim, false otherwise
   */
  public static Boolean isInClaim(org.bukkit.Location location) {
    if (exists) {
      try {
        FLocation fLocation = new FLocation(location);
        return Board.getInstance().getFactionAt(fLocation).getOwnerList(fLocation).isEmpty();
      } catch (Throwable t) {
        RTP.log(Level.WARNING, t.getMessage(), t);
        exists = false;
      }
    }
    return false;
  }
}
