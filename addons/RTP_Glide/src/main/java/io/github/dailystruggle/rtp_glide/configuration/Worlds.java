package io.github.dailystruggle.rtp_glide.configuration;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import io.github.dailystruggle.rtp_glide.RTP_Glide;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.logging.Level;

public class Worlds {
    private final RTP_Glide plugin;
    private FileConfiguration config;

    public Worlds(RTP_Glide plugin) {
        this.plugin = plugin;

        File f = new File(plugin.getDataFolder(), "worlds/default.yml");
        if(!f.exists())
        {
            plugin.saveResource("worlds/default.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(f);

        if( 	(config.getDouble("version") < 1.0) ) {
            SendMessage.sendMessage(Bukkit.getConsoleSender(), "[rtp_glide] old default.yml detected. Updating");
            FileStuff.renameFiles(plugin,"worlds");
            config = YamlConfiguration.loadConfiguration(f);
        }
        update();
    }

    public void update() {
        ArrayList<String> linesInWorlds = new ArrayList<>();
        int defaultRelative = config.getConfigurationSection("default").getInt("relative",75);
        int defaultMax = config.getConfigurationSection("default").getInt("max",320);

        try {
            Scanner scanner = new Scanner(
                    new File(plugin.getDataFolder().getAbsolutePath() + File.separator + "worlds/default.yml"));
            //for each line in original messages file
            String currWorldName = "default";
            while (scanner.hasNextLine()) {
                String s = scanner.nextLine();

                //append at first blank line
                if(!(s.matches(".*[a-z].*")||s.matches(".*[A-Z].*"))) {
                    //for each missing world, add some default data
                    for(World w : Bukkit.getWorlds()) {
                        String worldName = w.getName();
                        if(config.contains(worldName)) continue;
                        config.set(worldName, config.getConfigurationSection("default"));

                        if(linesInWorlds.get(linesInWorlds.size()-1).length() < 4)
                            linesInWorlds.set(linesInWorlds.size()-1,"    " + worldName + ":");
                        else linesInWorlds.add(worldName + ":");
                        linesInWorlds.add("    relative: " + defaultRelative);
                        linesInWorlds.add("    max: " + defaultMax);
                    }
                }
                else { //if not a blank line
                    if(s.startsWith("    relative:"))
                        s = "    relative: " + config.getConfigurationSection(currWorldName).getInt("relative", defaultRelative);
                    else if(s.startsWith("    max:"))
                        s = "    max: " + config.getConfigurationSection(currWorldName).getInt("max", defaultMax);
                    else if(!s.startsWith("#") && !s.startsWith("  ") && !s.startsWith("version") && (s.matches(".*[a-z].*") || s.matches(".*[A-Z].*")))
                    {
                        currWorldName = s.replace(":","");
                    }
                }

                //add line
                linesInWorlds.add(s);
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        FileWriter fw;
        String[] linesArray = linesInWorlds.toArray(new String[linesInWorlds.size()]);
        try {
            fw = new FileWriter(plugin.getDataFolder().getAbsolutePath() + File.separator + "worlds/default.yml");
            for (String s : linesArray) {
                fw.write(s + "\n");
            }
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //-------------UPDATE INTERNAL VERSION ACCORDINGLY-------------
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "worlds/default.yml"));
    }

    public Object getWorldSetting(String worldName, String name, Object def) {
        if(!config.contains(worldName)) return def;
        return this.config.getConfigurationSection(worldName).get(name,def);
    }

    public Boolean checkWorldExists(String worldName) {
        if(worldName == null) return false;
        Boolean bukkitWorldExists = Bukkit.getWorld(worldName)!=null;
        Boolean worldKnown = this.config.contains(worldName);
        if( !bukkitWorldExists ) {
            return false;
        }
        else if( !worldKnown ) {
            SendMessage.sendMessage(Bukkit.getConsoleSender(),"[rtp_glide] detected a new world '" + worldName + "'");
            SendMessage.sendMessage(Bukkit.getConsoleSender(),"[rtp_glide] updating worlds configuration...");
            update(); //not optimal but it works
            SendMessage.sendMessage(Bukkit.getConsoleSender(),"[rtp_glide] updated worlds configuration!");
        }
        return true;
    }
}
