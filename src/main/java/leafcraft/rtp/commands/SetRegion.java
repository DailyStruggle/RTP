package leafcraft.rtp.commands;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

public class SetRegion implements CommandExecutor {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    private final Set<String> regionParams = new HashSet<>();

    public SetRegion(leafcraft.rtp.RTP plugin, Configs configs, Cache cache) {
        this.plugin = plugin;
        this.configs = configs;
        this.cache = cache;

        regionParams.add("region");
        regionParams.add("world");
        regionParams.add("shape");
        regionParams.add("radius");
        regionParams.add("centerRadius");
        regionParams.add("centerX");
        regionParams.add("centerZ");
        regionParams.add("weight");
        regionParams.add("minY");
        regionParams.add("maxY");
        regionParams.add("requireSkyLight");
        regionParams.add("requirePermission");
        regionParams.add("worldBorderOverride");
        regionParams.add("uniquePlacements");
        regionParams.add("expand");
        regionParams.add("queueLen");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("rtp.setRegion")) {
            sender.sendMessage(configs.lang.getLog("noPerms"));
            return true;
        }

        Map<String,String> regionArgs = new HashMap<>();
        for(int i = 0; i < args.length; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(this.regionParams.contains(arg)) {
                regionArgs.putIfAbsent(arg,args[i].substring(idx+1)); //only use first instance
            }
        }

        World world;
        if(regionArgs.containsKey("world")) {
            String worldName = regionArgs.get("world");
            worldName = configs.worlds.worldPlaceholder2Name(worldName);
            if(!configs.worlds.checkWorldExists(worldName)) {
                sender.sendMessage(configs.lang.getLog("invalidWorld",worldName));
                return true;
            }
            world = Bukkit.getWorld(worldName);
            switch(world.getEnvironment()) {
                case NETHER: {
                    regionArgs.putIfAbsent("requireSkyLight", "false");
                    regionArgs.putIfAbsent("maxY", "127");
                    regionArgs.putIfAbsent("minY", "32");
                    break;
                }
                case THE_END: {
                    regionArgs.putIfAbsent("requireSkyLight", "false");
                    regionArgs.putIfAbsent("maxY", "96");
                    regionArgs.putIfAbsent("minY", "48");
                    break;
                }
            }
        }
        else {
            if(regionArgs.containsKey("region")) {
                String probe = (String) configs.regions.getRegionSetting(regionArgs.get("region"),"world","");
                String worldName;
                if(probe.equals("")) {
                    worldName = (String) configs.regions.getRegionSetting("default", "world", Bukkit.getWorlds().get(0).getName());
                }
                else {
                    worldName = (String) configs.regions.getRegionSetting(regionArgs.get("region"), "world", Bukkit.getWorlds().get(0).getName());
                }
                regionArgs.putIfAbsent("world",worldName);
                world = Bukkit.getWorld(regionArgs.get("world"));
                if(!configs.worlds.checkWorldExists(world.getName())) world = Bukkit.getWorlds().get(0);
            }
            else if(sender instanceof Player) {
                world = ((Player) sender).getWorld();
                regionArgs.put("world",world.getName());
            }
            else {
                sender.sendMessage(configs.lang.getLog("consoleCmdNotAllowed"));
                return true;
            }
        }

        for(Map.Entry<String,String> entry : regionArgs.entrySet()) {
            if(entry.getKey().equals("region")) continue;
            Integer isCoord = 0;
            if(entry.getKey().endsWith("X")) isCoord = 1;
            else if(entry.getKey().endsWith("Y")) isCoord = 2;
            else if(entry.getKey().endsWith("Z")) isCoord = 3;

            if( isCoord>0 ) {
                Integer res = 0;
                if(entry.getValue().startsWith("~")) {
                    if(!(sender instanceof Player)) {
                        sender.sendMessage(configs.lang.getLog("consoleCmdNotAllowed"));
                        continue;
                    }
                    switch(isCoord) {
                        case 1: res = ((Player)sender).getLocation().getBlockX(); break;
                        case 2: res = ((Player)sender).getLocation().getBlockY(); break;
                        case 3: res = ((Player)sender).getLocation().getBlockZ(); break;
                    }
                    String numStr;
                    if(entry.getValue().startsWith("~-")) {
                        numStr = entry.getValue().substring(2);
                        try{
                            if(numStr.length()>0) res -= Integer.valueOf(numStr);
                        }
                        catch (NumberFormatException exception) {
                            sender.sendMessage(configs.lang.getLog("badArg",entry.getKey()+":"+entry.getValue()));
                            continue;
                        }
                    }
                    else {
                        numStr = entry.getValue().substring(1);
                        try{
                            if(numStr.length()>0) res += Integer.valueOf(numStr);
                        }
                        catch (NumberFormatException exception) {
                            sender.sendMessage(configs.lang.getLog("badArg",entry.getKey()+":"+entry.getValue()));
                            continue;
                        }
                    }
                    entry.setValue(res.toString());
                }
            }
        }

        if(regionArgs.containsKey("region")) {
            String region = regionArgs.get("region");
            //check region exists
            String probe = (String) configs.regions.getRegionSetting(region,"world","");
            RandomSelectParams params = new RandomSelectParams(world,regionArgs,configs);
            if(probe.equals("")) {
                configs.regions.addRegion(region,params);
            }
            cache.permRegions.remove(params);
            cache.permRegions.put(params, new TeleportRegion(params.params,configs,cache));
        }
        else {
            sender.sendMessage(configs.lang.getLog("missingRegionParam"));
            return true;
        }

        for(Map.Entry<String,String> entry : regionArgs.entrySet()) {
            if (entry.getKey().equals("region")) continue;
            Integer result = configs.regions.updateRegionSetting(regionArgs.get("region"), entry.getKey(), entry.getValue());
            if (result < 0) sender.sendMessage(configs.lang.getLog("badArg", entry.getValue()));
        }

        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatingRegions"));
        if(sender instanceof Player)sender.sendMessage(configs.lang.getLog("updatingRegions"));
        configs.regions.update();
        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatedRegions"));
        if(sender instanceof Player)sender.sendMessage(configs.lang.getLog("updatedRegions"));

        return true;
    }
}