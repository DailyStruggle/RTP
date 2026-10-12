package io.github.dailystruggle.rtp.folia.entity;

import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.bukkitplatform.entity.PersonalBossBars;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
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

/**
 * Folia {@link RTPPlayer}. Identity is held strongly, the live {@link Player} only through a
 * {@link WeakReference}, so a cached copy (teleport history, cooldown records) never pins a quit
 * player. Cooldown, delay and permissions are memoised from the last live read and served when the
 * player is gone.
 */
public final class FoliaRTPPlayer implements RTPPlayer {
  private volatile WeakReference<Player> playerRef;
  private final UUID uuid;
  private final String name;
  private volatile long lastCooldown;
  private volatile long lastDelay;
  private volatile Set<String> lastPermissions = Collections.emptySet();

  @RegionThread
  public FoliaRTPPlayer(Player player) {
    this.playerRef = new WeakReference<>(player);
    this.uuid = player.getUniqueId();
    this.name = player.getName();
  }

  private FoliaRTPPlayer(FoliaRTPPlayer other) {
    this.playerRef = other.playerRef;
    this.uuid = other.uuid;
    this.name = other.name;
    this.lastCooldown = other.lastCooldown;
    this.lastDelay = other.lastDelay;
    this.lastPermissions = other.lastPermissions;
  }

  @Override
  public UUID uuid() {
    return uuid;
  }

  /** Always {@code true}: the live handle is weak, so this wrapper never pins a {@link Player}. */
  @Override
  public boolean isSelfContained() {
    return true;
  }

  @Override
  public RTPPlayer toPureData() {
    return this;
  }

  @Override
  public Object getLiveSender() {
    return player();
  }

  @Override
  @RegionThread
  public boolean hasPermission(String permission) {
    Player player = player();
    if (player != null) return player.hasPermission(permission);
    Set<String> perms = lastPermissions;
    if (perms.contains("rtp.*") || perms.contains("*")) return true;
    return permission != null && perms.contains(permission.toLowerCase(java.util.Locale.ROOT));
  }

  @Override
  @RegionThread
  public void sendMessage(String message) {
    Player player = player();
    if (player != null) SendMessage.sendMessage(player, message);
  }

  @Override
  @RegionThread
  public long cooldown() {
    Player player = player();
    if (player != null) lastCooldown = new FoliaRTPCommandSender(player).cooldown();
    return lastCooldown;
  }

