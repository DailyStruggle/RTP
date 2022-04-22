package leafcraft.rtp.bukkit.api.config;

import leafcraft.rtp.api.RTPAPI;
import leafcraft.rtp.api.configuration.ConfigParser;
import leafcraft.rtp.api.configuration.MultiConfigParser;
import leafcraft.rtp.api.configuration.enums.LangKeys;
import leafcraft.rtp.bukkit.RTPBukkitPlugin;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.logging.Level;

public class BukkitMultiConfigParser<E extends Enum<E>> extends MultiConfigParser<E> {
    public BukkitMultiConfigParser(Class<E> eClass, String name, String version, File pluginDirectory, ConfigParser<LangKeys> lang) {
        super(eClass, name, pluginDirectory, lang);
        Plugin plugin = RTPBukkitPlugin.getInstance();

        File d = new File(myDirectory.getAbsolutePath() + File.separator + "default.yml");
        if(!d.exists()) {
            plugin.saveResource(name + File.separator + "default.yml", false);
        }

        File langMap = new File(RTPBukkitPlugin.getInstance().getDataFolder() + File.separator + "lang" + File.separator + name + "lang.yml");

        File[] files = myDirectory.listFiles();
        if(files == null) return;
        for(File file : files) {
            String fileName = file.getName();
            if(!fileName.endsWith(".yml")) continue;
            if(fileName.contains("old")) continue;

            fileName = fileName.replace(".yml","");

            BukkitConfigParser<E> parser = new BukkitConfigParser<>(
                    eClass,fileName,version,myDirectory,lang,langMap);
            addParser(parser);
        }
    }
}
