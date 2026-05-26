package io.github.dailystruggle.rtp.folia.entity;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;

public class FoliaRTPCommandSender implements RTPCommandSender {
  protected final CommandSender sender;

  @RegionThread
  public FoliaRTPCommandSender(CommandSender sender) {
    this.sender = sender;
  }

  @Override
  public UUID uuid() {
    if (sender instanceof Player) return ((Player) sender).getUniqueId();
    return RTPAPI.serverId;
  }

  @Override
  @RegionThread
  public boolean hasPermission(String permission) {
    return sender.hasPermission(permission);
  }

  @Override
  @RegionThread
  public void sendMessage(String message) {
    sender.sendMessage(message);
  }

  @Override
  @RegionThread
  public long cooldown() {
    if (sender.hasPermission("rtp.nocooldown")) return 0;
    if (sender.hasPermission("rtp.noCooldown")) return 0;

    int cooldown = ParsePermissions.getInt(this, "rtp.cooldown.");
    if (cooldown < 0) {
      ConfigParser<ConfigKeys> configParser =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      cooldown = configParser.getNumber(ConfigKeys.teleportCooldown, 0).intValue();
    }
    return TimeUnit.SECONDS.toMillis(cooldown);
  }

  @Override
  @RegionThread
  public long delay() {
    if (sender.hasPermission("rtp.nodelay")) return 0;
    if (sender.hasPermission("rtp.noDelay")) return 0;

    int delay = ParsePermissions.getInt(this, "rtp.delay.");
    if (delay < 0) {
      ConfigParser<ConfigKeys> configParser =
          (ConfigParser<ConfigKeys>) RTP.configs.getParser(ConfigKeys.class);
      delay = configParser.getNumber(ConfigKeys.teleportDelay, 0).intValue();
    }
    return TimeUnit.SECONDS.toMillis(delay);
  }

  @Override
  public String name() {
    return sender.getName();
  }

  @Override
  @RegionThread
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
  @RegionThread
  public void performCommand(@Nullable RTPPlayer player, String command) {
    CommandSender sender = this.sender;
    OfflinePlayer bukkitPlayer = (player != null) ? Bukkit.getOfflinePlayer(player.uuid()) : null;
    command = SendMessage.formatNoColor(bukkitPlayer, command);
    final String finalCommand = command;
    io.github.dailystruggle.rtp.common.RTP.serverAccessor.getScheduler()
        .runTask(() -> Bukkit.dispatchCommand(sender, finalCommand));
  }

  @Override
  @RegionThread
  public RTPCommandSender clone() {
    return new FoliaRTPCommandSender(sender);
  }

  public CommandSender sender() {
    return sender;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    FoliaRTPCommandSender that = (FoliaRTPCommandSender) obj;
    return Objects.equals(this.sender, that.sender);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sender);
  }

  @Override
  public String toString() {
    return "FoliaRTPCommandSender[" + "sender=" + sender + ']';
  }
}
