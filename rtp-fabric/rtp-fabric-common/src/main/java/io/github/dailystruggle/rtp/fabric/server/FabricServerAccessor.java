package io.github.dailystruggle.rtp.fabric.server;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer;
import io.github.dailystruggle.rtp.fabric.scheduling.FabricScheduler;
import io.github.dailystruggle.rtp.fabric.world.FabricRTPWorld;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import io.github.dailystruggle.rtp.api.RTPAPI;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/**
 * Fabric platform implementation of {@link RTPServerAccessor}.
 *
 * <p><b>Phase 2 status:</b> Step B (this class) wires {@link #getLocationGenerator()} so
 * the teleport pipeline can resolve a generator from the Fabric adapter. Every other
 * abstract method currently fails loud with {@link UnsupportedOperationException},
 * tagged with the implementation step that owns it (see
 * {@code docs/dev/MULTI_PLATFORM_PLAN.md} Phase 2 Steps A&ndash;H).
 *
 * <p><b>Why fail-loud:</b> per <b>REQ-RTP-S-006</b> ("require-by-contract API entry
 * points"), addons calling an unimplemented method must see an immediate
 * {@code IllegalStateException}/{@code UnsupportedOperationException} rather than a
 * silent null/no-op. This is identical to the Bukkit-family contract.
 *
 * <p><b>No Bukkit imports.</b> ADR-022 &sect;4 invariant.
 */
public final class FabricServerAccessor implements RTPServerAccessor {

  // ---------------------------------------------------------------------------
  // Step E2 state — populated by FabricEventBridge.
  // Maps are platform-internal; getRTPWorld/getPlayer expose immutable views.
  // ---------------------------------------------------------------------------

  private final FabricScheduler scheduler = new FabricScheduler();
  private final Map<UUID, FabricRTPPlayer> playersById = new ConcurrentHashMap<>();
  private final Map<String, FabricRTPPlayer> playersByName = new ConcurrentHashMap<>();
  private final Map<String, FabricRTPWorld> worldsByName = new ConcurrentHashMap<>();
  private final Map<UUID, FabricRTPWorld> worldsById = new ConcurrentHashMap<>();
  private volatile @Nullable MinecraftServer server;

  /** Called by {@code FabricEventBridge} on SERVER_STARTED. */
  public void bindServer(MinecraftServer server) {
    this.server = server;
    this.scheduler.setServer(server);
  }

  /** Called by {@code FabricEventBridge} on SERVER_STOPPING. */
  public void unbindServer() {
    this.scheduler.clearServer();
    this.server = null;
    this.playersById.clear();
    this.playersByName.clear();
    this.worldsByName.clear();
    this.worldsById.clear();
  }

  /** Called by {@code FabricEventBridge} on world load. */
  public FabricRTPWorld registerWorld(ServerLevel level) {
    FabricRTPWorld w = new FabricRTPWorld(level);
    worldsByName.put(w.name(), w);
    worldsById.put(w.id(), w);
    return w;
  }

  /** Called by {@code FabricEventBridge} on world unload. */
  public void unregisterWorld(ServerLevel level) {
    String name = level.dimension().location().toString();
    FabricRTPWorld removed = worldsByName.remove(name);
    if (removed != null) worldsById.remove(removed.id());
  }

  /** Called by {@code FabricEventBridge} on player JOIN. */
  public FabricRTPPlayer registerPlayer(net.minecraft.server.level.ServerPlayer player) {
    FabricRTPPlayer existing = playersById.get(player.getUUID());
    if (existing != null) {
      existing.rebind(player);
      return existing;
    }
    FabricRTPPlayer wrapper = new FabricRTPPlayer(player);
    playersById.put(wrapper.uuid(), wrapper);
    playersByName.put(wrapper.name(), wrapper);
    return wrapper;
  }

