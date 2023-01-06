package io.github.dailystruggle.rtp.softdepends;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.bukkit.WorldGuardPlugin;
import com.sk89q.worldguard.protection.ApplicableRegionSet;
import com.sk89q.worldguard.protection.managers.RegionManager;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Objects;

public class WorldGuardChecker {
    private static WorldGuardPlugin getWorldGuard() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("WorldGuard");

        // WorldGuard may not be loaded
        if (!(plugin instanceof WorldGuardPlugin)) {
            return null; // Maybe you want throw an exception instead
        }

        return (WorldGuardPlugin) plugin;
    }

    public static Boolean isInClaim(org.bukkit.Location location) {
        if(getWorldGuard() == null) return false;
        World world = BukkitAdapter.adapt(Objects.requireNonNull(location.getWorld()));
        BlockVector3 pt = BukkitAdapter.asBlockVector(location);
        RegionManager regionManager = WorldGuard.getInstance().getPlatform().getRegionContainer().get(world);
        ApplicableRegionSet set = Objects.requireNonNull(regionManager).getApplicableRegions(pt);
        return set.size()>0;
    }
}
