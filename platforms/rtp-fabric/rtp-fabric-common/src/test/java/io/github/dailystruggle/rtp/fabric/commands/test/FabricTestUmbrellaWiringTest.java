package io.github.dailystruggle.rtp.fabric.commands.test;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.scheduling.RTPScheduler;
import io.github.dailystruggle.rtp.api.scheduling.TrackedRTPTask;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaContext;
import io.github.dailystruggle.rtp.common.tasks.RTPRunnable;
import io.github.dailystruggle.rtp.fabric.FabricRTPCommonEntry;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("FabricTestUmbrella wiring, sender, and scheduler coverage")
class FabricTestUmbrellaWiringTest {

  private TestUmbrellaContext previousContext;
  private io.github.dailystruggle.rtp.api.server.RTPServerAccessor previousAccessor;
  private RTPScheduler previousScheduler;

  @BeforeEach
  void setUp() {
    previousContext = RTP.testUmbrellaContext;
    previousAccessor = RTP.serverAccessor;
    previousScheduler = RTP.scheduler;
    RTP.testUmbrellaContext = null;
  }

  @AfterEach
  void tearDown() {
    RTP.testUmbrellaContext = previousContext;
    RTP.serverAccessor = previousAccessor;
    RTP.scheduler = previousScheduler;
  }

  @Test
  @DisplayName("populateContext installs FabricTestUmbrellaSender and Scheduler onto RTP.testUmbrellaContext")
  void wireContext() {
    assertNull(RTP.testUmbrellaContext);
    FabricRTPCommonEntry.populateContext();

    TestUmbrellaContext ctx = TestUmbrellaContext.require();
    assertNotNull(ctx);
    assertTrue(ctx.sender() instanceof FabricTestUmbrellaSender);
    assertTrue(ctx.scheduler() instanceof FabricTestUmbrellaScheduler);
  }

  @Test
  @DisplayName("populateContext is idempotent")
  void wireContextIdempotent() {
    FabricRTPCommonEntry.populateContext();
    TestUmbrellaContext first = RTP.testUmbrellaContext;
    assertNotNull(first);

    FabricRTPCommonEntry.populateContext();
    assertSame(first, RTP.testUmbrellaContext);
  }

  @Test
  @DisplayName("FabricTestUmbrellaSender resolves caller name with player, serverId, or UUID fallback")
  void senderResolveCallerName() {
    UUID unknownId = UUID.randomUUID();

    FabricTestUmbrellaSender sender = new FabricTestUmbrellaSender();

    // 1. Null callerId resolves to serverId / CONSOLE
    String serverName = sender.resolveCallerName(null);
    assertNotNull(serverName);

    // 2. Fallback when accessor is null
    RTP.serverAccessor = null;
    assertEquals(unknownId.toString(), sender.resolveCallerName(unknownId));
  }

  @Test
  @DisplayName("FabricTestUmbrellaSender resolves player name via FabricServerAccessor")
  void senderResolvePlayerNameViaAccessor() {
    UUID playerId = UUID.randomUUID();
    io.github.dailystruggle.rtp.api.server.RTPServerAccessor mockAccessor =
            org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.server.RTPServerAccessor.class);
    RTPPlayer mockPlayer = org.mockito.Mockito.mock(RTPPlayer.class);
    org.mockito.Mockito.when(mockPlayer.name()).thenReturn("FabricPlayer1");
    org.mockito.Mockito.when(mockAccessor.getPlayer(playerId)).thenReturn(mockPlayer);

