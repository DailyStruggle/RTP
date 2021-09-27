package leafcraft.rtp.tools.configuration;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import leafcraft.rtp.RTP;
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
    private final RTP plugin;
    private FileConfiguration config;
    private final Lang lang;

    //key: world name
    //value: world placeholder
    private BiMap<String,String> worldNameLookup;

    public Worlds(RTP plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;

        File f = new File(plugin.getDataFolder(), "worlds.yml");
        if(!f.exists())
        {
            plugin.saveResource("worlds.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(f);

        if( 	(config.getDouble("version") < 1.7) ) {
            Bukkit.getLogger().log(Level.WARNING, lang.getLog("oldFile", "worlds.yml"));
            FileStuff.renameFiles(plugin,"worlds");
            config = YamlConfiguration.loadConfiguration(f);
        }
        update();
    }

    public void update() {
        final String quotes = "\"";

        ArrayList<String> linesInWorlds = new ArrayList<>();
        String defaultRegion = config.getConfigurationSection("default").getString("region","default");
        Boolean defaultRequirePermission = config.getConfigurationSection("default").getBoolean("requirePermission",true);
        String defaultOverride = config.getConfigurationSection("default").getString("override","world");
        String defaultNearShape = config.getConfigurationSection("default").getString("nearShape","CIRCLE");
        Integer defaultNearRadius = config.getConfigurationSection("default").getInt("nearRadius",16);
        Integer defaultNearCenterRadius = config.getConfigurationSection("default").getInt("nearCenterRadius",8);
        Integer defaultNearMinY = config.getConfigurationSection("default").getInt("nearMinY",48);
        Integer defaultNearMaxY = config.getConfigurationSection("default").getInt("nearMaxY",127);

        for(World w : Bukkit.getWorlds()) {
            String permName = "rtp.worlds." + w.getName();
            if(Bukkit.getPluginManager().getPermission(permName) == null) {
                Permission permission = new Permission(permName);
                permission.setDefault(PermissionDefault.OP);
                permission.addParent("rtp.worlds.*",true);
                Bukkit.getPluginManager().addPermission(permission);
            }
        }

        try {
            Scanner scanner = new Scanner(
                    new File(plugin.getDataFolder().getAbsolutePath() + File.separator + "worlds.yml"));
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
                        linesInWorlds.add("    name: " + quotes + worldName + quotes);
                        linesInWorlds.add("    region: " + quotes + defaultRegion + quotes);
                        linesInWorlds.add("    requirePermission: " + defaultRequirePermission);
                        linesInWorlds.add("    override: " + quotes + defaultOverride + quotes);
                        linesInWorlds.add("    nearShape: " + quotes + defaultNearShape + quotes);
                        linesInWorlds.add("    nearRadius: " + defaultNearRadius);
                        linesInWorlds.add("    nearCenterRadius: " + defaultNearCenterRadius);
                        linesInWorlds.add("    nearMinY: " + defaultNearMinY);
                        linesInWorlds.add("    nearMaxY: " + defaultNearMaxY);
                    }
                }
                else { //if not a blank line
                    if(s.startsWith("    name:"))
                        s = "    name: " + quotes + config.getConfigurationSection(currWorldName).getString("name",currWorldName) + quotes;
                    else if(s.startsWith("    region:"))
                        s = "    region: " + quotes + config.getConfigurationSection(currWorldName).getString("region",defaultRegion) + quotes;
                    else if(s.startsWith("    requirePermission:"))
                        s = "    requirePermission: " + config.getConfigurationSection(currWorldName).getBoolean("requirePermission",defaultRequirePermission);
                    else if(s.startsWith("    override:"))
                        s = "    override: " + quotes + config.getConfigurationSection(currWorldName).getString("override",defaultOverride) + quotes;
                    else if(s.startsWith("    nearShape:"))
                        s = "    nearShape: " + quotes + config.getConfigurationSection(currWorldName).getString("nearShape",defaultNearShape) + quotes;
                    else if(s.startsWith("    nearRadius:"))
                        s = "    nearRadius: " + config.getConfigurationSection(currWorldName).getInt("nearRadius",defaultNearRadius);
                    else if(s.startsWith("    nearCenterRadius:"))
                        s = "    nearCenterRadius: " + config.getConfigurationSection(currWorldName).getInt("nearCenterRadius",defaultNearCenterRadius);
                    else if(s.startsWith("    nearMinY:"))
                        s = "    nearMinY: " + config.getConfigurationSection(currWorldName).getInt("nearMinY",defaultNearMinY);
                    else if(s.startsWith("    nearMaxY:"))
                        s = "    nearMaxY: " + config.getConfigurationSection(currWorldName).getInt("nearMaxY",defaultNearMaxY);
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
            fw = new FileWriter(plugin.getDataFolder().getAbsolutePath() + File.separator + "worlds.yml");
            for (String s : linesArray) {
                fw.write(s + "\n");
            }
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //-------------UPDATE INTERNAL VERSION ACCORDINGLY-------------
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "worlds.yml"));

        this.worldNameLookup = HashBiMap.create();
        for(String worldName : config.getKeys(false)) {
            if(this.checkWorldExists(worldName)) {
                this.worldNameLookup.put(worldName,config.getConfigurationSection(worldName).getString("name"));
            }
        }
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
            Bukkit.getLogger().log(Level.INFO,lang.getLog("newWorld",worldName));
            Bukkit.getLogger().log(Level.INFO,lang.getLog("updatingWorlds"));
            update(); //not optimal but it works
            Bukkit.getLogger().log(Level.INFO,lang.getLog("updatedWorlds"));
        }
        return true;
    }

    public Integer updateWorldSetting(World world, String setting, String value){
        String worldName = world.getName();
        if(!config.getConfigurationSection(worldName).contains(setting)) {
            return -1;
        }

        if(config.getConfigurationSection(worldName).isString(setting)) {
            config.getConfigurationSection(worldName).set(setting,value);
        }
        else if(config.getConfigurationSection(worldName).isInt(setting)) {
            Integer num;
            try {
                num = Integer.valueOf(value);
            }
            catch (Exception exception) {
                return -3;
            }
            config.getConfigurationSection(worldName).set(setting,num);
        }
        else if(config.getConfigurationSection(worldName).isDouble(setting)) {
            Double num;
            try {
                num = Double.valueOf(value);
            }
            catch (Exception exception) {
                return -3;
            }
            config.getConfigurationSection(worldName).set(setting,num);
        }
        else if(config.getConfigurationSection(worldName).isBoolean(setting)) {
            Boolean num;
            try {
                num = Boolean.valueOf(value);
            }
            catch (Exception exception) {
                return -3;
            }
            config.getConfigurationSection(worldName).set(setting,num);
        }
        return 0;
    }

    public String worldPlaceholder2Name(String placeholder) {
        if(!worldNameLookup.containsValue(placeholder)) return placeholder;
        return worldNameLookup.inverse().get(placeholder);
    }

    public String worldName2Placeholder(String worldName) {
        if(!worldNameLookup.containsKey(worldName)) return worldName;
        return worldNameLookup.get(worldName);
    }
}
