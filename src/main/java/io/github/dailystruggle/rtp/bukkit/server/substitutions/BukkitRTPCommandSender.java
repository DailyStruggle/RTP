package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

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

        Set<PermissionAttachmentInfo> perms = sender.getEffectivePermissions();

        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            String node = perm.getPermission();
            if(node.startsWith("rtp.cooldown.")) {
                String[] val = node.split("\\.");
                if(val.length<3 || val[2]==null || val[2].equals("")) continue;
                int number;
                try {
                    number = Integer.parseInt(val[2]);
                } catch (NumberFormatException exception) {

                    RTP.log(Level.WARNING, "[rtp] invalid permission: " + node);
                    continue;
                }
                return TimeUnit.SECONDS.toNanos(number);
            }
        }

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.getInstance().configs.getParser(ConfigKeys.class);
        return TimeUnit.SECONDS.toNanos(configParser.getNumber(ConfigKeys.teleportCooldown,0).longValue());
    }

    @Override
    public long delay() {
        if(sender.hasPermission("rtp.noDelay")) return 0;

        Set<PermissionAttachmentInfo> perms = sender.getEffectivePermissions();

        for(PermissionAttachmentInfo perm : perms) {
            if(!perm.getValue()) continue;
            String node = perm.getPermission();
            if(node.startsWith("rtp.delay.")) {
                String[] val = node.split("\\.");
                if(val.length<3 || val[2]==null || val[2].equals("")) continue;
                int number;
                try {
                    number = Integer.parseInt(val[2]);
                } catch (NumberFormatException exception) {
                    RTP.log(Level.WARNING, "[rtp] invalid permission: " + node);
                    continue;
                }
                return TimeUnit.SECONDS.toNanos(number);
            }
        }

        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.getInstance().configs.getParser(ConfigKeys.class);
        return TimeUnit.SECONDS.toNanos(configParser.getNumber(ConfigKeys.teleportDelay,0).longValue());
    }
}
