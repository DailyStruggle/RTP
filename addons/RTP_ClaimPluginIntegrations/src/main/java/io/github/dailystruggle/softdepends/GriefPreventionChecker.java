package io.github.dailystruggle.softdepends;

import me.ryanhamshire.GriefPrevention.Claim;
import me.ryanhamshire.GriefPrevention.GriefPrevention;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.Collection;

public class GriefPreventionChecker {
    private static GriefPrevention getGriefPrevention() {
        Plugin plugin = Bukkit.getServer().getPluginManager().getPlugin("GriefPrevention");

        // WorldGuard may not be loaded
        if (!(plugin instanceof GriefPrevention)) {
            return null; // Maybe you want throw an exception instead
        }
        return (GriefPrevention) plugin;
    }

    public static Boolean isInClaim(org.bukkit.Location location) {
        if(getGriefPrevention() == null) return false;
        int chunkX = (location.getBlockX() >= 0 || (location.getBlockX()%16==0)) ? (location.getBlockX() / 16) : (location.getBlockX() / 16) - 1;
        int chunkZ = (location.getBlockZ() >= 0 || (location.getBlockZ()%16==0)) ? (location.getBlockZ() / 16) : (location.getBlockZ() / 16) - 1;
        Collection<Claim> claims = GriefPrevention.instance.dataStore.getClaims(chunkX,chunkZ);
        return claims.size()>0;
    }
}
