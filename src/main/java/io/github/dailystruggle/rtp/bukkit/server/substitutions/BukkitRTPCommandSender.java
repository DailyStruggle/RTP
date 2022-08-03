package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.UUID;

public record BukkitRTPCommandSender(CommandSender commandSender) implements RTPCommandSender {

    @Override
    public UUID uuid() {
        if(commandSender instanceof Player player)
            return player.getUniqueId();
        return CommandsAPI.serverId;
    }

    @Override
    public boolean hasPermission(String permission) {
        return commandSender.hasPermission(permission);
    }

    @Override
    public void sendMessage(String message) {
        SendMessage.sendMessage(commandSender,message);
    }
}
