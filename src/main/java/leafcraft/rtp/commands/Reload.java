package leafcraft.rtp.commands;

import leafcraft.rtp.tools.Cache;
import leafcraft.rtp.tools.configuration.Configs;
import leafcraft.rtp.tools.SendMessage;
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
            SendMessage.sendMessage(sender,msg);
            return true;
        }

        String msg = configs.lang.getLog("reloading");
        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
        if(sender instanceof Player) {
            SendMessage.sendMessage(sender,msg);
        }

        configs.refresh();
        cache.resetRegions();

        cache.storePlayerData();

        msg = configs.lang.getLog("reloaded");
        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
        if(sender instanceof Player) SendMessage.sendMessage(sender,msg);
        return true;
    }
}
