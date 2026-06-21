package io.github.dailystruggle.rtp.claimaddon;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import io.github.dailystruggle.rtp.common.RTP;
import java.util.Objects;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Checker for WorldGuard regions (honors the {@code can-rtp-select-here} state flag). */
public class WorldGuardChecker {
  public static StateFlag CAN_RTP_SELECT_HERE = null;
  private static WorldGuardPlugin worldGuardPlugin = null;
  private static boolean exists = true;

  public static void setupWGFlag() {
    FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
    try {
      StateFlag flag = new StateFlag("can-rtp-select-here", false);
      registry.register(flag);
      CAN_RTP_SELECT_HERE = flag;
    } catch (Exception e) {
      Flag<?> existing = registry.get("can-rtp-select-here");
      if (existing instanceof StateFlag) {
        CAN_RTP_SELECT_HERE = (StateFlag) existing;
      }
    }
  }

  private static WorldGuardPlugin getWorldGuard() {
    if (worldGuardPlugin != null) return worldGuardPlugin;
    Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("WorldGuard");
    if (!(plugin instanceof WorldGuardPlugin)) {
      return null;
    }
    worldGuardPlugin = (WorldGuardPlugin) plugin;
    return worldGuardPlugin;
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
      if (getWorldGuard() == null) return false;
      if (CAN_RTP_SELECT_HERE == null) setupWGFlag();
      if (CAN_RTP_SELECT_HERE == null) return false;
      World world = BukkitAdapter.adapt(Objects.requireNonNull(location.getWorld()));
      BlockVector3 pt = BukkitAdapter.asBlockVector(location);
      RegionManager regionManager =
          WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
      ApplicableRegionSet set = Objects.requireNonNull(regionManager).getApplicableRegions(pt);
      // Wilderness (no applicable WorldGuard region) is selectable: not "in a claim".
      if (set.size() == 0) return false;
      // Inside one or more regions: this location is protected and shall be rerolled,
      // unless an admin has explicitly opted-in by setting the can-rtp-select-here flag
      // to ALLOW on the region(s) covering this point.
      return !set.testState(null, CAN_RTP_SELECT_HERE);
    } catch (Throwable t) {
      exists = false;
      RTP.log(
          Level.SEVERE,
          "[RTP] Critical architectural incompatibility detected. Disabling WorldGuard integration for this session to prevent server instability.",
          t);
    }
    return false;
  }
}
