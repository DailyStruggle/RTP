package io.github.dailystruggle.rtp.spigot.server;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.RegionKeys;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.Mode;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Square;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.enums.GenericMemoryShapeParams;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.selection.worldborder.WorldBorder;
import io.github.dailystruggle.rtp.spigot.entity.BukkitRTPCommandSender;
import io.github.dailystruggle.rtp.spigot.entity.BukkitRTPPlayer;
import io.github.dailystruggle.rtp.spigot.world.BukkitRTPWorld;
import io.github.dailystruggle.rtp.common.tasks.RTPTaskPipe;
import io.github.dailystruggle.rtp.common.tasks.TimeBoundTaskPipe;
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
  protected Function<String, WorldBorder> worldBorderFunction =
      s -> {
        RTPWorld<?> rtpWorld = getRTPWorld(s);
        if (rtpWorld instanceof BukkitRTPWorld) {
          World world = ((BukkitRTPWorld) rtpWorld).world();
          org.bukkit.WorldBorder worldBorder = world.getWorldBorder();
          return new WorldBorder(
              () -> {
                Shape<?> shape = (Shape<?>) RTP.serverAccessor.getShape(s);
                if (shape == null || !shape.name.equalsIgnoreCase("SQUARE"))
                  shape = (Shape<?>) RTP.factoryMap.get(RTP.factoryNames.shape).get("SQUARE");
                Square square = (Square) shape;
                square.set(
                    GenericMemoryShapeParams.radius, ((long) worldBorder.getSize() * 0.9) / 32);
                square.set(GenericMemoryShapeParams.centerRadius, 0L);
                square.set(
                    GenericMemoryShapeParams.centerX, worldBorder.getCenter().getBlockX() / 16);
                square.set(
                    GenericMemoryShapeParams.centerZ, worldBorder.getCenter().getBlockZ() / 16);
                square.set(GenericMemoryShapeParams.expand, false);
                square.set(GenericMemoryShapeParams.weight, 1);
                square.set(GenericMemoryShapeParams.mode, Mode.NEAREST);
                square.set(GenericMemoryShapeParams.uniquePlacements, false);
                return shape;
              },
              rtpLocation -> {
                if (getServerIntVersion() > 10)
                  return worldBorder.isInside(
                      new Location(world, rtpLocation.x(), rtpLocation.y(), rtpLocation.z()));
                Location center = worldBorder.getCenter();
                double radius = worldBorder.getSize() / 2;
                RTPLocation c =
                    new RTPLocation(
                        rtpWorld, center.getBlockX(), center.getBlockY(), center.getBlockZ());
                return c.distanceSquaredXZ(rtpLocation) < Math.pow(radius, 2);
              });
        }
        return null;
      };

  public AbstractServerAccessor() {
    shapeFunction =
        s -> {
          World world = Bukkit.getWorld(s);
          if (world == null) return null;
          Region region = RTP.selectionAPI.getRegion(getRTPWorld(world.getUID()));
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
  public abstract @NotNull io.github.dailystruggle.rtp.api.world.RTPChunkManager getChunkManager();

  @Override
  public void executeTask(io.github.dailystruggle.rtp.common.tasks.RTPRunnable task) {
    task.runWithTracking();
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
    return worldBorderFunction.apply(worldName);
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
    return Bukkit.getPluginManager().getPlugin("RTP").getDataFolder();
  }

  @Override
  public void sendMessage(UUID target, MessagesKeys msgType) {
    ConfigParser<MessagesKeys> lang =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String message = lang.getConfigValue(msgType, "").toString();
    if (target.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
      return;
    }
    Player player = Bukkit.getPlayer(target);
    if (player != null) player.sendMessage(message);
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, MessagesKeys msgType) {
    ConfigParser<MessagesKeys> lang =
        (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
    String message = lang.getConfigValue(msgType, "").toString();
    Player p1 = Bukkit.getPlayer(target1);
    if (target1.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
    } else if (p1 != null) {
      p1.sendMessage(message);
    }
  }

  @Override
  public void sendMessage(UUID target, String message) {
    if (target.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
      return;
    }
    Player player = Bukkit.getPlayer(target);
    if (player != null) player.sendMessage(message);
  }

  @Override
  public void sendMessageAndSuggest(UUID target, String message, String suggestion) {
    // Implementation
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, String message) {
    if (target1.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
    } else {
      Player p1 = Bukkit.getPlayer(target1);
      if (p1 != null) p1.sendMessage(message);
    }
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
  public void announce(String msg, String permission) {
    Bukkit.broadcast(msg, permission);
    if (!permission.equalsIgnoreCase("rtp.see")) {
      Bukkit.getConsoleSender().sendMessage(msg);
    }
  }

  @Override
  public @NotNull Set<String> getBiomes(RTPWorld<?> rtpWorld) {
    return biomes.apply(rtpWorld);
  }

  @Override
  public @NotNull Set<String> materials() {
    return Arrays.stream(Material.values())
        .map(material -> material.name().toUpperCase())
        .collect(Collectors.toSet());
  }

  private Object plugin;

  @Override
  public void stop() {
    // Implementation
  }

  @Override
  public void start() {
    start(plugin);
  }

  @Override
  public void start(Object plugin) {
    this.plugin = plugin;
    if (!(plugin instanceof org.bukkit.plugin.java.JavaPlugin)) return;
    org.bukkit.plugin.java.JavaPlugin javaPlugin = (org.bukkit.plugin.java.JavaPlugin) plugin;

    new io.github.dailystruggle.rtp.spigot.server.SyncTeleportProcessing().runTaskTimer(javaPlugin, 0, 1);
    new io.github.dailystruggle.rtp.spigot.server.AsyncTeleportProcessing(javaPlugin).runTaskTimer(javaPlugin, 0, 1);
    new io.github.dailystruggle.rtp.spigot.server.FillTaskProcessing(javaPlugin).runTaskTimer(javaPlugin, 0, 1);
    new io.github.dailystruggle.rtp.spigot.server.DatabaseProcessing(javaPlugin).runTaskTimer(javaPlugin, 0, 1);
  }

  @Override
  public boolean setShapeFunction(Function<String, ?> shapeFunction) {
    this.shapeFunction = (Function<String, Shape<?>>) shapeFunction;
    return true;
  }

  @Override
  public boolean setWorldBorderFunction(Function<String, ?> function) {
    this.worldBorderFunction = (Function<String, WorldBorder>) function;
    return true;
  }

  @Override
  public RTPTaskPipe createTaskPipe() {
    return new TimeBoundTaskPipe();
  }

  @Override
  public RTPTaskPipe createCachePipe() {
    return new TimeBoundTaskPipe();
  }

  @Override
  public void setBiomeGetter(Function<RTPLocation, String> getter) {
    BukkitRTPWorld.setBiomeGetter(
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
    BukkitRTPWorld.setBiomesGetter(getter);
  }
}
