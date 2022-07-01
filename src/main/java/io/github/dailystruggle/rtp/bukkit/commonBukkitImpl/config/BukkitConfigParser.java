package io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.config;

import io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.substitutions.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.substitutions.RTPLocation;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BukkitConfigParser<E extends Enum<E>> extends ConfigParser<E> {
    private YamlConfiguration configuration;

    public BukkitConfigParser(Class<E> eClass, String name, String version, File pluginDirectory) {
        super(eClass, name, version, pluginDirectory);
    }

    public BukkitConfigParser(Class<E> eClass, String name, String version, File pluginDirectory, File langFile) {
        super(eClass, name, version, pluginDirectory, langFile);
    }

    @Override
    public void loadResource(File f) {
        configuration = YamlConfiguration.loadConfiguration(f);
        data = new EnumMap<>(myClass);
        for(E key : myClass.getEnumConstants()) {
            Object res = configuration.get(key.name());
            data.put(key,res);
        }
    }

    @Override
    protected Object getFromString(String val, @Nullable Object def) {
        Object res;
        Object o = configuration.get(val, def);

        if(o instanceof ConfigurationSection section) {
            res = getSectionRecursive(section);
        } else if(o instanceof Location location) {
            res = new RTPLocation(
                    new BukkitRTPWorld(location.getWorld()),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );
        } else if(o instanceof Color color) {
            res = color.asRGB();
        } //todo: item stacks
        else res = o;
        return res;
    }

    @Override
    public void set(@NotNull E key, @NotNull Object o) {
        super.set(key,o);
        configuration.set(key.name(),o);
    }

    protected Map<String,Object> getSectionRecursive(ConfigurationSection section) {
        Map<String,Object> res = new ConcurrentHashMap<>();
        Set<String> keys = section.getKeys(false);
        for(String key : keys) {
            Object val;
            Object o = section.get(key);
            if(o instanceof ConfigurationSection configurationSection) {
                val = getSectionRecursive(configurationSection);
            } else if(o instanceof Location location) {
                val = new RTPLocation(
                        new BukkitRTPWorld(location.getWorld()),
                        location.getBlockX(),
                        location.getBlockY(),
                        location.getBlockZ()
                );
            } else if(o instanceof Color color) {
                val = color.asRGB();
            } else {
                val = section.get(key);
            } //todo: item stacks
            res.put(key,val);
        }
        return res;
    }

    //todo: properly write updated values to yaml, including comments/descriptions
    //todo: use parameters and sub-parameters?

}
