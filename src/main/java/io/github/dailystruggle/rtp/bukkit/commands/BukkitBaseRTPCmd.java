package io.github.dailystruggle.rtp.bukkit.commands;

import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.MessagesKeys;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public abstract class BukkitBaseRTPCmd extends BukkitTreeCommand implements io.github.dailystruggle.rtp.common.commands.BaseRTPCmd {
    public BukkitBaseRTPCmd(Plugin plugin, @Nullable CommandsAPICommand parent) {
        super(plugin, parent);
    }

    @Override
    public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
        CommandSender sender = callerId.equals(CommandsAPI.serverId) ? Bukkit.getConsoleSender() : Bukkit.getPlayer(callerId);
        if(sender == null) return;

        ConfigParser<MessagesKeys> lang = (ConfigParser<MessagesKeys>) RTP.getInstance().configs.getParser(MessagesKeys.class);

        String msg = String.valueOf(lang.getConfigValue(MessagesKeys.badArg,""));
        msg = msg.replace("[arg]",parameterName + ":" + parameterValue);
        SendMessage.sendMessage(sender,msg);
    }
}
