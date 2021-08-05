package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Config;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;
import java.util.Set;


public class TabComplete implements TabCompleter {
    private class SubCommand {
        String perm;
        //parameter,perm
        Map<String,String> subParams = new HashMap<>();
        //command name, commands
        Map<String,SubCommand> commands = new HashMap<>();

        SubCommand(String perm) { this.perm = perm; }
    }

    private SubCommand subCommands = new SubCommand("rtp");

    private Config config;

    public TabComplete(Config config) {
        //load OnePlayerSleep.commands and permission nodes into map
        subCommands.subParams.put("world","rtp.world");
        subCommands.subParams.put("player","rtp.other");
        subCommands.commands.put("help",new SubCommand("rtp.see"));
        subCommands.commands.put("reload",new SubCommand("rtp.reload"));
        subCommands.commands.put("set",new SubCommand("rtp.set"));

        subCommands.commands.get("set").subParams.put("world","rtp.set");
        subCommands.commands.get("set").subParams.put("shape","rtp.set");
        subCommands.commands.get("set").subParams.put("radius","rtp.set");
        subCommands.commands.get("set").subParams.put("centerRadius","rtp.set");
        subCommands.commands.get("set").subParams.put("centerX","rtp.set");
        subCommands.commands.get("set").subParams.put("centerZ","rtp.set");
        subCommands.commands.get("set").subParams.put("weight","rtp.set");
        subCommands.commands.get("set").subParams.put("minY","rtp.set");
        subCommands.commands.get("set").subParams.put("maxY","rtp.set");
        subCommands.commands.get("set").subParams.put("requireSkyLight","rtp.set");
        subCommands.commands.get("set").subParams.put("requirePermission","rtp.set");
        subCommands.commands.get("set").subParams.put("worldBorderOverride","rtp.set");
        subCommands.commands.get("set").subParams.put("override","rtp.set");

        this.config = config;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if(!sender.hasPermission("rtp.see")) return null;

        List<String> res = new ArrayList<>();
        Set<String> knownParams = new HashSet<>();
        List<String> match = new ArrayList<>();
        this.getList(knownParams,res,this.subCommands,args, 0,sender);

        StringUtil.copyPartialMatches(args[args.length-1],match,res);
        return res;
    }

    public void getList(Set<String> knownParams,List<String> res, SubCommand command, String[] args, int i, CommandSender sender) {
        if(i>=args.length) return;
        int idx = args[i].indexOf(':');
        String arg = idx > 0 ? args[i].substring(0, idx) : args[i];
        if(i == args.length-1) { //if last arg
            //if semicolon, maybe suggest
            if (command.subParams.containsKey(arg) && !knownParams.contains(arg)) {
                if(!sender.hasPermission(command.subParams.get(arg))){
                    return;
                }
                switch (arg) {
                    case "world" :
                    case "override" : {
                        for (World world : Bukkit.getWorlds()) {
                            this.config.checkWorldExists(world.getName());
                            if (!this.config.getWorldPermReq(world.getName()) || sender.hasPermission("rtp.worlds." + world.getName())) {
                                res.add(arg + ":" + world.getName());
                            }
                        }
                        break;
                    }
                    case "player" : {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            res.add(arg + ":" + player.getName());
                        }
                        break;
                    }
                    case "minY":
                    case "maxY":
                    case "centerX":
                    case "centerZ": {
                        if(sender instanceof  Player) res.add(arg + ":" +"~");
                        break;
                    }
                    case "requireSkyLight":
                    case "requirePermission":
                    case "worldBorderOverride": {
                        res.add(arg+":true");
                        res.add(arg+":false");
                        break;
                    }
                    default: {
                        res.add(arg+":");
                    }
                }
            }
            else { //if no semicolon add all sub-commands or sub-parameters
                for(Map.Entry<String, String> entry : command.subParams.entrySet()) {
                    if(knownParams.contains(entry.getKey())) continue;
                    if(sender.hasPermission(entry.getValue())) {
                        res.add(entry.getKey() + ":");
                    }
                }
                if(knownParams.size() == 0) {
                    for (Map.Entry<String, SubCommand> entry : command.commands.entrySet()) {
                        if (sender.hasPermission(entry.getValue().perm)) {
                            res.add(entry.getKey());
                        }
                    }
                }
            }
        }
        else {
            //if current current argument is a parameter, add it to the list and go to next parameter
            if(command.subParams.containsKey(arg)) {
                if(sender.hasPermission(command.subParams.get(arg)))
                    knownParams.add(arg);
            }
            else if(command.commands.containsKey(args[i])) { //if argument is a command, use next layer
                SubCommand subCommand = command.commands.get(args[i]);
                if(sender.hasPermission(subCommand.perm))
                    command = command.commands.get(args[i]);
            }
            getList(knownParams,res,command,args,i+1,sender);
        }
    }
}
