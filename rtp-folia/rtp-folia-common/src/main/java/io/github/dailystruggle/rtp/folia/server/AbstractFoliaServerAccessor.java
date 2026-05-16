package io.github.dailystruggle.rtp.folia.server;

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
import io.github.dailystruggle.rtp.common.tools.MessageTagger;
import io.github.dailystruggle.rtp.folia.entity.FoliaRTPCommandSender;
import io.github.dailystruggle.rtp.folia.entity.FoliaRTPPlayer;
import io.github.dailystruggle.rtp.folia.world.FoliaLocationGenerator;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.folia.tasks.CountBoundTaskPipe;
import io.github.dailystruggle.rtp.folia.tasks.RegionKey;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import io.github.dailystruggle.rtp.folia.thread.GlobalRegionThread;

public abstract class AbstractFoliaServerAccessor implements RTPServerAccessor {
  protected final Map<UUID, FoliaRTPWorld> worldMap = new ConcurrentHashMap<>();
  protected final ConcurrentHashMap<RegionKey, ConcurrentLinkedQueue<io.github.dailystruggle.rtp.common.tasks.RTPRunnable>> regionQueues = new ConcurrentHashMap<>();
  protected final Set<RegionKey> activeProcessors = ConcurrentHashMap.newKeySet();
  protected Function<String, ?> shapeFunction = (s) -> null;
  protected Function<String, ?> worldBorderFunction = this::createNativeWorldBorder;
  private final Map<String, WorldBorder> nativeWorldBorderCache = new ConcurrentHashMap<>();

