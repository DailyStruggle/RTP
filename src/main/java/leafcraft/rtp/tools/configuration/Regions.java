package leafcraft.rtp.tools.configuration;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

public class Regions {
    private final RTP plugin;
    private FileConfiguration config;

    public Regions(RTP plugin, Lang lang) {
        this.plugin = plugin;

        File f = new File(plugin.getDataFolder(), "regions.yml");
        if(!f.exists())
        {
            plugin.saveResource("regions.yml", false);
        }
        config = YamlConfiguration.loadConfiguration(f);

        if( 	(config.getDouble("version") < 1.3) ) {
            String msg = lang.getLog("oldFile", "regions.yml");
            SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
            FileStuff.renameFiles(plugin,"regions");
            config = YamlConfiguration.loadConfiguration(f);
        }
        update();
    }

    public void setRegion(String name, RandomSelectParams params) {
        World world = Bukkit.getWorld(params.worldID);
        String worldName = Objects.requireNonNull(world).getName();

        ConfigurationSection section = (config.contains(name)) ? config.getConfigurationSection(name) : config.createSection(name);
        Objects.requireNonNull(section);

        ConfigurationSection defaultSection = config.getConfigurationSection("default");
        Objects.requireNonNull(defaultSection);

        section.set("world","\""+worldName+"\"");
        section.set("shape","\""+params.shape.toString()+"\"");
        section.set("mode",params.params.getOrDefault("mode",defaultSection.getString("mode","ACCUMULATE")));
        section.set("radius",params.r);
        section.set("centerRadius",params.cr);
        section.set("centerX",params.cx);
        section.set("centerZ",params.cz);
        section.set("weight",Double.valueOf(params.params.getOrDefault("weight",Double.toString(defaultSection.getDouble("weight",1.0)))));
        section.set("minY",params.minY);
        section.set("maxY",params.maxY);
        section.set("requireSkyLight",params.requireSkyLight);
        section.set("requirePermission",Boolean.valueOf(params.params.getOrDefault("requirePermission",Boolean.toString(defaultSection.getBoolean("requirePermission",true)))));
        section.set("worldBorderOverride",params.worldBorderOverride);
        section.set("uniquePlacements",params.uniquePlacements);
        section.set("expand",params.expand);
        section.set("queueLen",Integer.valueOf(params.params.getOrDefault("queueLen",Integer.toString(defaultSection.getInt("queueLen",0)))));
        section.set("price",Double.valueOf(params.params.getOrDefault("price",Double.toString(defaultSection.getDouble("price",0)))));
    }

    public Integer updateRegionSetting(String regionName, String setting, String value){
        ConfigurationSection regionSection = config.getConfigurationSection(regionName);
        if(regionSection == null || !regionSection.contains(setting)) {
            return -1;
        }

        if(regionSection.isString(setting)) {
            boolean goodEnum = false;
            try {
                TeleportRegion.Shapes.valueOf(value.toUpperCase(Locale.ROOT));
                goodEnum = true;
            }
            catch (IllegalArgumentException ignored) {

            }

            try {
                TeleportRegion.Modes.valueOf(value.toUpperCase(Locale.ROOT));
                goodEnum = true;
            }
            catch (IllegalArgumentException ignored) {

            }

            if(goodEnum || (Bukkit.getWorld(value) != null))
                regionSection.set(setting,value);
            else return -2;
        }
        else if(regionSection.isInt(setting)) {
            int num;
            try {
                num = Integer.parseInt(value);
            }
            catch (Exception exception) {
                return -3;
            }
            regionSection.set(setting,num);
        }
        else if(regionSection.isDouble(setting)) {
            double num;
            try {
                num = Double.parseDouble(value);
            }
            catch (Exception exception) {
                return -3;
            }
            regionSection.set(setting,num);
        }
        else if(regionSection.isBoolean(setting)) {
            boolean num;
            try {
                num = Boolean.parseBoolean(value);
            }
            catch (Exception exception) {
                return -3;
            }
            regionSection.set(setting,num);
        }
        return 0;
    }

