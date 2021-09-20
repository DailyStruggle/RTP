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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class Fill implements CommandExecutor {
    private final RTP plugin;
    private final Configs configs;
    private final Cache cache;

    private final Set<String> fillCommands = new HashSet<>();
    private final Set<String> fillParams = new HashSet<>();
    private final Set<String> fillCancelParams = new HashSet<>();

    public Fill() {
        this.plugin = RTP.getPlugin();
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();

        fillParams.add("region");

        fillCancelParams.add("region");

        fillCommands.add("cancel");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if(!sender.hasPermission("rtp.fill")) {
            String msg = configs.lang.getLog("noPerms");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        if(args.length > 0 && fillCommands.contains(args[0])) {
            if(args[0].equals("cancel")) {
                Map<String,String> fillCancelArgs = new HashMap<>();
                for(int i = 0; i < args.length; i++) {
                    int idx = args[i].indexOf(':');
                    String arg = idx>0 ? args[i].substring(0,idx) : args[i];
                    if(this.fillCancelParams.contains(arg)) {
                        fillCancelArgs.putIfAbsent(arg,args[i].substring(idx+1)); //only use first instance
                    }
                }

                String regionName;
                World world;
                if(!fillCancelArgs.containsKey("region")) {
                    if(sender instanceof Player) {
                        world = ((Player)sender).getWorld();
                        configs.worlds.checkWorldExists(world.getName());
                        regionName = (String) configs.worlds.getWorldSetting(world.getName(),"region", "default");
                    }
                    else {
                        String msg = configs.lang.getLog("consoleCmdNotAllowed");
                        SendMessage.sendMessage(sender,msg);
                        return true;
                    }
                }
                else regionName = fillCancelArgs.get("region");

                String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
                if(worldName.equals("")) {
                    String msg = configs.lang.getLog("badArg","region:"+regionName);
                    SendMessage.sendMessage(sender,msg);
                    return true;
                }

                if(!configs.worlds.checkWorldExists(worldName)) {
                    String msg = configs.lang.getLog("invalidWorld",worldName);
                    SendMessage.sendMessage(sender,msg);
                    return true;
                }

                RandomSelectParams randomSelectParams = new RandomSelectParams(Bukkit.getWorld(worldName),fillCancelArgs,configs);
                TeleportRegion region;
                if(cache.permRegions.containsKey(randomSelectParams)) {
                    region = cache.permRegions.get(randomSelectParams);
                }
                else {
                    String msg = configs.lang.getLog("badArg","region:"+regionName);
                    SendMessage.sendMessage(sender,msg);
                    return true;
                }

                if(!region.isFilling()) {
                    String msg = configs.lang.getLog("fillNotRunning", regionName);
                    SendMessage.sendMessage(sender,msg);
                    return true;
                }

                region.stopFill();
                return true;
            }
        }

        Map<String,String> fillArgs = new HashMap<>();
        for(int i = 0; i < args.length; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(this.fillParams.contains(arg)) {
                fillArgs.putIfAbsent(arg,args[i].substring(idx+1)); //only use first instance
            }
        }

        String regionName;
        World world;
        if(!fillArgs.containsKey("region")) {
            if(sender instanceof Player) {
                world = ((Player)sender).getWorld();
                configs.worlds.checkWorldExists(world.getName());
                regionName = (String) configs.worlds.getWorldSetting(world.getName(),"region", "default");
            }
            else {
                String msg = configs.lang.getLog("consoleCmdNotAllowed");
                SendMessage.sendMessage(sender,msg);
                return true;
            }
        }
        else regionName = fillArgs.get("region");

        String worldName = (String) configs.regions.getRegionSetting(regionName,"world","");
        if(worldName.equals("")) {
            String msg = configs.lang.getLog("badArg","region:"+regionName);
            
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        if(!configs.worlds.checkWorldExists(worldName)) {
            String msg = configs.lang.getLog("invalidWorld",worldName);
            
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        RandomSelectParams randomSelectParams = new RandomSelectParams(Bukkit.getWorld(worldName),fillArgs,configs);
        TeleportRegion region;
        if(cache.permRegions.containsKey(randomSelectParams)) {
            region = cache.permRegions.get(randomSelectParams);
        }
        else {
            String msg = configs.lang.getLog("badArg","region:"+regionName);
            
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        if(region.isFilling()) {
            String msg = configs.lang.getLog("fillRunning", regionName);
            
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        region.startFill(plugin);
        return true;
    }
}
