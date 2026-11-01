package io.github.dailystruggle.rtp.bukkitplatform.entity;

import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.Optional;
import io.github.dailystruggle.rtp.bukkitplatform.tools.BukkitServers;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.PlatformHookBitComponent;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
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
import org.jetbrains.annotations.Nullable;

/**
 * Bukkit-family {@link RTPPlayer}: identity and permission snapshot held strongly, the live
 * {@link Player} only through a {@link WeakReference}. A cached copy (teleport history, cooldown
 * records) can therefore never pin a quit player's {@code ServerPlayer} graph, whichever exit path
 * created it. Every live call resolves the player first and degrades to the snapshot (or a no-op)
 * when the player is gone.
 */
public class BukkitRTPPlayer implements RTPPlayer {
  private static final WeakReference<Player> NO_PLAYER = new WeakReference<>(null);

  private volatile WeakReference<Player> playerRef;
  private final UUID uuid;
  private final String name;
  private final long cooldown;
  private final long delay;
  private final Set<String> effectivePermissions;

  public BukkitRTPPlayer(Player player) {
    this.playerRef = new WeakReference<>(player);
    this.uuid = player.getUniqueId();
    this.name = player.getName();
    BukkitRTPCommandSender senderWrapper = new BukkitRTPCommandSender(player);
    this.cooldown = senderWrapper.cooldown();
    this.delay = senderWrapper.delay();
    this.effectivePermissions = senderWrapper.getEffectivePermissions();
  }

  public BukkitRTPPlayer(
      UUID uuid,
      String name,
      long cooldown,
      long delay,
      Set<String> effectivePermissions) {
    this.playerRef = NO_PLAYER;
    this.uuid = uuid;
    this.name = (name != null) ? name : uuid.toString();
    this.cooldown = cooldown;
    this.delay = delay;
    this.effectivePermissions = (effectivePermissions != null) ? Set.copyOf(effectivePermissions) : Collections.emptySet();
  }

