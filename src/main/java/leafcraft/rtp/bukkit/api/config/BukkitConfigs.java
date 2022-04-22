package leafcraft.rtp.bukkit.api.config;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.configuration.enums.*;
import leafcraft.rtp.api.substitutions.RTPWorld;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
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
            worldsParser = new BukkitConfigParser<>(WorldKeys.class, name, "1.0", worlds.myDirectory, lang);
            worlds.addParser(worldsParser);
        }
        return true;
    }

    @Override
    public void reload() {
        Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
            lang = new BukkitConfigParser<>(LangKeys.class,"lang.yml", "1.0", pluginDirectory, null);
            config = new BukkitConfigParser<>(ConfigKeys.class,"config.yml", "1.0", pluginDirectory, lang);
            economy = new BukkitConfigParser<>(EconomyKeys.class,"economy.yml", "1.0", pluginDirectory, lang);
            integrations = new BukkitConfigParser<>(IntegrationsKeys.class,"integrations.yml", "1.0", pluginDirectory, lang);
            performance = new BukkitConfigParser<>(PerformanceKeys.class,"performance.yml", "1.0", pluginDirectory, lang);
            worlds = new BukkitMultiConfigParser<>(WorldKeys.class,"worlds", "1.0", pluginDirectory, lang);
            regions = new BukkitMultiConfigParser<>(RegionKeys.class,"regions", "1.0", pluginDirectory, lang);

            File worldLangMap = new File(RTPBukkitPlugin.getInstance().getDataFolder() + File.separator + "lang" + File.separator + "worlds.lang.yml");
            for(World world : Bukkit.getWorlds()) {
                if(worlds.getParser(world.getName()) == null) {
                    worlds.addParser(new BukkitConfigParser<>(WorldKeys.class, world.getName(),"1.0", worlds.myDirectory, lang, worldLangMap));
                }
            }
        });
    }
}
