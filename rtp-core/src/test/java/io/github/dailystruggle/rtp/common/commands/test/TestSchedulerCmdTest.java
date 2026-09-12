package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.MockRTPWorld;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code rtp test scheduler} - the scheduler tiers it dispatches to
 * (async, primary, and, when the caller has a location, region) and the latency
 * report it emits per tier. Traces item 14 of ENTERPRISE_READINESS.md.
 */
class TestSchedulerCmdTest {

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
  }

  private static Map<String, List<String>> noArgs() {
    return new HashMap<>();
  }

  @Test
  void playerWithLocation_probesAllThreeTiers() {
    MockRTPWorld world = new MockRTPWorld("world");
    accessor.addWorld(world);
    RTPLocation loc = new RTPLocation(world, 0, 64, 0);
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", loc);
    accessor.addPlayer(player);

    boolean ret = new TestSchedulerCmd(null).onCommand(player.uuid(), noArgs(), null);
    assertTrue(ret);

    List<String> msgs = player.sentMessages;
    assertTrue(msgs.stream().anyMatch(m -> m.contains("begin")), msgs.toString());
    // The async tier is exercised; under the synchronous mock scheduler its
    // re-entrant dispatch resolves only after the outer task returns, so the
    // in-task await reports TIMEOUT rather than ok. We assert the tier ran
    // (a line was emitted) rather than pinning the platform-dependent verdict.
    assertTrue(msgs.stream().anyMatch(m -> m.contains("async:")), msgs.toString());
    assertTrue(msgs.stream().anyMatch(m -> m.contains("primary: ok")), msgs.toString());
    assertTrue(msgs.stream().anyMatch(m -> m.contains("region: ok")), msgs.toString());
    assertTrue(msgs.stream().anyMatch(m -> m.contains("end")), msgs.toString());
  }

  @Test
  void threadedMode_asyncTierReportsOk() throws Exception {
    // Under the threaded server-topology model the probe's nested async wait
    // resolves against a free worker, so the async tier reports ok instead of
    // the TIMEOUT seen under the synchronous trampoline.
    MockRTPWorld world = new MockRTPWorld("world");
    accessor.addWorld(world);
    RTPLocation loc = new RTPLocation(world, 0, 64, 0);
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", loc);
    accessor.addPlayer(player);
    accessor.getMockScheduler().enableServerThreads();
    try {
      new TestSchedulerCmd(null).onCommand(player.uuid(), noArgs(), null);
      // The probe registers a job under the caller and clears it when done;
      // wait for that drain so all per-tier report lines are delivered.
      CountDownLatch done = new CountDownLatch(1);
      ActiveTestJobs.addOnEmptyListener(player.uuid(), done::countDown);
      assertTrue(done.await(10, TimeUnit.SECONDS), "scheduler probe completed");

      List<String> msgs = player.sentMessages;
      assertTrue(msgs.stream().anyMatch(m -> m.contains("async: ok")), msgs.toString());
      assertTrue(msgs.stream().anyMatch(m -> m.contains("primary: ok")), msgs.toString());
      assertTrue(msgs.stream().anyMatch(m -> m.contains("region: ok")), msgs.toString());
      assertTrue(msgs.stream().anyMatch(m -> m.contains("end")), msgs.toString());
    } finally {
      accessor.getMockScheduler().shutdown();
    }
  }

  @Test
  void callerWithoutLocation_skipsRegionTier() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    new TestSchedulerCmd(null).onCommand(player.uuid(), noArgs(), null);

    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("region: skipped")),
        player.sentMessages.toString());
  }

  @Test
  void nullScheduler_reportsCoreNotLoaded() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);
    RTP.scheduler = null;
    try {
      new TestSchedulerCmd(null).onCommand(player.uuid(), noArgs(), null);
    } finally {
      RTP.scheduler = accessor.getMockScheduler();
    }
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("scheduler is null")),
        player.sentMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(
        new TestSchedulerCmd(null)
            .onCommand(RTPAPI.serverId, noArgs(), new TestSchedulerCmd(null)));
  }

  @Test
  void metadata_isStable() {
    TestSchedulerCmd cmd = new TestSchedulerCmd(null);
    assertEquals("scheduler", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertTrue(cmd.description().length() > 0);
  }
}
