package io.github.dailystruggle.rtp.fabric.server;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.fabric.player.FabricRTPPlayer;
import io.github.dailystruggle.rtp.fabric.scheduling.FabricScheduler;
import io.github.dailystruggle.rtp.fabric.world.FabricRTPWorld;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import io.github.dailystruggle.rtp.api.RTPAPI;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
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
  private final Map<String, WorldBorder> nativeWorldBorderCache = new ConcurrentHashMap<>();
  /**
   * World-border resolver function. Defaults to {@link #createNativeWorldBorder(String)}
   * which wraps the Fabric/Minecraft {@code net.minecraft.world.level.border.WorldBorder}
   * exposed by {@link ServerLevel#getWorldBorder()}. Replaceable via
   * {@link #setWorldBorderFunction(Function)} so addons can override per-world.
   * Mirrors {@code AbstractServerAccessor.worldBorderFunction} (Bukkit parity).
   */
  private Function<String, ?> worldBorderFunction = this::createNativeWorldBorder;
  /**
   * Shape resolver function. Defaults to {@link #createNativeShape(String)} which
   * derives a {@code SQUARE} from the world's native
   * {@code net.minecraft.world.level.border.WorldBorder} so {@code /rtp} bounds
   * match the server-configured border (in chunks) without any config required.
   * Replaceable via {@link #setShapeFunction(Function)} so addons / config can
   * override per-world. Bukkit's default is config-driven (region's shape); on
   * Fabric we prefer worldborder-derived to give a sensible out-of-the-box bound.
   */
  private Function<String, ?> shapeFunction = this::createNativeShape;

  /**
   * Per-location biome resolver. Default uses the level's dynamic biome
   * registry via {@link ServerLevel#getBiome(net.minecraft.core.BlockPos)} on
   * the server thread; addons override via {@link #setBiomeGetter(Function)}.
   * Mirrors {@code AbstractServerAccessor.biomeGetter} (Bukkit parity).
   */
  private Function<RTPLocation, String> biomeGetter = this::defaultBiomeAt;

  /**
   * Per-world biome-set resolver. Default returns every biome key registered
   * on the bound server (via {@code MinecraftServer.registryAccess()}); falls
   * back to an empty set when the server isn't bound yet. Addons override via
   * {@link #setBiomesGetter(Function)}. Mirrors
   * {@code AbstractServerAccessor.biomes} (Bukkit parity).
   */
  private Function<RTPWorld<?>, Set<String>> biomesGetter = this::defaultBiomesFor;

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
    // Bukkit parity: AbstractServerAccessor.getConsolePlayer() returns
    // getPlayer(RTPAPI.serverId). Fabric has no "console as player" — return
    // null and let callers fall back to getSender(serverId), which yields
    // FabricConsoleSender.
    return null;
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
    // Bukkit parity: AbstractServerAccessor.overTime() returns 0 (no
    // pipeline-wide deadline overshoot tracking yet). Fabric mirrors.
    return 0L;
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
    if (msgType == null) return;
    String template = lookupMessageTemplate(msgType);
    if (template == null) return;
    if (target1 != null) sendMessage(target1, template, tag);
    if (target2 != null && !target2.equals(target1)) sendMessage(target2, template, tag);
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
    // Fabric has no native click-suggestion routing on system messages without
    // building a clickable Component. Step E follow-up will replace this with
    // a Component.literal(...).withStyle(s -> s.withClickEvent(SUGGEST_COMMAND))
    // once SendMessage formatting parity lands. For now, deliver the message
    // and append the suggestion inline so it is at least visible to the player.
    if (target == null || message == null) return;
    String composed = (suggestion == null || suggestion.isEmpty())
        ? message
        : message + " " + suggestion;
    sendMessage(target, composed, null);
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
    // Step G G1 minimal: hover/click/tag annotations are dropped on Fabric
    // until Step E lands the SendMessage formatting parity that builds a
    // styled Component (HoverEvent.SHOW_TEXT / ClickEvent.SUGGEST_COMMAND).
    // Plain delivery preserves the message contract.
    if (target == null || message == null) return;
    target.sendMessage(message);
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
    // Bukkit parity: iterate online players, send to those holding the
    // permission, then route to console. Permission gating reuses the same
    // op-level fallback that FabricRTPPlayer.hasPermission uses (Step F
    // replaces with fabric-permissions-api).
    if (msg == null) return;
    for (FabricRTPPlayer p : playersById.values()) {
      if (permission == null || permission.isEmpty() || p.hasPermission(permission)) {
        p.sendMessage(msg);
      }
    }
    MinecraftServer s = server;
    if (s != null) {
      s.sendSystemMessage(Component.literal(msg));
    }
  }

  @Override
  public Set<String> getBiomes(RTPWorld<?> rtpWorld) {
    return biomesGetter.apply(rtpWorld);
  }

  @Override
  public Set<String> getBiomes() {
    return biomesGetter.apply(null);
  }

  @Override
  public boolean isPrimaryThread() {
    MinecraftServer s = server;
    return s != null && Thread.currentThread() == s.getRunningThread();
  }

  @Override
  public Set<String> materials() {
    // Mirror AbstractServerAccessor.materials(): return upper-case identifiers
    // for every block in the registry. Fabric's BuiltInRegistries.BLOCK is the
    // direct equivalent of Bukkit's Material.values() (block subset).
    Set<String> out = new HashSet<>();
    for (ResourceLocation key : BuiltInRegistries.BLOCK.keySet()) {
      out.add(key.toString().toUpperCase());
    }
    return out;
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

  /**
   * Build an RTP {@link WorldBorder} backed by the Fabric/Minecraft world's native
   * {@code net.minecraft.world.level.border.WorldBorder}. Mirrors
   * {@code AbstractServerAccessor.createNativeWorldBorder} so /rtp respects the
   * server-configured border out of the box on Fabric.
   *
   * <p>Shape supplier produces a {@code SQUARE} sized from {@code border.getSize()/32.0}
   * (border diameter in blocks &rarr; per-side radius in chunks) and centred at
   * {@code (border.getCenterX()/16.0, border.getCenterZ()/16.0)} (block &rarr; chunk).
   * The 32 vs 16 divisor pair matches the Bukkit reference implementation; the
   * border's diameter is twice its radius, hence /32 not /16 for radius.
   *
   * <p>Returns {@code null} if the named world is not registered (mirrors Bukkit
   * behaviour).
   */
  protected @Nullable WorldBorder createNativeWorldBorder(String worldName) {
    return nativeWorldBorderCache.computeIfAbsent(worldName, s -> {
      RTPWorld<?> rtpWorld = getRTPWorld(s);
      if (!(rtpWorld instanceof FabricRTPWorld fabricWorld)) return null;
      ServerLevel level = fabricWorld.level();
      net.minecraft.world.level.border.WorldBorder mcBorder = level.getWorldBorder();
      return new WorldBorder(
          () -> {
            Shape<?> shape =
                (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
            if (shape instanceof Square square) {
              square.set(GenericMemoryShapeParams.radius, (long) (mcBorder.getSize() / 32.0));
              square.set(GenericMemoryShapeParams.centerX, (long) (mcBorder.getCenterX() / 16.0));
              square.set(GenericMemoryShapeParams.centerZ, (long) (mcBorder.getCenterZ() / 16.0));
            }
            return shape;
          },
          rtpLocation ->
              level.getWorldBorder().isWithinBounds((double) rtpLocation.x(), (double) rtpLocation.z()));
    });
  }

  @Override
  public @Nullable Object getWorldBorder(String worldName) {
    Object res = worldBorderFunction.apply(worldName);
    if (res == null) res = createNativeWorldBorder(worldName);
    return res;
  }

  /**
   * Build a {@code SQUARE} {@link Shape} sized to the world's native
   * Minecraft {@code WorldBorder}, mirroring the shape supplier inside
   * {@link #createNativeWorldBorder(String)}. Radius is
   * {@code border.getSize()/32.0} (border diameter in blocks &rarr; per-side
   * radius in chunks); centre is {@code border.getCenter*()/16.0} (block &rarr;
   * chunk). Returns {@code null} if the named world is not registered, matching
   * the {@code @Nullable} contract on {@link RTPServerAccessor#getShape(String)}.
   */
  protected @Nullable Shape<?> createNativeShape(String worldName) {
    RTPWorld<?> rtpWorld = getRTPWorld(worldName);
    if (!(rtpWorld instanceof FabricRTPWorld fabricWorld)) return null;
    ServerLevel level = fabricWorld.level();
    net.minecraft.world.level.border.WorldBorder mcBorder = level.getWorldBorder();
    Shape<?> shape = (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
    if (shape instanceof Square square) {
      square.set(GenericMemoryShapeParams.radius, (long) (mcBorder.getSize() / 32.0));
      square.set(GenericMemoryShapeParams.centerX, (long) (mcBorder.getCenterX() / 16.0));
      square.set(GenericMemoryShapeParams.centerZ, (long) (mcBorder.getCenterZ() / 16.0));
    }
    return shape;
  }

  @Override
  public @Nullable Object getShape(String name) {
    return shapeFunction.apply(name);
  }

  @Override
  public boolean setWorldBorderFunction(Function<String, ?> function) {
    this.worldBorderFunction = function;
    return true;
  }

  @Override
  public boolean setShapeFunction(Function<String, ?> shapeFunction) {
    this.shapeFunction = shapeFunction;
    return true;
  }

  @Override
  public RTPTaskPipe createTaskPipe() {
    // Mirror AbstractServerAccessor: TimeBoundTaskPipe is the canonical default
    // for Spigot/Paper. Folia uses CountBound; Fabric's tick model is closest
    // to a single-region Folia, but for Phase 2 startup parity we use the same
    // default Bukkit ships. Step H may revisit per Folia threading nuance.
    return new TimeBoundTaskPipe();
  }

  @Override
  public Object createCachePipe() {
    return new TimeBoundTaskPipe();
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
    // Compute TPS from the server's recent average tick time. Mojang exposes
    // this via {@code MinecraftServer#getAverageTickTimeNanos()} on 1.21.x and
    // via {@code tickTimes} (long[] of ms*1000-ish) on older releases — we
    // resolve reflectively so the rtp-fabric-common module compiles against
    // any in-scope mappings without a hard dependency on a single Mojang API.
    // Falls back to the nominal 20 TPS when the server is not bound or no
    // metric is reachable, matching the &quot;don't gate the pipeline&quot;
    // contract documented on {@link RTPServerAccessor#getTPS}.
    MinecraftServer s = server;
    if (s == null) return 20.0;
    // Tickrate is configurable since 1.20.5 via /tick rate. Resolve the cap
    // reflectively too so we don't compile-pin a 1.21 method.
    double cap = 20.0;
    try {
      java.lang.reflect.Method tickRateManager = MinecraftServer.class.getMethod("tickRateManager");
      Object mgr = tickRateManager.invoke(s);
      if (mgr != null) {
        java.lang.reflect.Method tickrate = mgr.getClass().getMethod("tickrate");
        Object v = tickrate.invoke(mgr);
        if (v instanceof Float f) cap = f;
        else if (v instanceof Double d) cap = d;
      }
    } catch (Throwable ignored) {
      // Pre-1.20.5 server or relocated mapping — use the vanilla 20 default.
    }

    // Try nanos first (1.21+). Average over the requested window length isn't
    // directly exposed; the running mean field is the closest approximation
    // and is what {@code /tick} surfaces in-game.
    try {
      java.lang.reflect.Method m = MinecraftServer.class.getMethod("getAverageTickTimeNanos");
      Object v = m.invoke(s);
      if (v instanceof Long ln && ln > 0L) {
        double tps = 1_000_000_000.0 / ln;
        return Math.min(tps, cap);
      }
    } catch (Throwable ignored) {
      // Fall through to ms / tickTimes lookup.
    }
    try {
      java.lang.reflect.Method m = MinecraftServer.class.getMethod("getAverageTickTime");
      Object v = m.invoke(s);
      if (v instanceof Float f && f > 0f) {
        double tps = 1000.0 / f;
        return Math.min(tps, cap);
      }
    } catch (Throwable ignored) {
      // No accessible metric — fall through.
    }
    return cap;
  }

  @Override
  public void setBiomeGetter(Function<RTPLocation, String> getter) {
    // Mirror AbstractServerAccessor.setBiomeGetter — addons can override the
    // per-location biome resolver. Default lookup happens via the level's
    // dynamic biome registry (see #defaultBiomeAt).
    this.biomeGetter = getter;
  }

  @Override
  public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
    this.biomesGetter = getter;
  }

  // ---------------------------------------------------------------------------
  // Default biome resolvers (Bukkit parity)
  // ---------------------------------------------------------------------------

  /**
   * Resolve the biome at a given {@link RTPLocation} via the level's dynamic
   * biome registry. Returns the biome's {@code namespace:path} key, or
   * {@code null} when the world is not a {@link FabricRTPWorld} (foreign
   * impl) or the lookup is invoked off the server thread without a bound
   * server. Caller is expected to hop to the server thread when needed.
   */
  private @Nullable String defaultBiomeAt(RTPLocation location) {
    if (location == null) return null;
    RTPWorld<?> w = location.world();
    if (!(w instanceof FabricRTPWorld fw)) return null;
    ServerLevel level = fw.level();
    net.minecraft.core.BlockPos pos =
        new net.minecraft.core.BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    var holder = level.getBiome(pos);
    return holder.unwrapKey().map(k -> k.location().toString()).orElse(null);
  }

  /**
   * Return every biome key present in the bound server's biome registry. Falls
   * back to an empty set when the server is not yet bound (e.g. before
   * SERVER_STARTED) so callers get a deterministic empty result rather than
   * an NPE. The {@code rtpWorld} argument is currently ignored — Fabric's
   * biome registry is server-wide on 1.21.1; per-world filtering is a Step E
   * follow-up if needed.
   */
  private Set<String> defaultBiomesFor(@Nullable RTPWorld<?> rtpWorld) {
    MinecraftServer s = server;
    if (s == null) return Collections.emptySet();
    Registry<net.minecraft.world.level.biome.Biome> biomes =
        s.registryAccess().registryOrThrow(Registries.BIOME);
    Set<String> out = new HashSet<>();
    for (ResourceLocation key : biomes.keySet()) {
      out.add(key.toString());
    }
    return out;
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
