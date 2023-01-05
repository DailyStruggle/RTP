package io.github.dailystruggle.rtp.bukkit.server.substitutions;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.rtp.bukkit.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class BukkitRTPCommandSender implements RTPCommandSender {
    private final CommandSender sender;

    public BukkitRTPCommandSender(CommandSender sender) {
        this.sender = sender;
    }

    @Override
    public UUID uuid() {
        if (sender instanceof Player)
            return ((Player) sender).getUniqueId();
        return CommandsAPI.serverId;
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public void sendMessage(String message) {
        SendMessage.sendMessage(sender, message);
    }

    @Override
    public long cooldown() {
        if (sender.hasPermission("rtp.nocooldown")) return 0;
        if (sender.hasPermission("rtp.noCooldown")) return 0;

        int cooldown = ParsePermissions.getInt(this, "rtp.cooldown.");
        if (cooldown < 0) {
            ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
            cooldown = configParser.getNumber(ConfigKeys.teleportCooldown, 0).intValue();
        }
        return TimeUnit.SECONDS.toMillis(cooldown);
    }

    @Override
    public long delay() {
        if (sender.hasPermission("rtp.nodelay")) return 0;
        if (sender.hasPermission("rtp.noDelay")) return 0;

        int delay = ParsePermissions.getInt(new BukkitRTPCommandSender(sender), "rtp.delay.");
        if (delay < 0) {
            ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
            delay = configParser.getNumber(ConfigKeys.teleportDelay, 0).intValue();
        }
        return TimeUnit.SECONDS.toMillis(delay);
    }

    @Override
    public String name() {
        return sender.getName();
    }

    @Override
    public Set<String> getEffectivePermissions() {
        return sender.getEffectivePermissions().stream().map(permissionAttachmentInfo -> {
            if (permissionAttachmentInfo.getValue()) return permissionAttachmentInfo.getPermission().toLowerCase();
            else return null;
        }).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    @Override
    public void performCommand(String command) {
        Bukkit.dispatchCommand(sender, command);
    }

    @Override
    public RTPCommandSender clone() {
        RTPCommandSender clone = null;
        try {
            clone = (RTPCommandSender) super.clone();
        } catch (CloneNotSupportedException e) {
            e.printStackTrace();
        }
        return clone;
    }

    public CommandSender sender() {
        return sender;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        BukkitRTPCommandSender that = (BukkitRTPCommandSender) obj;
        return Objects.equals(this.sender, that.sender);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sender);
    }

    @Override
    public String toString() {
        return "BukkitRTPCommandSender[" +
                "sender=" + sender + ']';
    }

}
