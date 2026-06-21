package io.github.dailystruggle.rtp.bukkitplatform.entity;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
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

  /**
   * Class-probe for Folia. Cached: the runtime platform never changes mid-process. Lives here
   * (not in the rtp-plugin {@code BukkitServerProvider}) because this Spigot-classpath module
   * cannot reference it, and the basic Folia path in the free build needs an async teleport.
   */
  private static final boolean IS_FOLIA;

  static {
    boolean folia;
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      folia = true;
    } catch (ClassNotFoundException e) {
      folia = false;
    }
    IS_FOLIA = folia;
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    World world = ((BukkitRTPWorld) to.world()).world();
    double x = to.x() + 0.5;
    double y = to.y();
    double z = to.z() + 0.5;
    Location location = new Location(world, x, y, z);

    CompletableFuture<Boolean> future = new CompletableFuture<>();

    // Basic Folia path (free build, ADR-024 / ADR-061): a synchronous player.teleport() throws
    // on Folia because the RTP destination is almost always in a region other than the one this
    // thread owns. paper-api's Entity#teleportAsync is callable from any thread and performs the
    // cross-region hop itself, so route through it reflectively (this module compiles against the
    // Spigot API, which lacks teleportAsync). The tuned rtp-folia adapter (Pro) uses the
    // first-class FoliaRTPPlayer teleport path instead.
    if (IS_FOLIA) {
      try {
        Object result =
            player.getClass().getMethod("teleportAsync", Location.class).invoke(player, location);
        if (result instanceof CompletableFuture) {
          @SuppressWarnings("unchecked")
          CompletableFuture<Boolean> async = (CompletableFuture<Boolean>) result;
          async.whenComplete(
              (success, throwable) -> {
                if (throwable != null) {
                  RTP.log(Level.WARNING, "[RTP] teleportAsync failed", throwable);
                  future.complete(false);
                } else {
                  future.complete(Boolean.TRUE.equals(success));
                }
              });
          return future;
        }
      } catch (ReflectiveOperationException | RuntimeException e) {
        // teleportAsync unavailable/failed unexpectedly; fall through to the sync path below so
        // the teleport failure is never silently swallowed (REQ-RTP-S-004).
        RTP.log(Level.WARNING, "[RTP] teleportAsync unavailable, falling back to sync teleport", e);
      }
    }

    // Default path: legacy Spigot mandates that entity teleportation occurs on the main thread.
    Runnable tpTask = () -> future.complete(player.teleport(location));
    RTP.scheduler.runTask(tpTask);
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

  @Override
  @SuppressWarnings("deprecation")
  public void setRespawnLocation(RTPLocation to) {
    // BetterRTP SetAsRespawn parity: anchor the player's bed/respawn point to the
    // landed location. setBedSpawnLocation(Location, force=true) is used over the
    // newer setRespawnLocation(...) for compatibility with the Spigot API this
    // module compiles against; the force flag persists the anchor even with no bed.
    World world = ((BukkitRTPWorld) to.world()).world();
    Location location = new Location(world, to.x() + 0.5, to.y(), to.z() + 0.5);
    player.setBedSpawnLocation(location, true);
  }

  @Override
  public String getClientBlock(RTPLocation location) {
    if (location == null) return null;
    World world = ((BukkitRTPWorld) location.world()).world();
    // S-005: only read an already-loaded block; never let getBlockAt load a chunk.
    if (!world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) return null;
    return world.getBlockAt(location.x(), location.y(), location.z()).getBlockData().getAsString();
  }

  @Override
  public void sendClientBlockChange(RTPLocation location, String blockData) {
    if (location == null || blockData == null) return;
    World world = ((BukkitRTPWorld) location.world()).world();
    if (!world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) return;
    BlockData parsed = parseBlockData(blockData);
    if (parsed == null) return;
    player.sendBlockChange(new Location(world, location.x(), location.y(), location.z()), parsed);
  }

  @Override
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
      World world = ((BukkitRTPWorld) location.world()).world();
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
