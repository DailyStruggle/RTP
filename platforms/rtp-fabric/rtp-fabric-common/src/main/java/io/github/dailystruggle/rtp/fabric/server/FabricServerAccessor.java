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
import io.github.dailystruggle.rtp.fabric.tools.FabricAnsiText;
import io.github.dailystruggle.rtp.fabric.world.FabricRTPWorld;
import io.github.dailystruggle.rtp.common.tools.PlaceholderProvider;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.core.registries.BuiltInRegistries;
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
  // State populated by FabricEventBridge.
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

  // Map values are typed as the rtp-api RTPPlayer interface (not FabricRTPPlayer)
  // so that per-version adapters can register a wrapper compiled against their
  // own MC mappings (see FabricVersionAdapter#createPlayer). On deobfuscated
  // runtimes (MC 26.1+) the legacy FabricRTPPlayer's bytecode cannot link, but
  // its class file still loads fine when only used as a default fallback —
  // older adapters (1.20 / 1.21) don't override createPlayer and continue to
  // use FabricRTPPlayer transparently.
  private final Map<UUID, RTPPlayer> playersById = new ConcurrentHashMap<>();
  private final Map<String, RTPPlayer> playersByName = new ConcurrentHashMap<>();
  private final Map<String, io.github.dailystruggle.rtp.api.world.RTPWorld<?>> worldsByName = new ConcurrentHashMap<>();
  private final Map<UUID, io.github.dailystruggle.rtp.api.world.RTPWorld<?>> worldsById = new ConcurrentHashMap<>();
  private volatile @Nullable MinecraftServer server;

  /** Called by {@code FabricEventBridge} on SERVER_STARTED. */
  public void bindServer(MinecraftServer server) {
    this.server = server;
    this.scheduler.setServer(server);
    // Registries (BuiltInRegistries.BLOCK and per-block tag bindings) are only
    // fully populated by the time SERVER_STARTED fires. Pre-server rebuild
    // attempts (driven by Configs.reloadConfigs → SafetyTokenExpander during
    // onInitialize) intentionally returned an empty snapshot without sticky-
    // flagging; rebuild now so #tag tokens in safety.airBlocks/unsafeBlocks
    // get flattened against the live registry. Any failure here is logged
    // inside buildBlockTagSnapshot and falls back to the existing empty-map
    // behaviour, so mod startup is never blocked.
    try {
      rebuildBlockTagSnapshot();
    } catch (Throwable t) {
      log(Level.WARNING,
          "[RTP][Fabric] Post-SERVER_STARTED block-tag snapshot rebuild failed: "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  /**
   * Return the bound {@link MinecraftServer}, or {@code null} when the server
   * has not yet started (pre-SERVER_STARTED) or has stopped. Exposed so
   * post-teleport hooks (effects, console/player command dispatch) can route
   * through the server thread without each call site re-deriving the server
   * reference.
   */
  public @Nullable MinecraftServer getServer() {
    return this.server;
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
    io.github.dailystruggle.rtp.api.world.RTPWorld<?> removed = worldsByName.remove(name);
    if (removed != null) worldsById.remove(removed.id());
  }

  /**
   * Object-typed entry point for {@code FabricEventBridge.onServerStarted}'s reflective-free
   * world iteration path. Avoids pinning {@code net.minecraft.server.level.ServerLevel}
   * (intermediary {@code class_3218}) into the bridge's bytecode constant pool, which fails
   * JVM verify on MC 26.1's deobfuscated runtime where the intermediary name is not exposed.
   * The cast lives here, in the accessor, where {@code ServerLevel} is already on the
   * resolved-class set (the class compiles fine on every supported MC version).
   */
  public io.github.dailystruggle.rtp.api.world.RTPWorld<?> registerWorldObject(Object level) {
    if (level == null) return null;
    Class<?> serverLevelCls;
    try {
      serverLevelCls = Class.forName("net.minecraft.server.level.ServerLevel");
    } catch (Throwable t) {
      return null; // ServerLevel not resolvable on this runtime (e.g. 26.1 deobf) — skip.
    }
    if (!serverLevelCls.isInstance(level)) return null;

    // PRIMARY PATH: route through the active version adapter's createWorld
    // factory. Per-version modules compiled against the runtime's own
    // mappings (e.g. rtp-fabric-v26_1_R1) ship a wrapper whose bytecode
    // links cleanly. See FabricVersionAdapter#createWorld.
    try {
      io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
          io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
      if (adapter != null) {
        io.github.dailystruggle.rtp.api.world.RTPWorld<?> wrapped = adapter.createWorld(level);
        if (wrapped != null) {
          worldsByName.put(wrapped.name(), wrapped);
          worldsById.put(wrapped.id(), wrapped);
          RTP.log(Level.INFO,
              "[RTP][Fabric] Registered world " + wrapped.name()
                  + " (id=" + wrapped.id() + ") via adapter "
                  + adapter.mcVersion() + ".");
          return wrapped;
        }
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP][Fabric] registerWorldObject: adapter.createWorld threw "
              + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
      // fall through to legacy path
    }

    // FALLBACK PATH (1.20 / 1.21 obf-era runtimes): construct the legacy
    // FabricRTPWorld via the existing typed registerWorld((ServerLevel) level)
    // — invoked reflectively so this method's own constant pool stays free
    // of intermediary aliases.
    try {
      java.lang.reflect.Method m = FabricServerAccessor.class.getDeclaredMethod("registerWorld", serverLevelCls);
      Object result = m.invoke(this, level);
      return (io.github.dailystruggle.rtp.api.world.RTPWorld<?>) result;
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP][Fabric] registerWorldObject: legacy reflective dispatch threw "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
      return null;
    }
  }

  /** See {@link #registerWorldObject(Object)}. */
  public void unregisterWorldObject(Object level) {
    if (level == null) return;
    Class<?> serverLevelCls;
    try {
      serverLevelCls = Class.forName("net.minecraft.server.level.ServerLevel");
    } catch (Throwable t) {
      return;
    }
    if (!serverLevelCls.isInstance(level)) return;
    try {
      java.lang.reflect.Method m = FabricServerAccessor.class.getDeclaredMethod("unregisterWorld", serverLevelCls);
      m.invoke(this, level);
    } catch (Throwable t) {
      // swallow — sticky-fail behaviour matches register path.
    }
  }

  /** Called by {@code FabricEventBridge} on player JOIN. */
  public RTPPlayer registerPlayer(net.minecraft.server.level.ServerPlayer player) {
    RTPPlayer existing = playersById.get(player.getUUID());
    if (existing != null) {
      if (existing instanceof FabricRTPPlayer fp) fp.rebind(player);
      return existing;
    }
    FabricRTPPlayer wrapper = new FabricRTPPlayer(player);
    playersById.put(wrapper.uuid(), wrapper);
    playersByName.put(wrapper.name(), wrapper);
    return wrapper;
  }

  /** Called by {@code FabricEventBridge} on player DISCONNECT. */
  public void unregisterPlayer(UUID uuid) {
    RTPPlayer removed = playersById.remove(uuid);
    if (removed != null) {
      playersByName.remove(removed.name());
      if (removed instanceof FabricRTPPlayer fp) fp.unbind();
    }
  }

  /**
   * Object-typed entry point for the Fabric event bridge's JOIN proxy.
   * Avoids pinning {@code net.minecraft.server.level.ServerPlayer} (intermediary
   * {@code class_3222}) into the bridge's bytecode constant pool — that alias
   * is absent on MC 26.1.2's deobfuscated runtime and would fail JVM verify.
   * The cast lives here in the accessor where {@code ServerPlayer} resolves
   * fine on every supported MC version.
   */
  public RTPPlayer registerPlayerObject(Object player) {
    if (player == null) return null;
    // All player wrapping is delegated to the active FabricVersionAdapter SPI:
    //   - getPlayerUUID(Object) -> typed ServerPlayer.getUUID() per adapter.
    //   - createPlayer(Object)  -> per-version-typed RTPPlayer construction.
    //   - rebindPlayer(...)     -> re-bind existing wrapper on re-JOIN.
    // The previous name+arity reflective scan into FabricServerAccessor.
    // registerPlayer(ServerPlayer) has been removed (Rule D-005 refactor,
    // 2026-05-10): every shipped per-version adapter now overrides
    // createPlayer / rebindPlayer, so there is no surface left for a typed
    // fallback to handle. If the registered adapter does not implement the
    // player SPI, registration fails loud-but-survive (REQ-RTP-S-006).
    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
    if (adapter == null) {
      RTP.log(Level.WARNING,
          "[RTP][Fabric] registerPlayerObject: no FabricVersionAdapter registered; "
              + "player " + player.getClass().getName() + " not registered.");
      return null;
    }
    try {
      UUID uuid = adapter.getPlayerUUID(player);
      if (uuid == null) {
        // No-adapter-knowledge fallback for an exotic player object (e.g. a
        // test double): try the mojmap method name reflectively. On a real
        // server this branch is dead because every shipped adapter overrides
        // getPlayerUUID.
        try {
          java.lang.reflect.Method getUUID = player.getClass().getMethod("getUUID");
          uuid = (UUID) getUUID.invoke(player);
        } catch (NoSuchMethodException nsme) {
          RTP.log(Level.WARNING,
              "[RTP][Fabric] registerPlayerObject: adapter " + adapter.mcVersion()
                  + " returned null UUID and " + player.getClass().getName()
                  + " exposes no getUUID() method; player not registered.");
          return null;
        }
      }
      RTPPlayer existing = playersById.get(uuid);
      if (existing != null) {
        adapter.rebindPlayer(existing, player);
        return existing;
      }
      RTPPlayer wrapped = adapter.createPlayer(player);
      if (wrapped == null) {
        RTP.log(Level.WARNING,
            "[RTP][Fabric] registerPlayerObject: adapter " + adapter.mcVersion()
                + ".createPlayer returned null for " + player.getClass().getName()
                + "; player not registered.");
        return null;
      }
      playersById.put(wrapped.uuid(), wrapped);
      playersByName.put(wrapped.name(), wrapped);
      RTP.log(Level.INFO,
          "[RTP][Fabric] Registered player " + wrapped.name()
              + " (uuid=" + wrapped.uuid() + ") via adapter "
              + adapter.mcVersion() + ".");
      return wrapped;
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP][Fabric] registerPlayerObject: adapter dispatch threw "
              + t.getClass().getSimpleName() + ": " + t.getMessage(), t);
      return null;
    }
  }

  /**
   * Object-typed entry point for the Fabric event bridge's DISCONNECT proxy.
   * Extracts the player's UUID reflectively (via {@code getUUID()}) so the
   * caller never names {@code ServerPlayer} in its bytecode.
   */
  public void unregisterPlayerObject(Object player) {
    if (player == null) return;
    try {
      // Prefer the typed FabricVersionAdapter.getPlayerUUID SPI so Loom
      // remaps the descriptor per-runtime (mojmap getUUID on 26.x; intermediary
      // method_5667 on 1.20/1.21.x). Reflective getMethod("getUUID") fails on
      // intermediary because the mojmap method name is obfuscated there.
      io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
          io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
      UUID uuid = adapter != null ? adapter.getPlayerUUID(player) : null;
      if (uuid == null) {
        try {
          java.lang.reflect.Method getUuid = player.getClass().getMethod("getUUID");
          Object u = getUuid.invoke(player);
          if (u instanceof UUID resolved) uuid = resolved;
        } catch (NoSuchMethodException ignored) {
          // Adapter unavailable AND mojmap name absent → cleanup deferred to
          // sweep; not fatal.
          return;
        }
      }
      if (uuid != null) unregisterPlayer(uuid);
    } catch (Throwable t) {
      // swallow — DISCONNECT cleanup is best-effort.
    }
  }

  // ---------------------------------------------------------------------------
  // REQ-RTP-S-006
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

  private static UnsupportedOperationException notYet(String method) {
    return new UnsupportedOperationException(
        "[RTP] FabricServerAccessor." + method + " not yet implemented.");
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
  public io.github.dailystruggle.rtp.api.server.PlatformFamily getPlatformFamily() {
    return io.github.dailystruggle.rtp.api.server.PlatformFamily.FABRIC;
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
    RTPWorld<?> w = worldsByName.get(name);
    if (w != null) return w;
    // Fall back: callers may pass a bare path (e.g. "world") matching the
    // dimension's location path component. Linear scan over a small map.
    for (RTPWorld<?> candidate : worldsByName.values()) {
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
    if (uuid == null) return null;
    RTPPlayer cached = playersById.get(uuid);
    if (cached != null) return cached;
    // Lazy fallback: JOIN event registration may have failed silently
    // (e.g. fabric-api version drift on 26.1+). Look up live on the server.
    return lazyResolveAndRegisterByUuid(uuid);
  }

  @Override
  public RTPPlayer getPlayer(String name) {
    if (name == null) return null;
    RTPPlayer cached = playersByName.get(name);
    if (cached != null) return cached;
    return lazyResolveAndRegisterByName(name);
  }

  /**
   * Lazy-register a player who is online on the bound {@link MinecraftServer}
   * but missing from {@link #playersByName} (typically because the JOIN event
   * proxy failed to fire on this MC/fabric-api combo). Returns {@code null}
   * if no server is bound or no player matches.
   *
   * <p>Resolved entirely reflectively so this method's bytecode never pins
   * intermediary aliases for {@code PlayerList} / {@code ServerPlayer} on
   * deobfuscated 26.1+ runtimes.</p>
   */
  private RTPPlayer lazyResolveAndRegisterByName(String name) {
    Object sp = lookupOnlineServerPlayerByName(name);
    if (sp == null) return null;
    return registerPlayerObject(sp);
  }

  private RTPPlayer lazyResolveAndRegisterByUuid(UUID uuid) {
    Object sp = lookupOnlineServerPlayerByUuid(uuid);
    if (sp == null) return null;
    return registerPlayerObject(sp);
  }

  private Object lookupOnlineServerPlayerByName(String name) {
    MinecraftServer s = this.server;
    if (s == null) return null;
    try {
      Object playerList = s.getClass().getMethod("getPlayerList").invoke(s);
      if (playerList == null) return null;
      for (String candidate : new String[]{"getPlayerByName", "getPlayer"}) {
        try {
          java.lang.reflect.Method m = playerList.getClass().getMethod(candidate, String.class);
          Object res = m.invoke(playerList, name);
          if (res != null) return res;
        } catch (NoSuchMethodException ignored) {}
      }
    } catch (Throwable t) {
      RTP.log(Level.FINE,
          "[RTP][Fabric] lazyResolveByName failed: " + t.getClass().getSimpleName()
              + ": " + t.getMessage());
    }
    return null;
  }

  private Object lookupOnlineServerPlayerByUuid(UUID uuid) {
    MinecraftServer s = this.server;
    if (s == null) return null;
    try {
      Object playerList = s.getClass().getMethod("getPlayerList").invoke(s);
      if (playerList == null) return null;
      try {
        java.lang.reflect.Method m = playerList.getClass().getMethod("getPlayer", UUID.class);
        Object res = m.invoke(playerList, uuid);
        if (res != null) return res;
      } catch (NoSuchMethodException ignored) {}
    } catch (Throwable t) {
      RTP.log(Level.FINE,
          "[RTP][Fabric] lazyResolveByUuid failed: " + t.getClass().getSimpleName()
              + ": " + t.getMessage());
    }
    return null;
  }

  /**
   * Snapshot of names of every player currently online on this Fabric server.
   *
   * <p>Used by {@code RTPCmdFabricRoot}'s {@code player} parameter to surface
   * tab-completion suggestions on the Brigadier path (commands-api-ADR-001).
   * Backed by the same {@link #playersByName} map populated by
   * {@code FabricEventBridge} on JOIN/DISCONNECT, which avoids touching the
   * raw {@link MinecraftServer} player list off the main thread (Brigadier
   * suggestion providers run off-thread).
   *
   * <p>The returned set is a defensive copy taken at call time; callers may
   * iterate without external synchronization. Empty when no server is bound.
   *
   * <p>commands-api-ADR-001 addendum (2026-05-06): unblocks the
   * {@code Collections.emptySet()} stub in {@code RTPCmdFabricRoot} `player`
   * parameter; resolves CHECKLIST-fabric-tabcompletion-audit P3.
   */
  @Override
  public Set<String> getOnlinePlayerNames() {
    if (playersByName.isEmpty()) return Collections.emptySet();
    return new HashSet<>(playersByName.keySet());
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
    // Players resolved via the wrapper map; the sentinel
    // server UUID resolves to a console sender backed by MinecraftServer.
    if (uuid == null) return null;
    if (uuid.equals(RTPAPI.serverId)) {
      return new FabricConsoleSender(server);
    }
    RTPPlayer cached = playersById.get(uuid);
    if (cached != null) return cached;
    // Same lazy fallback as getPlayer(uuid): allow command-time recovery when
    // the JOIN event proxy didn't fire (e.g. fabric-api drift on 26.1+).
    Object sp = lookupOnlineServerPlayerByUuid(uuid);
    if (sp == null) return null;
    return registerPlayerObject(sp);
  }

  @Override
  public long overTime() {
    // Bukkit parity: AbstractServerAccessor.overTime() returns 0 (no
    // pipeline-wide deadline overshoot tracking yet). Fabric mirrors.
    return 0L;
  }

  @Override
  public File getPluginDirectory() {
    // Fabric stores per-mod config under <run>/config/<modid>/.
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
    // Resolve the lang template by key and forward.
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
    // Resolve target sender, then format placeholders + legacy colour codes
    // before dispatch so messages render with the same fidelity as on
    // Bukkit/Paper. The {@code tag} field is reserved for future styled
    // tag wrapping; ignored for now.
    if (target == null || message == null) return;
    RTPCommandSender sender = getSender(target);
    if (sender == null) return;
    String formatted = format(target, message);
    sender.sendMessage(formatted);
  }

  @Override
  public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
    // Build a clickable Component with ClickEvent.SUGGEST_COMMAND so the player
    // can tap the message to prefill the suggestion in their chat input — mirrors
    // the rtp-spigot SendMessage path that uses BaseComponent#setClickEvent.
    //
    // Defensive: any failure here (e.g. mapping drift on the chat-event
    // constructors in 1.21.5+, see FabricLegacyText#parseInteractive) MUST
    // NOT truncate callers that loop over multiple lines (InfoCmd and
    // the commands-api built-in TreeCommand#help() dispatch each row via
    // forEach — a propagated exception aborts the whole iteration and
    // leaves the user with a half-printed /rtp info or /rtp help). On
    // any error, fall back to the plain-string sink.
    if (target == null || message == null) return;
    String formattedMsg = format(target, message);
    try {
      RTPPlayer player = getPlayer(target);
      // Online player that can render interactive text builds the click-to-suggest
      // Component on its own runtime bytecode (the obf FabricRTPPlayer and every
      // per-version deobf sink, e.g. V26_1_R1FabricRTPPlayer, implement
      // FabricInteractiveMessageSink). Routing through the sink keeps the
      // net.minecraft.network.chat.* (intermediary class_2561) types out of this
      // accessor's bytecode so it links on the deobf MC 26.x runtime. Deliver
      // plain exactly once otherwise - DO NOT append the suggestion inline, which
      // produced the "over-printing" regression on 26.1.
      if (player instanceof io.github.dailystruggle.rtp.fabric.player.FabricInteractiveMessageSink sink
          && player.isOnline()) {
        sink.sendInteractive(formattedMsg, null, suggestion, false);
        return;
      }
      if (player != null && player.isOnline()) {
        player.sendMessage(formattedMsg);
        return;
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] sendMessageAndSuggest interactive path failed (" + t.getClass().getSimpleName()
              + "): falling back to plain message — " + t.getMessage());
    }
    // Console / detached sender (or interactive path failure): click-to-suggest
    // can't render. Fall back to the inline composition so the suggestion
    // remains visible to non-player recipients (consoles can't click).
    String composed = (suggestion == null || suggestion.isEmpty())
        ? formattedMsg
        : formattedMsg + " " + suggestion;
    RTPCommandSender sender = getSender(target);
    if (sender != null) sender.sendMessage(composed);
  }

  @Override
  public void sendMessage(UUID sender, UUID target, String message, String tag) {
    // Deliver to both endpoints.
    if (target != null) sendMessage(target, message, tag);
    if (sender != null && !sender.equals(target)) sendMessage(sender, message, tag);
  }

  @Override
  public void sendMessage(RTPCommandSender target, String message, String hover, String click,
                          String tag) {
    sendInteractiveMessage(target, message, hover, click, false);
  }

  /**
   * ADR-050 (2026-05-24): the chat-renderer-facing sibling that dispatches
   * the click as {@code RUN_COMMAND} instead of {@code SUGGEST_COMMAND}.
   * Every menu fragment carries a literal {@code /rtp menu ...} command
   * that must auto-fire on click; suggesting it would force the operator
   * to press Enter.
   */
  @Override
  public void sendMessageWithRunCommand(RTPCommandSender target, String message,
                                        String hover, String runCommand, String tag) {
    sendInteractiveMessage(target, message, hover, runCommand, true);
  }

  /**
   * Shared rich-text dispatch used by both {@link #sendMessage(RTPCommandSender,
   * String, String, String, String)} and {@link #sendMessageWithRunCommand}.
   *
   * <p>Mirrors rtp-spigot {@code SendMessage}: when the recipient is an online
   * player on this Fabric server, build a styled {@code Component} carrying
   * {@code HoverEvent.SHOW_TEXT} and a {@code ClickEvent} flavoured by
   * {@code clickKind}, so {@code /rtp info}, {@code /rtp help}, and the menu
   * chat renderer all render hover tooltips and clickable lines identically to
   * Bukkit/Paper. Console and detached senders fall back to the plain-string
   * path - hover/click cannot render there.
   */
  private void sendInteractiveMessage(RTPCommandSender target, String message,
                                      String hover, String click,
                                      boolean run) {
    if (target == null || message == null) return;
    String formattedMsg   = format(target.uuid(), message);
    String formattedHover = (hover == null) ? null : format(target.uuid(), hover);
    // click is a raw command string; do not run placeholder substitution on it
    // (the click contract is a literal command target, not user-facing text).
    String payload        = click;
    // Defensive: see sendMessageAndSuggest. Mapping drift on 1.21.5+
    // HoverEvent/ClickEvent constructors must not abort the per-line
    // forEach loop in InfoCmd or the commands-api built-in
    // TreeCommand#help() dispatch, nor the per-fragment forEach in the
    // chat menu renderer.
    try {
      RTPPlayer player = getPlayer(target.uuid());
      // Any online player that can render interactive text builds the hover/click
      // Component on its own runtime bytecode (the obf FabricRTPPlayer and every
      // per-version deobf sink implement FabricInteractiveMessageSink). Routing
      // through the sink keeps net.minecraft.network.chat.* (intermediary
      // class_2561) out of this accessor's bytecode so it links on the deobf MC
      // 26.x runtime. Otherwise deliver the plain formatted message exactly once;
      // appending the click target inline would produce visible "over-printing"
      // noise (regression observed on Fabric 26.1).
      if (player instanceof io.github.dailystruggle.rtp.fabric.player.FabricInteractiveMessageSink sink
          && player.isOnline()) {
        sink.sendInteractive(formattedMsg, formattedHover, payload, run);
        return;
      }
      if (player != null && player.isOnline()) {
        player.sendMessage(formattedMsg);
        return;
      }
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] sendMessage(hover/click) interactive path failed ("
              + t.getClass().getSimpleName() + "): falling back to plain message - "
              + t.getMessage());
    }
    target.sendMessage(formattedMsg);
  }

  @Override
  public String format(UUID player, String text) {
    if (text == null) return "";
    UUID uuid = (player != null) ? player : RTPAPI.serverId;
    text = PlaceholderProvider.fillPlaceholders(text, uuid);
    if (langParser() != null) {
      text = PlaceholderProvider.fillNumericPlaceholders(text);
    }
    // No PAPI on Fabric. Legacy &/hex codes are converted to the
    // section-sign form here so downstream RTPCommandSender.sendMessage
    // implementations can render them as styled Components.
    return text;
  }

  @Override
  public String formatNoColor(UUID player, String text) {
    if (text == null) return "";
    UUID uuid = (player != null) ? player : RTPAPI.serverId;
    text = PlaceholderProvider.fillPlaceholders(text, uuid);
    if (langParser() != null) {
      text = PlaceholderProvider.fillNumericPlaceholders(text);
    }
    return FabricAnsiText.stripColor(text);
  }

  /** Resolve the {@code messages.yml} parser, or null before configs are loaded. */
  @SuppressWarnings("unchecked")
  private static @Nullable ConfigParser<MessagesKeys> langParser() {
    if (RTP.configs == null) return null;
    return (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
  }

  @Override
  public void log(Level level, String msg) {
    // Honor the admin-configured logging.yml#min_level gate (mirrors rtp-spigot
    // SendMessage.isLoggable). When configs aren't loaded yet, default to ALL
    // so early-startup records aren't dropped.
    if (!isLoggable(level)) return;
    // Run the same placeholder pipeline as user-facing messages so shared
    // tokens like [P0] (defined in messages.yml#placeholders, expanding to
    // the configured plugin prefix) are resolved on console too. Mirrors
    // rtp-spigot SendMessage.log, which calls format(null, message) before
    // routing to the console sender. format() gracefully skips numeric
    // [Pn] expansion when messages.yml hasn't been parsed yet — early-boot
    // log lines that reference [P0] simply pass through unsubstituted, but
    // every log emitted after configs load (e.g. the locationLoaded
    // hydration banner) renders with the prefix resolved.
    String formatted = (msg == null) ? "" : formatForLog(msg);
    // Route through Log4j2 (Minecraft's own logger framework) so the dedicated
    // server's TerminalConsoleAppender translates §-codes to ANSI on the
    // console. Apply a level-coloured prefix that mirrors Spigot's
    // SendMessage.log so SEVERE/WARNING are visually distinct.
    String ansi = FabricAnsiText.toAnsiString(prefixForLevel(level) + formatted);
    org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger("RTP");
    logger.log(toLog4jLevel(level), ansi);
  }

  @Override
  public void log(Level level, String msg, Throwable throwable) {
    if (!isLoggable(level)) return;
    String formatted = (msg == null) ? "" : formatForLog(msg);
    String ansi = FabricAnsiText.toAnsiString(prefixForLevel(level) + formatted);
    org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger("RTP");
    logger.log(toLog4jLevel(level), ansi, throwable);
  }

  /**
   * Run the {@code messages.yml} placeholder pipeline against a console-bound
   * log line, defensively swallowing any failure so log emission can never
   * abort on a placeholder lookup. Returns the original text on failure.
   */
  private String formatForLog(String msg) {
    try {
      return format(null, msg);
    } catch (Throwable t) {
      return msg;
    }
  }

  /**
   * Cached parsed {@code logging.yml#min_level} so the hot-path log gate doesn't
   * re-parse on every call. Mirrors rtp-spigot {@code SendMessage} caching.
   */
  private static volatile String cachedMinLevelRaw = "ALL";
  private static volatile Level cachedMinLevel = Level.ALL;

  /**
   * Returns true when {@code level} meets the plugin-scoped {@code logging.yml#min_level}.
   * Defaults to {@link Level#ALL} (everything passes) when the config parser isn't
   * built yet or the configured value can't be parsed — keeping the gate purely
   * additive in those cases.
   */
  private static boolean isLoggable(Level level) {
    if (level == null) return true;
    Level min = resolveMinLevel();
    return level.intValue() >= min.intValue();
  }

  @SuppressWarnings("unchecked")
  private static Level resolveMinLevel() {
    try {
      if (RTP.configs == null) return Level.ALL;
      ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys> logging =
          (ConfigParser<io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys>)
              RTP.configs.getParser(
                  io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.class);
      if (logging == null) return Level.ALL;
      Object raw =
          logging.getConfigValue(
              io.github.dailystruggle.rtp.common.configuration.enums.LoggingKeys.min_level, "ALL");
      String name = (raw == null) ? "ALL" : raw.toString().trim().toUpperCase(java.util.Locale.ROOT);
      if (name.isEmpty()) name = "ALL";
      if (name.equals(cachedMinLevelRaw)) return cachedMinLevel;
      Level parsed;
      try {
        parsed = Level.parse(name);
      } catch (IllegalArgumentException ex) {
        parsed = Level.ALL;
      }
      cachedMinLevelRaw = name;
      cachedMinLevel = parsed;
      return parsed;
    } catch (Throwable t) {
      return Level.ALL;
    }
  }

  /**
   * Map JUL level → Log4j2 level for routing.
   *
   * <p>Sub-INFO records (FINE/FINER/FINEST) only reach this method when
   * {@link #isLoggable(Level)} already accepted them — i.e. when the admin set
   * {@code logging.yml#min_level} to FINE or finer (or left it at the default
   * {@code ALL}). In that case the operator has explicitly opted into verbose
   * output, so route FINE/FINER/FINEST to Log4j {@code INFO} rather than
   * {@code DEBUG}: Minecraft's stock {@code log4j2.xml} suppresses DEBUG at
   * the console appender, which would otherwise silently drop every line that
   * passed our gate. Promoting the visible-on-purpose lines to INFO mirrors
   * the rtp-spigot behaviour, where {@code SendMessage.log} writes via
   * {@code Bukkit.getConsoleSender().sendMessage}, which does not have a
   * threshold below INFO equivalent in the appender chain.
   */
  private static org.apache.logging.log4j.Level toLog4jLevel(Level level) {
    if (level == null) return org.apache.logging.log4j.Level.INFO;
    int v = level.intValue();
    if (v >= 1000) return org.apache.logging.log4j.Level.ERROR;   // SEVERE
    if (v >= 900)  return org.apache.logging.log4j.Level.WARN;    // WARNING
    if (v >= 800)  return org.apache.logging.log4j.Level.INFO;    // INFO
    if (v >= 700)  return org.apache.logging.log4j.Level.INFO;    // CONFIG
    return org.apache.logging.log4j.Level.INFO;                   // FINE/FINER/FINEST (gated upstream)
  }

  /**
   * Mirror rtp-spigot {@code SendMessage.colorFor}: prefix log lines with a
   * level-appropriate legacy colour so console output is scannable.
   */
  private static String prefixForLevel(Level level) {
    if (level == null) return "";
    int v = level.intValue();
    if (v >= 1000) return "&c"; // SEVERE  → red
    if (v >= 900)  return "&e"; // WARNING → yellow
    if (v >= 800)  return "";   // INFO    → default
    if (v >= 700)  return "&a"; // CONFIG  → green
    if (v >= 500)  return "&b"; // FINE    → aqua
    return "";
  }

  @Override
  public void announce(String msg, String permission, String tag) {
    // Bukkit parity: iterate online players, send to those holding the
    // permission, then route to console. Permission gating reuses the same
    // op-level fallback that FabricRTPPlayer.hasPermission uses.
    if (msg == null) return;
    // Run the messages.yml placeholder pipeline so tokens such as
    // [P0], [scan_chunks], [scan_totalChunks], [scan_cps], [scan_regions],
    // and [scan_eta] are resolved before the message reaches players or
    // the console. Mirrors rtp-spigot AbstractServerAccessor.announce, which
    // routes through SendMessage.sendMessage (which formats internally).
    for (RTPPlayer p : playersById.values()) {
      if (permission == null || permission.isEmpty() || p.hasPermission(permission)) {
        p.sendMessage(format(p.uuid(), msg));
      }
    }
    // Console branch: route via Log4j2 with §-form so colour is preserved
    // (sendSystemMessage(Component) logs plain text on the dedicated server).
    String consoleMsg = format(null, msg);
    org.apache.logging.log4j.LogManager.getLogger("RTP")
        .info(FabricAnsiText.toAnsiString(consoleMsg));
  }

  @Override
  public Set<String> getBiomes(RTPWorld<?> rtpWorld) {
    return biomesGetter.apply(rtpWorld);
  }

  @Override
  public Set<String> getBiomes() {
    return biomesGetter.apply(null);
  }

  // Cached reflective accessor for MinecraftServer.getRunningThread(). Direct
  // call gets remapped by Loom+officialMojangMappings to intermediary
  // `method_3777` which is not present on the deobfuscated MC 26.1.2 runtime.
  // The resulting NoSuchMethodError, thrown inside CompletableFuture.thenAccept,
  // is silently swallowed and stalls the teleport pipeline between LOAD and
  // TELEPORT phases. Resolve by name from the live instance to keep the
  // intermediary descriptor out of this method's constant pool.
  private static volatile java.lang.reflect.Method PRIMARY_THREAD_GETTER;

  @Override
  public boolean isPrimaryThread() {
    MinecraftServer s = server;
    if (s == null) return false;
    // Prefer per-version adapter override (Phase 4 migration of the prior
    // reflective patch). Falls through to reflective lookup for adapters
    // that don't override (1.20 / 1.21 family).
    try {
      io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
          io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
      if (adapter != null) {
        Thread t = adapter.getServerThread(s);
        if (t != null) return Thread.currentThread() == t;
      }
    } catch (Throwable ignored) {
      // fall through to reflective path
    }
    try {
      java.lang.reflect.Method m = PRIMARY_THREAD_GETTER;
      if (m == null) {
        m = s.getClass().getMethod("getRunningThread");
        m.setAccessible(true);
        PRIMARY_THREAD_GETTER = m;
      }
      return Thread.currentThread() == m.invoke(s);
    } catch (ReflectiveOperationException e) {
      return false;
    }
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

  // ---------------------------------------------------------------------------
  // Block-tag snapshot (ADR-017) — Fabric mirror of
  // AbstractServerAccessor.blockTagSnapshot / rebuildBlockTagSnapshot.
  //
  // Source: BuiltInRegistries.BLOCK. Each registered block's
  // builtInRegistryHolder().tags() exposes the TagKey<Block> set assigned by
  // the data-pack loader (vanilla + datapacks). We invert that into
  // {namespace:path -> upper-case "namespace:path" material names}, matching
  // the contract documented on RTPServerAccessor#blockTagSnapshot().
  //
  // Built lazily and cached; rebuildBlockTagSnapshot() forces a fresh pass
  // (e.g. after /rtp reload or /reload). Any failure (registry not yet
  // populated, missing TagKey API on an unsupported MC version) falls through
  // to an empty map — never null — so the SafetyTokenExpander preserves the
  // original tokens for a later retry.
  // ---------------------------------------------------------------------------

  /**
   * Lazy-initialised snapshot of the Fabric/Vanilla block-tag registry,
   * published under the ADR-017 {@code namespace:path} key form. {@code volatile}
   * because the reference is swapped atomically on
   * {@link #rebuildBlockTagSnapshot()}; the map itself is deeply immutable so
   * readers can iterate without locking.
   */
  private volatile Map<String, Set<String>> blockTagSnapshot;

  /**
   * Sticky flag set after the first {@link #buildBlockTagSnapshot()} call that fails with a
   * hard linkage error (e.g. {@code NoClassDefFoundError: net/minecraft/class_7923} on MC
   * 26.1's deobfuscated runtime, where the intermediary {@code BuiltInRegistries} symbol is
   * not exposed). Once set, subsequent rebuilds short-circuit to an empty map without
   * re-attempting the registry walk and without re-logging — otherwise
   * {@code SafetyTokenExpander.scheduleRetry} produces a per-2s WARNING storm.
   */
  private volatile boolean blockTagSnapshotPermanentlyUnavailable = false;

  @Override
  public Map<String, Set<String>> blockTagSnapshot() {
    Map<String, Set<String>> snap = this.blockTagSnapshot;
    if (snap != null) return snap;
    synchronized (this) {
      snap = this.blockTagSnapshot;
      if (snap != null) return snap;
      snap = buildBlockTagSnapshot();
      this.blockTagSnapshot = snap;
      return snap;
    }
  }

  @Override
  public void rebuildBlockTagSnapshot() {
    synchronized (this) {
      this.blockTagSnapshot = buildBlockTagSnapshot();
    }
  }

  /**
   * Walk {@link BuiltInRegistries#BLOCK} and invert each block's
   * {@code builtInRegistryHolder().tags()} into a
   * {@code namespace:path -> upper-case "namespace:path"} multimap, matching
   * the canonical material form produced by {@link #materials()} and the
   * contract on {@link RTPServerAccessor#blockTagSnapshot()}.
   *
   * <p>Defensive against partially-initialised registries during early
   * mod-init: any throw produces an empty (never null) snapshot and a
   * WARNING log line, so that {@code SafetyTokenExpander} preserves original
   * {@code #tag} tokens for a later retry pass.
   */
  private Map<String, Set<String>> buildBlockTagSnapshot() {
    // Defer until SERVER_STARTED has bound a server. BuiltInRegistries.BLOCK
    // is not fully populated at mod-init time (Configs.reloadConfigs →
    // SafetyTokenExpander runs from FabricDatabaseHandler.setupDatabase
    // inside onInitialize), and walking it then yields zero tags and a
    // misleading WARNING. Return empty silently; bindServer() triggers a
    // rebuild once the registry is live.
    if (this.server == null) {
      return Collections.emptyMap();
    }
    if (blockTagSnapshotPermanentlyUnavailable) {
      // Sticky-failed earlier (e.g. BuiltInRegistries class_7923 unresolvable on MC 26.1).
      // Return an empty snapshot silently — SafetyTokenExpander will preserve original
      // #tag tokens, and we avoid the per-2s WARNING spam from its retry loop.
      return Collections.emptyMap();
    }
    // ----------------------------------------------------------------------
    // rtp-fabric-ADR-010: prefer the typed per-version adapter SPI over the
    // reflective walk below. Each rtp-fabric-vXX_YY_RN module compiles against
    // its own Loom-mapped MC types and can walk BuiltInRegistries.BLOCK without
    // any reflection — bypassing the fragile Mojang-vs-intermediary method-name
    // dispatch entirely. Returning null from the SPI means "adapter cannot
    // resolve, fall through" so the legacy reflective path remains as a safety
    // net for runtimes without a registered adapter or with a stub adapter
    // (e.g. the v26_1_R1 deobf bring-up).
    // ----------------------------------------------------------------------
    try {
      io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
          io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
      if (adapter != null) {
        Map<String, Set<String>> typed = adapter.snapshotBlockTags();
        if (typed != null) {
          // Adapter returned a result (possibly empty). An empty result is
          // valid — registry walked but yielded zero tags (data-pack bindings
          // not yet attached); SafetyTokenExpander preserves original #tag
          // tokens and a later /rtp reload picks up the populated registry.
          if (!typed.isEmpty()) {
            log(Level.FINE,
                "[RTP][Fabric] Block-tag snapshot built via adapter (" + adapter.mcVersion()
                    + "): " + typed.size() + " tags.");
          }
          return typed;
        }
        // null → adapter declined; fall through to reflective walk.
      }
    } catch (Throwable t) {
      // Adapter threw (e.g. UnsupportedOperationException from the v26_1_R1
      // stub adapter). Log at FINE and fall through — the reflective path
      // below already handles all failure modes for the unmapped runtime.
      log(Level.FINE,
          "[RTP][Fabric] Adapter snapshotBlockTags() failed; falling back to reflection: "
              + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
    // Pre-probe BuiltInRegistries via Class.forName to detect deobfuscated 26.1+ runtimes
    // where intermediary class_7923 is not resolvable, BEFORE the JVM verifier hits the
    // direct reference below and produces a multi-frame NoClassDefFoundError stack trace.
    // On a successful probe we fall through to the typed body; on failure we sticky-flag
    // and emit a single concise WARNING (no throwable, no stack trace).
    // Probe both the Mojang-mapped name AND the Fabric intermediary name
    // (class_7923). On dev/deobfuscated runtimes only the Mojang name resolves;
    // on a remapped production jar running against an intermediary-only runtime
    // (or vice versa for MC 26.1.2 where the intermediary alias is absent),
    // the bytecode-level reference below will fail with
    // NoClassDefFoundError: net/minecraft/class_7923. Catch that here so we
    // sticky-flag and emit a single concise WARNING instead of letting the
    // verifier produce a multi-frame stack trace on every retry.
    // Probe both the Mojang-mapped name AND the Fabric intermediary name
    // (class_7923). Loom's officialMojangMappings() actually compiles to
    // INTERMEDIARY bytecode (class_7923 etc.); the Mojang name is source-level
    // only. So on deobfuscated MC 26.1.2 the intermediary alias is absent and
    // the typed walk below would fail at JVM verification with a multi-frame
    // NoClassDefFoundError. Pre-probe both: if the intermediary is missing
    // but Mojang is available, fall through to a reflection-only walk that
    // does NOT bake the intermediary name into bytecode.
    boolean mojangAvailable;
    try {
      Class.forName("net.minecraft.core.registries.BuiltInRegistries",
          false, FabricServerAccessor.class.getClassLoader());
      mojangAvailable = true;
    } catch (Throwable probeFailure) {
      mojangAvailable = false;
    }
    boolean intermediaryAvailable;
    try {
      Class.forName("net.minecraft.class_7923",
          false, FabricServerAccessor.class.getClassLoader());
      intermediaryAvailable = true;
    } catch (Throwable probeFailure) {
      intermediaryAvailable = false;
    }
    if (!mojangAvailable && !intermediaryAvailable) {
      // Neither registries class name resolves on this runtime — give up.
      blockTagSnapshotPermanentlyUnavailable = true;
      log(Level.WARNING,
          "[RTP][Fabric] Block-tag snapshot disabled: BuiltInRegistries unavailable on this"
              + " runtime (neither net.minecraft.core.registries.BuiltInRegistries nor"
              + " net.minecraft.class_7923 resolved);"
              + " #tag tokens in safety.airBlocks/unsafeBlocks will not be flattened.");
      return Collections.emptyMap();
    }
    if (!mojangAvailable) {
      // Intermediary-only production runtime (e.g. remapped 1.21.11 server):
      // walk the registry via reflection on the intermediary class name. The
      // Mojang-mapped typed body below cannot run because BuiltInRegistries
      // is not resolvable.
      return buildBlockTagSnapshotReflectively("net.minecraft.class_7923");
    }
    if (!intermediaryAvailable) {
      // Deobfuscated MC 26.1+: walk the registry purely via reflection on
      // Mojang names so no intermediary symbol is ever resolved by the
      // verifier.
      return buildBlockTagSnapshotReflectively("net.minecraft.core.registries.BuiltInRegistries");
    }
    Map<String, Set<String>> out = new java.util.HashMap<>();
    int totalEntries = 0;
    try {
      for (net.minecraft.world.level.block.Block block : BuiltInRegistries.BLOCK) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
        if (blockId == null) continue;
        String materialName = blockId.toString().toUpperCase();
        net.minecraft.core.Holder.Reference<net.minecraft.world.level.block.Block> holder;
        try {
          holder = block.builtInRegistryHolder();
        } catch (Throwable t) {
          continue;
        }
        if (holder == null) continue;
        java.util.stream.Stream<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>>
            tagStream;
        try {
          tagStream = holder.tags();
        } catch (Throwable t) {
          continue;
        }
        if (tagStream == null) continue;
        java.util.Iterator<net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block>> it =
            tagStream.iterator();
        while (it.hasNext()) {
          net.minecraft.tags.TagKey<net.minecraft.world.level.block.Block> tagKey = it.next();
          if (tagKey == null) continue;
          ResourceLocation tagId = tagKey.location();
          if (tagId == null) continue;
          String key = tagId.getNamespace() + ":" + tagId.getPath();
          out.computeIfAbsent(key, k -> new HashSet<>()).add(materialName);
          totalEntries++;
        }
      }
    } catch (Throwable e) {
      log(Level.WARNING,
          "[RTP][Fabric] Failed to snapshot block tag registry: "
              + e.getClass().getName() + ": " + e.getMessage(), e);
      // Hard linkage failures (e.g. NoClassDefFoundError on BuiltInRegistries / class_7923
      // on MC 26.1's deobfuscated runtime) are permanent for the lifetime of this JVM:
      // re-running the registry walk on every SafetyTokenExpander retry just re-throws
      // and floods the log. Sticky-flag the failure so subsequent calls return silently.
      if (e instanceof LinkageError) {
        blockTagSnapshotPermanentlyUnavailable = true;
        log(Level.WARNING,
            "[RTP][Fabric] Block-tag snapshot disabled for this JVM (linkage error);"
                + " #tag tokens in safety.airBlocks/unsafeBlocks will not be flattened.");
      }
      return Collections.emptyMap();
    }
    if (out.isEmpty()) {
      log(Level.WARNING,
          "[RTP][Fabric] Block-tag registry yielded no usable tags"
              + " (BuiltInRegistries.BLOCK may not be populated yet);"
              + " #tag tokens in safety.airBlocks/unsafeBlocks will not be flattened.");
      return Collections.emptyMap();
    }
    Map<String, Set<String>> immutable = new java.util.HashMap<>(out.size());
    for (Map.Entry<String, Set<String>> e : out.entrySet()) {
      immutable.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
    }
    log(Level.FINE,
        "[RTP][Fabric] Block-tag snapshot built: "
            + immutable.size() + " tags, " + totalEntries + " entries.");
    return Collections.unmodifiableMap(immutable);
  }

  /**
   * Reflection-only block-tag snapshot for deobfuscated MC 26.1+ runtimes
   * where the Loom intermediary alias {@code net.minecraft.class_7923} is
   * absent. Uses only Mojang names resolved at runtime via
   * {@link Class#forName(String)}, so no intermediary symbol is ever baked
   * into this method's bytecode by Loom's remapper.
   *
   * <p>Mirrors the typed walk semantically: invert each block's tags into a
   * {@code namespace:path -> upper-case "namespace:path"} multimap. On any
   * failure (missing class/method/field, partial registry, etc.) sticky-flag
   * and return an empty map so {@code SafetyTokenExpander} preserves
   * {@code #tag} tokens silently.
   */
  private Map<String, Set<String>> buildBlockTagSnapshotReflectively(String registriesClassName) {
    Map<String, Set<String>> out = new java.util.HashMap<>();
    int totalEntries = 0;
    try {
      ClassLoader cl = FabricServerAccessor.class.getClassLoader();
      // Entry point: the registries class was already verified resolvable by
      // the pre-probe above. Everything below is discovered dynamically from
      // the actual runtime classes of the returned instances — we never load
      // any hardcoded net.minecraft.* class name beyond this one. The class
      // name is parameterised so the same body works for both the Mojang
      // entry (net.minecraft.core.registries.BuiltInRegistries on
      // deobfuscated runtimes) and the Fabric intermediary entry
      // (net.minecraft.class_7923 on remapped production runtimes).
      Class<?> builtInRegistriesCls = Class.forName(registriesClassName, true, cl);
      Object blockRegistry = findBlockRegistry(builtInRegistriesCls);
      if (blockRegistry == null) {
        throw new NoSuchFieldException(
            "BLOCK registry field on " + registriesClassName
                + " (no static Iterable field whose elements expose builtInRegistryHolder)");
      }
      if (!(blockRegistry instanceof Iterable<?> registryIterable)) {
        throw new IllegalStateException("BuiltInRegistries.BLOCK is not Iterable: "
            + blockRegistry.getClass().getName());
      }
      // Method handles resolved lazily on first encountered instance, then cached.
      // Each lookup tries Mojang names first, then 1.21.x intermediary aliases,
      // then falls back to a signature-based scan so future intermediary
      // renumbering doesn't break the path. NEVER bake an intermediary name
      // into bytecode beyond the alias-string list inside findMethodAny.
      java.lang.reflect.Method getKey = findMethodAny(
          blockRegistry.getClass(), 1, new String[] { "getKey", "method_10221" });
      if (getKey == null) {
        throw new NoSuchMethodException(
            "getKey(Object) on " + blockRegistry.getClass().getName());
      }
      java.lang.reflect.Method builtInHolder = null;
      java.lang.reflect.Method tagsMethod = null;
      java.lang.reflect.Method locationMethod = null;
      java.lang.reflect.Method rlGetNamespace = null;
      java.lang.reflect.Method rlGetPath = null;
      for (Object block : registryIterable) {
        if (block == null) continue;
        Object blockId;
        try {
          blockId = getKey.invoke(blockRegistry, block);
        } catch (Throwable t) {
          continue;
        }
        if (blockId == null) continue;
        if (rlGetNamespace == null) {
          rlGetNamespace = findMethodAny(
              blockId.getClass(), 0, new String[] { "getNamespace", "method_12836" });
          rlGetPath = findMethodAny(
              blockId.getClass(), 0, new String[] { "getPath", "method_12832" });
          if (rlGetNamespace == null || rlGetPath == null) {
            // Last-resort signature-based scan: ResourceLocation has exactly
            // two zero-arg String getters (namespace, path). Pick them in
            // declaration order — namespace is declared before path in both
            // Mojang and intermediary ResourceLocation/class_2960.
            java.lang.reflect.Method[] stringGetters =
                findZeroArgStringMethods(blockId.getClass(), 2);
            if (stringGetters.length >= 2) {
              if (rlGetNamespace == null) rlGetNamespace = stringGetters[0];
              if (rlGetPath == null) rlGetPath = stringGetters[1];
            } else {
              throw new NoSuchMethodException(
                  "getNamespace/getPath on " + blockId.getClass().getName());
            }
          }
        }
        String bns = (String) rlGetNamespace.invoke(blockId);
        String bpath = (String) rlGetPath.invoke(blockId);
        String materialName = (bns + ":" + bpath).toUpperCase();
        if (builtInHolder == null) {
          builtInHolder = findMethodAny(
              block.getClass(), 0, new String[] { "builtInRegistryHolder", "method_40142" });
          if (builtInHolder == null) continue;
        }
        Object holder;
        try {
          holder = builtInHolder.invoke(block);
        } catch (Throwable t) {
          continue;
        }
        if (holder == null) continue;
        if (tagsMethod == null) {
          tagsMethod = findMethodAny(
              holder.getClass(), 0, new String[] { "tags", "method_40228" });
          if (tagsMethod == null) continue;
        }
        Object tagStream;
        try {
          tagStream = tagsMethod.invoke(holder);
        } catch (Throwable t) {
          continue;
        }
        if (!(tagStream instanceof java.util.stream.Stream<?> s)) continue;
        java.util.Iterator<?> it = s.iterator();
        while (it.hasNext()) {
          Object tagKey = it.next();
          if (tagKey == null) continue;
          if (locationMethod == null) {
            locationMethod = findMethodAny(
                tagKey.getClass(), 0, new String[] { "location", "method_29177" });
            if (locationMethod == null) break;
          }
          Object tagId;
          try {
            tagId = locationMethod.invoke(tagKey);
          } catch (Throwable t) {
            continue;
          }
          if (tagId == null) continue;
          String ns = (String) rlGetNamespace.invoke(tagId);
          String path = (String) rlGetPath.invoke(tagId);
          String key = ns + ":" + path;
          out.computeIfAbsent(key, k -> new HashSet<>()).add(materialName);
          totalEntries++;
        }
      }
    } catch (Throwable e) {
      blockTagSnapshotPermanentlyUnavailable = true;
      log(Level.WARNING,
          "[RTP][Fabric] Block-tag snapshot disabled (reflection fallback failed): "
              + e.getClass().getSimpleName() + ": " + e.getMessage()
              + "; #tag tokens in safety.airBlocks/unsafeBlocks will not be flattened.");
      return Collections.emptyMap();
    }
    if (out.isEmpty()) {
      log(Level.WARNING,
          "[RTP][Fabric] Block-tag registry yielded no usable tags via reflection"
              + " (BuiltInRegistries.BLOCK may not be populated yet);"
              + " #tag tokens in safety.airBlocks/unsafeBlocks will not be flattened.");
      return Collections.emptyMap();
    }
    Map<String, Set<String>> immutable = new java.util.HashMap<>(out.size());
    for (Map.Entry<String, Set<String>> e : out.entrySet()) {
      immutable.put(e.getKey(), Collections.unmodifiableSet(e.getValue()));
    }
    log(Level.FINE,
        "[RTP][Fabric] Block-tag snapshot built via reflection: "
            + immutable.size() + " tags, " + totalEntries + " entries.");
    return Collections.unmodifiableMap(immutable);
  }

  /**
   * Locate the static BLOCK-registry field on the given registries class
   * without baking either the Mojang field name ({@code BLOCK}) or any
   * intermediary alias ({@code field_41175} on 1.21.x) into bytecode.
   *
   * <p>Strategy: prefer a static field literally named {@code BLOCK} (Mojang
   * runtimes) or {@code field_41175} (the Fabric intermediary alias for
   * {@code BuiltInRegistries.BLOCK} on 1.21.x). On miss, scan all
   * {@code public static} fields whose value is an {@link Iterable} and
   * return the first whose first-iterated element exposes a
   * {@code builtInRegistryHolder} zero-arg method (which both Block and Item
   * declare \u2014 the BLOCK registry is conventionally listed first in
   * {@code BuiltInRegistries}, so the scan order accepts it).
   */
  private static Object findBlockRegistry(Class<?> registriesCls) {
    String[] preferred = { "BLOCK", "field_41175" };
    for (String name : preferred) {
      try {
        java.lang.reflect.Field f = registriesCls.getField(name);
        Object v = f.get(null);
        if (v != null) return v;
      } catch (NoSuchFieldException ignored) {
        // try next
      } catch (Throwable t) {
        // unexpected access failure; fall through to scan
      }
    }
    for (java.lang.reflect.Field f : registriesCls.getFields()) {
      if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
      Object v;
      try {
        v = f.get(null);
      } catch (Throwable t) {
        continue;
      }
      if (!(v instanceof Iterable<?> iterable)) continue;
      java.util.Iterator<?> it = iterable.iterator();
      if (!it.hasNext()) continue;
      Object first = it.next();
      if (first == null) continue;
      if (findMethod(first.getClass(), "builtInRegistryHolder", 0) != null) {
        return v;
      }
    }
    return null;
  }

  private static java.lang.reflect.Method findMethod(Class<?> cls, String name, int argCount) {
    for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
      for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
        if (m.getName().equals(name) && m.getParameterCount() == argCount) {
          try {
            m.setAccessible(true);
          } catch (Throwable ignored) {
            // continue
          }
          return m;
        }
      }
    }
    for (Class<?> iface : cls.getInterfaces()) {
      java.lang.reflect.Method m = findMethod(iface, name, argCount);
      if (m != null) return m;
    }
    return null;
  }

  /**
   * Try each candidate method name in order on {@code cls} (walking
   * superclasses and interfaces) and return the first hit with the requested
   * parameter count. Used by the reflective block-tag snapshot path so a
   * single call can cover both the Mojang name (e.g. {@code getKey}) and the
   * matching 1.21.x intermediary alias (e.g. {@code method_10221}) without
   * baking a hardcoded NoSuchMethodException site into bytecode.
   */
  private static java.lang.reflect.Method findMethodAny(
      Class<?> cls, int argCount, String[] candidateNames) {
    for (String name : candidateNames) {
      java.lang.reflect.Method m = findMethod(cls, name, argCount);
      if (m != null) return m;
    }
    return null;
  }

  /**
   * Last-resort signature-based scan for zero-arg {@link String}-returning
   * methods on {@code cls}, walking superclasses then declared interfaces.
   * Returned in declaration order, deduplicated by name. Used to recover
   * {@code ResourceLocation#getNamespace} / {@code #getPath} when neither the
   * Mojang nor known intermediary name resolves on a future remap.
   *
   * @param cls   class to scan
   * @param limit cap on the number of methods returned
   */
  private static java.lang.reflect.Method[] findZeroArgStringMethods(Class<?> cls, int limit) {
    java.util.LinkedHashMap<String, java.lang.reflect.Method> hits = new java.util.LinkedHashMap<>();
    for (Class<?> c = cls; c != null && hits.size() < limit; c = c.getSuperclass()) {
      for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
        if (m.getParameterCount() != 0) continue;
        if (m.getReturnType() != String.class) continue;
        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
        if (hits.containsKey(m.getName())) continue;
        try {
          m.setAccessible(true);
        } catch (Throwable ignored) {
          // continue
        }
        hits.put(m.getName(), m);
        if (hits.size() >= limit) break;
      }
    }
    return hits.values().toArray(new java.lang.reflect.Method[0]);
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
      if (rtpWorld == null) return null;
      // Per-version adapter route (v26_1_R1+): the adapter's compiled
      // bytecode references Mojang names that link cleanly on the
      // deobfuscated runtime, where the legacy FabricRTPWorld instanceof
      // path can't even load. Try the SPI first; on null fall back to the
      // legacy typed path (1.20 / 1.21 still go through here).
      try {
        var adapter = io.github.dailystruggle.rtp.fabric.version
                .FabricVersionAdapterRegistry.peek();
        if (adapter != null) {
          Object payload = rtpWorld.world();
          if (payload != null) {
            WorldBorder fromAdapter = adapter.createNativeWorldBorder(payload);
            if (fromAdapter != null) return fromAdapter;
          }
        }
      } catch (Throwable t) {
        RTP.log(Level.FINE,
                "[RTP][Fabric] adapter.createNativeWorldBorder threw for world "
                        + s + ": " + t);
      }
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
    // default Bukkit ships.
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

  // ADR-049 — platform-agnostic player join/quit dispatcher. The Fabric event
  // wiring lives in FabricEventBridge (reflective registration of
  // ServerPlayConnectionEvents.JOIN/DISCONNECT, see rtp-fabric-ADR-009);
  // the bridge invokes fireJoinFromPlayer / fireQuitFromPlayer on the hook.
  private final FabricPlayerLifecycleHook playerLifecycleHook = new FabricPlayerLifecycleHook();

  @Override
  public io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook getPlayerLifecycleHook() {
    return playerLifecycleHook;
  }

  /**
   * Accessor used by {@code FabricEventBridge} to invoke the concrete fan-out
   * methods that take {@code ServerPlayer}-typed objects without exposing the
   * native type on the SPI.
   */
  public FabricPlayerLifecycleHook getFabricPlayerLifecycleHook() {
    return playerLifecycleHook;
  }

  @Override
  public double getTPS(int ticks) {
    // C6 (Section C of CHECKLIST-metrics-and-multiserver) — when a
    // FabricMetricsBinding is installed the canonical TPS source is the
    // M2 metrics facade (the EMA-blended sampler driven from
    // FabricEventBridge.onEndServerTick). Read the snapshot first; if the
    // binding is the M0 NOOP the scalar is NaN and we fall back to the
    // reflective Mojang-API probe documented below.
    try {
      io.github.dailystruggle.metrics.api.MetricsSnapshot snap =
          RTP.metrics.snapshot();
      double v = (ticks >= 600) ? snap.tps15m : (ticks >= 100 ? snap.tps5m : snap.tps1m);
      if (!Double.isNaN(v)) return v;
    } catch (Throwable ignored) {
      // Defensive — snapshot() is non-throwing by contract.
    }
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
    String raw = holder.unwrapKey().map(k -> k.location().toString()).orElse(null);
    if (raw == null) return null;
    // Match the form `defaultBiomesFor` and the anvil probe adapter expose,
    // and the form Spigot's `Biome.name()` produces (upper-case path-only).
    // Without this, live-load biome comparisons would mismatch the allow-list.
    String n = io.github.dailystruggle.rtp.api.configuration
        .PaletteIdentifierNormalizer.normalize(raw);
    return (n != null && !n.isEmpty()) ? n : raw;
  }

  /**
   * Typed fast-path for {@link #defaultBiomesFor}: enumerate the bound server's
   * biome registry through direct, Loom-remapped 1.21.x descriptors. This works
   * on the intermediary production runtime (where the reflective Mojang-named
   * lookups in {@code defaultBiomesFor} cannot resolve the obfuscated runtime
   * method names) and throws on the deobf MC 26.x runtime (the intermediary
   * descriptors are absent there), where the caller falls back to reflection.
   */
  private Set<String> defaultBiomesForTyped(MinecraftServer s) {
    net.minecraft.core.Registry<?> biomeReg =
        s.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
    Set<String> out = new HashSet<>();
    for (Object rawKey : biomeReg.keySet()) {
      if (rawKey == null) continue;
      String raw = rawKey.toString();
      String n = io.github.dailystruggle.rtp.api.configuration
          .PaletteIdentifierNormalizer.normalize(raw);
      if (n != null && !n.isEmpty()) out.add(n);
      if (raw != null && !raw.isEmpty()) out.add(raw);
    }
    return out;
  }

  /**
   * Return every biome key present in the bound server's biome registry. Falls
   * back to an empty set when the server is not yet bound (e.g. before
   * SERVER_STARTED) so callers get a deterministic empty result rather than
   * an NPE. The {@code rtpWorld} argument is currently ignored — Fabric's
   * biome registry is server-wide on 1.21.1; per-world filtering is not yet implemented.
   */
  private Set<String> defaultBiomesFor(@Nullable RTPWorld<?> rtpWorld) {
    MinecraftServer s = server;
    if (s == null) return Collections.emptySet();
    // Typed fast-path: on the intermediary production runtime (1.21.x) the
    // reflective Mojang-named lookups below resolve nothing because the live
    // runtime methods are obfuscated (e.g. method_30611), so the registry
    // resolution fails and the biome allow-list comes back empty. Call the
    // Loom-remapped descriptors directly first. On the deobf MC 26.x runtime
    // those intermediary descriptors are absent, so this throws and we fall
    // through to the reflective resolver that targets the deobf names.
    try {
      Set<String> typed = defaultBiomesForTyped(s);
      if (!typed.isEmpty()) return typed;
    } catch (Throwable typedFail) {
      // deobf 26.x runtime — fall through to the reflective path below.
    }
    // Both MinecraftServer.registryAccess() and Registry.registryOrThrow are
    // baked as Yarn-intermediary descriptors (method_30611, method_29107) in
    // common-module bytecode by Loom + officialMojangMappings. Those aliases
    // don't exist on MC 26.1.2's deobf runtime, where the equivalents are
    // MinecraftServer.registryAccess()/reloadableRegistries() and
    // Registry.lookupOrThrow(ResourceKey). Resolve both reflectively from the
    // live class instance so descriptors are computed at lookup time, not
    // pinned in the constant pool. Same fix family as the getCommands/
    // createCommandSourceStack path below.
    Object registryAccess = null;
    try {
      java.lang.reflect.Method m;
      try {
        m = s.getClass().getMethod("registryAccess");
      } catch (NoSuchMethodException nsme) {
        m = s.getClass().getMethod("reloadableRegistries");
      }
      registryAccess = m.invoke(s);
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] defaultBiomesFor: failed to resolve registryAccess reflectively", t);
      return Collections.emptySet();
    }
    if (registryAccess == null) return Collections.emptySet();
    Object biomesRegistry = null;
    try {
      // Avoid ALL compile-time references to `net.minecraft.core.registries.Registries`
      // (Yarn intermediary `class_7924`) and `net.minecraft.resources.ResourceKey`
      // (intermediary `class_5321`). Both are absent on MC 26.1.2's deobf runtime
      // when this carrier was Loom-remapped against an older mapping set. Build
      // the biome ResourceKey by name via Class.forName + reflective factory,
      // then call lookupOrThrow/registryOrThrow by name+arity.
      Class<?> resourceKeyCls = Class.forName("net.minecraft.resources.ResourceKey");
      // MC 26.1.2 deobf runtime renames `ResourceLocation` to `Identifier`
      // (see V26_1_R1FabricRTPWorld which imports net.minecraft.resources.Identifier).
      // Try both names so this carrier works against either runtime.
      Class<?> resourceLocationCls;
      try {
        resourceLocationCls = Class.forName("net.minecraft.resources.ResourceLocation");
      } catch (ClassNotFoundException cnfe) {
        resourceLocationCls = Class.forName("net.minecraft.resources.Identifier");
      }
      // ResourceLocation.parse("minecraft:worldgen/biome") - fall back to fromNamespaceAndPath / of.
      Object biomeRegistryId = null;
      NoSuchMethodException lastNsme = null;
      for (String mName : new String[]{"parse", "tryParse"}) {
        try {
          biomeRegistryId = resourceLocationCls.getMethod(mName, String.class)
              .invoke(null, "minecraft:worldgen/biome");
          if (biomeRegistryId != null) break;
        } catch (NoSuchMethodException nsme) {
          lastNsme = nsme;
        }
      }
      if (biomeRegistryId == null) {
        for (String mName : new String[]{"fromNamespaceAndPath", "of"}) {
          try {
            biomeRegistryId = resourceLocationCls.getMethod(mName, String.class, String.class)
                .invoke(null, "minecraft", "worldgen/biome");
            break;
          } catch (NoSuchMethodException nsme) {
            lastNsme = nsme;
          }
        }
      }
      if (biomeRegistryId == null) {
        throw (lastNsme != null) ? lastNsme : new NoSuchMethodException("ResourceLocation/Identifier factory not found");
      }
      // ResourceKey.createRegistryKey(ResourceLocation) returns a ResourceKey<Registry<T>>.
      java.lang.reflect.Method createRegistryKey = null;
      for (java.lang.reflect.Method cand : resourceKeyCls.getMethods()) {
        if (cand.getName().equals("createRegistryKey") && cand.getParameterCount() == 1) {
          createRegistryKey = cand;
          break;
        }
      }
      if (createRegistryKey == null) {
        RTP.log(Level.WARNING,
            "[RTP] defaultBiomesFor: no ResourceKey.createRegistryKey on runtime");
        return Collections.emptySet();
      }
      Object biomeRegistryKey = createRegistryKey.invoke(null, biomeRegistryId);

      java.lang.reflect.Method lookup = null;
      for (java.lang.reflect.Method cand : registryAccess.getClass().getMethods()) {
        String n = cand.getName();
        if ((n.equals("lookupOrThrow") || n.equals("registryOrThrow"))
            && cand.getParameterCount() == 1) {
          lookup = cand;
          break;
        }
      }
      if (lookup == null) {
        RTP.log(Level.WARNING,
            "[RTP] defaultBiomesFor: no lookupOrThrow/registryOrThrow on " + registryAccess.getClass());
        return Collections.emptySet();
      }
      biomesRegistry = lookup.invoke(registryAccess, biomeRegistryKey);
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] defaultBiomesFor: failed to resolve biome registry reflectively", t);
      return Collections.emptySet();
    }
    if (biomesRegistry == null) {
      return Collections.emptySet();
    }
    // Avoid a compile-time cast/instanceof to net.minecraft.core.Registry
    // (Yarn intermediary `class_2378`) which is absent on MC 26.1.2's deobf
    // runtime. Call keySet() reflectively instead.
    Iterable<?> keys;
    try {
      Object ks = biomesRegistry.getClass().getMethod("keySet").invoke(biomesRegistry);
      if (!(ks instanceof Iterable<?> it)) return Collections.emptySet();
      keys = it;
    } catch (Throwable t) {
      RTP.log(Level.WARNING,
          "[RTP] defaultBiomesFor: failed to invoke keySet() on biome registry reflectively", t);
      return Collections.emptySet();
    }
    Set<String> out = new HashSet<>();
    for (Object rawKey : keys) {
      if (rawKey == null) continue;
      // Accept either ResourceLocation or 26.1.2's Identifier — both render
      // `namespace:path` via toString(). Avoid an instanceof on a class literal
      // (would re-introduce the intermediary class dependency we just removed).
      // Normalise to the same shape ScanTask / probe comparisons use:
      // namespace-stripped, upper-cased (e.g. "minecraft:plains" -> "PLAINS").
      // FabricAnvilColumnProbeAdapter reconciles probe biomes through
      // PaletteIdentifierNormalizer.normalize, and ScanTask matches via
      // defaultBiomes.contains(probeBiome.toUpperCase()). Without this
      // normalisation every probed biome (e.g. "PLAINS") fails to match the
      // raw registry form ("minecraft:plains"), causing the anvil prefilter
      // to falsely reject the vast majority of scanned locations on Fabric.
      String raw = rawKey.toString();
      String n = io.github.dailystruggle.rtp.api.configuration
          .PaletteIdentifierNormalizer.normalize(raw);
      if (n != null && !n.isEmpty()) out.add(n);
      // Also emit the raw namespaced form (`minecraft:badlands`) so that
      // Brigadier-suggested tab-completion ids pass /rtp's biome parameter
      // validator on Fabric (parity with Bukkit/Folia/Paper).
      if (raw != null && !raw.isEmpty()) out.add(raw);
    }
    return out;
  }

  // ---------------------------------------------------------------------------
  // Progress-bar surface (platform-neutral on-screen progress feedback)
  //
  // Renders RTPServerAccessor#updateProgressBars / #clearProgressBars as vanilla
  // ServerBossEvents. The platform-agnostic caller (core ScanProgressBars) supplies
  // only ProgressBar value objects; all ServerBossEvent / color / player handling
  // lives here so no Minecraft UI type leaks into core or the neutral adapters.
  //
  // Typed net.minecraft calls are Loom-remapped to intermediary for the 1.20/1.21
  // runtime family. On the deobf MC 26.x runtime the remapped descriptors may not
  // resolve; every path is wrapped so a failure degrades to "no bar shown" rather
  // than throwing (S-004 does not apply: this is non-essential progress feedback,
  // not a teleport outcome).
  // ---------------------------------------------------------------------------

  /**
   * Obf-runtime ({@code 1.20.x} / {@code 1.21.x}) boss-bar renderer. Lazily created the first
   * time the obf fallback path runs so its {@code net.minecraft.*} (intermediary-remapped)
   * bytecode is never loaded on the deobf MC 26.x runtime, where the per-version adapter owns
   * the boss-bar surface instead. Keeping the typed boss-bar/chat calls out of
   * {@code FabricServerAccessor} itself is what lets the accessor link on the deobf runtime
   * (where {@code net/minecraft/class_2561} does not exist).
   */
  private FabricProgressBars obfProgressBars;

  private FabricProgressBars obfProgressBars() {
    FabricProgressBars bars = obfProgressBars;
    if (bars == null) {
      bars = new FabricProgressBars();
      obfProgressBars = bars;
    }
    return bars;
  }

  @Override
  public void updateProgressBars(Map<String, io.github.dailystruggle.rtp.api.server.ProgressBar> bars) {
    if (bars == null || bars.isEmpty()) {
      clearProgressBars();
      return;
    }
    MinecraftServer s = server;
    if (s == null) return;

    // Deobf MC 26.x carrier: the typed ServerBossEvent calls live in FabricProgressBars,
    // whose intermediary-remapped descriptors don't resolve on the deobf runtime. When the
    // active per-version adapter ships an unobf boss-bar renderer (mojmap-typed), route
    // through it instead to preserve parity.
    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
    if (adapter != null && adapter.supportsProgressBars()) {
      try {
        adapter.dispatchProgressBars(s, bars, this::eligibleViewerIds);
      } catch (Throwable t) {
        log(Level.FINER, "[RTP][Fabric] adapter scan progress bar update skipped: "
            + t.getClass().getSimpleName() + ": " + t.getMessage());
      }
      return;
    }

    // Obf 1.20.x / 1.21.x fallback. FabricProgressBars is loaded lazily here so the deobf
    // runtime (which returns above) never links its intermediary-remapped NM bytecode.
    try {
      obfProgressBars().update(s, this::eligibleViewerIds, bars);
    } catch (Throwable t) {
      // Boss-bar feedback is non-essential; never let a UI/mapping failure escape.
      log(Level.FINER, "[RTP][Fabric] scan progress bar update skipped: "
          + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  @Override
  public void clearProgressBars() {
    io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
    if (adapter != null && adapter.supportsProgressBars()) {
      try {
        adapter.clearProgressBars();
      } catch (Throwable ignored) {
        // best-effort hide
      }
      return;
    }
    try {
      obfProgressBars().clear();
    } catch (Throwable ignored) {
      // best-effort hide
    }
  }

  /** Collects the uuids of online players who hold {@code permission} (or all if blank). */
  private Set<UUID> eligibleViewerIds(String permission) {
    Set<UUID> out = new HashSet<>();
    for (RTPPlayer p : playersById.values()) {
      if (permission == null || permission.isEmpty() || p.hasPermission(permission)) {
        out.add(p.uuid());
      }
    }
    return out;
  }


  // ---------------------------------------------------------------------------
  // Menu platform surface (ADR-048)
  //
  // permissionProbe / effectivePermissions: the SPI defaults already route
  // through getSender(uuid) -> FabricRTPPlayer.hasPermission /
  // getEffectivePermissions, which both delegate to
  // FabricEffectivePermissionsResolver (ADR-011). We override anyway for
  // explicitness and to short-circuit the sender lookup when the player is
  // not yet cached.
  //
  // locale / regionDescriptor: read reflectively from the bound
  // MinecraftServer's PlayerList so the bytecode pins no intermediary aliases
  // (deobf 26.1+ runtime safety, same pattern as lookupOnlineServerPlayerByUuid).
  // ---------------------------------------------------------------------------

  @Override
  public java.util.function.Predicate<String> menuPermissionProbe(UUID player) {
    return node -> {
      if (player == null || node == null) return false;
      try {
        RTPCommandSender sender = getSender(player);
        return sender != null && sender.hasPermission(node);
      } catch (Throwable t) {
        return false;
      }
    };
  }

  @Override
  public Set<String> menuEffectivePermissions(UUID player) {
    if (player == null) return Collections.emptySet();
    try {
      RTPCommandSender sender = getSender(player);
      if (sender == null) return Collections.emptySet();
      Set<String> perms = sender.getEffectivePermissions();
      return perms == null ? Collections.emptySet() : perms;
    } catch (Throwable t) {
      return Collections.emptySet();
    }
  }

  @Override
  public String menuLocale(UUID player) {
    if (player == null) return "en_us";
    try {
      Object sp = lookupOnlineServerPlayerByUuid(player);
      if (sp == null) return "en_us";
      // ServerPlayer#clientInformation() -> ClientInformation, then language().
      // Use reflection so the unobf 26.1 deobf runtime, where the intermediary
      // descriptor differs, doesn't link-fail.
      try {
        java.lang.reflect.Method ci = sp.getClass().getMethod("clientInformation");
        Object info = ci.invoke(sp);
        if (info != null) {
          try {
            java.lang.reflect.Method lang = info.getClass().getMethod("language");
            Object loc = lang.invoke(info);
            if (loc instanceof String s && !s.isEmpty()) return s;
          } catch (NoSuchMethodException ignored) {
            // Older 1.20 family exposed a 'language' field on ServerPlayer.
          }
        }
      } catch (NoSuchMethodException ignored) {}
      // Fallback: 1.20.x ServerPlayer had a public `language` field.
      try {
        java.lang.reflect.Field f = sp.getClass().getField("language");
        Object v = f.get(sp);
        if (v instanceof String s && !s.isEmpty()) return s;
      } catch (NoSuchFieldException ignored) {}
    } catch (Throwable t) {
      // fall through
    }
    return "en_us";
  }

  @Override
  public String menuRegionDescriptor(UUID player) {
    if (player == null) return "";
    try {
      Object sp = lookupOnlineServerPlayerByUuid(player);
      if (sp == null) return "";
      // ServerPlayer#serverLevel() -> ServerLevel#dimension() -> ResourceKey#location().
      Object level;
      try {
        level = sp.getClass().getMethod("serverLevel").invoke(sp);
      } catch (NoSuchMethodException nsme) {
        // Older API: ServerPlayer#getLevel()
        try {
          level = sp.getClass().getMethod("getLevel").invoke(sp);
        } catch (NoSuchMethodException nsme2) {
          return "";
        }
      }
      if (level == null) return "";
      Object key = level.getClass().getMethod("dimension").invoke(level);
      if (key == null) return "";
      Object loc = key.getClass().getMethod("location").invoke(key);
      return loc == null ? "" : loc.toString();
    } catch (Throwable t) {
      return "";
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
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
   * Minimal console sender for the Fabric server console.
   *
   * <p>Always reports op-level permissions (Fabric console is unconditionally
   * privileged), routes {@link #sendMessage(String)} through Log4j2 in
   * legacy {@code §}-form so MC's TerminalConsoleAppender renders ANSI
   * colour on the dedicated server.
   */
  private static final class FabricConsoleSender implements RTPCommandSender {
    private final @Nullable MinecraftServer server;

    FabricConsoleSender(@Nullable MinecraftServer server) {
      this.server = server;
    }

    @Override public UUID uuid() { return RTPAPI.serverId; }
    @Override public String name() { return "Console"; }
    @Override public boolean hasPermission(String permission) { return true; }
    @Override public Set<String> getEffectivePermissions() {
        // rtp-fabric-ADR-011: console returns op-equivalent closed-namespace grants.
        return io.github.dailystruggle.rtp.fabric.player.FabricEffectivePermissionsResolver.resolveConsole();
    }
    @Override public long cooldown() { return 0L; }
    @Override public long delay() { return 0L; }
    @Override public void performCommand(@Nullable RTPPlayer player, String command) {
      MinecraftServer s = server;
      if (s == null || command == null) return;
      // Phase 4 migration: prefer per-version adapter's typed dispatch.
      // Falls through to the reflective path for adapters that don't override
      // (1.20 / 1.21 family) — those bytecode descriptors link cleanly there.
      try {
        io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter adapter =
            io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapterRegistry.peek();
        if (adapter != null && adapter.dispatchConsoleCommand(s, command)) {
          return;
        }
      } catch (Throwable ignored) {
        // fall through to reflective path
      }
      // Both s.getCommands() and s.createCommandSourceStack() are baked into
      // common-module bytecode by Loom + officialMojangMappings as intermediary
      // descriptors (method_3734 / method_3739). Those aliases don't exist on
      // MC 26.1.2's deobfuscated runtime, so a direct typed call throws
      // NoSuchMethodError. Resolve both reflectively from the live class
      // instance — descriptors are computed at lookup time, not pinned in
      // the constant pool. Same fix family as serverRunningThread / the
      // FabricScheduler.serverRunningThread path.
      try {
        java.lang.reflect.Method getCommands = s.getClass().getMethod("getCommands");
        java.lang.reflect.Method createSrc = s.getClass().getMethod("createCommandSourceStack");
        Object commands = getCommands.invoke(s);
        Object source = createSrc.invoke(s);
        // performPrefixedCommand(CommandSourceStack, String) — resolve on the
        // commands-instance class so the param type binds at lookup time too.
        java.lang.reflect.Method perform = null;
        for (java.lang.reflect.Method m : commands.getClass().getMethods()) {
          if (!"performPrefixedCommand".equals(m.getName())) continue;
          if (m.getParameterCount() != 2) continue;
          if (m.getParameterTypes()[1] != String.class) continue;
          perform = m;
          break;
        }
        if (perform != null) perform.invoke(commands, source, command);
      } catch (Throwable t) {
        throw new RuntimeException(t);
      }
    }

    @Override
    public void sendMessage(String message) {
      if (message == null) return;
      // Console rendering note: MinecraftServer.sendSystemMessage(Component)
      // logs `component.getString()` (plain text) on the dedicated server,
      // which strips styling. To keep colour on the console we route the
      // ANSI form straight through Log4j2. Translating to ANSI here (rather
      // than relying on TerminalConsoleAppender to convert §-codes) avoids
      // mojibake on Windows consoles whose codepage isn't UTF-8: the raw §
      // (UTF-8 0xC2 0xA7) would otherwise render as "┬º".
      String ansi = FabricAnsiText.toAnsiString(message);
      org.apache.logging.log4j.LogManager.getLogger("RTP").info(ansi);
    }

    @Override public RTPCommandSender clone() { return new FabricConsoleSender(server); }
  }
}
