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
import java.util.Objects;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Checker for WorldGuard regions (honors the {@code can-rtp-select-here} state flag). */
public class WorldGuardChecker {
  public static StateFlag CAN_RTP_SELECT_HERE = null;
  private static WorldGuardPlugin worldGuardPlugin = null;
  private static boolean exists = true;
  private static volatile boolean flagSetupAttempted = false;

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
    if (!exists || location == null) return false;
    org.bukkit.Location loc = ClaimLocationResolver.toLocation(location);
    if (loc == null) return false;
    return isInClaim(loc);
  }

  public static Boolean isInClaim(org.bukkit.Location location) {
    if (!exists) return false;
    try {
      if (getWorldGuard() == null) return false;
      // One attempt only: the registry locks once WorldGuard enables, so a late registration
      // fails every time and the opt-in flag stays unavailable.
      if (CAN_RTP_SELECT_HERE == null && !flagSetupAttempted) {
        flagSetupAttempted = true;
        setupWGFlag();
      }
      World world = BukkitAdapter.adapt(Objects.requireNonNull(location.getWorld()));
      BlockVector3 pt = BukkitAdapter.asBlockVector(location);
      RegionManager regionManager =
          WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
      // Null when region protection is disabled for this world: no regions, not "in a claim".
      if (regionManager == null) return false;
      ApplicableRegionSet set = regionManager.getApplicableRegions(pt);
      // Wilderness (no applicable WorldGuard region) is selectable: not "in a claim".
      if (set.size() == 0) return false;
      // Inside one or more regions: protected unless the can-rtp-select-here flag is registered
      // and set to ALLOW on the covering region(s). An unregistered flag cannot opt in.
      StateFlag optIn = CAN_RTP_SELECT_HERE;
      if (optIn == null) return true;
      return !set.testState(null, optIn);
    } catch (Throwable t) {
      return ClaimCheckFailure.handle("WorldGuard", t, () -> exists = false);
    }
  }
}
