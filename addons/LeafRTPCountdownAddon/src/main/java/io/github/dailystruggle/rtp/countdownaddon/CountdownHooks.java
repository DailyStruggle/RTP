package io.github.dailystruggle.rtp.countdownaddon;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reference implementation of two distinct RTP countdowns, demonstrating why a single delay-based
 * countdown is not sufficient. Fully platform-agnostic: it uses only `rtp-core` / `rtp-api`
 * primitives ({@code RTP.scheduler}, {@code RTP.serverAccessor}, and the {@link
 * TeleportPipelineTask} / {@link Region} hook lists) and contains no {@code org.bukkit.*} imports,
 * so it runs on every RTP platform.
 *
 * <h2>Why two countdowns?</h2>
 *
 * <p>RTP's {@code teleportDelay} is waited out in {@link TeleportPipelineTask} {@code runLoad()} via
 * {@code remainingTime = delay - (now - commandTime)}. For an <b>immediate</b> teleport the command
 * time is "just now", so the full delay is observed and a countdown makes sense. For a <b>queued</b>
 * teleport the command time is when the player first ran {@code /rtp}; by the time the wait queue
 * pops them, {@code now - commandTime} already exceeds {@code delay}, so {@code remainingTime} is
 * negative and the teleport fires immediately. Good UX (the player already waited in line), but it
 * nullifies a delay-based countdown for queued players.
 *
 * <p>This addon therefore wires <b>two</b> countdowns:
 *
 * <ol>
 *   <li><b>Delay countdown</b> ({@link CountdownKeys#delayCountdown}): driven from {@link
 *       TeleportPipelineTask#setupPreActions}, which run only for immediate teleports (queue-popped
 *       tasks enter the pipeline at the LOAD phase and skip SETUP). Counts the configured delay down
 *       once per second.
 *   <li><b>Queue countdown</b> ({@link CountdownKeys#queueCountdown}): driven from the core {@link
 *       Region#onPlayerQueuePush} / {@link Region#onPlayerQueuePop} hook lists. Reports the player's
 *       live queue position once per second until they are popped, then announces "you're up!".
 * </ol>
 *
 * <p><b>Threading:</b> the RTP hook lists and pipeline pre-actions may fire off the main thread, so
 * every player-facing action (scheduling, messaging) is routed through {@code RTP.scheduler} and
 * {@code RTP.serverAccessor}, which dispatch onto the correct thread per platform.
 */
public final class CountdownHooks {

  /** Active per-player countdown tasks, keyed by player UUID. */
  private final Map<UUID, Object> delayTasks = new ConcurrentHashMap<>();

  private final Map<UUID, Object> queueTasks = new ConcurrentHashMap<>();

  /** Retained references so the static core hooks can be removed on disable. */
  private final Consumer<TeleportPipelineTask> setupHook = this::onSetup;

  private final BiConsumer<Region, UUID> pushHook = this::onQueuePush;

  private final BiConsumer<Region, UUID> popHook = this::onQueuePop;

  /** Registers the pipeline pre-action and the core queue hooks. */
  public void register() {
    TeleportPipelineTask.setupPreActions.add(setupHook);
    Region.onPlayerQueuePush.add(pushHook);
    Region.onPlayerQueuePop.add(popHook);
  }

  /** Cancels in-flight countdowns and detaches the static core hooks. */
  public void unregister() {
    TeleportPipelineTask.setupPreActions.remove(setupHook);
    Region.onPlayerQueuePush.remove(pushHook);
    Region.onPlayerQueuePop.remove(popHook);
    delayTasks.values().forEach(RTP.scheduler::cancelTask);
    delayTasks.clear();
    queueTasks.values().forEach(RTP.scheduler::cancelTask);
    queueTasks.clear();
  }

  // ---------------------------------------------------------------------------
  // Delay countdown (immediate teleports only)
  // ---------------------------------------------------------------------------

  private void onSetup(TeleportPipelineTask task) {
    if (!flag(CountdownKeys.delayCountdown)) return;
    if (task == null || task.player() == null) return;

    UUID id = task.player().uuid();
    // delay() is reported in milliseconds; round to whole seconds for the countdown.
    long delayMillis = task.player().delay();
    int seconds = (int) (delayMillis / 1000L);
    if (seconds <= 0) return;

    RTP.scheduler.runTask(() -> startDelayCountdown(id, seconds));
  }

  private void startDelayCountdown(UUID id, int seconds) {
    if (RTP.serverAccessor.getPlayer(id) == null) return;

    // Replace any stale countdown for this player.
    Object previous = delayTasks.remove(id);
    if (previous != null) RTP.scheduler.cancelTask(previous);

    int[] remaining = {seconds};
    Object task =
        RTP.scheduler.runTaskTimer(
            () -> {
              RTPPlayer p = RTP.serverAccessor.getPlayer(id);
              if (p == null || remaining[0] <= 0) {
                Object self = delayTasks.remove(id);
                if (self != null) RTP.scheduler.cancelTask(self);
                return;
              }
              RTP.serverAccessor.sendMessage(id, "[RTP] Teleporting in " + remaining[0] + "...");
              remaining[0]--;
            },
            0L,
            20L);
    delayTasks.put(id, task);
  }

  // ---------------------------------------------------------------------------
  // Queue countdown (players waiting in the public wait queue)
  // ---------------------------------------------------------------------------

  private void onQueuePush(Region region, UUID id) {
    if (!flag(CountdownKeys.queueCountdown)) return;
    RTP.scheduler.runTask(() -> startQueueCountdown(id));
  }

  private void onQueuePop(Region region, UUID id) {
    if (!flag(CountdownKeys.queueCountdown)) return;
    RTP.scheduler.runTask(
        () -> {
          Object task = queueTasks.remove(id);
          if (task != null) RTP.scheduler.cancelTask(task);
          if (RTP.serverAccessor.getPlayer(id) != null) {
            RTP.serverAccessor.sendMessage(id, "[RTP] You're up! Teleporting now...");
          }
        });
  }

  private void startQueueCountdown(UUID id) {
    if (RTP.serverAccessor.getPlayer(id) == null) return;

    Object previous = queueTasks.remove(id);
    if (previous != null) RTP.scheduler.cancelTask(previous);

    Object task =
        RTP.scheduler.runTaskTimer(
            () -> {
              RTPPlayer p = RTP.serverAccessor.getPlayer(id);
              if (p == null) {
                Object self = queueTasks.remove(id);
                if (self != null) RTP.scheduler.cancelTask(self);
                return;
              }
              TeleportData data = RTP.getInstance().latestTeleportData.get(id);
              // No longer queued (popped, cancelled, or completed): stop. The pop hook
              // delivers the "you're up!" message on the normal path.
              if (data == null || data.completed || data.queueLocation <= 0) {
                Object self = queueTasks.remove(id);
                if (self != null) RTP.scheduler.cancelTask(self);
                return;
              }
              RTP.serverAccessor.sendMessage(id, "[RTP] You are #" + data.queueLocation + " in the queue...");
            },
            0L,
            20L);
    queueTasks.put(id, task);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  @SuppressWarnings("unchecked")
  private boolean flag(CountdownKeys key) {
    ConfigParser<CountdownKeys> parser =
        (ConfigParser<CountdownKeys>) RTP.configs.getParser(CountdownKeys.class);
    if (parser == null) return false;
    Object value = parser.getConfigValue(key, false);
    return (value instanceof Boolean) ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
  }
}
