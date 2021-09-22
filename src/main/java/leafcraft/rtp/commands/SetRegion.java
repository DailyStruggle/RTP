package leafcraft.rtp.commands;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.selection.RandomSelectParams;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Level;

public class SetRegion implements CommandExecutor {
    private static Configs configs = null;
    private static Cache cache = null;
    private static final Set<String> regionParams = new HashSet<>();
    static {
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
        regionParams.add("price");
        regionParams.add("mode");
    }

    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if(!sender.hasPermission("rtp.setRegion")) {
            String msg = configs.lang.getLog("noPerms");

            SendMessage.sendMessage(sender,msg);
            return true;
        }

        if(configs == null) configs = RTP.getConfigs();
        if(cache == null) cache = RTP.getCache();

        Map<String,String> regionArgs = new HashMap<>();
        for (String s : args) {
            int idx = s.indexOf(':');
            String arg = idx > 0 ? s.substring(0, idx) : s;
            if (regionParams.contains(arg)) {
                regionArgs.putIfAbsent(arg, s.substring(idx + 1)); //only use first instance
            }
        }

        World world;
        if(regionArgs.containsKey("world")) {
            String worldName = regionArgs.get("world");
            worldName = configs.worlds.worldPlaceholder2Name(worldName);
            if(!configs.worlds.checkWorldExists(worldName)) {
                String msg = configs.lang.getLog("invalidWorld",worldName);

                SendMessage.sendMessage(sender,msg);
                return true;
            }
            world = Objects.requireNonNull(Bukkit.getWorld(worldName));
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
                if(!configs.worlds.checkWorldExists(Objects.requireNonNull(world).getName())) world = Bukkit.getWorlds().get(0);
            }
            else if(sender instanceof Player) {
                world = ((Player) sender).getWorld();
                regionArgs.put("world",world.getName());
            }
            else {
                String msg = configs.lang.getLog("consoleCmdNotAllowed");
                SendMessage.sendMessage(sender,msg);
                return true;
            }
        }

        for(Map.Entry<String,String> entry : regionArgs.entrySet()) {
            if(entry.getKey().equals("region")) continue;
            int isCoord = 0;
            if(entry.getKey().endsWith("X")) isCoord = 1;
            else if(entry.getKey().endsWith("Y")) isCoord = 2;
            else if(entry.getKey().endsWith("Z")) isCoord = 3;

            if( isCoord>0 ) {
                int res = 0;
                if(entry.getValue().startsWith("~")) {
                    if(!(sender instanceof Player)) {
                        String msg = configs.lang.getLog("consoleCmdNotAllowed");
                        SendMessage.sendMessage(sender,msg);
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
                            if(numStr.length()>0) res -= Integer.parseInt(numStr);
                        }
                        catch (NumberFormatException exception) {
                            String msg = configs.lang.getLog("badArg",entry.getKey()+":"+entry.getValue());

                            SendMessage.sendMessage(sender,msg);
                            continue;
                        }
                    }
                    else {
                        numStr = entry.getValue().substring(1);
                        try{
                            if(numStr.length()>0) res += Integer.parseInt(numStr);
                        }
                        catch (NumberFormatException exception) {
                            String msg = configs.lang.getLog("badArg",entry.getKey()+":"+entry.getValue());

                            SendMessage.sendMessage(sender,msg);
                            continue;
                        }
                    }
                    entry.setValue(Integer.toString(res));
                }
            }
        }

        if(regionArgs.containsKey("region")) {
            String region = regionArgs.get("region");
            //check region exists
            String probe = (String) configs.regions.getRegionSetting(region,"world","");
            RandomSelectParams params = new RandomSelectParams(world,regionArgs);
            if(probe.equals("")) {
                configs.regions.setRegion(region,params);
            }

            if(cache.permRegions.containsKey(params)){
                cache.permRegions.get(params).shutdown();
            }

            TeleportRegion teleportRegion = new TeleportRegion(region,params.params);
            cache.permRegions.put(params, teleportRegion);
            teleportRegion.loadFile();
        }
        else {
            String msg = configs.lang.getLog("missingRegionParam");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        for(Map.Entry<String,String> entry : regionArgs.entrySet()) {
            if (entry.getKey().equals("region")) continue;
            Integer result = configs.regions.updateRegionSetting(regionArgs.get("region"), entry.getKey(), entry.getValue());
            if (result < 0) {
                String msg = configs.lang.getLog("badArg", entry.getValue());
                SendMessage.sendMessage(sender,msg);
            }
        }

        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatingRegions"));
        if(sender instanceof Player){
            String msg = configs.lang.getLog("updatingRegions");
            SendMessage.sendMessage(sender,msg);
        }
        configs.regions.update();
        Bukkit.getLogger().log(Level.INFO,configs.lang.getLog("updatedRegions"));
        if(sender instanceof Player){
            String msg = configs.lang.getLog("updatedRegions");
            SendMessage.sendMessage(sender,msg);
        }
        return true;
    }
}