  /** Called by {@code FabricEventBridge} on player DISCONNECT. */
  public void unregisterPlayer(UUID uuid) {
    FabricRTPPlayer removed = playersById.remove(uuid);
    if (removed != null) {
      playersByName.remove(removed.name());
      removed.unbind();
    }
  }

  // ---------------------------------------------------------------------------
  // Step B - implemented (REQ-RTP-S-006)
  // ---------------------------------------------------------------------------

  @Override
  public ILocationGenerator getLocationGenerator() {
    // Mirror AbstractServerAccessor.getLocationGenerator() (rtp-spigot-common):
    // each call constructs a fresh generator backed by rtp-core's queue manager.
    // RTP.getInstance() is the require-by-contract gate (REQ-RTP-S-006); calling
    // before rtp-core is loaded must throw, not no-op.
    if (RTP.getInstance() == null) {
      throw new IllegalStateException(
          "[RTP] FabricServerAccessor.getLocationGenerator() called before rtp-core is initialized."
              + " This violates REQ-RTP-S-006 (require-by-contract API entry points).");
    }
    return new LocationGenerator();
  }

  // ---------------------------------------------------------------------------
  // Stubs - implementations land in later Phase 2 steps. Each throws with a
  // deterministic message so that any code path reaching them surfaces the gap
  // loudly during the Step H dual-runtime smoke test.
  // ---------------------------------------------------------------------------

  private static UnsupportedOperationException notYet(String method, String step) {
    return new UnsupportedOperationException(
        "[RTP] FabricServerAccessor." + method + " not yet implemented (Step " + step
            + " of MULTI_PLATFORM_PLAN.md).");
  }

  @Override
  public String getServerVersion() {
    MinecraftServer s = server;
    return s == null ? "unknown" : s.getServerVersion();
  }

  @Override
  public String getPluginVersion() {
    return FabricLoader.getInstance().getModContainer("rtp")
        .map(c -> c.getMetadata().getVersion().getFriendlyString())
        .orElse("unknown");
  }

  @Override
  public String getPlatform() {
    return "fabric";
  }

  @Override
  public Integer getServerIntVersion() {
    // Mojang server version string is e.g. "1.21.1". Extract the minor as the
    // integer version Bukkit-family code uses (e.g. 21). Defensive parse.
    String v = getServerVersion();
    String[] parts = v.split("\\.");
    if (parts.length >= 2) {
      try { return Integer.parseInt(parts[1]); } catch (NumberFormatException ignored) {}
    }
    return 0;
  }

  @Override
  public RTPWorld<?> getRTPWorld(String name) {
    if (name == null) return null;
    FabricRTPWorld w = worldsByName.get(name);
    if (w != null) return w;
    // Fall back: callers may pass a bare path (e.g. "world") matching the
    // dimension's location path component. Linear scan over a small map.
    for (FabricRTPWorld candidate : worldsByName.values()) {
      if (candidate.name().equals(name) || candidate.name().endsWith(":" + name)) {
        return candidate;
      }
    }
    return null;
  }

  @Override
  public RTPWorld<?> getRTPWorld(UUID id) {
    return id == null ? null : worldsById.get(id);
  }

  @Override
  public List<RTPWorld<?>> getRTPWorlds() {
    return new ArrayList<>(worldsByName.values());
  }

  @Override
  public RTPPlayer getPlayer(UUID uuid) {
    return uuid == null ? null : playersById.get(uuid);
  }

  @Override
  public RTPPlayer getPlayer(String name) {
    return name == null ? null : playersByName.get(name);
  }

  @Override
  public RTPPlayer getConsolePlayer() {
    throw notYet("getConsolePlayer()", "F");
  }

  @Override
  public RTPCommandSender getSender(UUID uuid) {
    // Step G G1 minimal: players resolved via the wrapper map; the sentinel
    // server UUID resolves to a console sender backed by MinecraftServer.
    // Full perms-aware sender (Step F) layers atop this.
    if (uuid == null) return null;
    if (uuid.equals(RTPAPI.serverId)) {
      return new FabricConsoleSender(server);
    }
    return playersById.get(uuid);
  }

