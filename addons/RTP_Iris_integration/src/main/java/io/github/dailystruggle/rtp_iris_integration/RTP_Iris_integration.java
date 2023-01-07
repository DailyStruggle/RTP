package io.github.dailystruggle.rtp_iris_integration;

import com.volmit.iris.core.tools.IrisToolbelt;
import com.volmit.iris.engine.object.IrisBiome;
import com.volmit.iris.engine.platform.PlatformChunkGenerator;
import com.volmit.iris.util.collection.KList;
import io.github.dailystruggle.rtp.bukkit.server.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Biome;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class RTP_Iris_integration extends JavaPlugin {
    private static final Pattern invalidCharacters = Pattern.compile("[ :,]");
    @Override
    public void onEnable() {
        // Plugin startup logic
        BukkitRTPWorld.setBiomesGetter(RTP_Iris_integration::getBiomes);
        BukkitRTPWorld.setBiomeGetter(RTP_Iris_integration::getBiome);
        Bukkit.getScheduler().scheduleSyncDelayedTask(this,() -> {
            RTP.baseCommand.addParameter("biome", new IrisBiomeParameter());
            RTP.configs.reload();
        });
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    private static Set<String> getBiomes(RTPWorld rtpWorld) {
        if(!(rtpWorld instanceof BukkitRTPWorld)) {
            return new HashSet<>();
        }
        BukkitRTPWorld world = (BukkitRTPWorld) rtpWorld;
        PlatformChunkGenerator access = IrisToolbelt.access(world.world());
        if(access == null) return Arrays.stream(Biome.values()).map(Enum::name).collect(Collectors.toSet());
        KList<IrisBiome> allBiomes = access.getEngine().getAllBiomes();
        Set<String> collect = new HashSet<>();
        for (IrisBiome irisBiome : allBiomes) {
            String s = irisBiome.getName().toUpperCase();
            s = invalidCharacters.matcher(s).replaceAll("_");
            collect.add(s);
        }
        return collect;
    }

    private static String getBiome(Location location) {
        PlatformChunkGenerator access = IrisToolbelt.access(location.getWorld());
        if(access == null) return location.getWorld().getBiome(location).name().toUpperCase();
        String s = access.getEngine().getBiome(location).getName().toUpperCase();
        s = invalidCharacters.matcher(s).replaceAll("_");
        return s;
    }
}
