package io.github.dailystruggle.rtp.folia.server;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.folia.entity.FoliaRTPCommandSender;
import io.github.dailystruggle.rtp.folia.entity.FoliaRTPPlayer;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public abstract class AbstractFoliaServerAccessor implements RTPServerAccessor {
  protected final Map<UUID, FoliaRTPWorld> worldMap = new ConcurrentHashMap<>();
  protected Function<String, ?> shapeFunction = (s) -> null;
  protected Function<String, ?> worldBorderFunction = (s) -> null;
  protected Function<RTPWorld<?>, Set<String>> biomes = (rtpWorld) -> new HashSet<>();

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
  public @Nullable Object getShape(String name) {
    return shapeFunction.apply(name);
  }

  @Override
  public boolean isPrimaryThread() {
    return false;
  }

  @Override
  public @Nullable Object getWorldBorder(String worldName) {
    return worldBorderFunction.apply(worldName);
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
    // Folia implementation
  }

  @Override
  public void sendMessage(UUID target1, UUID target2, String message) {
    Player p1 = Bukkit.getPlayer(target1);
    if (target1.equals(RTPAPI.serverId)) {
      Bukkit.getConsoleSender().sendMessage(message);
    } else if (p1 != null) {
      p1.sendMessage(message);
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

  @Override
  public void stop() {}

  @Override
  public void start() {}

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