  @Override
  public long overTime() {
    throw notYet("overTime()", "C");
  }

  @Override
  public File getPluginDirectory() {
    // Step D anchor: Fabric stores per-mod config under <run>/config/<modid>/.
    // Ensure the directory exists — rtp-core's Configs ctor reads/writes
    // immediately on construction, so a missing dir would fail YAML init.
    File dir = FabricLoader.getInstance().getConfigDir().resolve("rtp").toFile();
    if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
      RTP.log(Level.WARNING,
              "FabricServerAccessor: failed to create plugin directory " + dir.getAbsolutePath());
    }
    return dir;
  }

  @Override
  public void sendMessage(UUID target, MessagesKeys msgType, String tag) {
    // Step G G1 minimal: resolve the lang template by key and forward.
    // Full multi-target / sender-relative variants remain Step E work.
    if (target == null || msgType == null) return;
    String template = lookupMessageTemplate(msgType);
    if (template == null) return;
    sendMessage(target, template, tag);
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, MessagesKeys msgType, String tag) {
    throw notYet("sendMessage(UUID, UUID, MessagesKeys, String)", "E");
  }

  @Override
  public void sendMessage(UUID target, String message, String tag) {
    // Step G G1 minimal: route to the resolved sender. tag is currently
    // ignored on Fabric — placeholder/colour-code formatting lands in Step E
    // alongside the rest of the message-formatting parity work.
    if (target == null || message == null) return;
    RTPCommandSender sender = getSender(target);
    if (sender != null) sender.sendMessage(message);
  }

  @Override
  public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
    throw notYet("sendMessageAndSuggest(UUID, String, String)", "E");
  }

  @Override
  public void sendMessage(UUID sender, UUID target, String message, String tag) {
    // Step G G1 minimal: deliver to both endpoints. Full cross-sender
    // formatting (placeholders sourced from `sender`) is a Step E follow-up.
    if (target != null) sendMessage(target, message, tag);
    if (sender != null && !sender.equals(target)) sendMessage(sender, message, tag);
  }

  @Override
  public void sendMessage(RTPCommandSender target, String message, String hover, String click,
                          String tag) {
    throw notYet("sendMessage(RTPCommandSender, ...)", "E");
  }

  @Override
  public String format(UUID player, String text) {
    return text;
  }

  @Override
  public String formatNoColor(UUID player, String text) {
    return text;
  }

  @Override
  public void log(Level level, String msg) {
    // Fall back to JUL until Step E wires Fabric's logger. Safe to call before init.
    java.util.logging.Logger.getLogger("RTP").log(level, msg);
  }

  @Override
  public void log(Level level, String msg, Throwable throwable) {
    java.util.logging.Logger.getLogger("RTP").log(level, msg, throwable);
  }

  @Override
  public void announce(String msg, String permission, String tag) {
    throw notYet("announce(String, String, String)", "E");
  }

  @Override
  public Set<String> getBiomes(RTPWorld<?> rtpWorld) {
    throw notYet("getBiomes(RTPWorld)", "E");
  }

  @Override
  public Set<String> getBiomes() {
    throw notYet("getBiomes()", "E");
  }

  @Override
  public boolean isPrimaryThread() {
    MinecraftServer s = server;
    return s != null && Thread.currentThread() == s.getRunningThread();
  }

  @Override
  public Set<String> materials() {
    throw notYet("materials()", "E");
  }

  @Override
  public void stop() {
    // Lifecycle drives this from RTPFabricMod via FabricEventBridge.
    // Clearing maps + scheduler is handled by unbindServer().
    unbindServer();
  }

  @Override
  public void start() {
    // Initialization is event-bridge driven on Fabric — see RTPFabricMod.onInitialize().
    // No-op here so RTP.start() codepaths that call this don't fail.
  }

  @Override
  public void start(Object plugin) {
    start();
  }

  @Override
  public Object getWorldBorder(String worldName) {
    throw notYet("getWorldBorder(String)", "E");
  }

  @Override
  public Object getShape(String name) {
    throw notYet("getShape(String)", "E");
  }

  @Override
  public boolean setWorldBorderFunction(Function<String, ?> function) {
    throw notYet("setWorldBorderFunction(Function)", "E");
  }

  @Override
  public boolean setShapeFunction(Function<String, ?> shapeFunction) {
    throw notYet("setShapeFunction(Function)", "E");
  }

  @Override
  public Object createTaskPipe() {
    throw notYet("createTaskPipe()", "C");
  }

  @Override
  public Object createCachePipe() {
    throw notYet("createCachePipe()", "C");
  }

  @Override
  public Object getPlugin() {
    // The closest Fabric analogue of "the plugin object" is our ModContainer.
    // Return null if the mod isn't loaded (test classpath, headless build).
    return FabricLoader.getInstance().getModContainer("rtp").orElse((ModContainer) null);
  }

  @Override
  public RTPScheduler getScheduler() {
    return scheduler;
  }

  @Override
  public double getTPS(int ticks) {
    // Fabric's default tick-rate is 20; without server access we cannot measure
    // actual TPS yet (Step C). Return the nominal value so callers gating on TPS
    // do not block the pipeline on Fabric before Step C.
    return 20.0;
  }

  @Override
  public void setBiomeGetter(Function<RTPLocation, String> getter) {
    throw notYet("setBiomeGetter(Function)", "E");
  }

  @Override
  public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
    throw notYet("setBiomesGetter(Function)", "E");
  }

  // ---------------------------------------------------------------------------
  // Step G G1 helpers
  // ---------------------------------------------------------------------------

  /** Look up a {@code messages.yml} template by key, returning null if unset. */
  private static @Nullable String lookupMessageTemplate(MessagesKeys key) {
    if (RTP.configs == null) return null;
    @SuppressWarnings("unchecked")
    io.github.dailystruggle.rtp.common.configuration.ConfigParser<MessagesKeys> lang =
        (io.github.dailystruggle.rtp.common.configuration.ConfigParser<MessagesKeys>)
            RTP.configs.getParser(MessagesKeys.class);
    if (lang == null) return null;
    Object v = lang.getConfigValue(key, "");
    return v == null ? null : v.toString();
  }

  /**
   * Minimal console sender for the Fabric server console (Step G G1).
   *
   * <p>Always reports op-level permissions (Fabric console is unconditionally
   * privileged), routes {@link #sendMessage(String)} to
   * {@link MinecraftServer#sendSystemMessage(Component)} when the server is
   * bound, falls back to {@link RTP#log} otherwise. Step F replaces the
   * permission contract with fabric-permissions-api.
   */
  private static final class FabricConsoleSender implements RTPCommandSender {
    private final @Nullable MinecraftServer server;

    FabricConsoleSender(@Nullable MinecraftServer server) {
      this.server = server;
    }

    @Override public UUID uuid() { return RTPAPI.serverId; }
    @Override public String name() { return "Console"; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public Set<String> getEffectivePermissions() { return java.util.Collections.emptySet(); }
    @Override public long cooldown() { return 0L; }
    @Override public long delay() { return 0L; }
    @Override public void performCommand(@Nullable RTPPlayer player, String command) {
      MinecraftServer s = server;
      if (s != null && command != null) {
        s.getCommands().performPrefixedCommand(s.createCommandSourceStack(), command);
      }
    }

    @Override
    public void sendMessage(String message) {
      if (message == null) return;
      MinecraftServer s = server;
      if (s != null) {
        s.sendSystemMessage(Component.literal(message));
      } else {
        RTP.log(Level.INFO, message);
      }
    }

    @Override public RTPCommandSender clone() { return new FabricConsoleSender(server); }
  }
}
