package io.github.dailystruggle.rtp.countdownaddon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.entity.RTPCommandSender;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.selection.GenerationContext;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.configuration.ActiveConfigs;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import io.github.dailystruggle.rtp.common.tasks.teleport.TeleportPipelineTask;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CountdownHooks instant and delayed teleport boss-bar tests")
class CountdownHooksTest {

  private static final String DELAY_BAR = "rtp-delay-countdown";

  private RTPServerAccessor previousAccessor;
  private RTPScheduler previousScheduler;
  private Configs previousConfigs;
  private RTPServerAccessor accessor;
  private RTPScheduler scheduler;
  private final List<Runnable> scheduledTimers = new ArrayList<>();
  private CountdownHooks hooks;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    previousAccessor = RTPRuntime.serverAccessor();
    previousScheduler = RTPRuntime.scheduler();
    previousConfigs = ActiveConfigs.get();

    accessor = mock(RTPServerAccessor.class);
    scheduler = mock(RTPScheduler.class);
    doAnswer(
            inv -> {
              Runnable r = inv.getArgument(0);
              r.run();
              return null;
            })
        .when(scheduler)
        .runTask(any(Runnable.class));
    doAnswer(
            inv -> {
              Runnable r = inv.getArgument(0);
              scheduledTimers.add(r);
              return r;
            })
        .when(scheduler)
        .runTaskTimer(any(Runnable.class), anyLong(), anyLong());

    RTPRuntime.setServerAccessor(accessor);
    RTPAPI.serverAccessor = accessor;
    RTPRuntime.setScheduler(scheduler);
    TeleportSessions.install(new TeleportSessions());

    Configs configs = mock(Configs.class);
    ConfigParser<CountdownKeys> countdownParser = mock(ConfigParser.class);
    when(countdownParser.getConfigValue(eq(CountdownKeys.delayCountdown), any())).thenReturn(true);
    when(configs.getParser(CountdownKeys.class)).thenReturn((ConfigParser) countdownParser);
    ActiveConfigs.set(configs);

    hooks = new CountdownHooks();
    hooks.register();
  }

  @AfterEach
  void tearDown() {
    if (hooks != null) {
      hooks.unregister();
    }
    scheduledTimers.clear();
    TeleportSessions.current().latestTeleportData.clear();
    ActiveConfigs.set(previousConfigs);
    RTPRuntime.setScheduler(previousScheduler);
    RTPRuntime.setServerAccessor(previousAccessor);
    RTPAPI.serverAccessor = previousAccessor;
  }

  private RTPPlayer mockDelayedPlayer(long delayMillis) {
    UUID id = UUID.randomUUID();
    RTPPlayer player = mock(RTPPlayer.class);
    when(player.uuid()).thenReturn(id);
    when(player.delay()).thenReturn(delayMillis);
    when(accessor.getPlayer(id)).thenReturn(player);
    return player;
  }

  @Test
  @DisplayName("Console /rtp player=<name> does not start a countdown boss bar for the target player")
  void consoleTeleportDoesNotSpawnBossBar() {
    RTPPlayer target = mockDelayedPlayer(4000L);
    RTPCommandSender console = mock(RTPCommandSender.class);
    when(console.uuid()).thenReturn(new UUID(0, 0));
    when(console.delay()).thenReturn(0L);

    TeleportPipelineTask task =
        new TeleportPipelineTask(new GenerationContext(console, target, null), null);
    assertEquals(0L, CountdownHooks.effectiveDelayMillis(task));

    TeleportPipelineTask.setupPreActions.forEach(action -> action.accept(task));
    scheduledTimers.forEach(Runnable::run);

    verify(target, never()).showProgressBar(eq(DELAY_BAR), anyString(), anyDouble());
  }

  @Test
  @DisplayName("Sender with rtp.noDelay does not start a countdown boss bar even when player.delay() > 0")
  void noDelayPermissionSkipsBossBar() {
    RTPPlayer player = mockDelayedPlayer(4000L);
    when(player.hasPermission("rtp.noDelay")).thenReturn(true);

    TeleportPipelineTask task =
        new TeleportPipelineTask(new GenerationContext(player, player, null), null);
    assertEquals(0L, CountdownHooks.effectiveDelayMillis(task));

    TeleportPipelineTask.setupPreActions.forEach(action -> action.accept(task));
    scheduledTimers.forEach(Runnable::run);

    verify(player, never()).showProgressBar(eq(DELAY_BAR), anyString(), anyDouble());
  }

  @Test
  @DisplayName("Completed teleport does not start a countdown boss bar")
  void completedTeleportDoesNotShowBossBar() {
    RTPPlayer player = mockDelayedPlayer(4000L);
    TeleportData data = new TeleportData();
    data.sender = player;
    data.completed = true;
    TeleportSessions.current().latestTeleportData.put(player.uuid(), data);

    TeleportPipelineTask task =
        new TeleportPipelineTask(new GenerationContext(player, player, null), null);
    assertEquals(0L, CountdownHooks.effectiveDelayMillis(task));

    TeleportPipelineTask.setupPreActions.forEach(action -> action.accept(task));
    scheduledTimers.forEach(Runnable::run);

    verify(player, never()).showProgressBar(eq(DELAY_BAR), anyString(), anyDouble());
  }

  @Test
  @DisplayName("Self-initiated delayed teleport starts the countdown boss bar and clears on completion")
  void delayedSelfTeleportShowsAndClearsBossBar() {
    RTPPlayer player = mockDelayedPlayer(4000L);
    TeleportData data = new TeleportData();
    data.sender = player;
    data.completed = false;
    TeleportSessions.current().latestTeleportData.put(player.uuid(), data);

    TeleportPipelineTask task =
        new TeleportPipelineTask(new GenerationContext(player, player, null), null);
    assertEquals(4000L, CountdownHooks.effectiveDelayMillis(task));

    TeleportPipelineTask.setupPreActions.forEach(action -> action.accept(task));
    assertEquals(1, scheduledTimers.size());

    scheduledTimers.get(0).run();
    verify(player).showProgressBar(eq(DELAY_BAR), eq("&aTeleporting in 4..."), eq(1.0));

    data.completed = true;
    scheduledTimers.get(0).run();
    verify(player).clearProgressBar(DELAY_BAR);
  }
}
