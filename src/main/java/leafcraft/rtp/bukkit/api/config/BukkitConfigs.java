package leafcraft.rtp.bukkit.api.config;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.configuration.enums.*;
import leafcraft.rtp.api.selection.RegionParams;
import leafcraft.rtp.api.selection.region.Region;
import leafcraft.rtp.api.substitutions.RTPWorld;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import leafcraft.rtp.bukkit.api.selection.region.BukkitRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.util.Collection;
import java.util.EnumMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class BukkitConfigs extends Configs {
    public BukkitConfigs() {
        super(RTPBukkitPlugin.getInstance().getDataFolder());
    }

    @Override
    public boolean checkWorldExists(String name) {
        RTPWorld world = RTPAPI.getInstance().serverAccessor.getRTPWorld(name);
        if(world == null) return false;
        ConfigParser<WorldKeys> worldsParser = worlds.getParser(name);
        if(worldsParser == null) {
            worldsParser = new BukkitConfigParser<>(WorldKeys.class, name, "1.0", worlds.myDirectory);
            worlds.addParser(worldsParser);
        }
        return true;
    }

    @Override
    public CompletableFuture<Boolean> reload() {
        CompletableFuture<Boolean> res = new CompletableFuture<>();

        //ensure async to protect server timings
        Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(), () -> {
            try {
                reloadAction();
                res.complete(true);
            } catch (Exception e) { //on any code failure, complete false
                res.complete(false);
                throw e;
            }
        });
        return res;
    }

    protected void reloadAction() {
        lang = new BukkitConfigParser<>(LangKeys.class,"lang.yml", "1.0", pluginDirectory, null);

        config = new BukkitConfigParser<>(ConfigKeys.class,"config.yml", "1.0", pluginDirectory);
        economy = new BukkitConfigParser<>(EconomyKeys.class,"economy.yml", "1.0", pluginDirectory);
        integrations = new BukkitConfigParser<>(IntegrationsKeys.class,"integrations.yml", "1.0", pluginDirectory);
        performance = new BukkitConfigParser<>(PerformanceKeys.class,"performance.yml", "1.0", pluginDirectory);
        safety = new BukkitConfigParser<>(SafetyKeys.class,"safety", "1.0", pluginDirectory);

        File worldLangMap = new File(pluginDirectory + File.separator + "lang" + File.separator + "worlds.lang.yml");

        regions = new BukkitMultiConfigParser<>(RegionKeys.class,"regions", "1.0", pluginDirectory);
        worlds = new BukkitMultiConfigParser<>(WorldKeys.class,"worlds", "1.0", pluginDirectory);

        for(World world : Bukkit.getWorlds()) {
            if(worlds.getParser(world.getName()) == null) {
                worlds.addParser(new BukkitConfigParser<>(WorldKeys.class, world.getName(),"1.0", worlds.myDirectory, worldLangMap));
            }
        }

        for(ConfigParser<?> regionConfig : regions.configParserFactory.map.values()) {
            EnumMap<RegionKeys, Object> data = (EnumMap<RegionKeys, Object>) regionConfig.getData();

            RTPAPI.log(Level.WARNING, data.toString());
            Region region = new BukkitRegion(regionConfig.name.replace(".yml",""), data);
            RegionParams regionParams = new RegionParams(data);
            RTPAPI.getInstance().selectionAPI.permRegions.put(regionParams,region);
            RTPAPI.getInstance().selectionAPI.permRegionLookup.put(region.name,region);
        }
    }
}
