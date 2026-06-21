package io.github.dailystruggle.rtp.folia.entity;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
    double x = to.x() + 0.5;
    double y = to.y();
    double z = to.z() + 0.5;
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
            // Inside the Entity Scheduler task: Set player.setFallDistance(0.0f) and perform a
            // micro-rubberband by calling player.teleportAsync(destinationLocation) again to snap
            // the player safely onto the newly built platform. The previously hardcoded
            // SLOW_FALLING / BLINDNESS effects now live in effects/default.yml (postteleport
            // fallback group) so admins can customise or remove them without a code change.
            player.setFallDistance(0.0f);
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

  @Override
  @SuppressWarnings("deprecation")
  public void setRespawnLocation(RTPLocation to) {
    // BetterRTP SetAsRespawn parity. Setting the bed/respawn anchor touches the
    // player's state, which on Folia must happen on the player's entity scheduler
    // (the teleport completion callback does not necessarily own the player's
    // region). Dispatch there regardless of the calling thread.
    World world = ((FoliaRTPWorld) to.world()).world();
    Location location = new Location(world, to.x() + 0.5, to.y(), to.z() + 0.5);
    Plugin plugin = (Plugin) RTP.getInstance().getPlugin();
    if (plugin == null || !plugin.isEnabled()) return;
    player.getScheduler().run(
        plugin, task -> player.setBedSpawnLocation(location, true), null);
  }

  @Override
  @RegionThread
  public String getClientBlock(RTPLocation location) {
    if (location == null) return null;
    World world = ((FoliaRTPWorld) location.world()).world();
    // S-005: only read an already-loaded block; never let getBlockAt load a chunk.
    if (!world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) return null;
    return world.getBlockAt(location.x(), location.y(), location.z()).getBlockData().getAsString();
  }

  @Override
  @RegionThread
  public void sendClientBlockChange(RTPLocation location, String blockData) {
    if (location == null || blockData == null) return;
    World world = ((FoliaRTPWorld) location.world()).world();
    if (!world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) return;
    BlockData parsed = parseBlockData(blockData);
    if (parsed == null) return;
    player.sendBlockChange(new Location(world, location.x(), location.y(), location.z()), parsed);
  }

  @Override
  @RegionThread
  public void sendClientBlockChanges(Map<RTPLocation, String> changes) {
    if (changes == null || changes.isEmpty()) return;
    // Bin the changes into BlockState snapshots and hand them to Bukkit's bulk sender, which
    // packs them into one multi-block-change packet per chunk section instead of one packet per
    // block. The snapshots are detached copies (block.getState()), so re-typing them never
    // touches the real world (S-001..S-007), and we never load a chunk (S-005).
    List<BlockState> states = new ArrayList<>(changes.size());
    for (Map.Entry<RTPLocation, String> entry : changes.entrySet()) {
      RTPLocation location = entry.getKey();
      String blockData = entry.getValue();
      if (location == null || blockData == null) continue;
      World world = ((FoliaRTPWorld) location.world()).world();
      if (!world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) continue;
      BlockData parsed = parseBlockData(blockData);
      if (parsed == null) continue;
      Block block = world.getBlockAt(location.x(), location.y(), location.z());
      BlockState state = block.getState();
      state.setBlockData(parsed);
      states.add(state);
    }
    if (!states.isEmpty()) {
      player.sendBlockChanges(states);
    }
  }

  /**
   * Parse a platform-neutral block-data string into Bukkit {@link BlockData}, logging (never
   * silently swallowing - S-004) and returning {@code null} on a malformed string.
   */
  private static BlockData parseBlockData(String blockData) {
    try {
      return Bukkit.createBlockData(blockData);
    } catch (IllegalArgumentException e) {
      RTP.log(Level.WARNING, "[RTP] ignoring malformed client block-data string: " + blockData, e);
      return null;
    }
  }

  @RegionThread
  Player player() {
    return player;
  }

  /**
   * Schedule {@code task} on this player's entity scheduler after {@code delayTicks}.
   * The optional {@code retired} runnable fires if the entity is removed before the
   * task runs (Folia's "retired" callback). Keeps the underlying {@link Player}
   * reference encapsulated within this class so callers don't need to fetch it via
   * {@link #player()} or {@code Bukkit.getPlayer(uuid)}.
   *
   * @return {@code true} if the scheduler accepted the task, {@code false} if the
   *         player is no longer schedulable (caller should use a fallback path so
   *         the runnable is not silently dropped — see REQ-RTP-S-004).
   */
  public boolean scheduleOnSelf(Runnable task, Runnable retired, long delayTicks) {
    if (player == null) return false;
    Plugin plugin = (Plugin) RTP.getInstance().getPlugin();
    if (plugin == null || !plugin.isEnabled()) return false;
    Object scheduled = player
        .getScheduler()
        .runDelayed(plugin, st -> task.run(), retired, Math.max(1, delayTicks));
    return scheduled != null;
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
