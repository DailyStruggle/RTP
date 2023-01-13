package io.github.dailystruggle.rtpmixedseedfix;

import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.paperlib.PaperLib;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutionException;

public final class RTPMixedSeedFix extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        Region.maxBiomeChecksPerGen=1;
        BukkitRTPWorld.setBiomeGetter(location -> {
            Biome biome = Biome.PLAINS;
            try {
                Chunk chunk = PaperLib.getChunkAtAsync(location).get();
                biome = chunk.getBlock(
                        location.getBlockX()%16,
                        location.getBlockY(),
                        location.getBlockZ()%16
                ).getBiome();
            } catch (InterruptedException | ExecutionException e) {
                e.printStackTrace();
            }
            return biome.name();
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
