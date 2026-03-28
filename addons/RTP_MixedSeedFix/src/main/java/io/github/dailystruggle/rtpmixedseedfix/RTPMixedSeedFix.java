package io.github.dailystruggle.rtpmixedseedfix;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.papermc.lib.PaperLib;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutionException;
import java.util.logging.Level;

/**
 * Main class for RTPMixedSeedFix addon
 */
public final class RTPMixedSeedFix extends JavaPlugin {
    /**
     * Default constructor for RTPMixedSeedFix
     */
    public RTPMixedSeedFix() {
    }

    @Override
    public void onEnable() {
        // Plugin startup logic
        Region.maxBiomeChecksPerGen = 1;
        RTP.serverAccessor.setBiomeGetter(location -> {
            Biome biome = Biome.PLAINS;
            try {
                int x = location.x() % 16;
                int y = location.y();
                int z = location.z() % 16;
                if (x < 0) x += 16;
                if (z < 0) z += 16;
                org.bukkit.Location bukkitLoc = new org.bukkit.Location(org.bukkit.Bukkit.getWorld(location.world().id()), location.x(), location.y(), location.z());
                Chunk chunk = PaperLib.getChunkAtAsync(bukkitLoc).get();
                if (chunk == null) return biome.name();
                biome = chunk.getBlock(x, y, z).getBiome();
            } catch (InterruptedException | ExecutionException e) {
                RTP.log(Level.WARNING, e.getMessage(), e);
            }
            return biome.name();
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}


