package io.github.dailystruggle.rtp.softdepends;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagConflictException;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public class WorldGuardChecker {
    public static StateFlag CAN_RTP_SELECT_HERE = null;

    public static void setupWGFlag() {
        FlagRegistry registry = WorldGuard.getInstance().getFlagRegistry();
        try {
            // create a flag with the name "my-custom-flag", defaulting to true
            StateFlag flag = new StateFlag("can-rtp-select-here", false);
            registry.register(flag);
            CAN_RTP_SELECT_HERE = flag; // only set our field if there was no error
        } catch (FlagConflictException e) {
            // some other plugin registered a flag by the same name already.
            // you can use the existing flag, but this may cause conflicts - be sure to check type
            Flag<?> existing = registry.get("can-rtp-select-here");
            if (existing instanceof StateFlag) {
                CAN_RTP_SELECT_HERE = (StateFlag) existing;
            } else {
                // types don't match - this is bad news! some other plugin conflicts with you
                // hopefully this never actually happens
            }
        }
    }

    private static WorldGuardPlugin getWorldGuard() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("WorldGuard");

        // WorldGuard may not be loaded
        if (!(plugin instanceof WorldGuardPlugin)) {
            return null; // Maybe you want throw an exception instead
        }

        return (WorldGuardPlugin) plugin;
    }

    private static boolean exists = true;
    public static Boolean isInClaim(org.bukkit.Location location) {
        if(exists) {
            try {
                if(getWorldGuard() == null) return false;
                if(CAN_RTP_SELECT_HERE == null) setupWGFlag();
                if(CAN_RTP_SELECT_HERE == null) return false;

                World world = BukkitAdapter.adapt(Objects.requireNonNull(location.getWorld()));
                BlockVector3 pt = BukkitAdapter.asBlockVector(location);
                RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
                ApplicableRegionSet set = Objects.requireNonNull(regionManager).getApplicableRegions(pt);
                return set.testState(null, CAN_RTP_SELECT_HERE);
            } catch (Throwable t) {
                exists = false;
                t.printStackTrace();
            }
        }
        return false;
    }
}