  @Override
  @RegionThread
  public long delay() {
    Player player = player();
    if (player != null) lastDelay = new FoliaRTPCommandSender(player).delay();
    return lastDelay;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  @RegionThread
  public Set<String> getEffectivePermissions() {
    Player player = player();
    if (player == null) return lastPermissions;
    Set<String> perms = player.getEffectivePermissions().stream()
        .map(
            permissionAttachmentInfo -> {
              if (permissionAttachmentInfo.getValue())
                return permissionAttachmentInfo.getPermission().toLowerCase();
              else return null;
            })
        .filter(Objects::nonNull)
        .collect(Collectors.toSet());
    lastPermissions = Set.copyOf(perms);
    return perms;
  }

  @Override
  @RegionThread
  public void performCommand(RTPPlayer rtpPlayer, String command) {
    Player self = player();
    if (self == null) return;
    OfflinePlayer target = self;
    if (rtpPlayer instanceof FoliaRTPPlayer other) {
      Player p = other.player();
      if (p != null) target = p;
    } else if (rtpPlayer != null) {
      target = Bukkit.getOfflinePlayer(rtpPlayer.uuid());
    }
    final String finalCommand = SendMessage.formatNoColor(target, command);
    self.getScheduler()
        .run(
            org.bukkit.Bukkit.getPluginManager().getPlugin("RTP"),
            scheduledTask -> self.performCommand(finalCommand),
            null);
  }

  /** Shares the weak handle; never copies the live {@link Player} strongly. */
  @Override
  public RTPCommandSender clone() {
    return new FoliaRTPPlayer(this);
  }

  @Override
  @RegionThread
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    Player player = player();
    if (player == null) {
      RTP.log(Level.WARNING, "[RTP] teleport target " + name + " (" + uuid + ") is offline");
      if (to.getReservation() != null) to.getReservation().close();
      return CompletableFuture.completedFuture(false);
    }
    World world = ((FoliaRTPWorld) to.world()).world();

    // Destination: build the Location from the cached RTPLocation, carrying the
    // player's current yaw/pitch. Coordinates are pre-centered by the generation
    // pipeline (only the +0.5 block-center offset on X/Z).
    double x = to.x() + 0.5;
    double y = to.y();
    double z = to.z() + 0.5;
    Location destinationLocation = new Location(world, x, y, z, player.getLocation().getYaw(), player.getLocation().getPitch());

    // Initial teleport: Folia's native engine transfers the entity to the destination
    // region thread without waiting for a scheduler tick.
    return player.teleportAsync(destinationLocation).thenCompose(success -> {
      // On failure or offline player, close the reservation (leak guard) and bail.
      if (!success || !player.isOnline()) {
        if (to.getReservation() != null) to.getReservation().close();
        return CompletableFuture.completedFuture(false);
      }

      // Extract the chunkX (rtpLoc.getBlockX() >> 4) and chunkZ (rtpLoc.getBlockZ() >> 4).
      int chunkX = to.getBlockX() >> 4;
      int chunkZ = to.getBlockZ() >> 4;

      CompletableFuture<Boolean> completionFuture = new CompletableFuture<>();

      // Build-and-secure task: physically build the destination blocks (also closes the
      // reservation in the Folia impl), then hop to the entity scheduler.
      Runnable buildPlatformTask = () -> {
        try {
          to.world().platform(to);

          // S-004: a retired entity (player quit) never runs the task, so the retired
          // callback and a null schedule result must both fail the future, not hang it.
          Object scheduled = player.getScheduler().run((Plugin) RTP.getInstance().getPlugin(), task -> {
            // On the entity scheduler: reset fall distance and micro-rubberband (a second
            // teleportAsync) to snap the player onto the newly built platform. Any
            // slow-falling / blindness effects live in effects/default.yml (postteleport
            // fallback group), configurable without a code change.
            player.setFallDistance(0.0f);
            player.teleportAsync(destinationLocation)
                .whenComplete((s, err) -> completionFuture.complete(err == null && Boolean.TRUE.equals(s)));
          }, () -> completionFuture.complete(false));
          if (scheduled == null) completionFuture.complete(false);
        } catch (Exception e) {
          completionFuture.complete(false);
          if (to.getReservation() != null) to.getReservation().close();
        }
      };

      // Bypass the 50ms RegionScheduler delay via the RTP scheduler, which runs inline
      // when the current thread already owns the target region.
      RTP.serverAccessor.getScheduler().runTask(to.world(), chunkX, chunkZ, buildPlatformTask);
      return completionFuture;
    });
  }

