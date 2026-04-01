package io.github.dailystruggle.rtp.folia.entity;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

public final class FoliaRTPPlayer implements RTPPlayer {
  private final Player player;

  public FoliaRTPPlayer(Player player) {
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
    return new FoliaRTPCommandSender(player).cooldown();
  }

  @Override
  public long delay() {
    return new FoliaRTPCommandSender(player).delay();
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
    else player = ((FoliaRTPPlayer) rtpPlayer).player();
    command = SendMessage.formatNoColor(player, command);
    final String finalCommand = command;
    this.player
        .getScheduler()
        .run(
            org.bukkit.Bukkit.getPluginManager().getPlugin("RTP"),
            scheduledTask -> this.player.performCommand(finalCommand),
            null);
  }

  @Override
  public RTPCommandSender clone() {
    return new FoliaRTPPlayer(player);
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    World world = ((FoliaRTPWorld) to.world()).world();
    double x = to.x() + 0.5;
    double y = to.y();
    double z = to.z() + 0.5;

    return player.teleportAsync(new Location(world, x, y, z));
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
    FoliaRTPPlayer that = (FoliaRTPPlayer) obj;
    return Objects.equals(this.player, that.player);
  }

  @Override
  public int hashCode() {
    return Objects.hash(player);
  }

  @Override
  public String toString() {
    return "FoliaRTPPlayer[" + "player=" + player + ']';
  }
}
