package leafcraft.rtp.bukkit.commonBukkitImpl.substitutions;

import leafcraft.rtp.bukkit.tools.SendMessage;
import leafcraft.rtp.common.substitutions.RTPCommandSender;
import org.bukkit.command.CommandSender;

public record BukkitRTPCommandSender(CommandSender commandSender) implements RTPCommandSender {

    @Override
    public boolean hasPermission(String permission) {
        return commandSender.hasPermission(permission);
    }

    @Override
    public void sendMessage(String message) {
        SendMessage.sendMessage(commandSender,message);
    }
}
