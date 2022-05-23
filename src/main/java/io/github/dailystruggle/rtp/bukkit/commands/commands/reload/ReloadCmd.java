package io.github.dailystruggle.rtp.bukkit.commands.commands.reload;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class ReloadCmd implements CommandExecutor {


    public ReloadCmd() {

    }
    @Override
    public boolean onCommand(CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
//        if(!sender.hasPermission("rtp.reload")) {
//            String msg = Configs.lang.getLog("noPerms");
//            SendMessage.sendMessage(sender,msg);
//            return true;
//        }
//
//        String msg = Configs.lang.getLog("reloading");
//        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
//        if(sender instanceof Player) {
//            SendMessage.sendMessage(sender,msg);
//        }
//
//        Configs.refresh();
//        //todo
////        cache.resetRegions();
////
////        cache.storePlayerData();
//
//        msg = Configs.lang.getLog("reloaded");
//        SendMessage.sendMessage(Bukkit.getConsoleSender(),msg);
//        if(sender instanceof Player) SendMessage.sendMessage(sender,msg);
        return true;
    }
}
