package io.github.dailystruggle.server.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared platform-neutral base {@link ServerAccessor} for modded server runtimes (Fabric and
 * NeoForge) across both RTP and standalone monorepo mods (ADR-049, ADR-115, ADR-116).
 *
 * <p>Provides headline Minecraft version parsing ({@code 1.<minor>.<patch>} and {@code 26.<minor>}),
 * {@link DispatchingWorldEventHook} management, tick-driven repeating task scheduling, per-chunk
 * metadata storage, command caller location tracking, and {@code server.properties} level-name
 * resolution with zero platform or Minecraft bytecode imports.
 *
 * @governedBy ADR-049
 * @governedBy ADR-115
 * @governedBy ADR-116
 */
public abstract class AbstractModServerAccessor implements ServerAccessor {

  private final Logger logger;
  private final Path rootFolder;
  private final DispatchingWorldEventHook worldEventHook = new DispatchingWorldEventHook();
  private final DispatchingPlayerLifecycleHook playerLifecycleHook =
      new DispatchingPlayerLifecycleHook();
  private final List<ScheduledTask> scheduledTasks = new CopyOnWriteArrayList<>();
  private final Map<UUID, CallerLocation> callerLocations = new ConcurrentHashMap<>();
  private final Map<String, String> chunkDataStore = new ConcurrentHashMap<>();
  private final Map<String, CopyOnWriteArrayList<BiConsumer<UUID, byte[]>>> messagingChannels =
      new ConcurrentHashMap<>();

  /** Constructs a mod server accessor with default logger {@code "RTP"} and {@code config/rtp}. */
  protected AbstractModServerAccessor() {
    this("RTP", Path.of("config", "rtp"));
  }

  /**
   * Constructs a mod server accessor with the given JUL logger name and root configuration folder.
   *
   * @param loggerName JUL logger name; must not be {@code null}
   * @param rootFolder root configuration folder; must not be {@code null}
   */
  protected AbstractModServerAccessor(String loggerName, Path rootFolder) {
    this.logger = Logger.getLogger(Objects.requireNonNull(loggerName, "loggerName"));
    this.rootFolder = Objects.requireNonNull(rootFolder, "rootFolder");
  }

  /**
   * Parses the headline Minecraft integer version from a server version string ({@code 21} for
   * {@code "1.21.4"}, {@code 26} for {@code "26.1.2"}, {@code 0} when unparseable).
   *
   * @param version raw server version string
   * @return headline version integer, or {@code 0} if unknown
   */
  public static int parseIntVersion(String version) {
    if (version == null) return 0;
    String[] parts = version.split("\\.");
    if (parts.length == 0) return 0;
    try {
      int major = Integer.parseInt(digitPrefix(parts[0]));
      if (major != 1) return major;
      if (parts.length >= 2) return Integer.parseInt(digitPrefix(parts[1]));
    } catch (NumberFormatException ignored) {
      // fall through to unknown sentinel
    }
    return 0;
  }

  private static String digitPrefix(String s) {
    int i = 0;
    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
    return s.substring(0, i);
  }

  /**
   * Reads {@code level-name} from {@code server.properties} in the working directory, defaulting to
   * {@code "world"}.
   *
   * @return configured level name; never {@code null}
   */
  public static String readLevelName() {
    File props = new File("server.properties");
    if (props.exists()) {
      Properties p = new Properties();
      try (FileInputStream in = new FileInputStream(props)) {
        p.load(in);
        String name = p.getProperty("level-name");
        if (name != null && !name.isBlank()) return name.trim();
      } catch (IOException ignored) {
      }
    }
    return "world";
  }

  @Override
  public Integer getServerIntVersion() {
    return parseIntVersion(getServerVersion());
  }

  @Override
  public void log(Level level, String message) {
    logger.log(level, message);
  }

  @Override
  public void log(Level level, String message, Throwable throwable) {
    logger.log(level, message, throwable);
  }

  @Override
  public Logger logger() {
    return logger;
  }

  @Override
  public File getPluginDirectory() {
    return rootFolder.toFile();
  }

  @Override
  public Path getRootFolder() {
    return rootFolder;
  }

  @Override
  public Path getWorldFolder(String worldName) {
    return worldName != null ? Path.of(worldName) : null;
  }

  @Override
  public List<String> getWorldNames() {
    return List.of(readLevelName());
  }

  @Override
  public WorldEventHook getWorldEventHook() {
    return worldEventHook;
  }

  @Override
  public PlayerLifecycleHook getPlayerLifecycleHook() {
    return playerLifecycleHook;
  }

