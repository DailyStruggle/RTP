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
import io.github.dailystruggle.rtp.folia.tasks.FoliaRegionProcessor;
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
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

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

  public AbstractFoliaServerAccessor() {
    for (World world : Bukkit.getWorlds()) {
      worldMap.put(world.getUID(), new FoliaRTPWorld(world));
    }
  }

  @Override
  public String getServerVersion() {
    return Bukkit.getVersion();
  }

  @Override
  public @NotNull String getPluginVersion() {
    return Bukkit.getPluginManager().getPlugin("RTP").getDescription().getVersion();
  }

  @Override
  public @NotNull String getPlatform() {
    return "Folia";
  }

  @Override
  public Integer getServerIntVersion() {
    String version = Bukkit.getBukkitVersion().split("-")[0];
    String[] parts = version.split("\\.");
    if (parts.length < 2) return 0;
    try {
      return Integer.parseInt(parts[1]);
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  @Override
  public @Nullable RTPWorld<?> getRTPWorld(String name) {
    World world = Bukkit.getWorld(name);
    if (world == null) return null;
    return worldMap.computeIfAbsent(world.getUID(), uuid -> new FoliaRTPWorld(world));
  }

  @Override
  public @Nullable RTPWorld<?> getRTPWorld(UUID id) {
    World world = Bukkit.getWorld(id);
    if (world == null) return null;
    return worldMap.computeIfAbsent(id, uuid -> new FoliaRTPWorld(world));
  }

  @Override
  public abstract io.github.dailystruggle.rtp.api.world.RTPChunkManager getChunkManager();

  @Override
  public void executeTask(io.github.dailystruggle.rtp.common.tasks.RTPRunnable task) {
    if (!(plugin instanceof Plugin bukkitPlugin)) {
      RTP.scheduler.runTaskAsynchronously(task::runWithTracking);
      return;
    }

    RTPLocation rtpLoc = task.getLocation();
    RegionKey key = RegionKey.from(rtpLoc);

    if (key == null) {
      Bukkit.getAsyncScheduler().runNow(bukkitPlugin, scheduledTask -> task.runWithTracking());
    } else {
      regionQueues.computeIfAbsent(key, k -> new ConcurrentLinkedQueue<>()).add(task);
      if (activeProcessors.add(key)) {
        World world = Bukkit.getWorld(key.worldId());
        if (world != null) {
          Location bukkitLoc = new Location(world, key.regionX() << 9, 0, key.regionZ() << 9);
          FoliaRegionProcessor processor = new FoliaRegionProcessor(bukkitPlugin, key, regionQueues.get(key), activeProcessors);
          Bukkit.getRegionScheduler().run(bukkitPlugin, bukkitLoc, scheduledTask -> processor.run());
        }
      }
    }
  }

  @Override
  public @Nullable Object getShape(String name) {
    return shapeFunction.apply(name);
  }

  @Override
  public boolean isPrimaryThread() {
    return false;
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
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  @Override
  public @Nullable RTPPlayer getPlayer(UUID uuid) {
    Player player = Bukkit.getPlayer(uuid);
    if (player == null) return null;
    return new FoliaRTPPlayer(player);
  }

  @Override
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
  public long overTime() {
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
    message = tagMessage(message, tag);
    if (target.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
      return;
    }
    Player player = Bukkit.getPlayer(target);
    if (player != null) player.sendMessage(message);
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, MessagesKeys msgType, String tag) {
    ConfigParser<MessagesKeys> lang =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String message = lang.getConfigValue(msgType, "").toString();
    message = tagMessage(message, tag);
    Player p1 = Bukkit.getPlayer(target1);
    if (target1.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
    } else if (p1 != null) {
      p1.sendMessage(message);
    }
  }

  @Override
  public void sendMessage(UUID target, String message, String tag) {
    message = tagMessage(message, tag);
    if (target.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
      return;
    }
    Player player = Bukkit.getPlayer(target);
    if (player != null) player.sendMessage(message);
  }

  @Override
  public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
    message = tagMessage(message, null);
    // Folia implementation
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, String message, String tag) {
    message = tagMessage(message, tag);
    Player p1 = Bukkit.getPlayer(target1);
    if (target1.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
    } else if (p1 != null) {
      p1.sendMessage(message);
    }
  }

  @Override
  public void sendMessage(RTPCommandSender target, String message, String hover, String click, String tag) {
    // Folia implementation
  }

  @Override
  public String format(@Nullable UUID player, String text) {
    return text;
  }

  @Override
  public String formatNoColor(@Nullable UUID player, String text) {
    return text;
  }

  @Override
  public void log(Level level, String msg) {
    Bukkit.getLogger().log(level, msg);
  }

  @Override
  public void log(Level level, String msg, Throwable throwable) {
    Bukkit.getLogger().log(level, msg, throwable);
  }

  @Override
  public void announce(String msg, String permission, String tag) {
    msg = tagMessage(msg, tag);
    Bukkit.broadcast(msg, permission);
    if (!permission.equalsIgnoreCase("rtp.see")) {
      Bukkit.getConsoleSender().sendMessage(msg);
    }
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

  private Object plugin;

  @Override
  public void stop() {}

  @Override
  public void start() {
    start(plugin);
  }

  @Override
  public void start(Object plugin) {
    this.plugin = plugin;
    if (!(plugin instanceof org.bukkit.plugin.java.JavaPlugin)) return;
    org.bukkit.plugin.java.JavaPlugin javaPlugin = (org.bukkit.plugin.java.JavaPlugin) plugin;

    org.bukkit.Bukkit.getGlobalRegionScheduler().runAtFixedRate(javaPlugin, scheduledTask -> {
      RTP rtp = RTP.getInstance();
      if (rtp == null) return;

      if (rtp.miscSyncTasks != null) rtp.miscSyncTasks.execute();
      if (rtp.miscAsyncTasks != null) rtp.miscAsyncTasks.execute();
      if (rtp.cancelTasks != null) rtp.cancelTasks.execute();

      if (RTP.selectionAPI != null) {
        for (io.github.dailystruggle.rtp.common.selection.region.Region region : RTP.selectionAPI.permRegionLookup.values()) {
          region.execute(Long.MAX_VALUE);
        }
      }

      // Also process fill and database tasks as they are normally processed on Spigot via their own tasks
      if (rtp.fillTasks != null) {
        for (io.github.dailystruggle.rtp.common.tasks.FillTask fillTask : rtp.fillTasks.values()) {
          if (!fillTask.isRunning()) {
            rtp.scheduler.runTaskAsynchronously(fillTask);
          }
        }
      }

      if (rtp.databaseAccessor != null) {
        rtp.scheduler.runTaskAsynchronously(() -> rtp.databaseAccessor.processQueries(Long.MAX_VALUE));
      }
    }, 1, 1);
  }

  @Override
  public boolean setShapeFunction(Function<String, ?> shapeFunction) {
    this.shapeFunction = shapeFunction;
    return true;
  }

  @Override
  public boolean setWorldBorderFunction(Function<String, ?> function) {
    this.worldBorderFunction = function;
    return true;
  }

  @Override
  public RTPTaskPipe createTaskPipe() {
    return new CountBoundTaskPipe((Plugin) plugin, 10);
  }

  @Override
  public RTPTaskPipe createCachePipe() {
    return new CountBoundTaskPipe((Plugin) plugin, 20);
  }

  @Override
  public ILocationGenerator getLocationGenerator() {
    return new FoliaLocationGenerator();
  }

  @Override
  public io.github.dailystruggle.rtp.api.scheduling.RTPScheduler getScheduler() {
    return io.github.dailystruggle.rtp.common.RTP.scheduler;
  }

  @Override
  public double getTPS(int ticks) {
    try {
      return 20.0;
    } catch (Exception e) {
      return 20.0;
    }
  }

  @Override
  public void setBiomeGetter(Function<RTPLocation, String> getter) {
    FoliaRTPWorld.setBiomeGetter(
        location ->
            getter.apply(
                new RTPLocation(
                    getRTPWorld(location.getWorld().getUID()),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ())));
  }

  @Override
  public void setBiomesGetter(Function<RTPWorld<?>, Set<String>> getter) {
    this.biomes = getter;
    FoliaRTPWorld.setBiomesGetter(getter);
  }
}
