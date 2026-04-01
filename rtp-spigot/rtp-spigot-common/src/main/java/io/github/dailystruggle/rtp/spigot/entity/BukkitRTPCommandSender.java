package io.github.dailystruggle.rtp.spigot.entity;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

public class BukkitRTPCommandSender implements RTPCommandSender {
  protected final CommandSender sender;

  public BukkitRTPCommandSender(CommandSender sender) {
    this.sender = sender;
  }

  @Override
  public UUID uuid() {
    if (sender instanceof Player) return ((Player) sender).getUniqueId();
    return RTPAPI.serverId;
  }

  @Override
  public boolean hasPermission(String permission) {
    return sender.hasPermission(permission);
  }

  @Override
  public void sendMessage(String message) {
    sender.sendMessage(message);
  }

  @Override
  public long cooldown() {
    return ParsePermissions.getInt(uuid(), "rtp.cooldown.");
  }

  @Override
  public long delay() {
    return ParsePermissions.getInt(uuid(), "rtp.delay.");
  }

  @Override
  public String name() {
    return sender.getName();
  }

  @Override
  public Set<String> getEffectivePermissions() {
    return sender.getEffectivePermissions().stream()
        .map(
            permissionAttachmentInfo -> {
              if (permissionAttachmentInfo.getValue())
                return permissionAttachmentInfo.getPermission().toLowerCase();
              else return null;
            })
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
  }

  @Override
  public void performCommand(@Nullable RTPPlayer player, String command) {
    CommandSender sender = this.sender;
    OfflinePlayer bukkitPlayer = (player != null) ? Bukkit.getOfflinePlayer(player.uuid()) : null;
    command = SendMessage.formatNoColor(bukkitPlayer, command);
    Bukkit.dispatchCommand(sender, command);
  }

  @Override
  public RTPCommandSender clone() {
    return new BukkitRTPCommandSender(sender);
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
    return "BukkitRTPCommandSender[" + "sender=" + sender + ']';
  }
}
