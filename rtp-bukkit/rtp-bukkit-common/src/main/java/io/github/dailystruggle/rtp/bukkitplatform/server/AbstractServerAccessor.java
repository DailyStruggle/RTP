package io.github.dailystruggle.rtp.bukkitplatform.server;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.LocationGenerator;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.common.tools.MessageTagger;
import io.github.dailystruggle.rtp.bukkitplatform.entity.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.bukkitplatform.entity.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitRTPWorld;
import io.github.dailystruggle.rtp.bukkitplatform.world.BukkitSchematicPaster;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractServerAccessor implements RTPServerAccessor {
  protected static final Pattern versionPattern =
      Pattern.compile("[-+^.a-zA-Z]*", Pattern.CASE_INSENSITIVE);
  protected final Map<UUID, RTPWorld<?>> worldMap = new ConcurrentHashMap<>();
  protected final Map<String, RTPWorld<?>> worldMapStr = new ConcurrentHashMap<>();
  protected Function<String, Shape<?>> shapeFunction;
  protected String version = null;
  protected Integer intVersion = null;
  protected Function<RTPWorld<?>, Set<String>> biomes = BukkitRTPWorld::getBiomes;
  protected Function<String, ?> worldBorderFunction = this::createNativeWorldBorder;
  private final Map<String, WorldBorder> nativeWorldBorderCache = new ConcurrentHashMap<>();

  private File dataFolder;

  protected WorldBorder createNativeWorldBorder(String worldName) {
    return nativeWorldBorderCache.computeIfAbsent(worldName, s -> {
      RTPWorld<?> rtpWorld = getRTPWorld(s);
      if (!(rtpWorld instanceof BukkitRTPWorld)) return null;
      World world = ((BukkitRTPWorld) rtpWorld).world();
      org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
      return new WorldBorder(
          () -> {
            Shape<?> shape = (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
            if (shape instanceof Square square) {
              square.set(GenericMemoryShapeParams.radius, (long) (worldBorder.getSize() / 32.0));
              square.set(GenericMemoryShapeParams.centerX, (long) (worldBorder.getCenter().getBlockX() / 16.0));
              square.set(GenericMemoryShapeParams.centerZ, (long) (worldBorder.getCenter().getBlockZ() / 16.0));
            }
            return shape;
          },
          rtpLocation -> {
            Location location = new Location(world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z());
            return world.getWorldBorder().isInside(location);
          });
    });
  }

  public AbstractServerAccessor() {
    // ADR-058: install the native (WorldEdit-free) region-schematic paster. Inert until a
    // region's `schematic` knob is set and rtp-core invokes it on the confirmed-arrival path.
    BukkitRTPWorld.setSchematicPaster(new BukkitSchematicPaster());
    shapeFunction =
        s -> {
          World world = Bukkit.getWorld(s);
          if (world == null) return null;
          Region region = RTP.selectionAPI.getRegion(Objects.requireNonNull(getRTPWorld(world.getUID())));
          if (region == null) throw new IllegalStateException();
          Object o = region.getData(RegionKeys.shape);
          if (!(o instanceof Shape<?>)) throw new IllegalStateException();
          return (Shape<?>) o;
        };
  }

  @Override
  public @NotNull String getServerVersion() {
    if (version == null) {
      version = Bukkit.getServer().getClass().getPackage().getName();
      if (!version.contains("1_")) {
        String bukkitVersion = Bukkit.getServer().getBukkitVersion();
        int end = bukkitVersion.indexOf("-R");
        if (end < 0) return "1_13_2";
        bukkitVersion = bukkitVersion.substring(0, end).replaceAll("\\.", "_");
        return bukkitVersion;
      } else version = versionPattern.matcher(version).replaceAll("");
    }
    return version;
  }

  @Override
  public @NotNull String getPluginVersion() {
    // plugin is typed Object to match the platform-agnostic RTPServerAccessor
    // contract; at runtime every Bukkit-family platform passes a Plugin instance
    // into start(Object). Cast directly instead of going through reflection.
    if (!(plugin instanceof Plugin bukkitPlugin)) {
      throw new IllegalStateException(
          "[RTP] AbstractServerAccessor has no Bukkit Plugin bound; start(Object) must be called with a Plugin instance first.");
    }
    PluginDescriptionFile description = bukkitPlugin.getDescription();
    return description.getVersion();
  }

  @Override
  public @NotNull String getPlatform() {
    if (isFolia()) return "Folia";
    if (isPaper()) return "Paper";
    return "Spigot";
  }

  private boolean isPaper() {
    try {
      Class.forName("com.destroystokyo.paper.PaperConfig");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private boolean isFolia() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  @Override
  public @NotNull Integer getServerIntVersion() {
    if (intVersion == null) {
      String v = getServerVersion();
      if (v.contains("1_13")) intVersion = 13;
      else if (v.contains("1_14")) intVersion = 14;
      else if (v.contains("1_15")) intVersion = 15;
      else if (v.contains("1_16")) intVersion = 16;
      else if (v.contains("1_17")) intVersion = 17;
      else if (v.contains("1_18")) intVersion = 18;
      else if (v.contains("1_19")) intVersion = 19;
      else if (v.contains("1_20")) intVersion = 20;
      else if (v.contains("1_21")) intVersion = 21;
      else if (v.contains("26_1")) intVersion = 26;
      else intVersion = 13;
    }
    return intVersion;
  }

  @Override
  public @Nullable RTPWorld<?> getRTPWorld(String name) {
    if (worldMapStr.containsKey(name)) {
      RTPWorld<?> rtpWorld = worldMapStr.get(name);
      if (rtpWorld.isInactive()) {
        worldMapStr.remove(name);
        worldMap.remove(rtpWorld.id());
      } else return rtpWorld;
    }
    World world = Bukkit.getWorld(name);
    if (world == null) return null;
    RTPWorld<?> rtpWorld = new BukkitRTPWorld(world);
    worldMap.put(world.getUID(), rtpWorld);
    worldMapStr.put(name, rtpWorld);
    return rtpWorld;
  }

  @Override
  public @Nullable RTPWorld<?> getRTPWorld(UUID id) {
    if (worldMap.containsKey(id)) {
      RTPWorld<?> rtpWorld = worldMap.get(id);
      if (rtpWorld.isInactive()) {
        worldMap.remove(id);
        worldMapStr.remove(rtpWorld.name());
      } else return rtpWorld;
    }
    World world = Bukkit.getWorld(id);
    if (world == null) return null;
    RTPWorld<?> rtpWorld = new BukkitRTPWorld(world);
    worldMap.put(id, rtpWorld);
    worldMapStr.put(world.getName(), rtpWorld);
    return rtpWorld;
  }

  @Override
  public @Nullable Object getShape(String name) {
    return shapeFunction.apply(name);
  }

  @Override
  public boolean isPrimaryThread() {
    return Bukkit.isPrimaryThread();
  }

  @Override
  public @Nullable Object getWorldBorder(String worldName) {
    Object res = worldBorderFunction.apply(worldName);
    if (res == null) res = createNativeWorldBorder(worldName);
    return res;
  }

  @Override
  public @NotNull List<RTPWorld<?>> getRTPWorlds() {
    return Bukkit.getWorlds().stream()
        .map(world -> getRTPWorld(world.getUID()))
        .collect(Collectors.toList());
  }

  @Override
  public @Nullable RTPPlayer getPlayer(UUID uuid) {
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return null;
    return new BukkitRTPPlayer(player);
  }

  @Override
  public @Nullable RTPPlayer getPlayer(String name) {
    Player player = Bukkit.getPlayer(name);
    if (player == null) return null;
    return new BukkitRTPPlayer(player);
  }

  @Override
  public @Nullable RTPPlayer getConsolePlayer() {
    return getPlayer(RTPAPI.serverId);
  }

  @Override
  public @NotNull RTPCommandSender getSender(UUID uuid) {
    if (uuid.equals(RTPAPI.serverId)) return new BukkitRTPCommandSender(Bukkit.getConsoleSender());
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return new BukkitRTPCommandSender(Bukkit.getConsoleSender());
    return new BukkitRTPPlayer(player);
  }

  @Override
  public long overTime() {
    return 0;
  }

  @Override
  public @NotNull File getPluginDirectory() {
    return dataFolder;
  }

  @Override
  public void sendMessage(UUID target, MessagesKeys msgType, String tag) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String message = lang.getConfigValue(msgType, "").toString();
    RTP.log(Level.FINE, "[ENQUEUE_TRACE] AbstractServerAccessor.sendMessage(UUID, MessagesKeys) target=" + target
            + " msgType=" + msgType
            + " resolvedLen=" + (message == null ? -1 : message.length())
            + " resolvedEmpty=" + (message == null || message.isEmpty())
            + " preview=\"" + (message == null ? "null" : (message.length() > 80 ? message.substring(0, 80) + "..." : message)) + "\"");
    sendMessage(target, message, tag);
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, MessagesKeys msgType, String tag) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String message = lang.getConfigValue(msgType, "").toString();
    sendMessage(target1, target2, message, tag);
  }

  @Override
  public void sendMessage(UUID target, String message, String tag) {
    message = tagMessage(message, tag);
    if (target.equals(RTPAPI.serverId)) {
      RTP.log(Level.FINE, "[ENQUEUE_TRACE] AbstractServerAccessor.sendMessage routing target=SERVER -> console");
      io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(Bukkit.getConsoleSender(), message);
      return;
    }
    Player player = Bukkit.getPlayer(target);
    RTP.log(Level.FINE, "[ENQUEUE_TRACE] AbstractServerAccessor.sendMessage(UUID, String) target=" + target
            + " bukkitPlayerNonNull=" + (player != null)
            + " msgEmpty=" + (message == null || message.isEmpty())
            + " thread=" + Thread.currentThread().getName());
    if (player != null) {
      io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(player, message);
    } else {
      RTP.log(Level.WARNING, "[ENQUEUE_TRACE] AbstractServerAccessor.sendMessage DROPPED (Bukkit.getPlayer returned null) target=" + target);
    }
  }

  @Override
  public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
    message = tagMessage(message, null);
    RTPCommandSender sender = getSender(target);
    // Routes the suggestion safely as a click event via your formatting pipeline
    io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(sender, message, "", suggestion);
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, String message, String tag) {
    message = tagMessage(message, tag);

    if (target1.equals(RTPAPI.serverId)) {
      io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(Bukkit.getConsoleSender(), message);
    } else {
      Player p1 = Bukkit.getPlayer(target1);
      if (p1 != null) io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(p1, message);
    }

    // Prevent double sending if target1 and target2 are the exact same entity
    if (target1.equals(target2)) return;

    if (target2.equals(RTPAPI.serverId)) {
      io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(Bukkit.getConsoleSender(), message);
    } else {
      Player p2 = Bukkit.getPlayer(target2);
      if (p2 != null) io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(p2, message);
    }
  }

  @Override
  public void sendMessage(RTPCommandSender target, String message, String hover, String click, String tag) {
    io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(target, tagMessage(message, tag), hover, click);
  }

  @Override
  public void sendMessageWithRunCommand(
      RTPCommandSender target, String message, String hover, String runCommand, String tag) {
    // ADR-050 Stage 3β.D.2b (2026-05-24): menu fragment clicks dispatch the
    // literal /rtp menu ... command, so use RUN_COMMAND rather than the
    // SUGGEST_COMMAND used by the sibling sendMessage(...) above.
    io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(
        target,
        tagMessage(message, tag),
        hover,
        runCommand,
        net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND);
  }

  @Override
  public String format(@Nullable UUID player, String text) {
    OfflinePlayer bukkitPlayer = (player != null) ? Bukkit.getOfflinePlayer(player) : null;
    return io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.format(bukkitPlayer, text);
  }

  @Override
  public String formatNoColor(@Nullable UUID player, String text) {
    OfflinePlayer bukkitPlayer = (player != null) ? Bukkit.getOfflinePlayer(player) : null;
    return io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.formatNoColor(bukkitPlayer, text);
  }

  @Override
  public void log(Level level, String msg) {
    io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.log(level, msg);
  }

  @Override
  public void log(Level level, String msg, Throwable throwable) {
    io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.log(level, msg, throwable);
  }

  @Override
  public void announce(String msg, String permission, String tag) {
    msg = tagMessage(msg, tag);
    for (Player player : Bukkit.getOnlinePlayers()) {
      if (player.hasPermission(permission)) {
        io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(player, msg);
      }
    }

    // Route to console via the formatting pipeline
    io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(Bukkit.getConsoleSender(), msg);
  }

  private String tagMessage(String message, @Nullable String tag) {
    return MessageTagger.tagMessage(message, tag);
  }

  @Override
  public @NotNull Set<String> getBiomes(RTPWorld<?> rtpWorld) {
    return biomes.apply(rtpWorld);
  }

  @Override
  public @NotNull Set<String> getBiomes() {
    return biomes.apply(null);
  }

  @Override
  public @NotNull Set<String> materials() {
    return Arrays.stream(Material.values())
        .map(material -> material.name().toUpperCase())
        .collect(Collectors.toSet());
  }

  /**
   * Lazy-initialised snapshot of the Bukkit block-tag registry, published under
   * the ADR-017 {@code namespace:path} key form. {@code volatile} because the
   * reference is swapped atomically on {@link #rebuildBlockTagSnapshot()}; the
   * map itself is deeply immutable so readers can safely iterate without
   * locking.
   */
  private volatile Map<String, Set<String>> blockTagSnapshot;

  @Override
  public @NotNull Map<String, Set<String>> blockTagSnapshot() {
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
   * Iterate {@code Bukkit.getTags(Tag.REGISTRY_BLOCKS, Material.class)} and
   * produce an immutable {@code namespace:path → upper-case material name}
   * snapshot. Any failure (e.g. a server that hasn't loaded the tag registry
   * yet, or a pre-1.13 fork that doesn't implement the Tag API) is logged at
   * WARNING and falls through to an empty snapshot — never a null reference.
   */
  private Map<String, Set<String>> buildBlockTagSnapshot() {
    Map<String, Set<String>> out = new HashMap<>();
    int totalTags = 0;
    int totalEmpty = 0;
    try {
      Iterable<Tag<Material>> tags = Bukkit.getTags(Tag.REGISTRY_BLOCKS, Material.class);
      if (tags == null) {
        log(Level.WARNING,
            "[RTP] Bukkit.getTags(REGISTRY_BLOCKS, Material.class) returned null — "
                + "tag-flattening of safety.airBlocks/unsafeBlocks #tag tokens will be skipped");
        return Collections.emptyMap();
      }
      for (Tag<Material> tag : tags) {
        totalTags++;
        NamespacedKey key = tag.getKey();
        Set<Material> values;
        try {
          values = tag.getValues();
        } catch (Throwable t) {
          log(Level.WARNING,
              "[RTP] tag.getValues() threw for key="
                  + key.getNamespace() + ":" + key.getKey()
                  + " (" + t.getClass().getName() + ": " + t.getMessage() + ")");
          totalEmpty++;
          continue;
        }
        if (values == null || values.isEmpty()) {
          totalEmpty++;
          continue;
        }
        Set<String> members = new HashSet<>(values.size());
        for (Material m : values) members.add(m.name());
        out.put(
            key.getNamespace() + ":" + key.getKey(),
            Collections.unmodifiableSet(members));
      }
    } catch (Throwable e) {
      // Catch Throwable (not just RuntimeException) — Bukkit.getTags can throw
      // NoSuchMethodError / NoClassDefFoundError on forks where the Tag API
      // signature differs, and those would otherwise silently escape our handler
      // with no operator-visible log line.
      log(Level.WARNING,
          "[RTP] Failed to snapshot block tag registry: "
              + e.getClass().getName() + ": " + e.getMessage(), e);
      return Collections.emptyMap();
    }
    if (out.isEmpty()) {
      log(Level.WARNING,
          "[RTP] Bukkit block-tag registry yielded no usable tags (iterated="
              + totalTags + ", empty=" + totalEmpty
              + "); #tag tokens in safety.airBlocks/unsafeBlocks will not be flattened.");
    }
    return Collections.unmodifiableMap(out);
  }


  private Object plugin;

  // ADR-049 — platform-agnostic player join/quit dispatcher. Constructed lazily
  // and exposed via getPlayerLifecycleHook(); the Bukkit Listener registration
  // happens in start(Object) once the owning Plugin is known.
  private final BukkitPlayerLifecycleHook playerLifecycleHook = new BukkitPlayerLifecycleHook();
  private boolean playerLifecycleHookRegistered = false;

  @Override
  public void stop() {
    // Implementation
    for (RTPWorld<?> rtpWorld : worldMap.values()) {rtpWorld.forgetChunks();}
  }

  @Override
  public void start() {
    start(plugin);
  }

  @Override
  public void start(Object plugin) {
    this.plugin = Objects.requireNonNull(plugin);
    // See getPluginVersion(): plugin is Object in the API but is always a Bukkit
    // Plugin at runtime on this adapter. Avoid reflection and fail fast with a
    // descriptive IllegalStateException if someone wires in the wrong type.
    if (!(plugin instanceof Plugin bukkitPlugin)) {
      throw new IllegalStateException(
          "[RTP] AbstractServerAccessor.start(Object) requires an org.bukkit.plugin.Plugin instance, got: "
              + plugin.getClass().getName());
    }
    this.dataFolder = bukkitPlugin.getDataFolder();
    if (!playerLifecycleHookRegistered) {
      playerLifecycleHook.register(bukkitPlugin);
      playerLifecycleHookRegistered = true;
    }
  }

  @Override
  public io.github.dailystruggle.rtp.api.server.PlayerLifecycleHook getPlayerLifecycleHook() {
    return playerLifecycleHook;
  }

  @Override
  @SuppressWarnings("unchecked")
  public boolean setShapeFunction(Function<String, ?> shapeFunction) {
      this.shapeFunction = (Function<String, Shape<?>>) shapeFunction;
    return true;
  }

  @Override
  public boolean setWorldBorderFunction(Function<String, ?> function) {
    this.worldBorderFunction = function;
    return true;
  }

  @Override
  public RTPTaskPipe createTaskPipe() {
    return new TimeBoundTaskPipe();
  }

  @Override
  public Object createCachePipe() {
    return new TimeBoundTaskPipe();
  }

  @Override
  public Object getPlugin() {
    return plugin;
  }

  @Override
  public ILocationGenerator getLocationGenerator() {
    return new LocationGenerator();
  }

  @Override
  public io.github.dailystruggle.rtp.api.scheduling.RTPScheduler getScheduler() {
    return io.github.dailystruggle.rtp.common.RTP.scheduler;
  }

  @Override
  public double getTPS(int ticks) {
    // C6 (Section C of CHECKLIST-metrics-and-multiserver) — when a non-NOOP
    // MetricsBinding is installed the canonical TPS source is the M2 metrics
    // facade. Read the snapshot first; if the binding is the M0 NOOP (all
    // scalars NaN) fall back to the reflective recentTps lookup that has
    // shipped on Bukkit since pre-Metrics days.
    try {
      io.github.dailystruggle.metrics.api.MetricsSnapshot s =
          io.github.dailystruggle.rtp.common.RTP.metrics.snapshot();
      double v = (ticks >= 600) ? s.tps15m : (ticks >= 100 ? s.tps5m : s.tps1m);
      if (!Double.isNaN(v)) return v;
    } catch (Throwable ignored) {
      // snapshot() is contract-bound non-throwing, but stay defensive on
      // pre-bind classpath drift.
    }
    try {
      Object server = Bukkit.getServer();
      java.lang.reflect.Field field = server.getClass().getField("recentTps");
      double[] tps = (double[]) field.get(server);
      return tps[0];
    } catch (Exception e) {
      return 20.0;
    }
  }

  @Override
  public void setBiomeGetter(Function<RTPLocation, String> getter) {
    BukkitRTPWorld.setBiomeGetter(
        location ->
            getter.apply(
                new RTPLocation(
                    getRTPWorld(Objects.requireNonNull(location.getWorld()).getUID()),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ())));
  }

  @Override
  public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
    this.biomes = getter;
    BukkitRTPWorld.setBiomesGetter(getter);
  }

  // ---------------------------------------------------------------------------
  // Menu platform surface (ADR-048)
  // ---------------------------------------------------------------------------

  @Override
  public java.util.function.Predicate<String> menuPermissionProbe(UUID player) {
    return node -> {
      if (player == null || node == null) return false;
      try {
        Player p = Bukkit.getPlayer(player);
        if (p != null) return p.hasPermission(node);
        // Offline / unresolved: fall back to op-status on the OfflinePlayer.
        OfflinePlayer off = Bukkit.getOfflinePlayer(player);
        return off != null && off.isOp();
      } catch (Throwable t) {
        return false;
      }
    };
  }

  @Override
  public Set<String> menuEffectivePermissions(UUID player) {
    if (player == null) return Collections.emptySet();
    try {
      Player p = Bukkit.getPlayer(player);
      if (p == null) return Collections.emptySet();
      return p.getEffectivePermissions().stream()
          .filter(pai -> pai.getValue())
          .map(pai -> pai.getPermission())
          .collect(Collectors.toUnmodifiableSet());
    } catch (Throwable t) {
      return Collections.emptySet();
    }
  }

  @Override
  public String menuLocale(UUID player) {
    if (player == null) return "en_us";
    try {
      Player p = Bukkit.getPlayer(player);
      if (p == null) return "en_us";
      String loc = p.getLocale();
      return (loc == null || loc.isEmpty()) ? "en_us" : loc;
    } catch (Throwable t) {
      return "en_us";
    }
  }

  @Override
  public String menuRegionDescriptor(UUID player) {
    if (player == null) return "";
    try {
      Player p = Bukkit.getPlayer(player);
      if (p == null) return "";
      World w = p.getWorld();
      return (w == null) ? "" : w.getName();
    } catch (Throwable t) {
      return "";
    }
  }
}
