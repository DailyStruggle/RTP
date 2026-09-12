package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code rtp test cancel} - the server operations it performs (message
 * feedback, WARNING/INFO logging) and the {@link ActiveTestJobs} /
 * {@link TestSemaphore} state changes it must produce. Traces item 14 of
 * ENTERPRISE_READINESS.md.
 */
class TestCancelCmdTest {

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    ActiveTestJobs.cancelAll();
    TestSemaphore.clearAllForTesting();
  }

  @AfterEach
  void tearDown() {
    ActiveTestJobs.cancelAll();
    TestSemaphore.clearAllForTesting();
  }

  private static Map<String, List<String>> args(String... values) {
    Map<String, List<String>> m = new HashMap<>();
    if (values.length > 0) m.put("arg", List.of(values));
    return m;
  }

  @Test
  void cancelOwned_cancelsCallerJobs_andMessagesCaller() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);
    AtomicInteger cancelled = new AtomicInteger();
    ActiveTestJobs.register(
        player.uuid(), new ActiveTestJobs.Job("stress", cancelled::incrementAndGet));

    TestCancelCmd cmd = new TestCancelCmd(null);
    boolean ret = cmd.onCommand(player.uuid(), args(), null);

    assertTrue(ret);
    assertEquals(1, cancelled.get(), "caller's job must be cancelled");
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("cancelled 1 job")),
        "caller receives the cancel summary: " + player.sentMessages);
  }

  @Test
  void cancelOwned_withNoJobs_reportsNone() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    new TestCancelCmd(null).onCommand(player.uuid(), args(), null);

    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("no active jobs owned by you")),
        player.sentMessages.toString());
  }

  @Test
  void cancelAll_withAdmin_cancelsEveryOwner() {
    MockRTPPlayer admin = new MockRTPPlayer(UUID.randomUUID(), "admin", null);
    accessor.addPlayer(admin); // mock hasPermission == true -> admin
    AtomicInteger cancelled = new AtomicInteger();
    ActiveTestJobs.register(UUID.randomUUID(), new ActiveTestJobs.Job("a", cancelled::incrementAndGet));
    ActiveTestJobs.register(UUID.randomUUID(), new ActiveTestJobs.Job("b", cancelled::incrementAndGet));

    new TestCancelCmd(null).onCommand(admin.uuid(), args("all"), null);

    assertEquals(2, cancelled.get());
    assertTrue(
        admin.sentMessages.stream().anyMatch(m -> m.contains("across all owners")),
        admin.sentMessages.toString());
  }

  @Test
  void cancelAll_deniedForNonAdmin() {
    UUID id = UUID.randomUUID();
    MockRTPPlayer nonAdmin =
        new MockRTPPlayer(id, "peon", null) {
          @Override
          public boolean hasPermission(String permission) {
            return false;
          }
        };
    accessor.addPlayer(nonAdmin);
    AtomicInteger cancelled = new AtomicInteger();
    ActiveTestJobs.register(
        UUID.randomUUID(), new ActiveTestJobs.Job("a", cancelled::incrementAndGet));

    new TestCancelCmd(null).onCommand(id, args("all"), null);

    assertEquals(0, cancelled.get(), "non-admin must not trigger a global cancel");
    assertTrue(
        nonAdmin.sentMessages.stream().anyMatch(m -> m.contains("requires rtp.test.admin")),
        nonAdmin.sentMessages.toString());
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("WARNING") && m.contains("denied 'all'")),
        accessor.logMessages.toString());
  }

  @Test
  void consoleCaller_doesNotSendMessage_butLogs() {
    ActiveTestJobs.register(
        RTPAPI.serverId, new ActiveTestJobs.Job("stress", () -> {}));

    boolean ret = new TestCancelCmd(null).onCommand(RTPAPI.serverId, args(), null);

    assertTrue(ret);
    // Console suppresses sendMessage; the INFO summary must still be logged.
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("cancelled 1 job")),
        accessor.logMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    // A non-null nextCommand means dispatch continues down the chain; the
    // cancel body must not run.
    boolean ret =
        new TestCancelCmd(null)
            .onCommand(RTPAPI.serverId, args(), new TestCancelCmd(null));
    assertTrue(ret);
  }

  @Test
  void metadata_isStable() {
    TestCancelCmd cmd = new TestCancelCmd(null);
    assertEquals("cancel", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertTrue(cmd.description().toLowerCase().contains("cancel"));
  }
}
