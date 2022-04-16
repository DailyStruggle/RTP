package leafcraft.rtp.bukkit.api.config;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.Configs;
import leafcraft.rtp.api.configuration.enums.ConfigKeys;
import leafcraft.rtp.api.configuration.enums.LangKeys;
import leafcraft.rtp.api.configuration.enums.RegionKeys;
import leafcraft.rtp.api.configuration.enums.WorldKeys;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Level;

public class BukkitConfigs extends Configs {
    public BukkitConfigs() {
        super(RTPBukkitPlugin.getInstance().getDataFolder());
    }

    @Override
    public boolean checkWorldExists(String name) {
        World world = Bukkit.getWorld(name);
        if(world == null) return false;
        ConfigParser<WorldKeys> worldsParser = worlds.getParser(name);
        if(worldsParser == null) {
            worldsParser = new BukkitConfigParser<>(WorldKeys.class, name, "1.7", worlds.myDirectory, lang);
            worlds.addParser(worldsParser);
        }
        return true;
    }

    @Override
    public void reload() {
        Bukkit.getScheduler().runTaskAsynchronously(RTPBukkitPlugin.getInstance(),() -> {
            lang = new BukkitConfigParser<>(LangKeys.class,"lang.yml", "2.2", pluginDirectory, null);
            config = new BukkitConfigParser<>(ConfigKeys.class,"config.yml", "2.10", pluginDirectory, lang);

            EnumMap<ConfigKeys, Object> data = config.getData();
            for (Map.Entry<ConfigKeys, Object> entry : data.entrySet()) {
                Object value = entry.getValue();
                RTPAPI.log(Level.WARNING, entry.getKey().name() + " - " + (value == null ? "null" : value.toString()));
            }


            worlds = new BukkitMultiConfigParser<>(WorldKeys.class,"worlds", "1.7", pluginDirectory, lang);
            regions = new BukkitMultiConfigParser<>(RegionKeys.class,"regions", "1.3", pluginDirectory, lang);

            for(World world : Bukkit.getWorlds()) {
                if(worlds.getParser(world.getName()) == null) {
                    worlds.addParser(new BukkitConfigParser<>(WorldKeys.class, world.getName(),"1.7", worlds.myDirectory, lang));
                }
            }
        });
    }
}