    FabricTestUmbrellaSender sender = new FabricTestUmbrellaSender(mockAccessor);
    assertEquals("FabricPlayer1", sender.resolveCallerName(playerId));
  }

  @Test
  @DisplayName("FabricTestUmbrellaSender routes log through FabricServerAccessor")
  void senderRoutesLogToAccessor() {
    io.github.dailystruggle.rtp.api.server.RTPServerAccessor mockAccessor =
            org.mockito.Mockito.mock(io.github.dailystruggle.rtp.api.server.RTPServerAccessor.class);
    FabricTestUmbrellaSender sender = new FabricTestUmbrellaSender(mockAccessor);

    sender.log(Level.WARNING, "[Fabric Audit Test]");
    org.mockito.Mockito.verify(mockAccessor).log(Level.WARNING, "[Fabric Audit Test]");
  }

  @Test
  @DisplayName("FabricRTPCommonEntry.init executes safely")
  void commonEntryInit() {
    assertDoesNotThrow(FabricRTPCommonEntry::init);
  }

  @Test
  @DisplayName("FabricTestUmbrellaScheduler executes via MinecraftServer when delay is 0 and scheduler is null")
  void schedulerWithMinecraftServer() {
    net.minecraft.server.MinecraftServer mockServer = org.mockito.Mockito.mock(net.minecraft.server.MinecraftServer.class);
    FabricTestUmbrellaScheduler scheduler = new FabricTestUmbrellaScheduler(null, mockServer);

    scheduler.runLater(0L, () -> {});
    org.mockito.Mockito.verify(mockServer).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
  }

  @Test
  @DisplayName("FabricTestUmbrellaSender delivers to audit interceptors")
  void senderAuditInterceptors() {
    FabricTestUmbrellaSender sender = new FabricTestUmbrellaSender();
    List<String> auditLogs = new ArrayList<>();

    java.util.function.Consumer<String> consumer = auditLogs::add;
    sender.addAuditInterceptor(consumer);
    sender.log(Level.INFO, "[Test Line 1]");
    sender.log(Level.WARNING, "[Test Line 2]");

    assertEquals(2, auditLogs.size());
    assertEquals("[Test Line 1]", auditLogs.get(0));
    assertEquals("[Test Line 2]", auditLogs.get(1));

    // Remove interceptor
    sender.removeAuditInterceptor(consumer);
    sender.log(Level.INFO, "[Test Line 3]");
    assertEquals(2, auditLogs.size());
  }

  @Test
  @DisplayName("FabricTestUmbrellaSender handles null message gracefully")
  void senderNullMessage() {
    FabricTestUmbrellaSender sender = new FabricTestUmbrellaSender();
    AtomicBoolean called = new AtomicBoolean(false);
    sender.addAuditInterceptor(msg -> called.set(true));

    sender.log(Level.INFO, null);
    assertFalse(called.get());
  }

  @Test
  @DisplayName("FabricTestUmbrellaScheduler rejects null task")
  void schedulerNullTask() {
    FabricTestUmbrellaScheduler scheduler = new FabricTestUmbrellaScheduler();
    assertThrows(IllegalArgumentException.class, () -> scheduler.runLater(100L, null));
  }

  @Test
  @DisplayName("FabricTestUmbrellaScheduler executes via RTPScheduler when available")
  void schedulerWithRtpScheduler() {
    AtomicInteger cancelCalls = new AtomicInteger(0);
    AtomicBoolean taskRan = new AtomicBoolean(false);

    RTPScheduler mockSched = new RTPScheduler() {
      @Override
      public Object runTaskTimerAsynchronously(Runnable task, long delay, long period) {
        // Execute the task to simulate timer fire
        task.run();
        return 999;
      }

      @Override
      public void cancelTask(Object task) {
        cancelCalls.incrementAndGet();
      }

      @Override public TrackedRTPTask runTaskAsynchronously(Runnable task) { return null; }
      @Override public void runTask(Runnable task) {}
      @Override public void runTask(RTPLocation location, Runnable task) {}
      @Override public void runTask(RTPWorld<?> world, int cx, int cz, Runnable task) {}
      @Override public void runTaskLater(Runnable task, long delay) {}
      @Override public void runTaskLater(RTPWorld<?> world, int cx, int cz, Runnable task, long delay) {}
      @Override public Object runTaskTimer(Runnable task, long delay, long period) { return null; }
      @Override public Object runTaskTimer(RTPWorld<?> world, int cx, int cz, Runnable task, long delay, long period) { return null; }
      @Override public void runTaskForPlayer(RTPPlayer player, RTPRunnable task, long delayTicks) {}
    };

    FabricTestUmbrellaScheduler scheduler = new FabricTestUmbrellaScheduler(mockSched);
    scheduler.runLater(50L, () -> taskRan.set(true));

    assertTrue(taskRan.get());
    assertEquals(1, cancelCalls.get(), "Single-shot timer must cancel itself on execution");
  }

  @Test
  @DisplayName("FabricTestUmbrellaScheduler executes inline in headless mode")
  void schedulerHeadlessFallback() {
    FabricTestUmbrellaScheduler scheduler = new FabricTestUmbrellaScheduler();
    AtomicBoolean ran = new AtomicBoolean(false);

    scheduler.runLater(0L, () -> ran.set(true));
    assertTrue(ran.get());
  }

  @Test
  @DisplayName("FabricTestUmbrellaScheduler catches task exception without rethrowing")
  void schedulerCatchesTaskException() {
    FabricTestUmbrellaScheduler scheduler = new FabricTestUmbrellaScheduler();
    assertDoesNotThrow(() -> scheduler.runLater(0L, () -> {
      throw new RuntimeException("Simulated task failure");
    }));
  }
}
