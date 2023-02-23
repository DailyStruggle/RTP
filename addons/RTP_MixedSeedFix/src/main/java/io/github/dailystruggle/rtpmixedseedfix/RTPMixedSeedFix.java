package io.github.dailystruggle.rtpmixedseedfix;

import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.paperlib.PaperLib;
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
                int x = location.getBlockX()%16;
                int y = location.getBlockY();
                int z = location.getBlockZ()%16;
                if(x<0) x += 16;
                if(z<0) z += 16;
                Chunk chunk = PaperLib.getChunkAtAsync(location).get();
                if(chunk == null) return;
                biome = chunk.getBlock(x,y,z).getBiome();
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