  protected WorldBorder createNativeWorldBorder(String worldName) {
    return nativeWorldBorderCache.computeIfAbsent(worldName, s -> {
      RTPWorld<?> rtpWorld = getRTPWorld(s);
      if (!(rtpWorld instanceof FoliaRTPWorld)) return null;
      World world = ((FoliaRTPWorld) rtpWorld).world();
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
  protected Function<RTPWorld<?>, Set<String>> biomes = FoliaRTPWorld::getBiomes;

  @GlobalRegionThread
  public AbstractFoliaServerAccessor() {
    for (World world : Bukkit.getWorlds()) {
      worldMap.put(world.getUID(), new FoliaRTPWorld(world));
    }
  }

  @Override
  @GlobalRegionThread
  public String getServerVersion() {
    return Bukkit.getVersion();
  }

  @Override
  @GlobalRegionThread
  public @NotNull String getPluginVersion() {
    return Bukkit.getPluginManager().getPlugin("RTP").getDescription().getVersion();
  }

  @Override
  public @NotNull String getPlatform() { // @AnyThread — returns constant String
    return "Folia";
  }

  protected Integer intVersion = null;
  @Override
  public Integer getServerIntVersion() { // @AnyThread — pure parse/cache of version string
    if (intVersion == null) {
      // TODO: THREAD-VIOLATION - Requires async bridge; calls @GlobalRegionThread getServerVersion() from unannotated context
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
  @GlobalRegionThread
  public @Nullable RTPWorld<?> getRTPWorld(String name) {
    World world = Bukkit.getWorld(name);
    if (world == null) return null;
    return worldMap.computeIfAbsent(world.getUID(), uuid -> new FoliaRTPWorld(world));
  }

  @Override
  @GlobalRegionThread
  public @Nullable RTPWorld<?> getRTPWorld(UUID id) {
    World world = Bukkit.getWorld(id);
    if (world == null) return null;
    return worldMap.computeIfAbsent(id, uuid -> new FoliaRTPWorld(world));
  }

  @Override
  public @Nullable Object getShape(String name) { // @AnyThread — delegates to pure function
    return shapeFunction.apply(name);
  }

  @Override
  public boolean isPrimaryThread() { // @AnyThread — pure boolean check
    return false;
  }

  @Override
  @GlobalRegionThread
  public @Nullable Object getWorldBorder(String worldName) {
    Object res = worldBorderFunction.apply(worldName);
    if (res == null) res = createNativeWorldBorder(worldName);
    return res;
  }

  @Override
  @GlobalRegionThread
  public @NotNull List<RTPWorld<?>> getRTPWorlds() {
    return Bukkit.getWorlds().stream()
        .map(world -> getRTPWorld(world.getUID()))
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  @Override
  @GlobalRegionThread
  public @Nullable RTPPlayer getPlayer(UUID uuid) {
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return null;
    return new FoliaRTPPlayer(player);
  }

  @Override
  @GlobalRegionThread
  public @Nullable RTPPlayer getPlayer(String name) {
    Player player = Bukkit.getPlayer(name);
    if (player == null) return null;
    return new FoliaRTPPlayer(player);
  }

  @Override
  public @Nullable RTPPlayer getConsolePlayer() {
    return getPlayer(RTPAPI.serverId);
  }

  @Override
  public @NotNull RTPCommandSender getSender(UUID uuid) {
    if (uuid.equals(RTPAPI.serverId)) return new FoliaRTPCommandSender(Bukkit.getConsoleSender());
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return new FoliaRTPCommandSender(Bukkit.getConsoleSender());
    return new FoliaRTPPlayer(player);
  }

  @Override
  public long overTime() { // @AnyThread — returns constant
    return 0;
  }

  @Override
  public @NotNull File getPluginDirectory() {
    return Bukkit.getPluginManager().getPlugin("RTP").getDataFolder();
  }

  @Override
  public void sendMessage(UUID target, MessagesKeys msgType, String tag) {
    ConfigParser<MessagesKeys> lang =
            (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String message = lang.getConfigValue(msgType, "").toString();
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
      io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(Bukkit.getConsoleSender(), message);
      return;
    }
    Player player = Bukkit.getPlayer(target);
    if (player != null) io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.sendMessage(player, message);
  }

  @Override
  public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
    message = tagMessage(message, null);
    // TODO: THREAD-VIOLATION - Requires async bridge; calls @GlobalRegionThread getSender() from @RegionThread context
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
  public String format(@Nullable UUID player, String text) { // @AnyThread — pure text formatting
    org.bukkit.OfflinePlayer bukkitPlayer = (player != null) ? Bukkit.getOfflinePlayer(player) : null;
    return io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.format(bukkitPlayer, text);
  }

  @Override
  public String formatNoColor(@Nullable UUID player, String text) { // @AnyThread — pure text formatting
    org.bukkit.OfflinePlayer bukkitPlayer = (player != null) ? Bukkit.getOfflinePlayer(player) : null;
    return io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.formatNoColor(bukkitPlayer, text);
  }

  @Override
  public void log(Level level, String msg) { // @AnyThread — thread-safe logger
    // Route through SendMessage.log so that '&' color codes in messages.yml
    // (e.g. the locationLoaded hydration banner) are translated before being
    // printed to the console. Previously this delegated directly to
    // Bukkit.getLogger().log, causing raw "&e[P0] Successfully loaded ..." to
    // appear on Folia consoles.
    io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage.log(level, msg);
  }

  @Override
  public void log(Level level, String msg, Throwable throwable) { // @AnyThread — thread-safe logger
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
  public @NotNull Set<String> getBiomes(RTPWorld<?> rtpWorld) { // @AnyThread — delegates to pure function
    return biomes.apply(rtpWorld);
  }

  @Override
  public @NotNull Set<String> getBiomes() { // @AnyThread — delegates to pure function
    return biomes.apply(null);
  }

  @Override
  public @NotNull Set<String> materials() { // @AnyThread — pure enum enumeration
    return Arrays.stream(Material.values())
        .map(material -> material.name().toUpperCase())
        .collect(Collectors.toSet());
  }

  /**
   * Lazy-initialised snapshot of the Bukkit block-tag registry, mirroring the
   * Spigot-side {@code AbstractServerAccessor.blockTagSnapshot} contract
   * (ADR-017). Populated on first read or on explicit
   * {@link #rebuildBlockTagSnapshot()}, consumed by
   * {@code SafetyTokenExpander} / {@code SafetyCompilationCache} to flatten
   * {@code #namespace:tag} tokens in {@code safety.airBlocks} /
   * {@code unsafeBlocks} into their member material names.
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

  private Map<String, Set<String>> buildBlockTagSnapshot() {
    Map<String, Set<String>> out = new HashMap<>();
    int totalTags = 0;
    int totalEmpty = 0;
    try {
      Iterable<Tag<Material>> tags = Bukkit.getTags(Tag.REGISTRY_BLOCKS, Material.class);
      if (tags == null) {
        log(Level.WARNING,
            "[RTP] (Folia) Bukkit.getTags(REGISTRY_BLOCKS, Material.class) returned null — "
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
              "[RTP] (Folia) tag.getValues() threw for key="
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
      log(Level.WARNING,
          "[RTP] (Folia) Failed to snapshot block tag registry: "
              + e.getClass().getName() + ": " + e.getMessage(), e);
      return Collections.emptyMap();
    }
    if (out.isEmpty()) {
      log(Level.WARNING,
          "[RTP] (Folia) Bukkit block-tag registry yielded no usable tags (iterated="
              + totalTags + ", empty=" + totalEmpty
              + "); #tag tokens in safety.airBlocks/unsafeBlocks will not be flattened.");
    }
    return Collections.unmodifiableMap(out);
  }

  private Object plugin;

  @Override
  @GlobalRegionThread
  public void stop() {
    for (RTPWorld<?> rtpWorld : worldMap.values()) {rtpWorld.forgetChunks();}
  }

  @Override
  @GlobalRegionThread
  public void start() {
    start(plugin);
  }

  @Override
  @GlobalRegionThread
  public void start(Object plugin) {
    this.plugin = plugin;
  }

  @Override
  public boolean setShapeFunction(Function<String, ?> shapeFunction) { // @AnyThread — sets a pure function field
    this.shapeFunction = shapeFunction;
    return true;
  }

  @Override
  public boolean setWorldBorderFunction(Function<String, ?> function) { // @AnyThread — sets a pure function field
    this.worldBorderFunction = function;
    return true;
  }

  @Override
  public RTPTaskPipe createTaskPipe() { // @AnyThread — factory method, no Bukkit state
    return new CountBoundTaskPipe((Plugin) plugin, 10);
  }

  @Override
  public Object createCachePipe() { // @AnyThread — factory method, no Bukkit state
    return new CountBoundTaskPipe((Plugin) plugin, 2);
  }

  @Override
  public Object getPlugin() { // @AnyThread — returns stored field
    return plugin;
  }

  @Override
  public ILocationGenerator getLocationGenerator() { // @AnyThread — factory method
    return new FoliaLocationGenerator();
  }

  @Override
  public io.github.dailystruggle.rtp.api.scheduling.RTPScheduler getScheduler() { // @AnyThread — returns stored field
    return io.github.dailystruggle.rtp.common.RTP.scheduler;
  }

  @Override
  public double getTPS(int ticks) { // @AnyThread — returns constant
    try {
      return 20.0;
    } catch (Exception e) {
      return 20.0;
    }
  }

  @Override
  public void setBiomeGetter(Function<RTPLocation, String> getter) { // @AnyThread — sets a pure function field
    FoliaRTPWorld.setBiomeGetter(
        location ->
            getter.apply(
                new RTPLocation(
                    // TODO: THREAD-VIOLATION - Requires async bridge; calls @GlobalRegionThread getRTPWorld() from unannotated lambda context
                    getRTPWorld(location.getWorld().getUID()),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ())));
  }

  @Override
  public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) { // @AnyThread — sets a pure function field
    this.biomes = getter;
    FoliaRTPWorld.setBiomesGetter(getter);
  }
}
