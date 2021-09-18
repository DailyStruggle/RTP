package io.github.dailystruggle.rtp_glide.Commands;

import io.github.dailystruggle.rtp_glide.RTP_Glide;
import io.github.dailystruggle.rtp_glide.Tasks.SetupGlide;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Glide implements CommandExecutor {
    private final RTP_Glide plugin;

    public Glide(RTP_Glide plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("glide.use")) return false;
        String playerName;
        if(args.length==0) {
            if(!(sender instanceof Player)) {
                Bukkit.getLogger().warning("glide needs a player argument");
                return true;
            }
            else playerName = sender.getName();
        }
        else playerName = args[0];
        Player player = Bukkit.getPlayer(playerName);
        if(player == null) {
            sender.sendMessage("[glide] player '" + playerName + "' not found");
            return true;
        }

        new SetupGlide(player).runTask(plugin);

        return true;
    }
}
