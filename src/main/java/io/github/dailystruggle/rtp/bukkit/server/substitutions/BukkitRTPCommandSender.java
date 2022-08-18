package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public record BukkitRTPCommandSender(CommandSender sender) implements RTPCommandSender {

    @Override
    public UUID uuid() {
        if(sender instanceof Player player)
            return player.getUniqueId();
        return CommandsAPI.serverId;
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public void sendMessage(String message) {
        SendMessage.sendMessage(sender,message);
    }

    @Override
    public long cooldown() {
        if(sender.hasPermission("rtp.noCooldown")) return 0;

        int cooldown = ParsePermissions.getInt(new BukkitRTPCommandSender(sender), "rtp.cooldown.");
        if(cooldown<0) {
            ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.getInstance().configs.getParser(ConfigKeys.class);
            cooldown = configParser.getNumber(ConfigKeys.teleportCooldown,0).intValue();
        }
        return TimeUnit.SECONDS.toNanos(cooldown);
    }

    @Override
    public long delay() {
        if(sender.hasPermission("rtp.noDelay")) return 0;

        int delay = ParsePermissions.getInt(new BukkitRTPCommandSender(sender), "rtp.delay.");
        if(delay<0) {
            ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.getInstance().configs.getParser(ConfigKeys.class);
            delay = configParser.getNumber(ConfigKeys.teleportDelay,0).intValue();
        }
        return TimeUnit.SECONDS.toNanos(delay);
    }

    @Override
    public Set<String> getEffectivePermissions() {
        return sender.getEffectivePermissions().stream().map(permissionAttachmentInfo -> {
            if(permissionAttachmentInfo.getValue()) return permissionAttachmentInfo.getPermission().toLowerCase();
            else return null;
        }).filter(Objects::nonNull).collect(Collectors.toSet());
    }
}