  /** Copy sharing the same weak handle and snapshot; used by {@link #clone()} in subclasses. */
  protected BukkitRTPPlayer(BukkitRTPPlayer other) {
    this.playerRef = other.playerRef;
    this.uuid = other.uuid;
    this.name = other.name;
    this.cooldown = other.cooldown;
    this.delay = other.delay;
    this.effectivePermissions = other.effectivePermissions;
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
  public @Nullable Object getLiveSender() {
    return player();
  }

  public void unbind() {
    this.playerRef = NO_PLAYER;
  }

  public void rebind(Player player) {
    this.playerRef = (player != null) ? new WeakReference<>(player) : NO_PLAYER;
  }

  /** The online player for this UUID, or {@code null}. Re-binding only refreshes the weak handle. */
  public @Nullable Player player() {
    Player p = this.playerRef.get();
    if (p != null && p.isOnline()) return p;
    if (BukkitServers.absent()) return null;
    p = Bukkit.getPlayer(uuid);
    if (p != null) {
      this.playerRef = new WeakReference<>(p);
      return p;
    }
    return null;
  }

  /**
   * Handle for actions on the player (teleport, block changes, bars): the online player if any,
   * else the last bound one while it is still reachable elsewhere. Never strengthens the handle.
   */
  protected @Nullable Player liveOrLast() {
    Player p = player();
    return (p != null) ? p : this.playerRef.get();
  }

  @Override
  public boolean hasPermission(String permission) {
    Player p = player();
    if (p != null) return p.hasPermission(permission);
    if (effectivePermissions.contains("rtp.*") || effectivePermissions.contains("*")) return true;
    return effectivePermissions.contains(permission.toLowerCase(Locale.ROOT));
  }

  @Override
  public void sendMessage(String message) {
    Player p = player();
    if (p != null) SendMessage.sendMessage(p, message);
  }

  @Override
  public long cooldown() {
    Player p = player();
    if (p != null) return new BukkitRTPCommandSender(p).cooldown();
    return this.cooldown;
  }

  @Override
  public long delay() {
    Player p = player();
    if (p != null) return new BukkitRTPCommandSender(p).delay();
    return this.delay;
  }

  @Override
  public String name() {
    return name;
  }

  @Override
  public Set<String> getEffectivePermissions() {
    Player p = player();
    if (p != null) return new BukkitRTPCommandSender(p).getEffectivePermissions();
    return effectivePermissions;
  }

  @Override
  public void performCommand(RTPPlayer rtpPlayer, String command) {
    Player p = player();
    if (p == null) return;
    OfflinePlayer targetPlayer;
    if (rtpPlayer == null) targetPlayer = p;
    else targetPlayer = Bukkit.getOfflinePlayer(rtpPlayer.uuid());
    command = SendMessage.formatNoColor(targetPlayer, command);
    p.performCommand(command);
  }

  @Override
  public RTPCommandSender clone() {
    return new BukkitRTPPlayer(this);
  }

  private static volatile java.lang.reflect.Method cachedTeleportAsync;
  private static volatile boolean teleportAsyncResolved = false;

  private static java.lang.reflect.Method getTeleportAsyncMethod(Player p) {
    if (!teleportAsyncResolved) {
      synchronized (BukkitRTPPlayer.class) {
        if (!teleportAsyncResolved) {
          java.lang.reflect.Method m = null;
          try {
            m = p.getClass().getMethod("teleportAsync", Location.class);
          } catch (NoSuchMethodException | SecurityException | LinkageError t) {
            try {
              m = Player.class.getMethod("teleportAsync", Location.class);
            } catch (NoSuchMethodException | SecurityException | LinkageError e) {
              PlatformHookBitComponent.recordPlayerHookDegraded(
                  "Player#teleportAsync", e.getClass().getSimpleName() + ": " + e.getMessage());
              m = null;
            }
          }
          cachedTeleportAsync = m;
          teleportAsyncResolved = true;
        }
      }
    }
    return cachedTeleportAsync;
  }

  static void resetTeleportAsyncResolutionForTesting() {
    synchronized (BukkitRTPPlayer.class) {
      cachedTeleportAsync = null;
      teleportAsyncResolved = false;
    }
  }

  @Override
  public CompletableFuture<Boolean> setLocation(RTPLocation to) {
    World world = ((BukkitRTPWorld) to.world()).world();
    double x = to.x() + 0.5;
    double y = to.y();
    double z = to.z() + 0.5;
    Player player = liveOrLast();
    if (player == null) {
      RTP.log(Level.WARNING, "[RTP] teleport target " + name + " (" + uuid + ") is offline");
      return CompletableFuture.completedFuture(false);
    }
    Optional<Location> current = BukkitServers.location(player);
    Location location =
        new Location(
            world,
            x,
            y,
            z,
            current.map(Location::getYaw).orElse(0f),
            current.map(Location::getPitch).orElse(0f));

    CompletableFuture<Boolean> future = new CompletableFuture<>();

    // Async teleport path (Paper / Folia / Purpur): Paper and Folia provide Entity#teleportAsync(Location),
    // which loads the target chunk asynchronously and avoids synchronous chunk loads (S-005) or cross-region
    // exceptions on Folia. We route through it reflectively here because this module compiles against the
    // Spigot API (which lacks teleportAsync at compile time). On pure Spigot / CraftBukkit where teleportAsync
    // is absent, we fall back to synchronous player.teleport(Location) on the main thread.
    java.lang.reflect.Method asyncMethod = getTeleportAsyncMethod(player);
    if (asyncMethod != null) {
      if (tryDirectTeleport(location, to.x(), to.z(), future)) return future;
      try {
        Object result = asyncMethod.invoke(player, location);
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
        PlatformHookBitComponent.recordPlayerHookDegraded("Player#teleportAsync", e);
        RTP.log(Level.WARNING, "[RTP] teleportAsync unavailable, falling back to sync teleport", e);
      }
    }

    // Default path: legacy Spigot mandates that entity teleportation occurs on the main thread.
    Runnable tpTask = () -> future.complete(player.teleport(location));
    RTPRuntime.scheduler().runTask(tpTask);
    return future;
  }

  /**
   * Same-tick teleport when {@link TeleportPathSelector} allows it, saving the tick that
   * {@code teleportAsync} spends in Paper's main-thread queue. Completes {@code future} with the
   * teleport result and returns true; returns false (future untouched) when the async path must run,
   * including after an inline throw (logged, S-004).
   */
  protected boolean tryDirectTeleport(
      Location location, int blockX, int blockZ, CompletableFuture<Boolean> future) {
    if (!TeleportPathSelector.canTeleportDirect(location.getWorld(), blockX, blockZ)) return false;
    Player player = liveOrLast();
    if (player == null) return false;
    boolean success;
    try {
      success = player.teleport(location);
    } catch (RuntimeException | LinkageError t) {
      PlatformHookBitComponent.recordPlayerHookDegraded("Player#teleport", t);
      RTP.log(Level.WARNING, "[RTP] direct teleport threw, falling back to teleportAsync", t);
      return false;
    }
    future.complete(success);
    return true;
  }

  @Override
  public RTPLocation getLocation() {
    Player player = liveOrLast();
    if (player == null) return null;
    Location location = player.getLocation();
    return new RTPLocation(
        RTPRuntime.serverAccessor().getRTPWorld(player.getWorld().getUID()),
        location.getBlockX(),
        location.getBlockY(),
        location.getBlockZ());
  }

  @Override
  public boolean isOnline() {
    Player p = player();
    return p != null && p.isOnline();
  }

  @Override
  @SuppressWarnings("deprecation")
  public void setRespawnLocation(RTPLocation to) {
    // BetterRTP SetAsRespawn parity: anchor the player's bed/respawn point to the
    // landed location. setBedSpawnLocation(Location, force=true) is used over the
    // newer setRespawnLocation(...) for compatibility with the Spigot API this
    // module compiles against; the force flag persists the anchor even with no bed.
    Player player = liveOrLast();
    if (player == null) return;
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
    Player player = liveOrLast();
    if (player == null) return;
    World world = ((BukkitRTPWorld) location.world()).world();
    if (!world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) return;
    BlockData parsed = parseBlockData(blockData);
    if (parsed == null) return;
    player.sendBlockChange(new Location(world, location.x(), location.y(), location.z()), parsed);
  }

  @Override
  public void sendClientBlockChanges(Map<RTPLocation, String> changes) {
    if (changes == null || changes.isEmpty()) return;
    Player player = liveOrLast();
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
      World world = ((BukkitRTPWorld) location.world()).world();
      if (!world.isChunkLoaded(location.x() >> 4, location.z() >> 4)) continue;
      BlockData parsed = parseBlockData(blockData);
      if (parsed == null) continue;
      Block block = world.getBlockAt(location.x(), location.y(), location.z());
      BlockState state = block.getState();
      state.setBlockData(parsed);
      states.add(state);
    }
    if (states.isEmpty()) return;
    if (BULK_BLOCK_CHANGES) {
      player.sendBlockChanges(states);
    } else {
      for (BlockState state : states) {
        player.sendBlockChange(state.getLocation(), state.getBlockData());
      }
    }
  }

