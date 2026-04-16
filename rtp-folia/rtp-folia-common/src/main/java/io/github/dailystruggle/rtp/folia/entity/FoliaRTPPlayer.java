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
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import io.github.dailystruggle.rtp.folia.thread.RegionThread;

public final class FoliaRTPPlayer implements RTPPlayer {
  private final Player player;

  @RegionThread
  public FoliaRTPPlayer(Player player) {
    this.player = player;
  }

  @Override
  public UUID uuid() {
    return player.getUniqueId();
  }

  @Override
  @RegionThread
  public boolean hasPermission(String permission) {
    return player.hasPermission(permission);
  }

  @Override
  @RegionThread
  public void sendMessage(String message) {
    SendMessage.sendMessage(player, message);
  }

  @Override
  @RegionThread
  public long cooldown() {
    return new FoliaRTPCommandSender(player).cooldown();
  }

  @Override
  @RegionThread
  public long delay() {
    return new FoliaRTPCommandSender(player).delay();
  }

  @Override
  public String name() {
    return player.getName();
  }

  @Override
  @RegionThread
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
  @RegionThread
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
  @RegionThread
  public RTPCommandSender clone() {
    return new FoliaRTPPlayer(player);
  }

  @Override
  @RegionThread
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    World world = ((FoliaRTPWorld) to.world()).world();

    // Step 1: Prepare the Destination
    // Create an org.bukkit.Location directly from the cached RTPLocation.
    // Because the coordinates are already pre-centered by the generation pipeline, do not add any offsets to X, Y, or Z.
    // Just apply the player's current yaw and pitch to the new location.
    double x = to.x();
    double y = to.y();
    double z = to.z();
    Location destinationLocation = new Location(world, x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());

    // Step 2: Fire the Initial Teleport
    // Call player.teleportAsync(destinationLocation). This allows Folia's native engine to instantly transfer
    // the player's entity object to the destination Region Thread without waiting for a scheduler tick.
    return player.teleportAsync(destinationLocation).thenCompose(success -> {
      // Step 3: Hijack the Callback
      // If success is false or the player is offline, immediately call rtpLoc.getReservation().close() to prevent memory leaks and return.
      if (!success || !player.isOnline()) {
        if (to.getReservation() != null) to.getReservation().close();
        return CompletableFuture.completedFuture(false);
      }

      // Extract the chunkX (rtpLoc.getBlockX() >> 4) and chunkZ (rtpLoc.getBlockZ() >> 4).
      int chunkX = to.getBlockX() >> 4;
      int chunkZ = to.getBlockZ() >> 4;

      CompletableFuture<Boolean> completionFuture = new CompletableFuture<>();

      // Step 4: Define the Build and Secure Task
      Runnable buildPlatformTask = () -> {
        try {
          // Call to.world().platform(to); to physically build the blocks on the destination region.
          // This also handles reservation closure in the Folia implementation.
          to.world().platform(to);

          // Jump to the Entity Scheduler using: player.getScheduler().run(...)
          player.getScheduler().run((Plugin) RTP.getInstance().getPlugin(), task -> {
            // Inside the Entity Scheduler task: Set player.setFallDistance(0.0f), apply SLOW_FALLING (60 ticks) and BLINDNESS (40 ticks),
            // and perform a micro-rubberband by calling player.teleportAsync(destinationLocation) again to snap them safely onto the newly built platform.
            player.setFallDistance(0.0f);
            PotionEffect currentSlowFalling = player.getPotionEffect(PotionEffectType.SLOW_FALLING);
            if (currentSlowFalling == null || currentSlowFalling.getDuration() < 60) {
              player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 60, 1));
            }
            PotionEffect currentBlindness = player.getPotionEffect(PotionEffectType.BLINDNESS);
            if (currentBlindness == null || currentBlindness.getDuration() < 40) {
              player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 1));
            }
            player.teleportAsync(destinationLocation).thenAccept(s -> completionFuture.complete(true));
          }, null);
        } catch (Exception e) {
          completionFuture.complete(false);
          if (to.getReservation() != null) to.getReservation().close();
        }
      };

      // Step 5: The 0-Tick Execution Check
      // To bypass the 50ms RegionScheduler delay, use the RTP scheduler which checks for current region ownership.
      RTP.serverAccessor.getScheduler().runTask(to.world(), chunkX, chunkZ, buildPlatformTask);
      return completionFuture;
    });
  }

  @Override
  @RegionThread
  public RTPLocation getLocation() {
    Location location = player.getLocation();
    return new RTPLocation(
        RTP.serverAccessor.getRTPWorld(player.getWorld().getUID()),
        location.getBlockX(),
        location.getBlockY(),
        location.getBlockZ());
  }

  @Override
  @RegionThread
  public boolean isOnline() {
    return player.isOnline();
  }

  @RegionThread
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