  @Override
  @RegionThread
  public RTPLocation getLocation() {
    Player player = player();
    if (player == null) return null;
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
    Player player = player();
    return player != null && player.isOnline();
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
    Player player = player();
    if (player == null) return;
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
    Player player = player();
    if (player == null) return;
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
    Player player = player();
    if (player == null) return;
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

  @Override
  public void showProgressBar(String id, String title, double progress) {
    if (id == null) return;
    double clamped = Math.max(0.0, Math.min(1.0, progress));
    PersonalBossBars.show(player(), id, sanitizeBarTitle(title), barColorFromTemplate(title), clamped);
  }

  @Override
  public void clearProgressBar(String id) {
    PersonalBossBars.clear(uuid, id);
  }

  /**
   * Strips legacy {@code &x} / {@code §x} color codes and {@code #RRGGBB} / {@code &#RRGGBB} hex codes
   * from a bar title (boss-bar titles render as plain text on most clients) and truncates to Bukkit's
   * 64-character limit. Regex-free: runs on every bar update.
   */
  private static String sanitizeBarTitle(String title) {
    if (title == null) return "";
    String out = io.github.dailystruggle.rtp.common.text.LegacyColorStrip.strip(title);
    return out.length() > 64 ? out.substring(0, 64) : out;
  }

  /**
   * Maps the first legacy color code ({@code &x}) found in {@code template} to a Bukkit
   * {@link org.bukkit.boss.BarColor}. Returns {@code GREEN} when no recognizable code is present.
   */
  private static org.bukkit.boss.BarColor barColorFromTemplate(String template) {
    if (template == null) return org.bukkit.boss.BarColor.GREEN;
    for (int i = 0; i + 1 < template.length(); i++) {
      if (template.charAt(i) != '&') continue;
      char c = Character.toLowerCase(template.charAt(i + 1));
      switch (c) {
        case '4':
        case 'c':
          return org.bukkit.boss.BarColor.RED;
        case '6':
        case 'e':
          return org.bukkit.boss.BarColor.YELLOW;
        case '2':
        case 'a':
          return org.bukkit.boss.BarColor.GREEN;
        case '1':
        case '3':
        case '9':
        case 'b':
          return org.bukkit.boss.BarColor.BLUE;
        case '5':
        case 'd':
          return org.bukkit.boss.BarColor.PURPLE;
        case 'f':
          return org.bukkit.boss.BarColor.WHITE;
        default:
          break;
      }
    }
    return org.bukkit.boss.BarColor.GREEN;
  }

  // --- Per-player view distance (ADR-072) ----------------------------------
  // Resolved reflectively for the same reason as the Bukkit adapter, and called
  // on the owning region thread (the clamp/restore is dispatched via the entity
  // scheduler), so mutating player state here is region-safe.
  private static final java.lang.reflect.Method GET_VIEW_DISTANCE;
  private static final java.lang.reflect.Method SET_VIEW_DISTANCE;

  static {
    java.lang.reflect.Method get = null;
    java.lang.reflect.Method set = null;
    try {
      get = Player.class.getMethod("getViewDistance");
    } catch (Throwable ignored) {
      // older API without per-player view distance; feature no-ops
    }
    try {
      set = Player.class.getMethod("setViewDistance", int.class);
    } catch (Throwable ignored) {
      // older API without per-player view distance; feature no-ops
    }
    GET_VIEW_DISTANCE = get;
    SET_VIEW_DISTANCE = set;
  }

  @Override
  @RegionThread
  public int getViewDistance() {
    Player player = player();
    if (player == null || GET_VIEW_DISTANCE == null) return -1;
    try {
      Object v = GET_VIEW_DISTANCE.invoke(player);
      return (v instanceof Integer) ? (Integer) v : -1;
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] getViewDistance unavailable for " + uuid(), t);
      return -1;
    }
  }

  @Override
  @RegionThread
  public void setViewDistance(int viewDistance) {
    Player player = player();
    if (player == null || SET_VIEW_DISTANCE == null) return;
    try {
      SET_VIEW_DISTANCE.invoke(player, viewDistance);
    } catch (Throwable t) {
      RTP.log(Level.FINE, "[RTP] setViewDistance(" + viewDistance + ") failed for " + uuid(), t);
    }
  }

  @Override
  @RegionThread
  public int getSendViewDistance() {
    // folia-api carries the paper-api surface, so call the per-player send-view-distance getter
    // directly (no reflection needed).
    Player player = player();
    return player != null ? player.getSendViewDistance() : -1;
  }

  @Override
  @RegionThread
  public void setSendViewDistance(int viewDistance) {
    // Pinning the client send distance (ADR-072) lets the tracking view distance clamp/ramp
    // without the client ever seeing a view-distance change, removing the arrival "flash".
    Player player = player();
    if (player != null) player.setSendViewDistance(viewDistance);
  }

  /** The live player, re-resolved by UUID once the weak handle clears; {@code null} if offline. */
  Player player() {
    Player p = playerRef.get();
    if (p != null) return p;
    if (Bukkit.getServer() == null) return null;
    p = Bukkit.getPlayer(uuid);
    if (p != null) playerRef = new WeakReference<>(p);
    return p;
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
   *         the runnable is not silently dropped - see REQ-RTP-S-004).
   */
  public boolean scheduleOnSelf(Runnable task, Runnable retired, long delayTicks) {
    Player player = player();
    if (player == null) return false;
    Plugin plugin = (Plugin) RTP.getInstance().getPlugin();
    if (plugin == null || !plugin.isEnabled()) return false;
    Object scheduled = player
        .getScheduler()
        .runDelayed(plugin, st -> {
          if (player.isValid() && !player.isDead()) {
            task.run();
          }
        }, retired, Math.max(1, delayTicks));
    return scheduled != null;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    FoliaRTPPlayer that = (FoliaRTPPlayer) obj;
    return Objects.equals(this.uuid, that.uuid);
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid);
  }

  @Override
  public String toString() {
    return "FoliaRTPPlayer[" + "uuid=" + uuid + ", name=" + name + ']';
  }
}
