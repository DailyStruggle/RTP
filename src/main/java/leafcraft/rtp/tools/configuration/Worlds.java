package leafcraft.rtp.tools.configuration;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

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
            SendMessage.sendMessage(Bukkit.getConsoleSender(), lang.getLog("oldFile", "worlds.yml"));
            FileStuff.renameFiles(plugin,"worlds");
            config = YamlConfiguration.loadConfiguration(f);
        }
        update();
    }

    public void update() {
        final String quotes = "\"";

        ConfigurationSection defaultSection = config.getConfigurationSection("default");
        Objects.requireNonNull(defaultSection);

        ArrayList<String> linesInWorlds = new ArrayList<>();
        String defaultRegion = defaultSection.getString("region","default");
        boolean defaultRequirePermission = defaultSection.getBoolean("requirePermission",true);
        String defaultOverride = defaultSection.getString("override","world");
        String defaultNearShape = defaultSection.getString("nearShape","CIRCLE");
        int defaultNearRadius = defaultSection.getInt("nearRadius",16);
        int defaultNearCenterRadius = defaultSection.getInt("nearCenterRadius",8);
        int defaultNearMinY = defaultSection.getInt("nearMinY",48);
        int defaultNearMaxY = defaultSection.getInt("nearMaxY",127);

        Bukkit.getScheduler().runTaskLater(RTP.getInstance(),()->{
            for(World w : Bukkit.getWorlds()) {
                String permName = "rtp.worlds." + w.getName();
                if(Bukkit.getPluginManager().getPermission(permName) == null) {
                    Permission permission = new Permission(permName);
                    permission.setDefault(PermissionDefault.OP);
                    permission.addParent("rtp.worlds.*",true);
                    Bukkit.getPluginManager().addPermission(permission);
                }
            }
        },1000);


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
                        config.set(worldName, defaultSection);

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
                    ConfigurationSection worldSection = config.getConfigurationSection(currWorldName);
                    Objects.requireNonNull(worldSection);
                    if(s.startsWith("    name:"))
                        s = "    name: " + quotes + worldSection.getString("name",currWorldName) + quotes;
                    else if(s.startsWith("    region:"))
                        s = "    region: " + quotes + worldSection.getString("region",defaultRegion) + quotes;
                    else if(s.startsWith("    requirePermission:"))
                        s = "    requirePermission: " + worldSection.getBoolean("requirePermission",defaultRequirePermission);
                    else if(s.startsWith("    override:"))
                        s = "    override: " + quotes + worldSection.getString("override",defaultOverride) + quotes;
                    else if(s.startsWith("    nearShape:"))
                        s = "    nearShape: " + quotes + worldSection.getString("nearShape",defaultNearShape) + quotes;
                    else if(s.startsWith("    nearRadius:"))
                        s = "    nearRadius: " + worldSection.getInt("nearRadius",defaultNearRadius);
                    else if(s.startsWith("    nearCenterRadius:"))
                        s = "    nearCenterRadius: " + worldSection.getInt("nearCenterRadius",defaultNearCenterRadius);
                    else if(s.startsWith("    nearMinY:"))
                        s = "    nearMinY: " + worldSection.getInt("nearMinY",defaultNearMinY);
                    else if(s.startsWith("    nearMaxY:"))
                        s = "    nearMaxY: " + worldSection.getInt("nearMaxY",defaultNearMaxY);
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
        String[] linesArray = linesInWorlds.toArray(new String[0]);
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
                ConfigurationSection worldSection = config.getConfigurationSection(worldName);
                Objects.requireNonNull(worldSection);
                this.worldNameLookup.put(worldName,worldSection.getString("name"));
            }
        }
    }

    public Object getWorldSetting(String worldName, String name, Object def) {
        if(!config.contains(worldName)) return def;
        ConfigurationSection worldSection = config.getConfigurationSection(worldName);
        Objects.requireNonNull(worldSection);
        Object res = worldSection.get(name,def);
        //TODO: optimize search and replace
        if(res instanceof String u && u.contains("[")) {
            List<World> worlds = Bukkit.getWorlds();
            for(int i = 0; i < worlds.size(); i++) {
                u = u.replaceAll("["+ i +"]", worlds.get(i).getName());
            }
            res = u;
        }
        return res;
    }

    public Boolean checkWorldExists(String worldName) {
        if(worldName == null) return false;
        boolean bukkitWorldExists = Bukkit.getWorld(worldName)!=null;
        boolean worldKnown = this.config.contains(worldName);
        if( !bukkitWorldExists ) {
            return false;
        }
        else if( !worldKnown ) {
            SendMessage.sendMessage(Bukkit.getConsoleSender(),lang.getLog("newWorld",worldName));
            SendMessage.sendMessage(Bukkit.getConsoleSender(),lang.getLog("updatingWorlds"));
            update(); //not optimal but it works
            SendMessage.sendMessage(Bukkit.getConsoleSender(),lang.getLog("updatedWorlds"));
        }
        return true;
    }

    public Integer updateWorldSetting(World world, String setting, String value){
        String worldName = world.getName();
        ConfigurationSection worldSection = config.getConfigurationSection(worldName);
        Objects.requireNonNull(worldSection);
        if(!worldSection.contains(setting)) {
            return -1;
        }

        if(worldSection.isString(setting)) {
            worldSection.set(setting,value);
        }
        else if(worldSection.isInt(setting)) {
            int num;
            try {
                num = Integer.parseInt(value);
            }
            catch (Exception exception) {
                return -3;
            }
            worldSection.set(setting,num);
        }
        else if(worldSection.isDouble(setting)) {
            double num;
            try {
                num = Double.parseDouble(value);
            }
            catch (Exception exception) {
                return -3;
            }
            worldSection.set(setting,num);
        }
        else if(worldSection.isBoolean(setting)) {
            boolean num;
            try {
                num = Boolean.parseBoolean(value);
            }
            catch (Exception exception) {
                return -3;
            }
            worldSection.set(setting,num);
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
