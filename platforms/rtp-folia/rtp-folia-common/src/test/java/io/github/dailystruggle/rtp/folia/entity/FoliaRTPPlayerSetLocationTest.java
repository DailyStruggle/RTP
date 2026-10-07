package io.github.dailystruggle.rtp.folia.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.folia.world.FoliaRTPWorld;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** S-004: the Folia rubberband snap must never hang or report a failed snap as success. */
class FoliaRTPPlayerSetLocationTest {

  private Player player;
  private EntityScheduler entityScheduler;
  private RTPLocation destination;
  private RTPServerAccessor previousAccessor;
  private Object previousInstance;

  @BeforeEach
  void setUp() throws Exception {
    World bukkitWorld = mock(World.class);
    FoliaRTPWorld rtpWorld = mock(FoliaRTPWorld.class);
    when(rtpWorld.world()).thenReturn(bukkitWorld);
    destination = new RTPLocation(rtpWorld, 100, 70, -50);

    player = mock(Player.class);
    entityScheduler = mock(EntityScheduler.class);
    when(player.getLocation()).thenReturn(new Location(bukkitWorld, 0, 64, 0));
    when(player.isOnline()).thenReturn(true);
    when(player.getScheduler()).thenReturn(entityScheduler);

    // Region hop runs inline so the test observes the entity-scheduler hand-off directly.
    RTPScheduler scheduler = mock(RTPScheduler.class);
    doAnswer(inv -> {
      inv.<Runnable>getArgument(3).run();
      return null;
    }).when(scheduler).runTask(any(RTPWorld.class), anyInt(), anyInt(), any(Runnable.class));
    RTPServerAccessor accessor = mock(RTPServerAccessor.class);
    when(accessor.getScheduler()).thenReturn(scheduler);
    previousAccessor = RTP.serverAccessor;
    RTP.serverAccessor = accessor;

    RTP rtp = mock(RTP.class);
    when(rtp.getPlugin()).thenReturn(mock(Plugin.class));
    previousInstance = swapInstance(rtp);
  }

  @AfterEach
  void tearDown() throws Exception {
    RTP.serverAccessor = previousAccessor;
    swapInstance(previousInstance);
  }

  private static Object swapInstance(Object value) throws Exception {
    Field f = RTP.class.getDeclaredField("instance");
    f.setAccessible(true);
    Object old = f.get(null);
    f.set(null, value);
    return old;
  }

  @Test
  @DisplayName("S-004: retired entity (player quit before snap) completes false instead of hanging")
  void retiredEntityCompletesFalse() throws Exception {
    when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
    doAnswer(inv -> {
      inv.<Runnable>getArgument(2).run();
      return null;
    }).when(entityScheduler).run(any(Plugin.class), any(), any());

    CompletableFuture<Boolean> result = new FoliaRTPPlayer(player).setLocation(destination);

    assertTrue(result.isDone(), "future must not hang on a retired entity");
    assertEquals(false, result.get(1, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("S-004: null schedule result without retired callback completes false")
  void nullScheduleResultCompletesFalse() throws Exception {
    when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
    when(entityScheduler.run(any(Plugin.class), any(), any())).thenReturn(null);

    CompletableFuture<Boolean> result = new FoliaRTPPlayer(player).setLocation(destination);

    assertTrue(result.isDone());
    assertEquals(false, result.get(1, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("S-004: failed or exceptional rubberband snap is reported as failure")
  void failedSnapReportsFalse() throws Exception {
    when(player.teleportAsync(any(Location.class)))
        .thenReturn(CompletableFuture.completedFuture(true))
        .thenReturn(CompletableFuture.completedFuture(false));
    runEntityTasksInline();
    assertEquals(false, new FoliaRTPPlayer(player).setLocation(destination).get(1, TimeUnit.SECONDS));

    when(player.teleportAsync(any(Location.class)))
        .thenReturn(CompletableFuture.completedFuture(true))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("snap")));
    assertEquals(false, new FoliaRTPPlayer(player).setLocation(destination).get(1, TimeUnit.SECONDS));
  }

  @Test
  @DisplayName("S-004: successful rubberband snap completes true")
  void successfulSnapReportsTrue() throws Exception {
    when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
    runEntityTasksInline();

    assertEquals(true, new FoliaRTPPlayer(player).setLocation(destination).get(1, TimeUnit.SECONDS));
  }

  private void runEntityTasksInline() {
    ScheduledTask handle = mock(ScheduledTask.class);
    doAnswer(inv -> {
      inv.<Consumer<ScheduledTask>>getArgument(1).accept(handle);
      return handle;
    }).when(entityScheduler).run(any(Plugin.class), any(), any());
  }
}
