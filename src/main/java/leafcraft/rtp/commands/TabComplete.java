package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Config;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.util.StringUtil;

import java.util.*;
import java.util.function.Predicate;

public class TabComplete implements TabCompleter {
    private Map<String,String> subCommands = new HashMap<String,String>();
    private Map<String,String> rtpParams = new HashMap<String,String>();

    private Config config;

    public TabComplete(Config config) {
        //load OnePlayerSleep.commands and permission nodes into map
        subCommands.put("reload","rtp.reload");
        subCommands.put("help","rtp.see");

        //alternate parameters
        rtpParams.put("player", "rtp.other");
        rtpParams.put("world", "rtp.world");
        this.config = config;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if(!sender.hasPermission("rtp.see")) return null;
        if(args.length > 5) return null;

        //don't suggest redundant args
        Set<String> hasRtpArg = new HashSet<>();
        for(int i = 0; i < args.length-1; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(rtpParams.keySet().contains(arg))
                hasRtpArg.add(arg);
        }
        int idx = args[args.length-1].indexOf(':');
        String subArg = idx>0 ? args[args.length-1].substring(0,idx) : "";
        if(hasRtpArg.contains(subArg)) return null; //stop suggesting if redundant

        List<String> res = new ArrayList<>();
        List<String> subCom = new ArrayList<>();
        if(rtpParams.containsKey(subArg) && sender.hasPermission(rtpParams.get(subArg))){
            switch (subArg) {
                case "player" : {
                    for(Player player : Bukkit.getOnlinePlayers())
                        if(!player.hasPermission("rtp.notme"))
                            subCom.add("player:"+player.getName());
                        break;
                }
                case "world" : {
                    for(World world : Bukkit.getWorlds()) {
                        String worldName  = world.getName();
                        this.config.checkWorldExists(worldName);
                        if(     !this.config.getWorldPermReq(worldName)
                                || sender.hasPermission("rtp.worlds."+worldName)
                                || sender.hasPermission("rtp.worlds.*"))
                            subCom.add("world:"+worldName);
                    }
                }
            }
        }
        else {
            switch (args.length) {
                case 1: { //help, reload, set or rtp parameters
                    // TODO: rtp set
                    for(Map.Entry<String,String> entry : subCommands.entrySet()) {
                        if(sender.hasPermission(entry.getValue()))
                            subCom.add(entry.getKey());
                    }
                    for(Map.Entry<String,String> entry : rtpParams.entrySet()) {
                        if(sender.hasPermission(entry.getValue()) && !hasRtpArg.contains(entry.getKey()))
                            subCom.add(entry.getKey()+":");
                    }
                    break;
                }
                default: {
                    if(subCommands.keySet().contains(args[0])) break; //skip rtp params if help or reload
                    for(Map.Entry<String,String> entry : rtpParams.entrySet()) {
                        if(hasRtpArg.contains(entry.getKey())) continue; //skip if redundant
                        if(!sender.hasPermission(entry.getValue())) continue; //skip if no perm
                        subCom.add(entry.getKey()+":");
                    }
                }
            }
        }

        StringUtil.copyPartialMatches(args[args.length-1],subCom,res);
        return res;
    }
}