  /**
   * Returns the concrete {@link DispatchingWorldEventHook} for firing platform events.
   *
   * @return dispatching world event hook; never {@code null}
   */
  public DispatchingWorldEventHook dispatchingWorldEventHook() {
    return worldEventHook;
  }

  /**
   * Returns the concrete {@link DispatchingPlayerLifecycleHook} for firing join/quit events.
   *
   * @return dispatching player lifecycle hook; never {@code null}
   */
  public DispatchingPlayerLifecycleHook dispatchingPlayerLifecycleHook() {
    return playerLifecycleHook;
  }

  @Override
  public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
    if (task == null) return;
    scheduledTasks.add(new ScheduledTask(task, delayTicks, periodTicks));
  }

  /** Advances all registered repeating tasks by one server tick. */
  protected final void tickScheduledTasks() {
    for (ScheduledTask t : scheduledTasks) {
      t.tick();
    }
  }

  @Override
  public String getChunkData(String worldName, int chunkX, int chunkZ, String key) {
    if (worldName == null || key == null) return null;
    return chunkDataStore.get(worldName + ":" + chunkX + ":" + chunkZ + ":" + key);
  }

  @Override
  public void setChunkData(String worldName, int chunkX, int chunkZ, String key, String data) {
    if (worldName == null || key == null) return;
    String mapKey = worldName + ":" + chunkX + ":" + chunkZ + ":" + key;
    if (data == null || data.isEmpty()) {
      chunkDataStore.remove(mapKey);
    } else {
      chunkDataStore.put(mapKey, data);
    }
  }

  /**
   * Records a caller's resolved world and block coordinates for command default lookups.
   *
   * @param callerId caller UUID
   * @param location caller location snapshot
   */
  protected final void recordCallerLocation(UUID callerId, CallerLocation location) {
    if (callerId != null && location != null) {
      callerLocations.put(callerId, location);
    }
  }

  @Override
  public CallerLocation resolveCallerLocation(UUID callerId) {
    return callerId != null ? callerLocations.get(callerId) : null;
  }

  private static final List<String> MOD_SLEEP_PERMISSION_PROBES =
      List.of(
          "leafsleep.status",
          "leafsleep.sleep",
          "leafsleep.cancel",
          "leafsleep.ignore",
          "leafsleep.message",
          "leafsleep.bypass",
          "leafsleep.reload",
          "leafsleep.test",
          "leafsleep.admin",
          "oneplayersleep.status",
          "oneplayersleep.sleep",
          "oneplayersleep.cancel",
          "oneplayersleep.ignore",
          "oneplayersleep.message",
          "oneplayersleep.bypass",
          "oneplayersleep.reload",
          "oneplayersleep.test",
          "oneplayersleep.admin");

  /**
   * Evaluates a permission node for {@code playerId} on modded runtimes (Fabric/NeoForge),
   * consulting {@code fabric-permissions-api} or {@code LuckPerms} when present and falling back to
   * {@code ops.json} for administrative nodes ({@code .reload}, {@code .test}, {@code .admin}) and
   * {@code false} for opt-out {@code .bypass} nodes.
   *
   * @param playerId player UUID (or {@code null} / console sentinel)
   * @param permission permission node to test
   * @param opsFile server {@code ops.json} file, or {@code null} to use {@code ./ops.json}
   * @return {@code true} if granted
   */
  public boolean hasModPermission(UUID playerId, String permission, File opsFile) {
    if (permission == null || permission.isBlank()) {
      return true;
    }
    if (playerId == null || ( playerId.getMostSignificantBits() == 0L && playerId.getLeastSignificantBits() == 0L)) {
      return true;
    }
    Boolean external = queryExternalPermission(playerId, permission);
    if (external != null) {
      return external;
    }
    String lower = permission.trim().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".bypass")) {
      return false;
    }
    if (lower.endsWith(".reload") || lower.endsWith(".test") || lower.endsWith(".admin")) {
      return isUuidInOpsFile(playerId, opsFile);
    }
    return true;
  }

  /**
   * Builds the effective sleep permission snapshot set for {@code playerId} on modded runtimes.
   *
   * @param playerId player UUID
   * @param opsFile server {@code ops.json} file, or {@code null}
   * @return immutable set of granted permission nodes
   */
  public Set<String> resolveModPermissionSet(UUID playerId, File opsFile) {
    Set<String> perms = new HashSet<>();
    for (String node : MOD_SLEEP_PERMISSION_PROBES) {
      if (hasModPermission(playerId, node, opsFile)) {
        perms.add(node);
      }
    }
    return Set.copyOf(perms);
  }

  /**
   * Reads the scoreboard entity tags from a modded player instance across Minecraft 1.21
   * ({@code getTags()}) and 26.x ({@code entityTags()}).
   *
   * @param playerEntity platform player entity instance
   * @return immutable set of entity tags; never {@code null}
   */
  public Set<String> readModEntityTags(Object playerEntity) {
    if (playerEntity == null) return Set.of();
    for (String methodName : List.of("entityTags", "getTags")) {
      try {
        java.lang.reflect.Method m = playerEntity.getClass().getMethod(methodName);
        Object raw = m.invoke(playerEntity);
        if (raw instanceof Set<?> set && !set.isEmpty()) {
          Set<String> out = new HashSet<>(set.size());
          for (Object item : set) {
            if (item != null) {
              out.add(item.toString());
            }
          }
          return Set.copyOf(out);
        }
      } catch (ReflectiveOperationException | RuntimeException ex) {
        logger.log(Level.FINEST, "Entity tag probe skipped for " + methodName, ex);
      }
    }
    return Set.of();
  }

  /**
   * Evaluates whether a modded player's scoreboard tags mark them as sleep-ignored.
   *
   * @param scoreboardTags entity scoreboard tags; may be {@code null}
   * @return {@code true} if tagged as sleep-ignored
   */
  public boolean isModPlayerSleepIgnored(Set<String> scoreboardTags) {
    if (scoreboardTags == null || scoreboardTags.isEmpty()) {
      return false;
    }
    for (String tag : scoreboardTags) {
      if (tag == null) continue;
      String lower = tag.trim().toLowerCase(Locale.ROOT);
      if (lower.equals("sleep_ignored")
          || lower.equals("sleeping_ignored")
          || lower.equals("leafsleep.ignored")
          || lower.equals("oneplayersleep.ignored")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Evaluates whether a modded player is vanished or AFK via scoreboard tags, server idle timeout,
   * or modded Vanish APIs ({@code VanishAPI}).
   *
   * @param playerEntity platform player entity instance (or {@code null})
   * @param scoreboardTags entity scoreboard tags; may be {@code null}
   * @param idleElapsedMs milliseconds elapsed since the player's last action ({@code <= 0} if unknown)
   * @param idleTimeoutMinutes server {@code player-idle-timeout} in minutes ({@code <= 0} if disabled)
   * @return {@code true} if vanished or AFK
   */
  public boolean isModPlayerVanishedOrAfk(
      Object playerEntity,
      Set<String> scoreboardTags,
      long idleElapsedMs,
      int idleTimeoutMinutes) {
    if (scoreboardTags != null) {
      for (String tag : scoreboardTags) {
        if (tag == null) continue;
        String lower = tag.trim().toLowerCase(Locale.ROOT);
        if (lower.equals("vanished")
            || lower.equals("isvanished")
            || lower.equals("afk")
            || lower.equals("essentials.afk")
            || lower.equals("leafsleep.afk")
            || lower.equals("leafsleep.vanished")) {
          return true;
        }
      }
    }
    if (idleTimeoutMinutes > 0 && idleElapsedMs >= idleTimeoutMinutes * 60_000L) {
      return true;
    }
    if (playerEntity != null) {
      try {
        Class<?> vanishApi = Class.forName("me.drex.vanish.api.VanishAPI");
        for (java.lang.reflect.Method m : vanishApi.getMethods()) {
          if (m.getName().equals("isVanished")
              && m.getParameterCount() == 1
              && m.getParameterTypes()[0].isAssignableFrom(playerEntity.getClass())) {
            Object res = m.invoke(null, playerEntity);
            if (res instanceof Boolean b && b) {
              return true;
            }
          }
        }
      } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
        logger.log(Level.FINEST, "Optional VanishAPI probe skipped", ex);
      }
    }
    return false;
  }

  @SuppressWarnings("unchecked")
  private Boolean queryExternalPermission(UUID playerId, String permission) {
    try {
      Class<?> permsCls = Class.forName("me.lucko.fabric.api.permissions.v0.Permissions");
      java.lang.reflect.Method m =
          permsCls.getMethod("getPermissionValue", UUID.class, String.class);
      Object cf = m.invoke(null, playerId, permission);
      if (cf instanceof CompletableFuture<?> future) {
        Class<?> tsCls = Class.forName("net.fabricmc.fabric.api.util.TriState");
        Object def = tsCls.getField("DEFAULT").get(null);
        Object state = ((CompletableFuture<Object>) future).getNow(def);
        if (state == tsCls.getField("TRUE").get(null)) return Boolean.TRUE;
        if (state == tsCls.getField("FALSE").get(null)) return Boolean.FALSE;
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
      logger.log(Level.FINEST, "Optional fabric-permissions-api probe skipped", ex);
    }
    try {
      Class<?> providerCls = Class.forName("net.luckperms.api.LuckPermsProvider");
      Object api = providerCls.getMethod("get").invoke(null);
      Object userManager = api.getClass().getMethod("getUserManager").invoke(api);
      Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, playerId);
      if (user != null) {
        Object cachedData = user.getClass().getMethod("getCachedData").invoke(user);
        Object permData = cachedData.getClass().getMethod("getPermissionData").invoke(cachedData);
        Object tristate =
            permData.getClass().getMethod("checkPermission", String.class).invoke(permData, permission);
        if (tristate != null) {
          String name = tristate.toString();
          if ("TRUE".equalsIgnoreCase(name)) return Boolean.TRUE;
          if ("FALSE".equalsIgnoreCase(name)) return Boolean.FALSE;
        }
      }
    } catch (ReflectiveOperationException | RuntimeException | LinkageError ex) {
      logger.log(Level.FINEST, "Optional LuckPerms probe skipped", ex);
    }
    return null;
  }

  private record CachedOpsSnapshot(String path, long lastModified, long length, String body) {}

  private volatile CachedOpsSnapshot cachedOpsSnapshot;

  private boolean isUuidInOpsFile(UUID playerId, File opsFile) {
    File target = (opsFile != null && opsFile.isFile()) ? opsFile : new File("ops.json");
    if (!target.isFile()) {
      return false;
    }
    String absPath = target.getAbsolutePath();
    long mtime = target.lastModified();
    long len = target.length();
    CachedOpsSnapshot snap = this.cachedOpsSnapshot;
    if (snap == null
        || snap.lastModified() != mtime
        || snap.length() != len
        || !snap.path().equals(absPath)) {
      try {
        String body = Files.readString(target.toPath(), StandardCharsets.UTF_8);
        snap = new CachedOpsSnapshot(absPath, mtime, len, body);
        this.cachedOpsSnapshot = snap;
      } catch (IOException ex) {
        logger.log(Level.FINE, "Could not read ops.json at " + target, ex);
        return false;
      }
    }
    return snap.body().contains("\"" + playerId + "\"");
  }

  @Override
  public AutoCloseable registerMessagingChannel(
      String channel, BiConsumer<UUID, byte[]> receiver) {
    if (channel == null || channel.isBlank() || receiver == null) {
      return () -> {};
    }
    CopyOnWriteArrayList<BiConsumer<UUID, byte[]>> list =
        messagingChannels.computeIfAbsent(channel, k -> new CopyOnWriteArrayList<>());
    list.add(receiver);
    return () -> list.remove(receiver);
  }

  @Override
  public boolean sendPluginMessage(String channel, byte[] payload) {
    return dispatchLocalMessagingChannel(channel, null, payload);
  }

  /**
   * Dispatches an inbound or local-loopback plugin message payload to all registered {@link
   * #registerMessagingChannel(String, BiConsumer)} receivers on {@code channel}.
   *
   * @param channel channel identifier
   * @param senderId sender player UUID (or {@code null} when server/proxy originated)
   * @param payload raw message bytes
   * @return {@code true} if at least one listener was registered on {@code channel}
   */
  protected final boolean dispatchLocalMessagingChannel(
      String channel, UUID senderId, byte[] payload) {
    if (channel == null || channel.isBlank() || payload == null) {
      return false;
    }
    CopyOnWriteArrayList<BiConsumer<UUID, byte[]>> list = messagingChannels.get(channel);
    if (list == null || list.isEmpty()) {
      return false;
    }
    for (BiConsumer<UUID, byte[]> receiver : list) {
      try {
        receiver.accept(senderId, payload.clone());
      } catch (Throwable ex) {
        logger.log(Level.FINE, "Plugin message receiver error on " + channel, ex);
      }
    }
    return true;
  }

  private static final class ScheduledTask {
    private final Runnable task;
    private final long period;
    private long remaining;

    ScheduledTask(Runnable task, long delayTicks, long periodTicks) {
      this.task = task;
      this.period = Math.max(1L, periodTicks);
      this.remaining = Math.max(0L, delayTicks);
    }

    void tick() {
      if (remaining > 0) {
        remaining--;
        return;
      }
      task.run();
      remaining = period - 1;
    }
  }
}
