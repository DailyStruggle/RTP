package io.github.dailystruggle.rtp.softdepends;

import io.github.dailystruggle.rtp.common.RTP;
import java.util.Collection;
import java.util.logging.Level;
import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public class GriefPreventionChecker {
  private static boolean exists = true;

  private static GriefPrevention getGriefPrevention() {
    Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("GriefPrevention");

    // WorldGuard may not be loaded
    if (!(plugin instanceof GriefPrevention)) {
      return null; // Maybe you want throw an exception instead
    }
    return (GriefPrevention) plugin;
  }

  public static Boolean isInClaim(io.github.dailystruggle.rtp.api.world.RTPCoords location) {
    if (!exists) return false;
    org.bukkit.World world = org.bukkit.Bukkit.getWorld(location.worldName());
    if (world == null) return false;
    return isInClaim(new org.bukkit.Location(world, location.x(), location.y(), location.z()));
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
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling GriefPrevention integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
