package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Configuration.Configs;
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

        //parameter name,perm, for
        Map<String,String> subParams = new HashMap<>();

        //perm, list of params
        Map<String,ArrayList<String>> subParamsPermList = new HashMap<>();

        //command name, commands
        Map<String,SubCommand> commands = new HashMap<>();

        SubCommand(String perm) { this.perm = perm; }

        void addSubParam(String name, String perm) {
            subParams.put(name,perm);
            subParamsPermList.putIfAbsent(perm, new ArrayList<>());
            subParamsPermList.get(perm).add(name+":");
        }
    }

    private final SubCommand subCommands = new SubCommand("rtp");

    private final Configs configs;

    public TabComplete(Configs configs) {
        //load OnePlayerSleep.commands and permission nodes into map
        subCommands.addSubParam("world","rtp.world");
        subCommands.addSubParam("region","rtp.region");
        subCommands.addSubParam("player","rtp.other");
        subCommands.addSubParam("shape","rtp.params");
        subCommands.addSubParam("radius","rtp.params");
        subCommands.addSubParam("centerRadius","rtp.params");
        subCommands.addSubParam("centerX","rtp.params");
        subCommands.addSubParam("centerZ","rtp.params");
        subCommands.addSubParam("weight","rtp.params");
        subCommands.addSubParam("minY","rtp.params");
        subCommands.addSubParam("maxY","rtp.params");
        subCommands.addSubParam("requireSkyLight","rtp.params");
        subCommands.addSubParam("worldBorderOverride","rtp.params");
        
        subCommands.commands.put("help",new SubCommand("rtp.see"));
        subCommands.commands.put("reload",new SubCommand("rtp.reload"));
        subCommands.commands.put("setRegion",new SubCommand("rtp.setRegion"));
        subCommands.commands.put("setWorld",new SubCommand("rtp.setWorld"));

        subCommands.commands.get("setRegion").addSubParam("region","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("world","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("shape","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("radius","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("centerRadius","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("centerX","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("centerZ","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("weight","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("minY","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("maxY","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("requireSkyLight","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("requirePermission","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("worldBorderOverride","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("override","rtp.setRegion");
        subCommands.commands.get("setRegion").addSubParam("queueLen","rtp.setRegion");

        subCommands.commands.get("setWorld").addSubParam("world","rtp.setWorld");
        subCommands.commands.get("setWorld").addSubParam("name","rtp.setWorld");
        subCommands.commands.get("setWorld").addSubParam("region","rtp.setWorld");
        subCommands.commands.get("setWorld").addSubParam("override","rtp.setWorld");


//        subCommands.commands.put("fill",new SubCommand("rtp.fill"));
//        subCommands.commands.get("fill").addSubParam("world","rtp.fill");
//        subCommands.commands.get("fill").commands.put("cancel",new SubCommand("rtp.fill"));
//        subCommands.commands.get("fill").commands.get("cancel").addSubParam("world","rtp.fill");

        this.configs = configs;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if(!sender.hasPermission("rtp.see")) return null;

        List<String> match = new ArrayList<>();
        Set<String> knownParams = new HashSet<>();
        this.getList(knownParams,match,this.subCommands,args, 0,sender);

        List<String> res = new ArrayList<>();
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
                    case "shape": {
                        res.add(arg+":CIRCLE");
                        res.add(arg+":SQUARE");
                        break;
                    }
                    case "region" : {
                        List<String> regions = configs.regions.getRegionNames();
                        for(String region : regions) {
                            if (!((Boolean)configs.regions.getRegionSetting(region,"requirePermission",true))
                                    || sender.hasPermission("rtp.regions." + region)) {
                                res.add(arg + ":" + region);
                            }
                        }
                        break;
                    }
                    case "world" :
                    case "override" : {
                        for (World world : Bukkit.getWorlds()) {
                            configs.worlds.checkWorldExists(world.getName());
                            if (!((Boolean)configs.worlds.getWorldSetting(world.getName(),"requirePermission",true))
                                    || sender.hasPermission("rtp.worlds." + world.getName())) {
                                res.add(arg + ":" + configs.worlds.worldName2Placeholder(world.getName()));
                            }
                        }
                        break;
                    }
                    case "player" : {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            res.add(arg + ":" + player.getUniqueId());
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
                for(Map.Entry<String, ArrayList<String>> entry : command.subParamsPermList.entrySet()) {
                    if(knownParams.contains(entry.getKey())) continue;
                    if(sender.hasPermission(entry.getKey())) {
                        res.addAll(entry.getValue());
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
