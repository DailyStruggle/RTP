package io.github.dailystruggle.rtp.bukkit.commonBukkitImpl.config;

import io.github.dailystruggle.rtp.bukkit.RTPBukkitPlugin;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import org.bukkit.plugin.Plugin;

import java.io.File;

public class BukkitMultiConfigParser<E extends Enum<E>> extends MultiConfigParser<E> {
    public BukkitMultiConfigParser(Class<E> eClass, String name, String version, File pluginDirectory) {
        super(eClass, name, pluginDirectory);
        Plugin plugin = RTPBukkitPlugin.getInstance();

        File d = new File(myDirectory.getAbsolutePath() + File.separator + "default.yml");
        if(!d.exists()) {
            plugin.saveResource(name + File.separator + "default.yml", false);
        }

        File langMap = new File(RTPBukkitPlugin.getInstance().getDataFolder() + File.separator + "lang" + File.separator + name + ".lang.yml");

        File[] files = myDirectory.listFiles();
        if(files == null) return;
        for(File file : files) {
            String fileName = file.getName();
            if(!fileName.endsWith(".yml")) continue;
            if(fileName.contains("old")) continue;

            fileName = fileName.replace(".yml","");

            BukkitConfigParser<E> parser = new BukkitConfigParser<>(
                    eClass,fileName,version,myDirectory,langMap);
            addParser(parser);
        }
    }
}
