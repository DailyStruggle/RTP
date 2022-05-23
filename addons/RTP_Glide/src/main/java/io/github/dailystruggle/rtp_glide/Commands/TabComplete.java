package io.github.dailystruggle.rtp_glide.Commands;

import io.github.dailystruggle.rtp_glide.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.*;


public class TabComplete implements TabCompleter {
    private class SubCommand {
        String perm;

        //parameter name,perm, for
        Map<String,String> subParams = new ConcurrentHashMap<>();

        //perm, list of params
        Map<String,ArrayList<String>> subParamsPermList = new ConcurrentHashMap<>();

        //command name, commands
        Map<String,SubCommand> commands = new ConcurrentHashMap<>();

        SubCommand(String perm) { this.perm = perm; }

        void addSubParam(String name, String perm) {
            subParams.put(name,perm);
            subParamsPermList.putIfAbsent(perm, new ArrayList<>());
            if(name.contains(":")) subParamsPermList.get(perm).add(name);
            else subParamsPermList.get(perm).add(name+":");
        }
    }

    private final SubCommand subCommands = new SubCommand("rtp");

    public TabComplete() {
        //load rtp commands and permission nodes into map
        subCommands.addSubParam("player","glide.use.other");

        subCommands.commands.put("reload",new SubCommand("rtp.reload"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        if(!sender.hasPermission("glide.use")) return null;

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
            if ((command.subParams.containsKey(arg)) && !knownParams.contains(arg)) {
                if(!sender.hasPermission(command.subParams.get(arg))){
                    return;
                }
                switch (arg) {
                    case "player" : {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            res.add(arg + ":" + player.getName());
                        }
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
            //if current argument is a parameter, add it to the list and go to next parameter
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
