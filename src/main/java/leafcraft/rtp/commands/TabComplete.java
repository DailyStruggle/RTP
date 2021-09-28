package leafcraft.rtp.commands;

import leafcraft.rtp.API.Commands.SubCommand;
import leafcraft.rtp.RTP;
import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.selection.TeleportRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Biome;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;


public class TabComplete implements TabCompleter {
    private final SubCommand subCommands;

    private static RTP plugin = null;
    private static Configs configs = null;
    private static Cache cache = null;

    public TabComplete(SubCommand mainCommand) {
        this.subCommands = mainCommand;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, String[] args) {
        if(!sender.hasPermission("rtp.see")) return null;

        if(plugin == null) plugin = RTP.getPlugin();
        if(configs == null) configs = RTP.getConfigs();
        if(cache == null) cache = RTP.getCache();

        List<String> match = new ArrayList<>();
        Set<String> knownParams = new HashSet<>();
        this.getList(knownParams,match,this.subCommands,args, 0,sender);

        List<String> res = new ArrayList<>();
        StringUtil.copyPartialMatches(args[args.length-1],match,res);
        return res;
    }

    public void getList(Set<String> knownParams, List<String> res, SubCommand command, String[] args, int i, CommandSender sender) {
        if(i>=args.length) return;
        int idx = args[i].indexOf(':');
        String arg = idx > 0 ? args[i].substring(0, idx) : args[i];
        if(i == args.length-1) { //if last arg
            //if semicolon, maybe suggest
            String perm = command.getSubParamPerm(arg);
            if (perm!=null && !knownParams.contains(arg)) {
                if(!sender.hasPermission(perm)){
                    return;
                }
                SubCommand.ParamType type = command.getSubParamType(arg);
                switch (Objects.requireNonNull(type)) {
                    case SHAPE -> {
                        for (TeleportRegion.Shapes shape : TeleportRegion.Shapes.values()) {
                            res.add(arg + ":" + shape.name());
                        }
                    }
                    case REGION -> {
                        List<String> regions = configs.regions.getRegionNames();
                        for (String region : regions) {
                            if (!((Boolean) configs.regions.getRegionSetting(region, "requirePermission", true))
                                    || sender.hasPermission("rtp.regions." + region)) {
                                res.add(arg + ":" + region);
                            }
                        }
                    }
                    case WORLD -> {
                        for (World world : Bukkit.getWorlds()) {
                            configs.worlds.checkWorldExists(world.getName());
                            if (!((Boolean) configs.worlds.getWorldSetting(world.getName(), "requirePermission", true))
                                    || sender.hasPermission("rtp.worlds." + world.getName())) {
                                res.add(arg + ":" + configs.worlds.worldName2Placeholder(world.getName()));
                            }
                        }
                    }
                    case PLAYER -> {
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            res.add(arg + ":" + player.getName());
                        }
                    }
                    case COORDINATE -> {
                        if (sender instanceof Player) res.add(arg + ":" + "~");
                    }
                    case BOOLEAN -> {
                        res.add(arg + ":true");
                        res.add(arg + ":false");
                    }
                    case BIOME -> {
                        for (Biome biome : Biome.values()) {
                            if (sender.hasPermission("rtp.biome.*") || sender.hasPermission("rtp.biome." + biome.name()))
                                res.add(arg + ":" + biome.name());
                        }
                        if (sender instanceof Player) {
                            World world = ((Player) sender).getWorld();
                            BiomeProvider provider = ((Player) sender).getWorld().getBiomeProvider();
                            if (provider != null) {
                                for (Biome biome : provider.getBiomes(world)) {
                                    res.add(arg + ":" + biome.getKey().getNamespace() + "." + biome.getKey().getKey());
                                }
                            }
                        }
                    }
                    case MODE -> {
                        for (TeleportRegion.Modes mode : TeleportRegion.Modes.values()) {
                            res.add(arg + ":" + mode.name());
                        }
                    }
                    default -> {
                        if (arg.equals("near")) {
                            int numValidPlayers = 0;
                            if (sender.hasPermission("rtp.near.other")) {
                                for (Player player : Bukkit.getOnlinePlayers()) {
                                    if (player.getName().equals(sender.getName())) continue;
                                    if (player.hasPermission("rtp.near.notme")) continue;
                                    res.add(arg + ":" + player.getName());
                                    numValidPlayers++;
                                }
                            }

                            if (sender.hasPermission("rtp.near.random")) {
                                if (numValidPlayers == 0) {
                                    for (Player player : Bukkit.getOnlinePlayers()) {
                                        if (player.getName().equals(sender.getName())) continue;
                                        if (player.hasPermission("rtp.near.notme")) continue;
                                        numValidPlayers++;
                                    }
                                }
                                if (numValidPlayers > 0)
                                    res.add("near:random");
                            }

                            if (sender instanceof Player && sender.hasPermission("rtp.near")) {
                                res.add(arg + ":" + sender.getName());
                            }
                        } else {
                            res.add(arg + ":");
                        }
                    }
                }
            }
            else { //if no semicolon add all sub-commands or sub-parameters
                for(Map.Entry<String, ArrayList<String>> entry : Objects.requireNonNull(command.getSubParams())) {
                    if(knownParams.contains(entry.getKey())) continue;
                    if(sender.hasPermission(entry.getKey())) {
                        res.addAll(entry.getValue());
                    }
                }
                if(knownParams.size() == 0) {
                    for (Map.Entry<String, SubCommand> entry : Objects.requireNonNull(command.getSubCommands()).entrySet()) {
                        if (sender.hasPermission(entry.getValue().getPerm())) {
                            res.add(entry.getKey());
                        }
                    }
                }
            }
        }
        else {
            //if current argument is a parameter, add it to the list and go to next parameter
            String paramPerm = command.getSubParamPerm(arg);
            SubCommandImpl cmd = (SubCommandImpl) command.getSubCommand(args[i]);
            if(paramPerm !=null) {
                if(sender.hasPermission(paramPerm))
                    knownParams.add(arg);
            }
            else if(cmd!=null) { //if argument is a command, use next layer
                if(sender.hasPermission(cmd.getPerm()))
                    command = cmd;
            }
            getList(knownParams,res,command,args,i+1,sender);
        }
    }
}
