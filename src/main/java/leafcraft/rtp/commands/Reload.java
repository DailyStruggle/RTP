package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Config;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Reload implements CommandExecutor {
    private Config config;
    private Cache cache;

    public Reload(Config config, Cache cache) {
        this.config = config;
        this.cache = cache;
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("rtp.reload")) {
            sender.sendMessage(config.getLog("noPerms"));
            return true;
        }

        String str = this.config.getLog("reloading");
        Bukkit.getConsoleSender().sendMessage(str);
        if(sender instanceof Player) {
            if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) str = PlaceholderAPI.setPlaceholders((Player)sender, str);
            sender.sendMessage(str);
        }

        this.cache.resetQueues();
        this.config.refreshConfigs();

        str = this.config.getLog("reloaded");
        Bukkit.getConsoleSender().sendMessage(str);
        if(sender instanceof Player) {
            if(Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) str = PlaceholderAPI.setPlaceholders((Player)sender, str);
            sender.sendMessage(str);
        }
        return true;
    }
}
