package io.github.dailystruggle.rtp.spigot.entity;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import io.github.dailystruggle.rtp.spigot.world.BukkitRTPWorld;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class BukkitRTPPlayer implements RTPPlayer {
  private final Player player;

  public BukkitRTPPlayer(Player player) {
    this.player = player;
  }

  @Override
  public UUID uuid() {
    return player.getUniqueId();
  }

  @Override
  public boolean hasPermission(String permission) {
    return player.hasPermission(permission);
  }

  @Override
  public void sendMessage(String message) {
    SendMessage.sendMessage(player, message);
  }

  @Override
  public long cooldown() {
    return new BukkitRTPCommandSender(player).cooldown();
  }

  @Override
  public long delay() {
    return new BukkitRTPCommandSender(player).delay();
  }

  @Override
  public String name() {
    return player.getName();
  }

  @Override
  public Set<String> getEffectivePermissions() {
    return player.getEffectivePermissions().stream()
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
  public void performCommand(RTPPlayer rtpPlayer, String command) {
    OfflinePlayer player;
    if (rtpPlayer == null) player = player();
    else player = ((BukkitRTPPlayer) rtpPlayer).player();
    command = SendMessage.formatNoColor(player, command);
    player().performCommand(command);
  }

  @Override
  public RTPCommandSender clone() {
    return new BukkitRTPPlayer(player);
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    World world = ((BukkitRTPWorld) to.world()).world();
    double x = to.x() + 0.5;
    double y = to.y();
    double z = to.z() + 0.5;
    Location location = new Location(world, x, y, z);

    // Graceful fallback for pure Spigot servers running 1.18.2 or older
    CompletableFuture<Boolean> future = new CompletableFuture<>();
    Runnable tpTask = () -> future.complete(player.teleport(location));

    // Legacy Spigot mandates that entity teleportation occurs on the main thread
    if (org.bukkit.Bukkit.isPrimaryThread()) {
      tpTask.run();
    } else {
      org.bukkit.plugin.Plugin plugin = org.bukkit.Bukkit.getPluginManager().getPlugin("RTP");
      if (plugin != null) {
        org.bukkit.Bukkit.getScheduler().runTask(plugin, tpTask);
      } else {
        future.complete(false);
      }
    }
    return future;
  }

  @Override
  public RTPLocation getLocation() {
    Location location = player.getLocation();
    return new RTPLocation(
        RTP.serverAccessor.getRTPWorld(player.getWorld().getUID()),
        location.getBlockX(),
        location.getBlockY(),
        location.getBlockZ());
  }

  @Override
  public boolean isOnline() {
    return player.isOnline();
  }

  public Player player() {
    return player;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    BukkitRTPPlayer that = (BukkitRTPPlayer) obj;
    return Objects.equals(this.player, that.player);
  }

  @Override
  public int hashCode() {
    return Objects.hash(player);
  }

  @Override
  public String toString() {
    return "BukkitRTPPlayer[" + "player=" + player + ']';
  }
}
