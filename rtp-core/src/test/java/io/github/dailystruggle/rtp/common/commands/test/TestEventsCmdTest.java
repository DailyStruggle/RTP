package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.RTPCommandEvents;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link TestEventsCmd}.
 */
class TestEventsCmdTest {

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    TestSemaphore.clearAllForTesting();
    ActiveTestJobs.cancelAll();
    RTPCommandEvents.clear();
    accessor = RTPTestSetup.install(tempDir);
  }

  @AfterEach
  void tearDown() {
    TestSemaphore.clearAllForTesting();
    ActiveTestJobs.cancelAll();
    RTPCommandEvents.clear();
  }

  private static Map<String, List<String>> noArgs() {
    return new HashMap<>();
  }

  @Test
  void metadata_isStable() {
    TestEventsCmd cmd = new TestEventsCmd(null);
    assertEquals("events", cmd.name());
    assertEquals("rtp.test.events", cmd.permission());
    assertTrue(cmd.description().length() > 0);
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(new TestEventsCmd(null).onCommand(RTPAPI.serverId, noArgs(), new TestEventsCmd(null)));
  }

  @Test
  void nullScheduler_reportsCoreNotLoaded() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);
    RTP.scheduler = null;
    try {
      boolean ret = new TestEventsCmd(null).onCommand(player.uuid(), noArgs(), null);
      assertFalse(ret);
    } finally {
      RTP.scheduler = accessor.getMockScheduler();
    }
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("core not yet loaded")),
        player.sentMessages.toString());
  }

  @Test
  void concurrentDispatch_blockedBySemaphore() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    assertTrue(TestSemaphore.tryAcquire(player.uuid(), "other-test"));

    boolean ret = new TestEventsCmd(null).onCommand(player.uuid(), noArgs(), null);
    assertFalse(ret);

    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("another test is already in flight")),
        player.sentMessages.toString());
  }

  @Test
  void successfulProbe_dispatchesAllEvents_andReportsPass() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    boolean ret = new TestEventsCmd(null).onCommand(player.uuid(), noArgs(), null);
    assertTrue(ret);

    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("PASS")),
        "probe must report PASS: " + player.sentMessages);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("command.onSuccess: ok")),
        player.sentMessages.toString());
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("command.onFail: ok")),
        player.sentMessages.toString());
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("prefabEvents: ok")),
        player.sentMessages.toString());
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("playerMoveEvents: ok")),
        player.sentMessages.toString());

    // Verify all listeners unregistered in finally block
    assertFalse(RTPAPI.prefabEvents.hasSubscribers(), "prefabEvents must not retain listeners");
    assertFalse(RTPAPI.playerMoveEvents.isWatched(player.uuid()), "player must not be watched");
  }

  @Test
  void consoleCaller_logsAndReportsPass() {
    boolean ret = new TestEventsCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);

    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("PASS")),
        accessor.logMessages.toString());

    assertFalse(RTPAPI.prefabEvents.hasSubscribers(), "prefabEvents must not retain listeners");
  }
}
