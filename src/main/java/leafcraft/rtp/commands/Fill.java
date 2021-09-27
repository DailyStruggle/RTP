package leafcraft.rtp.commands;

import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.SendMessage;
import leafcraft.rtp.tools.configuration.Configs;
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

public class Fill implements CommandExecutor {
    private final Configs configs;
    private final Cache cache;

    private final Set<String> fillCommands = new HashSet<>();
    private final Set<String> fillParams = new HashSet<>();

    public Fill() {
        this.configs = RTP.getConfigs();
        this.cache = RTP.getCache();

        fillParams.add("region");

        fillCommands.add("start");
        fillCommands.add("cancel");
        fillCommands.add("pause");
        fillCommands.add("resume");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if(!sender.hasPermission("rtp.fill")) {
            String msg = configs.lang.getLog("noPerms");
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        String regionName = null;
        World world;
        if(args.length > 0 && fillCommands.contains(args[0])) {
            Map<String,String> fillCommandArgs = new HashMap<>();
            for (String s : args) {
                int idx = s.indexOf(':');
                String arg = idx > 0 ? s.substring(0, idx) : s;
                if (this.fillParams.contains(arg)) {
                    fillCommandArgs.putIfAbsent(arg, s.substring(idx + 1)); //only use first instance
                }
            }

            if(!fillCommandArgs.containsKey("region")) {
                if(sender instanceof Player) {
                    world = ((Player)sender).getWorld();
                    configs.worlds.checkWorldExists(world.getName());
                    regionName = (String) configs.worlds.getWorldSetting(world.getName(),"region", "default");
                }
                else {
                    regionName = "default";
                }
            }
            else regionName = fillCommandArgs.get("region");

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

            RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)), fillCommandArgs);
            TeleportRegion region;
            if(cache.permRegions.containsKey(randomSelectParams)) {
                region = cache.permRegions.get(randomSelectParams);
            }
            else {
                String msg = configs.lang.getLog("badArg","region:"+regionName);
                SendMessage.sendMessage(sender,msg);
                return true;
            }

            switch (args[0]) {
                case "start" : {
                    if(region.isFilling()) {
                        String msg = configs.lang.getLog("fillRunning", regionName);
                        SendMessage.sendMessage(sender,msg);
                        return true;
                    }

                    region.startFill();
                    return true;
                }
                case "cancel" : {
                    if(!region.isFilling()) {
                        String msg = configs.lang.getLog("fillNotRunning", regionName);
                        SendMessage.sendMessage(sender,msg);
                        return true;
                    }

                    region.stopFill();
                    return true;
                }
                case "pause" : {
                    if(!region.isFilling()) {
                        String msg = configs.lang.getLog("fillNotRunning", regionName);
                        SendMessage.sendMessage(sender,msg);
                        return true;
                    }

                    region.pauseFill();
                    return true;
                }
                case "resume" : {
                    if(region.isFilling()) {
                        String msg = configs.lang.getLog("fillRunning", regionName);
                        SendMessage.sendMessage(sender,msg);
                        return true;
                    }

                    region.resumeFill();
                    return true;
                }
            }
        }

        Map<String,String> fillArgs = new HashMap<>();
        for (String s : args) {
            int idx = s.indexOf(':');
            String arg = idx > 0 ? s.substring(0, idx) : s;
            if (this.fillParams.contains(arg)) {
                fillArgs.putIfAbsent(arg, s.substring(idx + 1)); //only use first instance
            }
        }

        if(!fillArgs.containsKey("region") && regionName==null) {
            if(sender instanceof Player) {
                world = ((Player)sender).getWorld();
                configs.worlds.checkWorldExists(world.getName());
                regionName = (String) configs.worlds.getWorldSetting(world.getName(),"region", "default");
            }
            else {
                regionName = "default";
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

        RandomSelectParams randomSelectParams = new RandomSelectParams(Objects.requireNonNull(Bukkit.getWorld(worldName)),fillArgs);
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

        region.resumeFill();
        return true;
    }
}
