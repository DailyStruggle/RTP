package leafcraft.rtp.tools.Configuration;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class Regions {
    private RTP plugin;
    private Lang lang;
    private FileConfiguration config;

    public Regions(RTP plugin, Lang lang) {
        this.plugin = plugin;
        this.lang = lang;

        File f = new File(plugin.getDataFolder(), "regions.yml");
        if(!f.exists())
        {
            plugin.saveResource("regions.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(f);

        if( 	(config.getDouble("version") < 1.0) ) {
            Bukkit.getLogger().log(Level.WARNING, lang.getLog("oldFile", "regions.yml"));
            FileStuff.renameFiles(plugin,"regions");
            config = YamlConfiguration.loadConfiguration(f);
        }
        update();
    }

    public void addRegion(String name, RandomSelectParams params) {
        String worldName = Bukkit.getWorld(params.worldID).getName();

        ConfigurationSection section = config.createSection(name);
        section.set("world","\""+worldName+"\"");
        section.set("shape","\""+params.shape.toString()+"\"");
        section.set("radius",params.r);
        section.set("centerRadius",params.cr);
        section.set("centerX",params.cx);
        section.set("centerZ",params.cz);
        section.set("weight",Double.valueOf(params.params.getOrDefault("weight",Double.toString(config.getConfigurationSection("default").getDouble("weight",1.0)))));
        section.set("minY",params.minY);
        section.set("maxY",params.maxY);
        section.set("requireSkyLight",params.requireSkyLight);
        section.set("requirePermission",Boolean.valueOf(params.params.getOrDefault("requirePermission",Boolean.toString(config.getConfigurationSection("default").getBoolean("requirePermission",true)))));
        section.set("worldBorderOverride",params.worldBorderOverride);
        section.set("queueLen",Integer.valueOf(params.params.getOrDefault("queueLen",Integer.toString(config.getConfigurationSection("default").getInt("queueLen",0)))));

    }

    public Integer updateRegionSetting(String regionName, String setting, String value){
        if(!config.getConfigurationSection(regionName).contains(setting)) {
            return -1;
        }

        if(config.getConfigurationSection(regionName).isString(setting)) {
            Boolean goodEnum = true;
            try {
                TeleportRegion.Shapes.valueOf(value.toUpperCase(Locale.ROOT));
            }
            catch (IllegalArgumentException ex) {
                goodEnum = false;
            }

            if(!goodEnum && Bukkit.getWorld(value) == null)
                return -2;
            config.getConfigurationSection(regionName).set(setting,value);
        }
        else if(config.getConfigurationSection(regionName).isInt(setting)) {
            Integer num;
            try {
                num = Integer.valueOf(value);
            }
            catch (Exception exception) {
                return -3;
            }
            config.getConfigurationSection(regionName).set(setting,num);
        }
        else if(config.getConfigurationSection(regionName).isDouble(setting)) {
            Double num;
            try {
                num = Double.valueOf(value);
            }
            catch (Exception exception) {
                return -3;
            }
            config.getConfigurationSection(regionName).set(setting,num);
        }
        else if(config.getConfigurationSection(regionName).isBoolean(setting)) {
            Boolean num;
            try {
                num = Boolean.valueOf(value);
            }
            catch (Exception exception) {
                return -3;
            }
            config.getConfigurationSection(regionName).set(setting,num);
        }
        return 0;
    }

    public void update() {
        final String quotes = "\"";

        ArrayList<String> linesInRegions = new ArrayList<>();

        String  defaultWorld = config.getConfigurationSection("default").getString("world", "world");
        String  defaultShape = config.getConfigurationSection("default").getString("shape", "SQUARE");
        Integer defaultRadius = config.getConfigurationSection("default").getInt("radius", 4096);
        Integer defaultCenterRadius = config.getConfigurationSection("default").getInt("centerRadius", 1024);
        Integer defaultCenterX = config.getConfigurationSection("default").getInt("centerX", 0);
        Integer defaultCenterZ = config.getConfigurationSection("default").getInt("centerZ", 0);
        Double  defaultWeight = config.getConfigurationSection("default").getDouble("weight", 1.0);
        Integer defaultMinY = config.getConfigurationSection("default").getInt("minY", 48);
        Integer defaultMaxY = config.getConfigurationSection("default").getInt("maxY", 96);
        Boolean defaultRequireSkylight = config.getConfigurationSection("default").getBoolean("requireSkyLight", true);
        Boolean defaultRequirePermission = config.getConfigurationSection("default").getBoolean("requirePermission",true);
        Boolean defaultWorldBorderOverride = config.getConfigurationSection("default").getBoolean("worldBorderOverride",false);
        Integer defaultQueueLen = config.getConfigurationSection("default").getInt("queueLen", 10);

        try {
            Scanner scanner = new Scanner(
                    new File(plugin.getDataFolder().getAbsolutePath() + File.separator + "regions.yml"));
            //for each line in original messages file
            Set<String> regions = new HashSet<>();
            String region = "default";
            while (scanner.hasNextLine()) {
                String s = scanner.nextLine();

                //append at first blank line
                if(!(s.matches(".*[a-z].*")||s.matches(".*[A-Z].*"))) {
                    //for each missing region, add its data
                    for(String regionName : config.getKeys(false)) {
                        if(regionName.equals("version")) continue;
                        if(regions.contains(regionName)) continue;

                        linesInRegions.add(regionName + ":");
                        linesInRegions.add("    world: \"" + defaultWorld + "\"");
                        linesInRegions.add("    shape: \"" + defaultShape + "\"");
                        linesInRegions.add("    radius: " + defaultRadius);
                        linesInRegions.add("    centerRadius: " + defaultCenterRadius);
                        linesInRegions.add("    centerX: " + defaultCenterX);
                        linesInRegions.add("    centerZ: " + defaultCenterZ);
                        linesInRegions.add("    minY: " + defaultMinY);
                        linesInRegions.add("    maxY: " + defaultMaxY);
                        linesInRegions.add("    weight: " + defaultWeight);
                        linesInRegions.add("    requireSkyLight: " + defaultRequireSkylight);
                        linesInRegions.add("    requirePermission: " + defaultRequirePermission);
                        linesInRegions.add("    worldBorderOverride: " + defaultWorldBorderOverride);
                        linesInRegions.add("    queueLen: " + defaultQueueLen);
                    }
                }
                else { //if not a blank line
                    if(s.startsWith("    world:"))
                        s = "    world: " + quotes + config.getConfigurationSection(region).getString("world",defaultWorld) + quotes;
                    else if(s.startsWith("    shape:"))
                        s = "    shape: " + quotes + config.getConfigurationSection(region).getString("shape",defaultShape) + quotes;
                    else if(s.startsWith("    radius:"))
                        s = "    radius: " + config.getConfigurationSection(region).getInt("radius",defaultRadius);
                    else if(s.startsWith("    centerRadius:"))
                        s = "    centerRadius: " + config.getConfigurationSection(region).getInt("centerRadius",defaultCenterRadius);
                    else if(s.startsWith("    centerX:"))
                        s = "    centerX: " + config.getConfigurationSection(region).getInt("centerX",defaultCenterX);
                    else if(s.startsWith("    centerZ:"))
                        s = "    centerZ: " + config.getConfigurationSection(region).getInt("centerZ",defaultCenterZ);
                    else if(s.startsWith("    weight:"))
                        s = "    weight: " + config.getConfigurationSection(region).getDouble("weight",defaultWeight);
                    else if(s.startsWith("    minY:"))
                        s = "    minY: " + config.getConfigurationSection(region).getInt("minY",defaultMinY);
                    else if(s.startsWith("    maxY:"))
                        s = "    maxY: " + config.getConfigurationSection(region).getInt("maxY", defaultMaxY);
                    else if(s.startsWith("    requireSkyLight:"))
                        s = "    requireSkyLight: " + config.getConfigurationSection(region).getBoolean("requireSkyLight", defaultRequireSkylight);
                    else if(s.startsWith("    requirePermission:"))
                        s = "    requirePermission: " + config.getConfigurationSection(region).getBoolean("requirePermission",defaultRequirePermission);
                    else if(s.startsWith("    worldBorderOverride:"))
                        s = "    worldBorderOverride: " + config.getConfigurationSection(region).getBoolean("worldBorderOverride",defaultWorldBorderOverride);
                    else if(s.startsWith("    queueLen:"))
                        s = "    queueLen: " + config.getConfigurationSection(region).getInt("queueLen",defaultQueueLen);
                    else if(!s.startsWith("#") && !s.startsWith("  ") && !s.startsWith("version") && (s.matches(".*[a-z].*") || s.matches(".*[A-Z].*")))
                    {
                        region = s.replace(":","");
                        regions.add(region);
                    }
                }

                //add line
                linesInRegions.add(s);
            }
            scanner.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        FileWriter fw;
        String[] linesArray = linesInRegions.toArray(new String[linesInRegions.size()]);
        try {
            fw = new FileWriter(plugin.getDataFolder().getAbsolutePath() + File.separator + "regions.yml");
            for (String s : linesArray) {
                fw.write(s + "\n");
            }
            fw.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        //-------------UPDATE INTERNAL VERSION ACCORDINGLY-------------
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "regions.yml"));

    }

    public Object getRegionSetting(String region, String name, Object def) {
        if(!config.contains(region)) return def;
        return this.config.getConfigurationSection(region).get(name,def);
    }

    public List<String> getRegionNames() {
        List<String> res = new ArrayList<>();
        for(String key : config.getKeys(false)) {
            if(key.equals("version")) continue;
            res.add(key);
        }
        return res;
    }
}
