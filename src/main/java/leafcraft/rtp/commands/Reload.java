package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.Configuration.Configs;
import leafcraft.rtp.tools.softdepends.PAPIChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Reload implements CommandExecutor {
    private final Configs configs;
    private final Cache cache;

    public Reload(Configs configs, Cache cache) {
        this.configs = configs;
        this.cache = cache;
    }
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if(!sender.hasPermission("rtp.reload")) {
            String msg = configs.lang.getLog("noPerms");
            if(sender instanceof Player) msg = PAPIChecker.fillPlaceholders((Player)sender,msg);
            if(!msg.equals("")) sender.sendMessage(msg);
            return true;
        }

        String msg = configs.lang.getLog("reloading");
        if(!msg.equals("")) Bukkit.getConsoleSender().sendMessage(msg);
        if(sender instanceof Player) {
            PAPIChecker.fillPlaceholders((Player)sender, msg);
            if(!msg.equals("")) sender.sendMessage(msg);
        }

        configs.refresh();
        cache.resetRegions();

        msg = configs.lang.getLog("reloaded");
        if(!msg.equals("")) Bukkit.getConsoleSender().sendMessage(msg);
        if(sender instanceof Player) {
            PAPIChecker.fillPlaceholders((Player)sender, msg);
            if(!msg.equals("")) sender.sendMessage(msg);
        }
        return true;
    }
}