  /** {@code Player#sendBlockChanges(Collection)} is absent from the 1.18.2 API; older servers send one by one (ADR-113). */
  private static final boolean BULK_BLOCK_CHANGES = hasBulkBlockChanges();

  private static boolean hasBulkBlockChanges() {
    try {
      Player.class.getMethod("sendBlockChanges", java.util.Collection.class);
      return true;
    } catch (NoSuchMethodException | SecurityException | LinkageError e) {
      PlatformHookBitComponent.recordPlayerHookDegraded(
          "Player#sendBlockChanges", e.getClass().getSimpleName() + ": " + e.getMessage());
      return false;
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
    PersonalBossBars.show(liveOrLast(), id, sanitizeBarTitle(title), barColorFromTemplate(title), clamped);
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
  // Resolved reflectively so this Spigot-classpath module compiles against
  // spigot-api 1.20.1 regardless of whether Player#getViewDistance /
  // Player#setViewDistance are present at compile time. Both exist on modern
  // Spigot/Paper/Folia; on a platform without them the methods stay null and
  // the feature silently no-ops (getViewDistance() -> -1 "unsupported").
  private static final java.lang.reflect.Method GET_VIEW_DISTANCE;
  private static final java.lang.reflect.Method SET_VIEW_DISTANCE;

  static {
    java.lang.reflect.Method get = null;
    java.lang.reflect.Method set = null;
    try {
      get = Player.class.getMethod("getViewDistance");
    } catch (NoSuchMethodException | SecurityException | LinkageError e) {
      PlatformHookBitComponent.recordPlayerHookDegraded(
          "Player#getViewDistance", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    try {
      set = Player.class.getMethod("setViewDistance", int.class);
    } catch (NoSuchMethodException | SecurityException | LinkageError e) {
      PlatformHookBitComponent.recordPlayerHookDegraded(
          "Player#setViewDistance", e.getClass().getSimpleName() + ": " + e.getMessage());
    }
    GET_VIEW_DISTANCE = get;
    SET_VIEW_DISTANCE = set;
  }

  @Override
  public int getViewDistance() {
    Player p = player();
    if (p == null || GET_VIEW_DISTANCE == null) return -1;
    try {
      Object v = GET_VIEW_DISTANCE.invoke(p);
      return (v instanceof Integer) ? (Integer) v : -1;
    } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
      PlatformHookBitComponent.recordPlayerHookDegraded(
          "Player#getViewDistance", t.getClass().getSimpleName() + ": " + t.getMessage());
      RTP.log(Level.FINE, "[RTP] getViewDistance unavailable for " + uuid(), t);
      return -1;
    }
  }

  @Override
  public void setViewDistance(int viewDistance) {
    Player p = player();
    if (p == null || SET_VIEW_DISTANCE == null) return;
    try {
      SET_VIEW_DISTANCE.invoke(p, viewDistance);
    } catch (ReflectiveOperationException | RuntimeException | LinkageError t) {
      PlatformHookBitComponent.recordPlayerHookDegraded(
          "Player#setViewDistance", t.getClass().getSimpleName() + ": " + t.getMessage());
      RTP.log(Level.FINE, "[RTP] setViewDistance(" + viewDistance + ") failed for " + uuid(), t);
    }
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || !(obj instanceof RTPPlayer)) return false;
    RTPPlayer that = (RTPPlayer) obj;
    return Objects.equals(this.uuid, that.uuid());
  }

  @Override
  public int hashCode() {
    return Objects.hash(uuid);
  }

  @Override
  public String toString() {
    return "BukkitRTPPlayer[" + "uuid=" + uuid + ", name=" + name + ", bound=" + (playerRef.get() != null) + ']';
  }
}
