package io.github.dailystruggle.rtp.claimaddon;

import java.util.Collection;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Checker for GriefPrevention claims */
public class GriefPreventionChecker {
  private static boolean exists = true;

  private static GriefPrevention getGriefPrevention() {
    Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("GriefPrevention");
    if (!(plugin instanceof GriefPrevention)) {
      return null;
    }
    return (GriefPrevention) plugin;
  }

  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists || location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      if (getGriefPrevention() == null) return false;
      int chunkX = location.getBlockX() >> 4;
      int chunkZ = location.getBlockZ() >> 4;
      Collection<Claim> claims = GriefPrevention.instance.dataStore.getClaims(chunkX, chunkZ);
      return !claims.isEmpty();
    } catch (Throwable t) {
      return ClaimCheckFailure.handle("GriefPrevention", t, () -> exists = false);
    }
  }
}
