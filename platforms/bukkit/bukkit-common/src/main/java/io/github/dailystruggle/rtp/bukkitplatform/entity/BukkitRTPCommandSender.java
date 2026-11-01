package io.github.dailystruggle.rtp.bukkitplatform.entity;

import io.github.dailystruggle.rtp.bukkitplatform.tools.BukkitServers;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.ConfigKeys;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.common.tools.ParsePermissions;
import java.util.Collections;
import java.util.Locale;
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

public class BukkitRTPCommandSender implements RTPCommandSender {
  protected volatile @Nullable CommandSender sender;
  protected final UUID uuid;
  protected final String name;
  protected final long cooldown;
  protected final long delay;
  protected final Set<String> effectivePermissions;

  public BukkitRTPCommandSender(CommandSender sender) {
    this.sender = sender;
    if (sender instanceof Player) {
      this.uuid = ((Player) sender).getUniqueId();
    } else {
      this.uuid = RTPAPI.serverId;
    }
    this.name = (sender != null) ? sender.getName() : "CONSOLE";
    this.cooldown = computeCooldown(sender, this);
    this.delay = computeDelay(sender, this);
    this.effectivePermissions = capturePermissions(sender);
  }

  public BukkitRTPCommandSender(
      UUID uuid,
      String name,
      long cooldown,
      long delay,
      Set<String> effectivePermissions) {
    this.sender = null;
    this.uuid = uuid;
    this.name = (name != null) ? name : uuid.toString();
    this.cooldown = cooldown;
    this.delay = delay;
    this.effectivePermissions = (effectivePermissions != null) ? Set.copyOf(effectivePermissions) : Collections.emptySet();
  }

  @Override
  public UUID uuid() {
    return uuid;
  }

  @Override
  public boolean isSelfContained() {
    return sender == null;
  }

  @Override
  public RTPCommandSender toPureData() {
    if (isSelfContained()) return this;
    return new BukkitRTPCommandSender(uuid, name, cooldown(), delay(), getEffectivePermissions());
  }

  @Override
  public @Nullable CommandSender getLiveSender() {
    CommandSender s = this.sender;
    if (s != null) {
      if (s instanceof Player && !((Player) s).isOnline()) {
        this.sender = null;
      } else {
        return s;
      }
    }
    if (BukkitServers.absent()) return null;
    if (uuid.equals(RTPAPI.serverId)) {
      CommandSender console = Bukkit.getConsoleSender();
      this.sender = console;
      return console;
    }
    Player p = Bukkit.getPlayer(uuid);
    if (p != null) {
      this.sender = p;
      return p;
    }
    return null;
  }

  public void unbind() {
    this.sender = null;
  }

  public void rebind(CommandSender liveSender) {
    this.sender = liveSender;
  }

  @Override
  public boolean hasPermission(String permission) {
    CommandSender s = this.sender;
    if (s != null) return s.hasPermission(permission);
    if (effectivePermissions.contains("rtp.*") || effectivePermissions.contains("*")) return true;
    return effectivePermissions.contains(permission.toLowerCase(Locale.ROOT));
  }

  @Override
  public void sendMessage(String message) {
    CommandSender s = (CommandSender) getLiveSender();
    if (s != null) {
      SendMessage.sendMessage(s, message);
    }
  }

  @Override
  public long cooldown() {
    CommandSender s = this.sender;
    if (s != null) {
      return computeCooldown(s, this);
    }
    return this.cooldown;
  }

  @Override
  public long delay() {
    CommandSender s = this.sender;
    if (s != null) {
      return computeDelay(s, this);
    }
    return this.delay;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Set<String> getEffectivePermissions() {
    CommandSender s = this.sender;
    if (s != null) {
      return capturePermissions(s);
    }
    return effectivePermissions;
  }

  @Override
  public void performCommand(@Nullable RTPPlayer player, String command) {
    CommandSender s = (CommandSender) getLiveSender();
    if (s == null) return;
    OfflinePlayer bukkitPlayer = (player != null) ? Bukkit.getOfflinePlayer(player.uuid()) : null;
    command = SendMessage.formatNoColor(bukkitPlayer, command);
    Bukkit.dispatchCommand(s, command);
  }

  @Override
  public RTPCommandSender clone() {
    if (isSelfContained()) {
      return new BukkitRTPCommandSender(uuid, name, cooldown, delay, effectivePermissions);
    }
    return new BukkitRTPCommandSender(sender);
  }

  public @Nullable CommandSender sender() {
    return (sender != null) ? sender : (CommandSender) getLiveSender();
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || !(obj instanceof RTPCommandSender)) return false;
    RTPCommandSender that = (RTPCommandSender) obj;
    return Objects.equals(this.uuid, that.uuid());
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid);
  }

  @Override
  public String toString() {
    return "BukkitRTPCommandSender[" + "uuid=" + uuid + ", name=" + name + ", selfContained=" + isSelfContained() + ']';
  }

  private static long computeCooldown(CommandSender s, BukkitRTPCommandSender senderWrapper) {
    if (s == null) return 0;
    try {
      if (s.hasPermission("rtp.nocooldown") || s.hasPermission("rtp.noCooldown")) return 0;
      int cooldown = ParsePermissions.getInt(senderWrapper, "rtp.cooldown.");
      if (cooldown < 0) {
        if (ActiveConfigs.get() == null) return 0;
        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
        Object val = (configParser != null) ? configParser.getConfigValue(ConfigKeys.teleportCooldown, 0) : 0;
        return ConfigParser.parseSecondsDurationMillis(val, 0L);
      }
      return TimeUnit.SECONDS.toMillis(cooldown);
    } catch (Throwable t) {
      return 0;
    }
  }

  private static long computeDelay(CommandSender s, BukkitRTPCommandSender senderWrapper) {
    if (s == null) return 0;
    try {
      if (s.hasPermission("rtp.nodelay") || s.hasPermission("rtp.noDelay")) return 0;
      int delay = ParsePermissions.getInt(senderWrapper, "rtp.delay.");
      if (delay < 0) {
        if (ActiveConfigs.get() == null) return 0;
        ConfigParser<ConfigKeys> configParser = (ConfigParser<ConfigKeys>) ActiveConfigs.get().getParser(ConfigKeys.class);
        Object val = (configParser != null) ? configParser.getConfigValue(ConfigKeys.teleportDelay, 0) : 0;
        return ConfigParser.parseSecondsDurationMillis(val, 0L);
      }
      return TimeUnit.SECONDS.toMillis(delay);
    } catch (Throwable t) {
      return 0;
    }
  }

  private static Set<String> capturePermissions(CommandSender s) {
    if (s == null) return Collections.emptySet();
    try {
      return s.getEffectivePermissions().stream()
          .map(
              permissionAttachmentInfo -> {
                if (permissionAttachmentInfo.getValue())
                  return permissionAttachmentInfo.getPermission().toLowerCase(Locale.ROOT);
                else return null;
              })
          .filter(Objects::nonNull)
          .collect(Collectors.toUnmodifiableSet());
    } catch (Throwable t) {
      return Collections.emptySet();
    }
  }
}