    public void update() {
        final String quotes = "\"";

        ArrayList<String> linesInRegions = new ArrayList<>();

        ConfigurationSection defaultSection = config.getConfigurationSection("default");

        Objects.requireNonNull(defaultSection);

        String  defaultWorld = defaultSection.getString("world", "world");
        String  defaultShape = defaultSection.getString("shape", "SQUARE");
        String  defaultMode = defaultSection.getString("mode", "ACCUMULATE");
        int defaultRadius = defaultSection.getInt("radius", 4096);
        int defaultCenterRadius = defaultSection.getInt("centerRadius", 1024);
        int defaultCenterX = defaultSection.getInt("centerX", 0);
        int defaultCenterZ = defaultSection.getInt("centerZ", 0);
        double defaultWeight = defaultSection.getDouble("weight", 1.0);
        int defaultMinY = defaultSection.getInt("minY", 48);
        int defaultMaxY = defaultSection.getInt("maxY", 96);
        boolean defaultRequireSkylight = defaultSection.getBoolean("requireSkyLight", true);
        boolean defaultRequirePermission = defaultSection.getBoolean("requirePermission",true);
        boolean defaultWorldBorderOverride = defaultSection.getBoolean("worldBorderOverride",false);
        boolean defaultUniquePlacements = defaultSection.getBoolean("uniquePlacements",true);
        boolean defaultExpand = defaultSection.getBoolean("expand",false);
        int defaultQueueLen = defaultSection.getInt("queueLen", 10);
        double defaultPrice = defaultSection.getDouble("price", 50.0);

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

                        ConfigurationSection regionSection = config.getConfigurationSection(regionName);
                        Objects.requireNonNull(regionSection);

                        linesInRegions.add(regionName + ":");
                        linesInRegions.add("    world: \"" + regionSection.getString("world",defaultWorld) + "\"");
                        linesInRegions.add("    shape: \"" + regionSection.getString("shape",defaultShape) + "\"");
                        linesInRegions.add("    mode: \"" + regionSection.getString("mode",defaultMode) + "\"");
                        linesInRegions.add("    radius: " + regionSection.getInt("radius",defaultRadius));
                        linesInRegions.add("    centerRadius: " + regionSection.getInt("centerRadius",defaultCenterRadius));
                        linesInRegions.add("    centerX: " + regionSection.getInt("centerX",defaultCenterX));
                        linesInRegions.add("    centerZ: " + regionSection.getInt("centerZ",defaultCenterZ));
                        linesInRegions.add("    minY: " + regionSection.getInt("minY",defaultMinY));
                        linesInRegions.add("    maxY: " + regionSection.getInt("maxY", defaultMaxY));
                        linesInRegions.add("    weight: " + regionSection.getDouble("weight",defaultWeight));
                        linesInRegions.add("    requireSkyLight: " + regionSection.getBoolean("requireSkyLight", defaultRequireSkylight));
                        linesInRegions.add("    requirePermission: " + regionSection.getBoolean("requirePermission",defaultRequirePermission));
                        linesInRegions.add("    worldBorderOverride: " + regionSection.getBoolean("worldBorderOverride",defaultWorldBorderOverride));
                        linesInRegions.add("    uniquePlacements: " + regionSection.getBoolean("uniquePlacements",defaultUniquePlacements));
                        linesInRegions.add("    expand: " + regionSection.getBoolean("expand",defaultExpand));
                        linesInRegions.add("    queueLen: " + regionSection.getInt("queueLen",defaultQueueLen));
                        linesInRegions.add("    price: " + regionSection.getDouble("price",defaultPrice));
                    }
                }
                else { //if not a blank line
                    ConfigurationSection regionSection = config.getConfigurationSection(region);
                    Objects.requireNonNull(regionSection);
                    if(s.startsWith("    world:"))
                        s = "    world: " + quotes + regionSection.getString("world",defaultWorld) + quotes;
                    else if(s.startsWith("    shape:"))
                        s = "    shape: " + quotes + regionSection.getString("shape",defaultShape) + quotes;
                    else if(s.startsWith("    mode:"))
                        s = "    mode: " + quotes + regionSection.getString("mode",defaultMode) + quotes;
                    else if(s.startsWith("    radius:"))
                        s = "    radius: " + regionSection.getInt("radius",defaultRadius);
                    else if(s.startsWith("    centerRadius:"))
                        s = "    centerRadius: " + regionSection.getInt("centerRadius",defaultCenterRadius);
                    else if(s.startsWith("    centerX:"))
                        s = "    centerX: " + regionSection.getInt("centerX",defaultCenterX);
                    else if(s.startsWith("    centerZ:"))
                        s = "    centerZ: " + regionSection.getInt("centerZ",defaultCenterZ);
                    else if(s.startsWith("    weight:"))
                        s = "    weight: " + regionSection.getDouble("weight",defaultWeight);
                    else if(s.startsWith("    minY:"))
                        s = "    minY: " + regionSection.getInt("minY",defaultMinY);
                    else if(s.startsWith("    maxY:"))
                        s = "    maxY: " + regionSection.getInt("maxY", defaultMaxY);
                    else if(s.startsWith("    requireSkyLight:"))
                        s = "    requireSkyLight: " + regionSection.getBoolean("requireSkyLight", defaultRequireSkylight);
                    else if(s.startsWith("    requirePermission:"))
                        s = "    requirePermission: " + regionSection.getBoolean("requirePermission",defaultRequirePermission);
                    else if(s.startsWith("    worldBorderOverride:"))
                        s = "    worldBorderOverride: " + regionSection.getBoolean("worldBorderOverride",defaultWorldBorderOverride);
                    else if(s.startsWith("    uniquePlacements:"))
                        s = "    uniquePlacements: " + regionSection.getBoolean("uniquePlacements",defaultRequirePermission);
                    else if(s.startsWith("    expand:"))
                        s = "    expand: " + regionSection.getBoolean("expand",defaultWorldBorderOverride);
                    else if(s.startsWith("    queueLen:"))
                        s = "    queueLen: " + regionSection.getInt("queueLen",defaultQueueLen);
                    else if(s.startsWith("    price:"))
                        s = "    price: " + regionSection.getDouble("price",defaultPrice);
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
        String[] linesArray = linesInRegions.toArray(new String[0]);
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
        ConfigurationSection regionSection = config.getConfigurationSection(region);
        Objects.requireNonNull(regionSection);
        Object res = regionSection.get(name,def);
        //TODO: optimize search and replace
        if(res instanceof String u && u.contains("[")) {
            List<World> worlds = Bukkit.getWorlds();
            for(int i = 0; i < worlds.size(); i++) {
                u = u.replaceAll("\\["+ i +"]", worlds.get(i).getName());
            }
            res = u;
        }
        return res;
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
