package io.github.dailystruggle.rtp_glide.Commands;

import io.github.dailystruggle.rtp_glide.RTP_Glide;
import io.github.dailystruggle.rtp_glide.Tasks.SetupGlide;
import io.github.dailystruggle.rtp_glide.configuration.Configs;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class Glide implements CommandExecutor {
    private final RTP_Glide plugin;
    private final Configs Configs;

    private final Map<String,String> glideCommands = new HashMap<>();
    private final Map<String,String> glideParams = new HashMap<>();
    private final Map<String,CommandExecutor> commandHandles = new HashMap<>();

    public Glide(RTP_Glide plugin, Configs Configs) {
        this.plugin = plugin;
        this.Configs = Configs;

        this.glideParams.put("player", "rtp.other");
    }

    public void addCommandHandle(String command, String perm, CommandExecutor handle) {
        commandHandles.put(command,handle);
        glideCommands.put(command,perm);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!command.getName().equals("glide")) return true;

        if(args.length > 0 && glideCommands.containsKey(args[0])) {
            if(!sender.hasPermission(glideCommands.get(args[0]))) {
                return false;
            }
            else {
                return commandHandles.get(args[0]).onCommand(sender,command,label, Arrays.copyOfRange(args, 1, args.length));
            }
        }

        if(!sender.hasPermission("glide.use")) return false;

        Map<String,String> glideArgs = new HashMap<>();
        for(int i = 0; i < args.length; i++) {
            int idx = args[i].indexOf(':');
            String arg = idx>0 ? args[i].substring(0,idx) : args[i];
            if(this.glideParams.containsKey(arg) && sender.hasPermission(glideParams.get(arg)) && idx < args[i].length()-1) {
                glideArgs.putIfAbsent(arg,args[i].substring(idx+1)); //only use first instance
            }
        }

        String playerName;
        if(!glideArgs.containsKey("player")) {
            if(!(sender instanceof Player)) {
                Bukkit.getLogger().warning("glide needs a player argument");
                return true;
            }
            else playerName = sender.getName();
        }
        else playerName = glideArgs.get("player");
        Player player = Bukkit.getPlayer(playerName);
        if(player == null) {
            sender.sendMessage("[glide] player '" + playerName + "' not found");
            return true;
        }

        new SetupGlide(player, Configs).runTask(plugin);

        return true;
    }
}
