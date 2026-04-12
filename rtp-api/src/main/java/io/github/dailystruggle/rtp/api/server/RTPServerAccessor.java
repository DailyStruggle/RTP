package io.github.dailystruggle.rtp.api.server;

import io.github.dailystruggle.rtp.api.configuration.enums.MessagesKeys;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.selection.ILocationGenerator;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.io.File;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;

/** Interface for accessing server-specific functionality */
public interface RTPServerAccessor {
  ConcurrentHashMap<String, TrackedRTPTask> activeTasks = new ConcurrentHashMap<>();

  default void registerAction(TrackedRTPTask task) {
    activeTasks.put(task.getTrackingId(), task);
  }

  default void removeAction(String trackingId) {
    activeTasks.remove(trackingId);
  }

  default Map<String, Long> getTaskSnapshot() {
    ConcurrentHashMap<String, Long> snapshot = new ConcurrentHashMap<>();
    activeTasks.forEach(
        (s, trackedRTPTask) ->
            snapshot.put(s, System.currentTimeMillis() - trackedRTPTask.getQueuedTime()));
    return snapshot;
  }

  String getServerVersion();

  String getPluginVersion();

  String getPlatform();

  Integer getServerIntVersion();

  RTPWorld<?> getRTPWorld(String name);

  RTPWorld<?> getRTPWorld(UUID id);

  io.github.dailystruggle.rtp.api.world.RTPChunkManager getChunkManager();

  List<RTPWorld<?>> getRTPWorlds();

  RTPPlayer getPlayer(UUID uuid);

  RTPPlayer getPlayer(String name);

  @Nullable
  RTPPlayer getConsolePlayer();

  RTPCommandSender getSender(UUID uuid);

  long overTime();

  File getPluginDirectory();

  void sendMessage(UUID target, MessagesKeys msgType, String tag);

  default void sendMessage(UUID target, MessagesKeys msgType) {
    sendMessage(target, msgType, null);
  }

  void sendMessage(UUID target1, UUID target2, MessagesKeys msgType, String tag);

  default void sendMessage(UUID target1, UUID target2, MessagesKeys msgType) {
    sendMessage(target1, target2, msgType, null);
  }

  void sendMessage(UUID target, String message, String tag);

  default void sendMessage(UUID target, String message) {
    sendMessage(target, message, null);
  }

  void sendMessageAndSuggest(UUID target, String message, String suggestion);

  void sendMessage(UUID sender, UUID target, String message, String tag);

  default void sendMessage(UUID sender, UUID target, String message) {
    sendMessage(sender, target, message, null);
  }

  void sendMessage(RTPCommandSender target, String message, String hover, String click, String tag);

  default void sendMessage(RTPCommandSender target, String message, String hover, String click) {
    sendMessage(target, message, hover, click, null);
  }

  String format(@Nullable UUID player, String text);

  String formatNoColor(@Nullable UUID player, String text);

  void log(Level level, String msg);

  void log(Level level, String msg, Throwable throwable);

  void announce(String msg, String permission, String tag);

  default void announce(String msg, String permission) {
    announce(msg, permission, null);
  }

  Set<String> getBiomes(RTPWorld<?> rtpWorld);
  Set<String> getBiomes();

  boolean isPrimaryThread();

  Set<String> materials();

  void stop();

  void start();

  void start(Object plugin);

  void setBiomeGetter(
      java.util.function.Function<io.github.dailystruggle.rtp.api.world.RTPLocation, String>
          getter);

  void setBiomesGetter(
      java.util.function.Function<
              io.github.dailystruggle.rtp.api.world.RTPWorld<?>, java.util.Set<String>>
          getter);

  Object getWorldBorder(String worldName);

  Object getShape(String name);

  boolean setWorldBorderFunction(Function<String, ?> function);

  boolean setShapeFunction(java.util.function.Function<String, ?> shapeFunction);

  Object createTaskPipe();

  Object createCachePipe();

  io.github.dailystruggle.rtp.api.scheduling.RTPScheduler getScheduler();

  ILocationGenerator getLocationGenerator();

  double getTPS(int ticks);
}
