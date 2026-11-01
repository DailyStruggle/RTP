package io.github.dailystruggle.rtp.bukkitplatform.server;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.effectsapi.EffectsAPI;
import io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer;
import io.github.dailystruggle.effectsapi.bukkit.BukkitHandles;
import io.github.dailystruggle.rtp.bukkitplatform.tools.MiniMessageRenderer;
import io.github.dailystruggle.server.api.BlockPos3i;
import io.github.dailystruggle.server.api.BookMenuSpec;
import io.github.dailystruggle.server.api.CallerLocation;
import io.github.dailystruggle.server.api.MiniMessageColorExpander;
import io.github.dailystruggle.server.api.PlayerLifecycleHook;
import io.github.dailystruggle.server.api.PlayerSleepSnapshot;
import io.github.dailystruggle.server.api.ServerAccessor;
import io.github.dailystruggle.server.api.ServerAccessors;
import io.github.dailystruggle.server.api.TaggedItemSpec;
import io.github.dailystruggle.server.api.TaskHandle;
import io.github.dailystruggle.server.api.TaskScheduler;
import io.github.dailystruggle.server.api.WeatherState;
import io.github.dailystruggle.server.api.WorldEventHook;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.structure.Structure;

/**
 * Shared Bukkit / Paper / Folia {@link ServerAccessor} implementation reusable as a standalone
 * plugin accessor or as the base class of RTP's {@code AbstractServerAccessor} (ADR-049, ADR-115,
 * ADR-116).
 *
 * <p>Provides {@link BukkitWorldEventHook} event subscriptions, regional/global task scheduling
 * (Folia region/global schedulers with Bukkit scheduler fallback), chunk PDC persistence (guarded by
 * region ownership and {@code isChunkLoaded} per S-005), block/structure placement, custom-tagged
 * item creation, and dynamic {@code CommandMap} registration.
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public class BukkitPluginServerAccessor implements ServerAccessor {

  protected Plugin plugin;
  protected String namespace;
  protected Integer intVersion;
  protected final BukkitWorldEventHook worldEventHook = new BukkitWorldEventHook();
  private boolean worldEventHookRegistered;
  private final Map<String, BlockData> blockDataCache = new ConcurrentHashMap<>();
  private final Map<String, NamespacedKey> keyCache = new ConcurrentHashMap<>();
  private final Map<String, Long> lastWorldTimeCache = new ConcurrentHashMap<>();
  private final Map<String, Long> lastWorldFullTimeCache = new ConcurrentHashMap<>();
  private final Map<String, WeatherState> lastWeatherCache = new ConcurrentHashMap<>();
  private final Map<UUID, PlayerSleepSnapshot> playerSleepSnapshotCache =
      new ConcurrentHashMap<>();
  private final Map<UUID, CallerLocation> callerLocationCache = new ConcurrentHashMap<>();
  private final AtomicLong lastRepeatingTickWallNanos = new AtomicLong(Long.MIN_VALUE);
  private volatile double sampledTickIntervalNanos = 50_000_000.0;
  private volatile double sampledWorkDurationNanos = 500_000.0;

  /** No-arg constructor for subclasses that bind a {@link Plugin} later via {@link #bindPlugin}. */
  protected BukkitPluginServerAccessor() {
    this.plugin = null;
    this.namespace = "rtp";
    this.worldEventHook.bindSleepSnapshotHooks(
        this::recordPlayerSleepSnapshotOnEntityThread, this::clearPlayerCaches);
  }

  public BukkitPluginServerAccessor(JavaPlugin plugin) {
    this((Plugin) plugin);
  }

  public BukkitPluginServerAccessor(Plugin plugin) {
    this(
        plugin,
        plugin != null ? plugin.getName().toLowerCase(Locale.ROOT).replace('_', '-') : "rtp");
  }

  public BukkitPluginServerAccessor(JavaPlugin plugin, String namespace) {
    this((Plugin) plugin, namespace);
  }

  public BukkitPluginServerAccessor(Plugin plugin, String namespace) {
    this.plugin = plugin;
    this.namespace = (namespace != null && !namespace.isBlank()) ? namespace : "rtp";
    this.worldEventHook.bindSleepSnapshotHooks(
        this::recordPlayerSleepSnapshotOnEntityThread, this::clearPlayerCaches);
    installDefaultBindings();
  }

  private void installDefaultBindings() {
    if (ServerAccessors.get() == null) {
      ServerAccessors.set(this);
    }
    if (CommandsAPI.getMessageSink() == null) {
      CommandsAPI.setMessageSink((callerId, rawTemplate) -> sendMessage(callerId, rawTemplate, false));
    }
  }

  /**
   * Binds the owning Bukkit {@link Plugin} and registers {@link BukkitWorldEventHook} once.
   *
   * @param bukkitPlugin owning plugin; must not be {@code null}
   */
  protected final void bindPlugin(Plugin bukkitPlugin) {
    this.plugin = bukkitPlugin;
    if (bukkitPlugin != null) {
      String name = bukkitPlugin.getName();
      if (name != null && !name.isBlank()) {
        this.namespace = name.toLowerCase(Locale.ROOT).replace('_', '-');
      }
      registerEvents();
    }
  }

  /** Registers the underlying {@link BukkitWorldEventHook} with the server plugin manager. */
  public void registerEvents() {
    if (plugin != null && !worldEventHookRegistered) {
      worldEventHook.register(plugin);
      worldEventHookRegistered = true;
      try {
        EffectsAPI.init(plugin);
        BukkitEffectsInitializer.registerAll();
      } catch (RuntimeException ex) {
        logger().log(Level.WARNING, "BukkitEffectsInitializer registration failed", ex);
      }
    }
  }

  @Override
  public String getServerVersion() {
    return Bukkit.getVersion();
  }

  @Override
  public String getPlatform() {
    return isFolia() ? "folia" : "paper";
  }

  @Override
  public Integer getServerIntVersion() {
    if (intVersion == null) {
      String v = getServerVersion();
      if (v == null) intVersion = 21;
      else if (v.contains("1_13") || v.contains("1.13")) intVersion = 13;
      else if (v.contains("1_14") || v.contains("1.14")) intVersion = 14;
      else if (v.contains("1_15") || v.contains("1.15")) intVersion = 15;
      else if (v.contains("1_16") || v.contains("1.16")) intVersion = 16;
      else if (v.contains("1_17") || v.contains("1.17")) intVersion = 17;
      else if (v.contains("1_18") || v.contains("1.18")) intVersion = 18;
      else if (v.contains("1_19") || v.contains("1.19")) intVersion = 19;
      else if (v.contains("1_20") || v.contains("1.20")) intVersion = 20;
      else if (v.contains("1_21") || v.contains("1.21")) intVersion = 21;
      else if (v.contains("26_") || v.contains("26.")) intVersion = 26;
      else intVersion = 13;
    }
    return intVersion;
  }

  @Override
  public void log(Level level, String message) {
    logger().log(level, message);
  }

  @Override
  public void log(Level level, String message, Throwable throwable) {
    logger().log(level, message, throwable);
  }

  @Override
  public Logger logger() {
    return plugin != null ? plugin.getLogger() : Bukkit.getLogger();
  }

  @Override
  public File getPluginDirectory() {
    return plugin != null ? plugin.getDataFolder() : new File(".");
  }

  @Override
  public Path getRootFolder() {
    return getPluginDirectory().toPath();
  }

  @Override
  public WorldEventHook getWorldEventHook() {
    return worldEventHook;
  }

  @Override
  public PlayerLifecycleHook getPlayerLifecycleHook() {
    return worldEventHook.playerLifecycleHook();
  }

  @Override
  public Object wrapEffectPlayer(Object player) {
    if (player instanceof Player bukkitPlayer) {
      return BukkitHandles.getInstance().wrapPlayer(bukkitPlayer);
    }
    if (player instanceof UUID playerId) {
      Player resolved = Bukkit.getPlayer(playerId);
      if (resolved != null) {
        return BukkitHandles.getInstance().wrapPlayer(resolved);
      }
    }
    return null;
  }

  @Override
  public Path getWorldFolder(String worldName) {
    World world = Bukkit.getWorld(worldName);
    if (world != null) {
      return resolveLevelFolder(world.getWorldFolder().toPath());
    }
    File folder = new File(worldName);
    if (folder.exists() && folder.isDirectory()) {
      return folder.toPath();
    }
    return null;
  }

  private static Path resolveLevelFolder(Path worldFolder) {
    Path current = worldFolder;
    for (int depth = 0; depth < 8 && current != null; depth++) {
      if (new File(current.toFile(), "datapacks").isDirectory()) {
        return current;
      }
      current = current.getParent();
    }
    return worldFolder;
  }

  @Override
  public List<String> getWorldNames() {
    List<String> names = new ArrayList<>();
    for (World w : Bukkit.getWorlds()) {
      names.add(w.getName());
    }
    return Collections.unmodifiableList(names);
  }

  @Override
  public Set<String> getOnlinePlayerNames() {
    Set<String> out = new HashSet<>();
    for (Player p : Bukkit.getOnlinePlayers()) {
      out.add(p.getName());
    }
    return Collections.unmodifiableSet(out);
  }

  @Override
  public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
    if (plugin == null || task == null) return;
    long safeDelay = Math.max(1L, delayTicks);
    long safePeriod = Math.max(1L, periodTicks);
    Runnable instrumented =
        safePeriod == 1L
            ? () -> {
              long start = System.nanoTime();
              long prev = lastRepeatingTickWallNanos.getAndSet(start);
              if (prev != Long.MIN_VALUE) {
                long delta = start - prev;
                if (delta > 0L && delta <= 500_000_000L) {
                  sampledTickIntervalNanos =
                      sampledTickIntervalNanos + (delta - sampledTickIntervalNanos) / 100.0;
                }
              }
              try {
                task.run();
              } finally {
                long elapsed = Math.max(0L, System.nanoTime() - start);
                sampledWorkDurationNanos =
                    sampledWorkDurationNanos + (elapsed - sampledWorkDurationNanos) / 40.0;
              }
            }
            : task;
    if (!scheduleFoliaGlobalRepeating(plugin, instrumented, safeDelay, safePeriod)) {
      Bukkit.getScheduler().runTaskTimer(plugin, instrumented, safeDelay, safePeriod);
    }
  }

  @Override
  public Optional<TaskScheduler> scheduler() {
    if (plugin == null) {
      return Optional.empty();
    }
    return Optional.of(
        new TaskScheduler() {
          @Override
          public TaskHandle runAsync(Runnable task) {
            if (task == null) {
              return noopTaskHandle();
            }
            if (plugin.isEnabled()) {
              if (isFolia() && scheduleFoliaAsyncOneShot(plugin, task)) {
                return noopTaskHandle();
              }
              try {
                Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
                return noopTaskHandle();
              } catch (RuntimeException ex) {
                logger().log(Level.WARNING, "Async task dispatch fallback", ex);
              }
            }
            task.run();
            return noopTaskHandle();
          }

          @Override
          public TaskHandle runSync(Runnable task) {
            if (task != null) {
              runOnGlobalRegionOrMain(task);
            }
            return noopTaskHandle();
          }

          @Override
          public TaskHandle runSyncTimer(Runnable task, long delayTicks, long periodTicks) {
            if (task == null || !plugin.isEnabled()) {
              return noopTaskHandle();
            }
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Runnable guarded = () -> {
              if (!cancelled.get()) {
                task.run();
              }
            };
            scheduleRepeatingTask(guarded, delayTicks, periodTicks);
            return new TaskHandle() {
              @Override
              public void cancel() {
                cancelled.set(true);
              }

              @Override
              public boolean isCancelled() {
                return cancelled.get();
              }
            };
          }

          @Override
          public TaskHandle runAsyncTimer(Runnable task, long delayTicks, long periodTicks) {
            if (task == null || !plugin.isEnabled()) {
              return noopTaskHandle();
            }
            AtomicBoolean cancelled = new AtomicBoolean(false);
            Runnable guarded = () -> {
              if (!cancelled.get()) {
                runAsync(task);
              }
            };
            scheduleRepeatingTask(guarded, delayTicks, periodTicks);
            return new TaskHandle() {
              @Override
              public void cancel() {
                cancelled.set(true);
              }

              @Override
              public boolean isCancelled() {
                return cancelled.get();
              }
            };
          }

          @Override
          public boolean isPrimaryThread() {
            return BukkitPluginServerAccessor.this.isPrimaryThread();
          }
        });
  }

  private static TaskHandle noopTaskHandle() {
    AtomicBoolean cancelled = new AtomicBoolean(false);
    return new TaskHandle() {
      @Override
      public void cancel() {
        cancelled.set(true);
      }

      @Override
      public boolean isCancelled() {
        return cancelled.get();
      }
    };
  }

  @Override
  public boolean isPrimaryThread() {
    if (isFolia()) {
      try {
        Method m = Bukkit.class.getMethod("isGlobalTickThread");
        Object res = m.invoke(null);
        if (res instanceof Boolean b) {
          return b;
        }
      } catch (ReflectiveOperationException | RuntimeException ex) {
        logger().log(Level.FINE, "Folia global tick thread probe fallback", ex);
      }
    }
    try {
      return Bukkit.isPrimaryThread();
    } catch (RuntimeException ex) {
      return true;
    }
  }

  @Override
  public double getTPS(int ticks) {
    if (!isFolia()) {
      try {
        Method getTps = Bukkit.getServer().getClass().getMethod("getTPS");
        Object raw = getTps.invoke(Bukkit.getServer());
        if (raw instanceof double[] tps && tps.length > 0) {
          int idx = ticks >= 600 ? Math.min(2, tps.length - 1) : (ticks >= 100 ? Math.min(1, tps.length - 1) : 0);
          double val = tps[idx];
          if (Double.isFinite(val) && val > 0.0) {
            return Math.min(20.0, val);
          }
        }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
        logger().log(Level.FINE, "Server#getTPS fallback to sampled interval", ex);
      }
    }
    double interval = sampledTickIntervalNanos;
    if (interval > 0.0) {
      return Math.min(20.0, Math.max(0.0, 1_000_000_000.0 / interval));
    }
    return 20.0;
  }

  @Override
  public double getMSPT() {
    if (!isFolia()) {
      try {
        Method getAvg = Bukkit.getServer().getClass().getMethod("getAverageTickTime");
        Object raw = getAvg.invoke(Bukkit.getServer());
        if (raw instanceof Number n) {
          double mspt = n.doubleValue();
          if (Double.isFinite(mspt) && mspt >= 0.0) {
            return mspt;
          }
        }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
        logger().log(Level.FINE, "Server#getAverageTickTime fallback to sampled duration", ex);
      }
    } else {
      double foliaMspt = readFoliaRegionMspt();
      if (foliaMspt > 0.0) {
        return foliaMspt;
      }
    }
    return Math.max(0.0, sampledWorkDurationNanos / 1_000_000.0);
  }

  private double readFoliaRegionMspt() {
    try {
      Class<?> trs = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler");
      Object currentRegion = trs.getMethod("getCurrentRegion").invoke(null);
      if (currentRegion != null) {
        Object data = currentRegion.getClass().getMethod("getData").invoke(currentRegion);
        if (data != null) {
          Object handle =
              data.getClass().getMethod("getRegionSchedulingHandle").invoke(data);
          if (handle != null) {
            Object report =
                handle.getClass().getMethod("getTickReport5s", long.class).invoke(handle, System.nanoTime());
            if (report != null) {
              Object timePerTick = report.getClass().getMethod("timePerTickData").invoke(report);
              Object segAll = timePerTick.getClass().getMethod("segmentAll").invoke(timePerTick);
              Object avgNanos = segAll.getClass().getMethod("average").invoke(segAll);
              if (avgNanos instanceof Number n && n.doubleValue() > 0.0) {
                return n.doubleValue() / 1_000_000.0;
              }
            }
          }
        }
      }
    } catch (ReflectiveOperationException | RuntimeException ex) {
      logger().log(Level.FINE, "Folia TickRegionScheduler report fallback", ex);
    }
    return -1.0;
  }

  @Override
  public void scheduleDelayedTask(
      String worldName, int chunkX, int chunkZ, Runnable task, long delayTicks) {
    if (task == null) return;
    World world = Bukkit.getWorld(worldName);
    if (world == null) return;
    if (delayTicks <= 0L) {
      if (isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
        task.run();
      } else if (plugin != null && !scheduleFoliaRegion(plugin, world, chunkX, chunkZ, task, 0L)) {
        Bukkit.getScheduler().runTask(plugin, task);
      }
    } else if (plugin != null
        && !scheduleFoliaRegion(plugin, world, chunkX, chunkZ, task, delayTicks)) {
      Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }
  }

  @Override
  public String getBiomeKey(String worldName, int x, int y, int z) {
    World world = Bukkit.getWorld(worldName);
    return BukkitWorldEventHook.readBiomeKey(world, x, y, z);
  }

  private NamespacedKey namespacedKey(String key) {
    return keyCache.computeIfAbsent(
        key,
        k -> {
          int colon = k.indexOf(':');
          if (colon > 0) {
            return new NamespacedKey(k.substring(0, colon), k.substring(colon + 1));
          }
          return new NamespacedKey(namespace, k);
        });
  }

  @Override
  public String getChunkData(String worldName, int chunkX, int chunkZ, String key) {
    World world = Bukkit.getWorld(worldName);
    if (world == null || key == null) return null;
    if (!isOwnedByCurrentRegion(world, chunkX, chunkZ) || !world.isChunkLoaded(chunkX, chunkZ)) {
      return null;
    }
    return readChunkPdcLoaded(world, chunkX, chunkZ, namespacedKey(key));
  }

  @Override
  public void setChunkData(String worldName, int chunkX, int chunkZ, String key, String data) {
    World world = Bukkit.getWorld(worldName);
    if (world == null || key == null) return;
    NamespacedKey nsKey = namespacedKey(key);
    if (isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
      if (world.isChunkLoaded(chunkX, chunkZ)) {
        writeChunkPdcLoaded(world, chunkX, chunkZ, nsKey, data);
      }
    } else {
      scheduleDelayedTask(
          worldName,
          chunkX,
          chunkZ,
          () -> {
            if (world.isChunkLoaded(chunkX, chunkZ)) {
              writeChunkPdcLoaded(world, chunkX, chunkZ, nsKey, data);
            }
          },
          0L);
    }
  }

  @Override
  public void updateChunkData(
      String worldName, int chunkX, int chunkZ, String key, UnaryOperator<String> updater) {
    World world = Bukkit.getWorld(worldName);
    if (world == null || key == null) return;
    NamespacedKey nsKey = namespacedKey(key);
    if (isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
      if (!world.isChunkLoaded(chunkX, chunkZ)) return;
      String current = readChunkPdcLoaded(world, chunkX, chunkZ, nsKey);
      writeChunkPdcLoaded(world, chunkX, chunkZ, nsKey, updater.apply(current));
    } else {
      scheduleDelayedTask(
          worldName,
          chunkX,
          chunkZ,
          () -> {
            if (!world.isChunkLoaded(chunkX, chunkZ)) return;
            String current = readChunkPdcLoaded(world, chunkX, chunkZ, nsKey);
            writeChunkPdcLoaded(world, chunkX, chunkZ, nsKey, updater.apply(current));
          },
          0L);
    }
  }

  private static String readChunkPdcLoaded(World world, int chunkX, int chunkZ, NamespacedKey key) {
    return world
        .getChunkAt(chunkX, chunkZ)
        .getPersistentDataContainer()
        .get(key, PersistentDataType.STRING);
  }

  private static void writeChunkPdcLoaded(
      World world, int chunkX, int chunkZ, NamespacedKey key, String data) {
    PersistentDataContainer pdc = world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer();
    if (data == null || data.isEmpty()) {
      pdc.remove(key);
    } else {
      pdc.set(key, PersistentDataType.STRING, data);
    }
  }

  @Override
  public boolean isReplaceable(String worldName, int x, int y, int z) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return false;
    int cx = x >> 4;
    int cz = z >> 4;
    if (!isOwnedByCurrentRegion(world, cx, cz) || !world.isChunkLoaded(cx, cz)) {
      return false;
    }
    Material type = world.getBlockAt(x, y, z).getType();
    if (type.isAir() || !type.isSolid()) {
      return true;
    }
    String name = type.name();
    return name.endsWith("_LEAVES") || name.endsWith("_SAPLING") || name.equals("SNOW");
  }

  @Override
  public String getBlockState(String worldName, int x, int y, int z) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return null;
    int cx = x >> 4;
    int cz = z >> 4;
    if (!isOwnedByCurrentRegion(world, cx, cz) || !world.isChunkLoaded(cx, cz)) {
      return null;
    }
    return BukkitWorldEventHook.readKeyedOrName(world.getBlockAt(x, y, z).getType());
  }

  @Override
  public int getHighestBlockY(String worldName, int x, int z) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return Integer.MIN_VALUE;
    int cx = x >> 4;
    int cz = z >> 4;
    if (!isOwnedByCurrentRegion(world, cx, cz) || !world.isChunkLoaded(cx, cz)) {
      return Integer.MIN_VALUE;
    }
    return world.getHighestBlockYAt(x, z);
  }

  @Override
  public int getMinHeight(String worldName) {
    World world = Bukkit.getWorld(worldName);
    return world != null ? world.getMinHeight() : -64;
  }

  @Override
  public boolean isChunkLoaded(String worldName, int chunkX, int chunkZ) {
    World world = Bukkit.getWorld(worldName);
    return world != null && world.isChunkLoaded(chunkX, chunkZ);
  }

  @Override
  public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return false;
    NamespacedKey key = NamespacedKey.fromString(structureKey);
    if (key == null) return false;

    Structure s = Bukkit.getStructureManager().getStructure(key);
    if (s == null) {
      try {
        s = Bukkit.getStructureManager().loadStructure(key);
      } catch (Exception ignored) {
      }
    }
    if (s == null) return false;

    Location loc = new Location(world, x, y, z);
    int offsetX = s.getSize().getBlockX() / 2;
    int offsetZ = s.getSize().getBlockZ() / 2;
    Location origin = loc.clone().subtract(offsetX, 0, offsetZ);

    try {
      s.place(origin, true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
      return true;
    } catch (Exception ex) {
      logger().warning("Failed to place " + structureKey + ": " + ex.getMessage());
      return false;
    }
  }

  private BlockData getCachedBlockData(String data) {
    return blockDataCache.computeIfAbsent(
        data,
        k -> {
          BlockData bd = Bukkit.createBlockData(k);
          if (bd instanceof Leaves leaves) {
            if (!k.contains("persistent")) {
              leaves.setPersistent(false);
            }
            if (!k.contains("distance")) {
              leaves.setDistance(1);
            }
          }
          return bd;
        });
  }

  @Override
  public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
    World world = Bukkit.getWorld(worldName);
    if (world == null) return false;
    try {
      world.getBlockAt(x, y, z).setBlockData(getCachedBlockData(blockData), false);
      return true;
    } catch (Exception ex) {
      logger()
          .warning(
              "Failed to set block "
                  + blockData
                  + " at "
                  + x
                  + ","
                  + y
                  + ","
                  + z
                  + ": "
                  + ex.getMessage());
      return false;
    }
  }

  @Override
  public boolean setChunkBlocks(
      String worldName, int chunkX, int chunkZ, Map<BlockPos3i, String> blocks) {
    World world = Bukkit.getWorld(worldName);
    if (world == null || blocks == null || blocks.isEmpty()) return false;
    if (!isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
      scheduleDelayedTask(
          worldName, chunkX, chunkZ, () -> applyChunkBlocksLoaded(world, chunkX, chunkZ, blocks), 0L);
      return true;
    }
    return applyChunkBlocksLoaded(world, chunkX, chunkZ, blocks);
  }

  private boolean applyChunkBlocksLoaded(
      World world, int chunkX, int chunkZ, Map<BlockPos3i, String> blocks) {
    if (!world.isChunkLoaded(chunkX, chunkZ)) {
      return false;
    }
    Chunk chunk = world.getChunkAt(chunkX, chunkZ);
    List<BlockState> statesToUpdate = new ArrayList<>(blocks.size());
    try {
      for (Map.Entry<BlockPos3i, String> entry : blocks.entrySet()) {
        String data = entry.getValue();
        if (data == null) continue;
        String base = data.split("\\[")[0].toLowerCase(Locale.ROOT);
        if (base.equals("minecraft:air")
            || base.equals("minecraft:cave_air")
            || base.equals("air")
            || base.equals("cave_air")) {
          continue;
        }
        BlockData bd;
        try {
          bd = getCachedBlockData(data);
        } catch (Exception parseEx) {
          logger()
              .warning(
                  "Skipping invalid block data '"
                      + data
                      + "' in chunk "
                      + chunkX
                      + ","
                      + chunkZ
                      + ": "
                      + parseEx.getMessage());
          continue;
        }
        BlockPos3i pos = entry.getKey();
        BlockState state = chunk.getBlock(pos.x() & 0xF, pos.y(), pos.z() & 0xF).getState();
        state.setBlockData(bd);
        statesToUpdate.add(state);
      }
      for (BlockState state : statesToUpdate) {
        state.update(true, false);
      }
      return true;
    } catch (Exception ex) {
      logger()
          .warning(
              "Failed to set batch of blocks in chunk "
                  + chunkX
                  + ","
                  + chunkZ
                  + ": "
                  + ex.getMessage());
      return false;
    }
  }

  @Override
  public boolean giveItem(UUID fallbackPlayerId, String playerName, TaggedItemSpec spec) {
    if (spec == null) return false;
    Player target =
        (playerName != null && !playerName.isBlank())
            ? Bukkit.getPlayerExact(playerName)
            : (fallbackPlayerId != null && !fallbackPlayerId.equals(CommandsAPI.serverId)
                ? Bukkit.getPlayer(fallbackPlayerId)
                : null);
    if (target == null) return false;
    ItemStack stack = buildItemStack(spec);
    target
        .getInventory()
        .addItem(stack)
        .values()
        .forEach(leftover -> target.getWorld().dropItemNaturally(target.getLocation(), leftover));
    return true;
  }

  @Override
  public void dropItem(String worldName, int x, int y, int z, TaggedItemSpec spec) {
    World world = Bukkit.getWorld(worldName);
    if (world == null || spec == null) return;
    Location dropAt = new Location(world, x + 0.5, y + 0.5, z + 0.5);
    world.dropItemNaturally(dropAt, buildItemStack(spec));
  }

  @SuppressWarnings("deprecation")
  private ItemStack buildItemStack(TaggedItemSpec spec) {
    Material mat = Material.matchMaterial(spec.itemKey());
    if (mat == null) {
      mat = Material.OAK_SAPLING;
    }
    ItemStack item = new ItemStack(mat, Math.max(1, spec.amount()));
    ItemMeta meta = item.getItemMeta();
    if (meta != null) {
      if (!spec.displayName().isBlank()) {
        meta.setDisplayName(ChatColor.GREEN + spec.displayName());
      }
      if (!spec.lore().isEmpty()) {
        List<String> loreLines = new ArrayList<>(spec.lore().size());
        for (int i = 0; i < spec.lore().size(); i++) {
          ChatColor color = i == 0 ? ChatColor.GRAY : ChatColor.DARK_GRAY;
          loreLines.add(color + spec.lore().get(i));
        }
        meta.setLore(loreLines);
      }
      PersistentDataContainer pdc = meta.getPersistentDataContainer();
      for (Map.Entry<String, String> tag : spec.customTags().entrySet()) {
        pdc.set(namespacedKey(tag.getKey()), PersistentDataType.STRING, tag.getValue());
      }
      item.setItemMeta(meta);
    }
    return item;
  }

  @Override
  public void sendMessage(UUID playerId, String message, boolean error) {
    if (message == null || message.isEmpty()) return;
    boolean hasMarkup =
        message.indexOf('&') >= 0
            || message.indexOf('§') >= 0
            || message.indexOf('#') >= 0
            || message.indexOf('<') >= 0;
    String raw = hasMarkup ? message : ((error ? "&c" : "&a") + message);
    if (Bukkit.getServer() == null) return;
    CommandSender targetSender;
    if (playerId == null || playerId.equals(CommandsAPI.serverId)) {
      targetSender = Bukkit.getConsoleSender();
    } else {
      Player p = Bukkit.getPlayer(playerId);
      targetSender = (p != null) ? p : Bukkit.getConsoleSender();
    }
    if (targetSender != null) {
      deliverFormattedMessage(targetSender, raw);
    }
  }

  private static void deliverFormattedMessage(CommandSender targetSender, String raw) {
    if (targetSender == null || raw == null || raw.isEmpty()) return;
    if (raw.indexOf('<') >= 0) {
      try {
        BaseComponent[] rendered = MiniMessageRenderer.render(raw);
        if (targetSender instanceof Player p && p.spigot() != null) {
          p.spigot().sendMessage(rendered);
          return;
        }
      } catch (RuntimeException | LinkageError ignored) {
        // Pure Spigot fallback
      }
    }
    String expanded = MiniMessageColorExpander.expand(raw);
    targetSender.sendMessage(ChatColor.translateAlternateColorCodes('&', expanded));
  }

  @Override
  public boolean openBook(UUID playerId, BookMenuSpec book) {
    if (playerId == null || playerId.equals(CommandsAPI.serverId) || book == null) {
      return false;
    }
    try {
      Player player = Bukkit.getPlayer(playerId);
      if (player == null || !player.isOnline()) {
        return false;
      }
      ItemStack writtenBook = new ItemStack(Material.WRITTEN_BOOK, 1);
      ItemMeta rawMeta = writtenBook.getItemMeta();
      if (!(rawMeta instanceof BookMeta bookMeta)) {
        return false;
      }
      bookMeta.setTitle(ChatColor.translateAlternateColorCodes('&', book.title()));
      bookMeta.setAuthor(ChatColor.translateAlternateColorCodes('&', book.author()));
      List<BaseComponent[]> pages = new ArrayList<>(Math.max(1, book.pages().size()));
      for (BookMenuSpec.Page page : book.pages()) {
        pages.add(toBungeePage(page));
      }
      if (pages.isEmpty()) {
        pages.add(new BaseComponent[] {new TextComponent("")});
      }
      bookMeta.spigot().setPages(pages);
      writtenBook.setItemMeta(bookMeta);
      runOnEntityOrMain(player, () -> player.openBook(writtenBook));
      return true;
    } catch (RuntimeException | LinkageError ex) {
      logger().log(Level.FINE, "openBook fallback to interactive chat", ex);
      return false;
    }
  }

  @Override
  public void sendInteractiveLine(UUID playerId, BookMenuSpec.Line line) {
    if (line == null) {
      return;
    }
    String plainColored = ChatColor.translateAlternateColorCodes('&', line.plainText());
    if (playerId == null || playerId.equals(CommandsAPI.serverId)) {
      if (Bukkit.getServer() != null && Bukkit.getConsoleSender() != null) {
        Bukkit.getConsoleSender().sendMessage(plainColored);
      }
      return;
    }
    Player player = Bukkit.getServer() != null ? Bukkit.getPlayer(playerId) : null;
    if (player == null) {
      if (Bukkit.getServer() != null && Bukkit.getConsoleSender() != null) {
        Bukkit.getConsoleSender().sendMessage(plainColored);
      }
      return;
    }
    try {
      BaseComponent[] components = toBungeeLine(line, false);
      Player.Spigot spigot = player.spigot();
      if (spigot != null) {
        spigot.sendMessage(components);
        return;
      }
    } catch (RuntimeException | LinkageError ex) {
      logger().log(Level.FINE, "sendInteractiveLine fallback to plain text", ex);
    }
    player.sendMessage(plainColored);
  }

  private static BaseComponent[] toBungeePage(BookMenuSpec.Page page) {
    List<BaseComponent> out = new ArrayList<>();
    List<BookMenuSpec.Line> lines = page.lines();
    for (int i = 0; i < lines.size(); i++) {
      if (i > 0) {
        out.add(new TextComponent("\n"));
      }
      Collections.addAll(out, toBungeeLine(lines.get(i), true));
    }
    if (out.isEmpty()) {
      out.add(new TextComponent(""));
    }
    return out.toArray(new BaseComponent[0]);
  }

  private static BaseComponent[] toBungeeLine(BookMenuSpec.Line line, boolean allowChangePage) {
    List<BaseComponent> out = new ArrayList<>();
    for (BookMenuSpec.Fragment fragment : line.fragments()) {
      String colored = ChatColor.translateAlternateColorCodes('&', fragment.text());
      BaseComponent[] segs = TextComponent.fromLegacyText(colored);
      HoverEvent hoverEv = null;
      if (fragment.hover() != null && !fragment.hover().isEmpty()) {
        String hoverColored = ChatColor.translateAlternateColorCodes('&', fragment.hover());
        hoverEv = new HoverEvent(
            HoverEvent.Action.SHOW_TEXT, new Text(TextComponent.fromLegacyText(hoverColored)));
      }
      ClickEvent clickEv = toBungeeClickEvent(fragment, allowChangePage);
      for (BaseComponent seg : segs) {
        if (hoverEv != null) {
          seg.setHoverEvent(hoverEv);
        }
        if (clickEv != null) {
          seg.setClickEvent(clickEv);
        }
        out.add(seg);
      }
    }
    if (out.isEmpty()) {
      out.add(new TextComponent(""));
    }
    return out.toArray(new BaseComponent[0]);
  }

  private static ClickEvent toBungeeClickEvent(
      BookMenuSpec.Fragment fragment, boolean allowChangePage) {
    if (fragment.clickAction() == null || fragment.clickValue() == null) {
      return null;
    }
    return switch (fragment.clickAction()) {
      case RUN_COMMAND -> new ClickEvent(ClickEvent.Action.RUN_COMMAND, fragment.clickValue());
      case SUGGEST_COMMAND ->
          new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, fragment.clickValue());
      case OPEN_URL -> new ClickEvent(ClickEvent.Action.OPEN_URL, fragment.clickValue());
      case CHANGE_PAGE -> {
        if (!allowChangePage) {
          yield null;
        }
        int oneBased = 1;
        try {
          oneBased = Math.max(1, Integer.parseInt(fragment.clickValue()) + 1);
        } catch (NumberFormatException ignored) {
          // keep page 1
        }
        yield new ClickEvent(ClickEvent.Action.CHANGE_PAGE, Integer.toString(oneBased));
      }
      case NONE -> null;
    };
  }

  @Override
  public CallerLocation resolveCallerLocation(UUID callerId) {
    if (callerId == null || callerId.equals(CommandsAPI.serverId)) return null;
    Player player = Bukkit.getPlayer(callerId);
    if (player == null) return callerLocationCache.get(callerId);
    if (!isEntityOwnedByCurrentRegion(player)) {
      runOnEntityOrMain(
          player,
          () -> {
            if (player.isOnline()) {
              recordPlayerSleepSnapshotOnEntityThread(player, null);
            }
          });
      return callerLocationCache.get(callerId);
    }
    Location playerLoc = player.getLocation();
    World world = playerLoc.getWorld();
    if (world == null) return callerLocationCache.get(callerId);
    Location base = playerLoc;
    int pcx = playerLoc.getBlockX() >> 4;
    int pcz = playerLoc.getBlockZ() >> 4;
    if (isOwnedByCurrentRegion(world, pcx, pcz) && world.isChunkLoaded(pcx, pcz)) {
      try {
        Block target = player.getTargetBlockExact(16);
        if (target != null
            && isOwnedByCurrentRegion(world, target.getX() >> 4, target.getZ() >> 4)
            && world.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) {
          base = target.getLocation().add(0, 1, 0);
        }
      } catch (RuntimeException ex) {
        logger().log(Level.WARNING, "Target block lookup fallback to player location", ex);
      }
    }
    CallerLocation resolved =
        new CallerLocation(world.getName(), base.getBlockX(), base.getBlockY(), base.getBlockZ());
    callerLocationCache.put(callerId, resolved);
    return resolved;
  }

  @Override
  public void registerCommands(Object rootCommand, String... aliases) {
    if (!(rootCommand instanceof TreeCommand command)) return;
    installDefaultBindings();
    Command wrapper =
        new Command(command.name()) {
          @Override
          public boolean execute(CommandSender sender, String label, String[] args) {
            if (sender instanceof Player player && isEntityOwnedByCurrentRegion(player)) {
              recordPlayerSleepSnapshotOnEntityThread(player, null);
            }
            UUID callerId =
                (sender instanceof Player player) ? player.getUniqueId() : CommandsAPI.serverId;
            Consumer<String> fallback = msg -> deliverFormattedMessage(sender, msg);
            Consumer<String> messageMethod = CommandsAPI.messageMethodFor(callerId, fallback);
            command.onCommand(callerId, sender::hasPermission, messageMethod, args);
            CommandsAPI.execute();
            return true;
          }

          @Override
          public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (sender instanceof Player player && isEntityOwnedByCurrentRegion(player)) {
              recordPlayerSleepSnapshotOnEntityThread(player, null);
            }
            UUID callerId =
                (sender instanceof Player player) ? player.getUniqueId() : CommandsAPI.serverId;
            List<String> result = command.onTabComplete(callerId, sender::hasPermission, args);
            return result != null ? result : Collections.emptyList();
          }
        };
    wrapper.setDescription(command.description());
    wrapper.setPermission(command.permission());
    if (aliases != null && aliases.length > 0) {
      wrapper.setAliases(List.of(aliases));
    }
    String fallbackPrefix =
        plugin != null ? plugin.getName().toLowerCase(Locale.ROOT) : namespace;
    try {
      Method getCommandMap = Bukkit.getServer().getClass().getMethod("getCommandMap");
      Object mapObj = getCommandMap.invoke(Bukkit.getServer());
      if (mapObj instanceof CommandMap commandMap) {
        commandMap.register(fallbackPrefix, wrapper);
      }
      try {
        Bukkit.getServer().getClass().getMethod("syncCommands").invoke(Bukkit.getServer());
      } catch (Exception ignored) {
      }
      attachBrigadierSubcommands(command, fallbackPrefix);
      scheduleOneShotGlobal(() -> attachBrigadierSubcommands(command, fallbackPrefix), 1L);
    } catch (Exception ex) {
      logger().warning("Failed to register command '/" + command.name() + "': " + ex.getMessage());
    }
  }

  private void scheduleOneShotGlobal(Runnable task, long delayTicks) {
    if (plugin == null || task == null) return;
    if (isFolia()) {
      try {
        Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
        Method runDelayed =
            scheduler
                .getClass()
                .getMethod("runDelayed", Plugin.class, Consumer.class, long.class);
        runDelayed.invoke(
            scheduler, plugin, (Consumer<Object>) t -> task.run(), Math.max(1L, delayTicks));
        return;
      } catch (Exception ignored) {
      }
    }
    try {
      Bukkit.getScheduler().runTaskLater(plugin, task, Math.max(1L, delayTicks));
    } catch (Exception ignored) {
    }
  }

  private static void attachBrigadierSubcommands(TreeCommand command, String fallbackPrefix) {
    try {
      Object dispatcher = resolveBrigadierDispatcher();
      if (dispatcher == null) return;
      Object root = dispatcher.getClass().getMethod("getRoot").invoke(dispatcher);
      if (root == null) return;
      Class<?> commandNodeClass = Class.forName("com.mojang.brigadier.tree.CommandNode");
      Class<?> literalNodeClass = Class.forName("com.mojang.brigadier.tree.LiteralCommandNode");
      Class<?> brigCmdClass = Class.forName("com.mojang.brigadier.Command");
      Class<?> redirectModClass = Class.forName("com.mojang.brigadier.RedirectModifier");
      Method getChild = commandNodeClass.getMethod("getChild", String.class);
      Method addChild = commandNodeClass.getMethod("addChild", commandNodeClass);
      Method getCommand = commandNodeClass.getMethod("getCommand");
      Method getRequirement = commandNodeClass.getMethod("getRequirement");
      java.lang.reflect.Constructor<?> litCtor =
          literalNodeClass.getConstructor(
              String.class,
              brigCmdClass,
              java.util.function.Predicate.class,
              commandNodeClass,
              redirectModClass,
              boolean.class);

      for (String label : List.of(command.name(), fallbackPrefix + ":" + command.name())) {
        Object cmdNode = getChild.invoke(root, label);
        if (cmdNode == null) continue;
        Object brigCmd = getCommand.invoke(cmdNode);
        Object requirement = getRequirement.invoke(cmdNode);
        Object argsChild = getChild.invoke(cmdNode, "args");
        for (io.github.dailystruggle.commandsapi.common.CommandsAPICommand sub :
            command.getCommandLookup().values()) {
          if (sub == null || sub.name() == null || sub.name().isBlank()) continue;
          if (getChild.invoke(cmdNode, sub.name()) != null) continue;
          Object subNode =
              litCtor.newInstance(sub.name(), brigCmd, requirement, null, null, false);
          if (argsChild != null
              && sub instanceof TreeCommand subTree
              && !subTree.getParameterLookup().isEmpty()) {
            addChild.invoke(subNode, argsChild);
          }
          addChild.invoke(cmdNode, subNode);
        }
      }
    } catch (Throwable ignored) {
    }
  }

  private static Object resolveBrigadierDispatcher() {
    try {
      Object mcServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
      if (mcServer == null) return null;
      for (Method m : mcServer.getClass().getMethods()) {
        if (m.getParameterCount() == 0
            && m.getReturnType().getName().equals("com.mojang.brigadier.CommandDispatcher")) {
          return m.invoke(mcServer);
        }
      }
      for (Method m : mcServer.getClass().getMethods()) {
        if (m.getParameterCount() != 0) continue;
        String retName = m.getReturnType().getName();
        if (retName.endsWith(".Commands") || retName.endsWith(".CommandDispatcher")) {
          Object commandsObj = m.invoke(mcServer);
          if (commandsObj == null) continue;
          for (Method cm : commandsObj.getClass().getMethods()) {
            if (cm.getParameterCount() == 0
                && cm.getReturnType().getName().equals("com.mojang.brigadier.CommandDispatcher")) {
              return cm.invoke(commandsObj);
            }
          }
        }
      }
    } catch (Throwable ignored) {
    }
    return null;
  }

  @Override
  @SuppressWarnings("deprecation")
  public int getDataVersion() {
    try {
      return Bukkit.getUnsafe().getDataVersion();
    } catch (Throwable t) {
      return ServerAccessor.super.getDataVersion();
    }
  }

  private static final List<String> SLEEP_PERMISSION_PROBES =
      List.of(
          "leafsleep.sleep",
          "leafsleep.cancel",
          "leafsleep.ignore",
          "leafsleep.bypass",
          "leafsleep.admin",
          "oneplayersleep.sleep",
          "oneplayersleep.cancel",
          "oneplayersleep.ignore",
          "oneplayersleep.bypass",
          "oneplayersleep.admin");

  @Override
  public long getWorldTime(String worldName) {
    if (worldName == null) return 0L;
    World world = Bukkit.getWorld(worldName);
    if (world == null) return lastWorldTimeCache.getOrDefault(worldName, 0L);
    try {
      long t = world.getTime();
      lastWorldTimeCache.put(worldName, t);
      return t;
    } catch (RuntimeException ex) {
      return lastWorldTimeCache.getOrDefault(worldName, 0L);
    }
  }

  @Override
  public long getWorldFullTime(String worldName) {
    if (worldName == null) return 0L;
    World world = Bukkit.getWorld(worldName);
    if (world == null) return lastWorldFullTimeCache.getOrDefault(worldName, 0L);
    try {
      long ft = world.getFullTime();
      lastWorldFullTimeCache.put(worldName, ft);
      return ft;
    } catch (RuntimeException ex) {
      return lastWorldFullTimeCache.getOrDefault(worldName, 0L);
    }
  }

  @Override
  public void setWorldTime(String worldName, long timeTicks) {
    if (worldName == null) return;
    lastWorldFullTimeCache.put(worldName, timeTicks);
    lastWorldTimeCache.put(worldName, Math.floorMod(timeTicks, 24000L));
    runOnGlobalRegionOrMain(
        () -> {
          World world = Bukkit.getWorld(worldName);
          if (world == null) return;
          if (timeTicks >= 24000L) {
            world.setFullTime(timeTicks);
          } else {
            world.setTime(Math.max(0L, timeTicks));
          }
        });
  }

  @Override
  public WeatherState getWeather(String worldName) {
    if (worldName == null) return WeatherState.CLEAR;
    World world = Bukkit.getWorld(worldName);
    if (world == null) return lastWeatherCache.getOrDefault(worldName, WeatherState.CLEAR);
    try {
      WeatherState st =
          world.isThundering()
              ? WeatherState.THUNDER
              : (world.hasStorm() ? WeatherState.RAIN : WeatherState.CLEAR);
      lastWeatherCache.put(worldName, st);
      return st;
    } catch (RuntimeException ex) {
      return lastWeatherCache.getOrDefault(worldName, WeatherState.CLEAR);
    }
  }

  @Override
  public void setWeather(String worldName, WeatherState state, int durationTicks) {
    if (worldName == null || state == null) return;
    runOnGlobalRegionOrMain(
        () -> {
          World world = Bukkit.getWorld(worldName);
          if (world == null) return;
          switch (state) {
            case CLEAR -> {
              world.setStorm(false);
              world.setThundering(false);
              if (durationTicks > 0) {
                world.setClearWeatherDuration(durationTicks);
              }
            }
            case RAIN -> {
              world.setStorm(true);
              world.setThundering(false);
              if (durationTicks > 0) {
                world.setWeatherDuration(durationTicks);
              }
            }
            case THUNDER -> {
              world.setStorm(true);
              world.setThundering(true);
              if (durationTicks > 0) {
                world.setWeatherDuration(durationTicks);
                world.setThunderDuration(durationTicks);
              }
            }
          }
        });
  }

  @Override
  public List<PlayerSleepSnapshot> getWorldPlayers(String worldName) {
    World world = worldName != null ? Bukkit.getWorld(worldName) : null;
    if (world == null) return Collections.emptyList();
    List<Player> players = world.getPlayers();
    List<PlayerSleepSnapshot> snapshots = new ArrayList<>(players.size());
    for (Player p : players) {
      if (p == null || !p.isOnline()) continue;
      if (isEntityOwnedByCurrentRegion(p)) {
        PlayerSleepSnapshot fresh = recordPlayerSleepSnapshotOnEntityThread(p, null);
        if (fresh != null) {
          snapshots.add(fresh);
        }
      } else {
        runOnEntityOrMain(
            p,
            () -> {
              if (p.isOnline()) {
                recordPlayerSleepSnapshotOnEntityThread(p, null);
              }
            });
        PlayerSleepSnapshot cached = playerSleepSnapshotCache.get(p.getUniqueId());
        if (cached != null) {
          snapshots.add(cached);
        } else {
          PlayerSleepSnapshot fallback =
              new PlayerSleepSnapshot(
                  p.getUniqueId(),
                  p.getName(),
                  false,
                  0,
                  false,
                  "SURVIVAL",
                  false,
                  0,
                  collectSleepPermissions(p));
          playerSleepSnapshotCache.putIfAbsent(p.getUniqueId(), fallback);
          snapshots.add(fallback);
        }
      }
    }
    return Collections.unmodifiableList(snapshots);
  }

  private Set<String> collectSleepPermissions(Player p) {
    Set<String> perms = new HashSet<>();
    for (String node : SLEEP_PERMISSION_PROBES) {
      try {
        if (node.endsWith(".bypass")) {
          if (p.isPermissionSet(node) && p.hasPermission(node)) {
            perms.add(node);
          }
        } else if (p.hasPermission(node)) {
          perms.add(node);
        }
      } catch (RuntimeException ex) {
        logger().log(Level.WARNING, "Permission check failed for " + node, ex);
      }
    }
    return perms;
  }

  private PlayerSleepSnapshot recordPlayerSleepSnapshotOnEntityThread(
      Player p, Boolean sleepingOverride) {
    if (p == null) return null;
    int restTicks = 0;
    try {
      restTicks = p.getStatistic(Statistic.TIME_SINCE_REST);
    } catch (IllegalArgumentException ex) {
      logger().log(Level.WARNING, "Could not read TIME_SINCE_REST statistic", ex);
    } catch (RuntimeException ignored) {
    }
    boolean sleeping = sleepingOverride != null ? sleepingOverride : p.isSleeping();
    int sleepTicks = 0;
    try {
      sleepTicks = sleeping ? Math.max(0, p.getSleepTicks()) : 0;
    } catch (RuntimeException ignored) {
    }
    PlayerSleepSnapshot snap =
        new PlayerSleepSnapshot(
            p.getUniqueId(),
            p.getName(),
            sleeping,
            sleepTicks,
            p.isSleepingIgnored(),
            p.getGameMode() != null ? p.getGameMode().name() : "SURVIVAL",
            isPlayerVanishedOrAfk(p),
            restTicks,
            collectSleepPermissions(p));
    playerSleepSnapshotCache.put(p.getUniqueId(), snap);
    try {
      Location loc = p.getLocation();
      if (loc != null && loc.getWorld() != null) {
        callerLocationCache.put(
            p.getUniqueId(),
            new CallerLocation(
                loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
      }
    } catch (RuntimeException ignored) {
    }
    return snap;
  }

  private void clearPlayerCaches(UUID playerId) {
    if (playerId == null) return;
    playerSleepSnapshotCache.remove(playerId);
    callerLocationCache.remove(playerId);
  }

  private boolean isPlayerVanishedOrAfk(Player player) {
    for (String metaKey : List.of("vanished", "isVanished", "afk", "essentials.afk")) {
      try {
        for (MetadataValue val : player.getMetadata(metaKey)) {
          if (val != null && val.asBoolean()) {
            return true;
          }
        }
      } catch (RuntimeException ex) {
        logger().log(Level.WARNING, "Failed to read player metadata " + metaKey, ex);
      }
    }
    return false;
  }

  @Override
  public void wakePlayer(UUID playerId, boolean setSpawn) {
    if (playerId == null) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) return;
    runOnEntityOrMain(
        player,
        () -> {
          if (player.isOnline()) {
            if (player.isSleeping()) {
              player.wakeup(setSpawn);
            }
            recordPlayerSleepSnapshotOnEntityThread(player, Boolean.FALSE);
          }
        });
  }

  @Override
  public void resetTimeSinceRest(UUID playerId) {
    if (playerId == null) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) return;
    runOnEntityOrMain(
        player,
        () -> {
          if (player.isOnline()) {
            try {
              player.setStatistic(Statistic.TIME_SINCE_REST, 0);
            } catch (IllegalArgumentException ex) {
              logger().log(Level.WARNING, "Could not reset TIME_SINCE_REST statistic", ex);
            }
            recordPlayerSleepSnapshotOnEntityThread(player, null);
          }
        });
  }

  @Override
  public int getMaxPlayers() {
    try {
      return Math.max(1, Bukkit.getMaxPlayers());
    } catch (RuntimeException ex) {
      logger().log(Level.WARNING, "Fallback max players used", ex);
      return 20;
    }
  }

  @Override
  public AutoCloseable registerMessagingChannel(
      String channel, BiConsumer<UUID, byte[]> receiver) {
    if (plugin == null || channel == null || channel.isBlank()) {
      return () -> {};
    }
    PluginMessageListener listener =
        (ch, player, message) -> {
          if (channel.equals(ch) && receiver != null && message != null) {
            receiver.accept(player != null ? player.getUniqueId() : null, message);
          }
        };
    Runnable registerTask =
        () -> {
          try {
            if (plugin.isEnabled()) {
              Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
              Bukkit.getMessenger().registerIncomingPluginChannel(plugin, channel, listener);
            }
          } catch (RuntimeException ex) {
            logger().log(Level.WARNING, "Failed to register messaging channel " + channel, ex);
          }
        };
    if (plugin.isEnabled()) {
      registerTask.run();
    } else {
      scheduleOneShotGlobal(registerTask, 1L);
    }
    return () -> {
      try {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, channel, listener);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, channel);
      } catch (RuntimeException ex) {
        logger().log(Level.WARNING, "Failed to unregister messaging channel " + channel, ex);
      }
    };
  }

  @Override
  public boolean sendPluginMessage(String channel, byte[] payload) {
    if (plugin == null || channel == null || channel.isBlank() || payload == null) {
      return false;
    }
    try {
      if (!Bukkit.getMessenger().isOutgoingChannelRegistered(plugin, channel)) {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, channel);
      }
      for (Player player : Bukkit.getOnlinePlayers()) {
        if (player != null && player.isOnline()) {
          player.sendPluginMessage(plugin, channel, payload);
          return true;
        }
      }
      Bukkit.getServer().sendPluginMessage(plugin, channel, payload);
      return true;
    } catch (RuntimeException ex) {
      logger().log(Level.WARNING, "Failed to send plugin message on " + channel, ex);
      return false;
    }
  }

  @Override
  public void sendActionBar(UUID playerId, String message) {
    if (playerId == null || message == null || message.isEmpty()) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) return;
    String colored = ChatColor.translateAlternateColorCodes('&', message);
    runOnEntityOrMain(
        player,
        () -> {
          if (!player.isOnline()) return;
          try {
            Player.Spigot spigot = player.spigot();
            if (spigot != null) {
              spigot.sendMessage(
                  net.md_5.bungee.api.ChatMessageType.ACTION_BAR,
                  TextComponent.fromLegacyText(colored));
            }
          } catch (RuntimeException | LinkageError ex) {
            logger().log(Level.FINE, "sendActionBar fallback", ex);
          }
        });
  }

  @Override
  public void showBossBar(UUID playerId, String barId, String title, double progress) {
    if (playerId == null || barId == null || barId.isBlank()) return;
    Player player = Bukkit.getPlayer(playerId);
    if (player == null) return;
    double clamped = Math.max(0.0, Math.min(1.0, progress));
    org.bukkit.boss.BarColor color = parseBossBarColor(title);
    String sanitized = ChatColor.translateAlternateColorCodes('&', title == null ? "" : title);
    if (sanitized.length() > 64) {
      sanitized = sanitized.substring(0, 64);
    }
    String finalTitle = sanitized;
    runOnGlobalRegionOrMain(
        () -> {
          if (player.isOnline()) {
            io.github.dailystruggle.rtp.bukkitplatform.entity.PersonalBossBars.show(
                player, barId, finalTitle, color, clamped);
          }
        });
  }

  @Override
  public void clearBossBar(UUID playerId, String barId) {
    if (playerId == null || barId == null || barId.isBlank()) return;
    runOnGlobalRegionOrMain(
        () ->
            io.github.dailystruggle.rtp.bukkitplatform.entity.PersonalBossBars.clear(
                playerId, barId));
  }

  private static org.bukkit.boss.BarColor parseBossBarColor(String template) {
    if (template == null) return org.bukkit.boss.BarColor.GREEN;
    for (int i = 0; i + 1 < template.length(); i++) {
      char prefix = template.charAt(i);
      if (prefix != '&' && prefix != '§') continue;
      char c = Character.toLowerCase(template.charAt(i + 1));
      switch (c) {
        case '4', 'c' -> {
          return org.bukkit.boss.BarColor.RED;
        }
        case '6', 'e' -> {
          return org.bukkit.boss.BarColor.YELLOW;
        }
        case '2', 'a' -> {
          return org.bukkit.boss.BarColor.GREEN;
        }
        case '1', '3', '9', 'b' -> {
          return org.bukkit.boss.BarColor.BLUE;
        }
        case '5' -> {
          return org.bukkit.boss.BarColor.PURPLE;
        }
        case 'd' -> {
          return org.bukkit.boss.BarColor.PINK;
        }
        case 'f', '7', '8', '0' -> {
          return org.bukkit.boss.BarColor.WHITE;
        }
        default -> {}
      }
    }
    return org.bukkit.boss.BarColor.GREEN;
  }

  private void runOnGlobalRegionOrMain(Runnable task) {
    if (task == null) return;
    if (isFolia()) {
      if (isPrimaryThread()) {
        task.run();
        return;
      }
      if (plugin != null && scheduleFoliaGlobalOneShot(plugin, task)) {
        return;
      }
    }
    if (Bukkit.isPrimaryThread() || plugin == null) {
      task.run();
    } else {
      Bukkit.getScheduler().runTask(plugin, task);
    }
  }

  static boolean scheduleFoliaAsyncOneShot(Plugin plugin, Runnable task) {
    try {
      Object asyncScheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
      Method runNow = asyncScheduler.getClass().getMethod("runNow", Plugin.class, Consumer.class);
      runNow.invoke(asyncScheduler, plugin, (Consumer<Object>) t -> task.run());
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private void runOnEntityOrMain(Player player, Runnable task) {
    if (player == null || task == null) return;
    if (isFolia() && plugin != null && scheduleFoliaEntity(plugin, player, task)) {
      return;
    }
    if (Bukkit.isPrimaryThread() || plugin == null) {
      task.run();
    } else {
      Bukkit.getScheduler().runTask(plugin, task);
    }
  }

  static boolean scheduleFoliaGlobalOneShot(Plugin plugin, Runnable task) {
    try {
      Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
      Method run = scheduler.getClass().getMethod("run", Plugin.class, Consumer.class);
      run.invoke(scheduler, plugin, (Consumer<Object>) t -> task.run());
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  static boolean scheduleFoliaEntity(Plugin plugin, Player player, Runnable task) {
    try {
      Object entityScheduler = player.getClass().getMethod("getScheduler").invoke(player);
      Method run =
          entityScheduler
              .getClass()
              .getMethod("run", Plugin.class, Consumer.class, Runnable.class);
      run.invoke(entityScheduler, plugin, (Consumer<Object>) t -> task.run(), null);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  private static boolean isFolia() {
    try {
      Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
      return true;
    } catch (ClassNotFoundException ex) {
      return false;
    }
  }

  static boolean isOwnedByCurrentRegion(World world, int chunkX, int chunkZ) {
    try {
      Method m = Bukkit.class.getMethod("isOwnedByCurrentRegion", World.class, int.class, int.class);
      Object res = m.invoke(null, world, chunkX, chunkZ);
      return !(res instanceof Boolean b) || b;
    } catch (NoSuchMethodException ex) {
      return Bukkit.isPrimaryThread();
    } catch (Exception ex) {
      return Bukkit.isPrimaryThread();
    }
  }

  static boolean isEntityOwnedByCurrentRegion(org.bukkit.entity.Entity entity) {
    if (entity == null) return false;
    if (isFolia()) {
      try {
        Method m =
            Bukkit.class.getMethod("isOwnedByCurrentRegion", org.bukkit.entity.Entity.class);
        Object res = m.invoke(null, entity);
        return !(res instanceof Boolean b) || b;
      } catch (NoSuchMethodException ex) {
        return Bukkit.isPrimaryThread();
      } catch (Exception ex) {
        return false;
      }
    }
    try {
      return Bukkit.isPrimaryThread();
    } catch (RuntimeException ex) {
      return true;
    }
  }

  static boolean scheduleFoliaGlobalRepeating(
      Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
    try {
      Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
      Method runAtFixedRate =
          scheduler
              .getClass()
              .getMethod("runAtFixedRate", Plugin.class, Consumer.class, long.class, long.class);
      runAtFixedRate.invoke(scheduler, plugin, (Consumer<Object>) t -> task.run(), delayTicks, periodTicks);
      return true;
    } catch (Exception ex) {
      return false;
    }
  }

  static boolean scheduleFoliaRegion(
      Plugin plugin, World world, int chunkX, int chunkZ, Runnable task, long delayTicks) {
    try {
      Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
      if (delayTicks <= 0L) {
        Method run =
            scheduler
                .getClass()
                .getMethod("run", Plugin.class, World.class, int.class, int.class, Consumer.class);
        run.invoke(scheduler, plugin, world, chunkX, chunkZ, (Consumer<Object>) t -> task.run());
      } else {
        Method runDelayed =
            scheduler
                .getClass()
                .getMethod(
                    "runDelayed",
                    Plugin.class,
                    World.class,
                    int.class,
                    int.class,
                    Consumer.class,
                    long.class);
        runDelayed.invoke(
            scheduler, plugin, world, chunkX, chunkZ, (Consumer<Object>) t -> task.run(), delayTicks);
      }
      return true;
    } catch (Exception ex) {
      return false;
    }
  }
}
