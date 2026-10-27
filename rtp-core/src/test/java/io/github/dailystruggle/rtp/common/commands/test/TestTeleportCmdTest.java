package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.api.RTPResult;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.mock.BitTeleportLog;
import io.github.dailystruggle.rtp.common.mock.MockPlayerRegistry;
import io.github.dailystruggle.rtp.common.mock.MockPlayerSession;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import io.github.dailystruggle.rtp.common.mock.RuntimeMockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.TrackedMockWorld;
import io.github.dailystruggle.rtp.common.playerData.TeleportSessions;
import io.github.dailystruggle.rtp.common.selection.ActiveSelection;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.RegionSettings;
import io.github.dailystruggle.rtp.common.selection.region.selectors.memory.shapes.Circle;
import io.github.dailystruggle.rtp.common.selection.region.selectors.verticalAdjustors.linear.LinearAdjustor;
import io.github.dailystruggle.rtp.common.tools.MemoryTracker;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** BIT mock-player teleport probe (ADR-121 section 11; REQ-RTP-S-004, REQ-RTP-S-002). */
@DisplayName("ADR-121 REQ-RTP-S-004 rtp test teleport drives real-pipeline teleports for BIT mocks")
class TestTeleportCmdTest {

  private static final String REGION = "bit_teleport_region";

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;
  private TrackedMockWorld world;

  @BeforeEach
  void setUp() {
    MemoryTracker.reset();
    accessor = RTPTestSetup.install(tempDir);
    MockPlayerRegistry.clear();
    BitTeleportLog.clear();
    TeleportSessions.current().processingPlayers.clear();
    TeleportSessions.current().latestTeleportData.clear();
    world = new TrackedMockWorld("bit_teleport_world");
    accessor.addWorld(world);
    Circle circle = new Circle();
    circle.setRng(new Random(42L));
    RegionSettings settings = new RegionSettings(REGION, world, circle,
        new LinearAdjustor(new ArrayList<>()), false, false, 10L, 1000L, 0L, 5, 0.0, 1L, "", false);
    ActiveSelection.get().permRegionLookup.put(REGION, new Region(REGION, settings));
    CommandsAPI.commandPipeline.clear();
  }

  @AfterEach
  void tearDown() {
    ActiveSelection.get().permRegionLookup.remove(REGION);
    MockPlayerRegistry.clear();
    BitTeleportLog.clear();
    TeleportSessions.current().processingPlayers.clear();
    TeleportSessions.current().latestTeleportData.clear();
    TestSemaphore.clearAllForTesting();
    RTP.reloading.set(false);
    MemoryTracker.reset();
  }

  private static List<String> codes(BitReport report) {
    return report.findings().stream().map(BitFinding::code).toList();
  }

  /** Lands the registered mock at a fixed point, as the pipeline's TELEPORT stage would. */
  private TestTeleportCmd.TeleportFn landing(List<RuntimeMockRTPPlayer> seen) {
    return (id, target) -> {
      RuntimeMockRTPPlayer mock = (RuntimeMockRTPPlayer) MockPlayerRegistry.get(id);
      seen.add(mock);
      RTPLocation at = new RTPLocation(world, 16, 70, 16);
      mock.setLocation(at);
      return CompletableFuture.completedFuture(RTPResult.success(at));
    };
  }

  @Test
  @DisplayName("ADR-121: a landed mock yields HEALTHY with exactly the INFO TEST_TELEPORT_MOCKED finding")
  void landedMockIsHealthyAndOnlyReportsMocked() {
    List<RuntimeMockRTPPlayer> seen = new CopyOnWriteArrayList<>();

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, landing(seen), 5_000L);

    assertEquals(BitHealth.HEALTHY, report.health(), report.toJson());
    assertEquals(List.of("TEST_TELEPORT_MOCKED"), codes(report));
    assertEquals(java.lang.System.Logger.Level.INFO, report.findings().get(0).severity());
    assertEquals("teleport.mock", report.findings().get(0).path());
    assertEquals(1, seen.size());
    assertEquals(1, seen.get(0).setLocationCount());
    assertTrue(seen.get(0).name().startsWith(MockPlayerSession.NAME_PREFIX));
    assertTrue(MockPlayerRegistry.isEmpty(), "every mock session must close");
  }

  @Test
  @DisplayName("ADR-121: count mocks run sequentially, one session each, and all are unregistered")
  void countRunsEachMockInItsOwnSession() {
    List<RuntimeMockRTPPlayer> seen = new CopyOnWriteArrayList<>();

    BitReport report = TestTeleportCmd.runProbe(REGION, 3, landing(seen), 5_000L);

    assertEquals(BitHealth.HEALTHY, report.health(), report.toJson());
    assertEquals(3, seen.stream().map(RuntimeMockRTPPlayer::uuid).distinct().count());
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121 end-to-end: RTPAPI.teleport lands the mock, logs one BitTeleportLog record, leaks nothing")
  void realPipelineLandsMockWithoutLeaks() {
    AtomicReference<RuntimeMockRTPPlayer> seen = new AtomicReference<>();
    TestTeleportCmd.TeleportFn real = (id, target) -> {
      seen.set((RuntimeMockRTPPlayer) MockPlayerRegistry.get(id));
      return RTPAPI.teleport(id, target);
    };

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, real, 10_000L);

    assertEquals(BitHealth.HEALTHY, report.health(), report.toJson());
    assertEquals(List.of("TEST_TELEPORT_MOCKED"), codes(report));
    assertNotNull(seen.get());
    assertEquals(1, seen.get().setLocationCount());
    assertEquals(1, BitTeleportLog.snapshot().size(), "mock landing goes to BitTeleportLog");
    assertEquals(seen.get().uuid(), BitTeleportLog.snapshot().get(0).mockId());
    assertEquals(0, MemoryTracker.trackedCountByLabel("TeleportData-" + seen.get().uuid()));
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: an unknown region reports TEST_TELEPORT_UNAVAILABLE plus MOCKED and registers no mock")
  void unknownRegionIsUnavailable() {
    List<RuntimeMockRTPPlayer> seen = new CopyOnWriteArrayList<>();

    BitReport report = TestTeleportCmd.runProbe("no_such_region_xyz", 1, landing(seen), 5_000L);

    assertEquals(BitHealth.FAILED, report.health());
    assertTrue(codes(report).contains("TEST_TELEPORT_UNAVAILABLE"), codes(report).toString());
    assertTrue(codes(report).contains("TEST_TELEPORT_MOCKED"), codes(report).toString());
    assertTrue(seen.isEmpty(), "no teleport may be attempted for an unresolved region");
    assertTrue(MockPlayerRegistry.isEmpty());
    assertTrue(accessor.logMessages.stream().anyMatch(m -> m.contains("TEST_TELEPORT_UNAVAILABLE")),
        "S-004: failure must be logged");
  }

  @Test
  @DisplayName("ADR-121 REQ-RTP-S-004: a RELOADING rejection reports TEST_TELEPORT_REJECTED plus MOCKED")
  void reloadingRejectionIsReported() {
    RTP.reloading.set(true);
    TestTeleportCmd.TeleportFn real = RTPAPI::teleport;

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, real, 5_000L);

    assertEquals(BitHealth.FAILED, report.health());
    assertTrue(codes(report).contains("TEST_TELEPORT_REJECTED"), codes(report).toString());
    assertTrue(codes(report).contains("TEST_TELEPORT_MOCKED"), codes(report).toString());
    assertFalse(codes(report).contains("TEST_TELEPORT_SET_LOCATION_NOT_REACHED"));
    BitFinding rejected = report.findings().stream()
        .filter(f -> f.code().equals("TEST_TELEPORT_REJECTED")).findFirst().orElseThrow();
    assertEquals("teleport.result", rejected.path());
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121 REQ-RTP-S-004: a simulated rejection reason is named in the REJECTED finding")
  void simulatedRejectionNamesReason() {
    TestTeleportCmd.TeleportFn rejecting = (id, target) -> CompletableFuture.completedFuture(
        RTPResult.failure(RTPResult.Reason.NO_SAFE_LOCATION, "none"));

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, rejecting, 5_000L);

    BitFinding rejected = report.findings().stream()
        .filter(f -> f.code().equals("TEST_TELEPORT_REJECTED")).findFirst().orElseThrow();
    assertTrue(rejected.message().contains("NO_SAFE_LOCATION"), rejected.message());
    assertTrue(codes(report).contains("TEST_TELEPORT_MOCKED"));
  }

  @Test
  @DisplayName("ADR-121 REQ-RTP-S-004: a stuck pipeline reports TIMEOUT and SET_LOCATION_NOT_REACHED and still closes")
  void stuckPipelineTimesOutAndCloses() {
    AtomicReference<UUID> id = new AtomicReference<>();
    TestTeleportCmd.TeleportFn stuck = (uuid, target) -> {
      id.set(uuid);
      return new CompletableFuture<>();
    };

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, stuck, 50L);

    assertEquals(BitHealth.FAILED, report.health());
    assertTrue(codes(report).contains("TEST_TELEPORT_TIMEOUT"), codes(report).toString());
    assertTrue(codes(report).contains("TEST_TELEPORT_SET_LOCATION_NOT_REACHED"), codes(report).toString());
    assertTrue(codes(report).contains("TEST_TELEPORT_MOCKED"));
    assertNotNull(id.get());
    assertFalse(MockPlayerRegistry.isMock(id.get()), "close() must run after a timeout");
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: a thrown teleport call is reported as REJECTED, never swallowed")
  void thrownTeleportIsRejected() {
    TestTeleportCmd.TeleportFn throwing = (uuid, target) -> {
      throw new IllegalStateException("core not loaded");
    };

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, throwing, 1_000L);

    assertTrue(codes(report).contains("TEST_TELEPORT_REJECTED"), codes(report).toString());
    assertTrue(MockPlayerRegistry.isEmpty());
  }

  @Test
  @DisplayName("ADR-121: a processingPlayers entry left after completion reports TEST_TELEPORT_PROCESSING_LEAK")
  void plantedProcessingEntryIsALeak() {
    List<RuntimeMockRTPPlayer> seen = new CopyOnWriteArrayList<>();
    TestTeleportCmd.TeleportFn leaking = (uuid, target) -> {
      TeleportSessions.current().processingPlayers.add(uuid);
      return landing(seen).apply(uuid, target);
    };

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, leaking, 5_000L);

    assertEquals(List.of("TEST_TELEPORT_PROCESSING_LEAK", "TEST_TELEPORT_MOCKED"), codes(report));
    assertEquals("teleport.processingPlayers", report.findings().get(0).path());
    assertFalse(TeleportSessions.current().processingPlayers.contains(seen.get(0).uuid()),
        "MockPlayerSession.close() purges the planted entry");
  }

  @Test
  @DisplayName("ADR-121 REQ-RTP-S-002: a tracked TeleportData-<uuid> left after completion reports TEST_TELEPORT_TRACKER_LEAK")
  void plantedTrackerEntryIsALeak() {
    List<RuntimeMockRTPPlayer> seen = new CopyOnWriteArrayList<>();
    List<Object> pins = new ArrayList<>();
    TestTeleportCmd.TeleportFn leaking = (uuid, target) -> {
      Object data = new Object();
      pins.add(data);
      MemoryTracker.track(data, "TeleportData-" + uuid, 60_000L);
      return landing(seen).apply(uuid, target);
    };

    BitReport report = TestTeleportCmd.runProbe(REGION, 1, leaking, 5_000L);

    assertEquals(List.of("TEST_TELEPORT_TRACKER_LEAK", "TEST_TELEPORT_MOCKED"), codes(report));
    assertEquals("teleport.memoryTracker", report.findings().get(0).path());
    assertEquals(1, pins.size());
  }

  @Test
  @DisplayName("ADR-121: component is HEAVY IBIT, excluded from group selectors and selectable by name")
  void componentMetadataAndSelection() {
    assertEquals("teleport", TestTeleportCmd.COMPONENT.id());
    assertEquals("test.teleport", TestTeleportCmd.COMPONENT.subsystem());
    assertEquals(BitTier.IBIT, TestTeleportCmd.COMPONENT.tier());
    assertEquals(BitSideEffect.HEAVY, TestTeleportCmd.COMPONENT.sideEffect());
    assertEquals(120_000L, TestTeleportCmd.COMPONENT.timeoutMillis());
    assertTrue(RtpBit.RUNNER.components().contains(TestTeleportCmd.COMPONENT));

    for (String group : List.of("all", "tier:ibit", "subsystem:test.teleport")) {
      List<BitResult> results = RtpBit.RUNNER.run(BitSelector.parse(group), Runnable::run);
      assertTrue(results.stream().noneMatch(r -> r.component().id().equals("teleport")),
          group + " must not run a HEAVY component");
    }
    assertEquals(BitSelector.Kind.COMPONENT, BitSelector.parse("teleport").kind());
    assertTrue(RtpBit.RUNNER.components().stream()
        .anyMatch(c -> c.id().equals(BitSelector.parse("teleport").value())));
  }

  @Test
  @DisplayName("ADR-121: count accepts 1..16 and rejects values outside the range")
  void countValidatorBounds() {
    TestTeleportCmd cmd = new TestTeleportCmd(null);
    var count = cmd.getParameterLookup().get("count");
    assertNotNull(count);
    for (String ok : List.of("1", "8", "16")) assertTrue(count.isRelevant.apply(RTPAPI.serverId, ok), ok);
    for (String bad : List.of("0", "17", "-1", "abc", "")) {
      assertFalse(count.isRelevant.apply(RTPAPI.serverId, bad), bad);
    }
    assertEquals(1, TestTeleportCmd.count(Map.of()));
    assertEquals(16, TestTeleportCmd.count(Map.of("count", List.of("16"))));
    assertEquals(1, TestTeleportCmd.count(Map.of("count", List.of("99"))));
    assertEquals("default", TestTeleportCmd.region(Map.of()));
    assertEquals("r1", TestTeleportCmd.region(Map.of("region", List.of("r1"))));
  }

  @Test
  @DisplayName("ADR-121: the command body runs async and emits a JSON report with the MOCKED finding")
  void onCommandEmitsJsonReport() {
    boolean ok = new TestTeleportCmd(null).onCommand(RTPAPI.serverId,
        Map.of("region", List.of("no_such_region_xyz"), "json", List.of("true")), null);

    assertTrue(ok);
    String json = accessor.logMessages.stream()
        .filter(m -> m.contains("[RTP bit/test.teleport]")).findFirst().orElseThrow();
    assertTrue(json.contains("TEST_TELEPORT_MOCKED"), json);
    assertTrue(json.contains("TEST_TELEPORT_UNAVAILABLE"), json);
    assertNull(TestSemaphore.holderOf(RTPAPI.serverId), "permit released after the async body");
    assertTrue(ActiveTestJobs.snapshot().getOrDefault(RTPAPI.serverId, List.of()).isEmpty());
    BitResult last = RtpBit.RUNNER.last("teleport");
    assertNotNull(last);
    assertSame(TestTeleportCmd.COMPONENT, last.component());
  }

  @Test
  @DisplayName("ADR-121: a parent-held permit for teleport is reused, not contended")
  void parentHeldPermitIsReused() {
    assertTrue(TestSemaphore.tryAcquire(RTPAPI.serverId, "teleport"));

    boolean ok = new TestTeleportCmd(null).onCommand(RTPAPI.serverId,
        Map.of("region", List.of("no_such_region_xyz")), null);

    assertTrue(ok);
    assertNotNull(TestSemaphore.holderOf(RTPAPI.serverId), "the parent owns and releases its permit");
  }

  @Test
  @DisplayName("ADR-121: null callerId falls back to RTPAPI.serverId without throwing IllegalArgumentException")
  void nullCallerIdFallsBackToServerId() {
    boolean ok = new TestTeleportCmd(null).onCommand(null,
        Map.of("region", List.of("no_such_region_xyz"), "json", List.of("true")), null);

    assertTrue(ok);
    assertNull(TestSemaphore.holderOf(RTPAPI.serverId), "permit released after the async body");
  }

  // --- parser-level grammar (ADR-121 section 11) ----------------------------------------------

  private static final class Capturing extends TestTeleportCmd {
    final AtomicReference<Map<String, List<String>>> seen = new AtomicReference<>();

    Capturing() {
      super(null);
    }

    @Override
    public boolean onCommand(
        UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
      seen.set(parameterValues);
      return true;
    }
  }

  private static boolean dispatch(Capturing cmd, String... args) {
    CompletableFuture<Boolean> future =
        cmd.onCommand(RTPAPI.serverId, perm -> true, msg -> {}, args, 0, null);
    while (!CommandsAPI.commandPipeline.isEmpty()) CommandsAPI.execute();
    assertTrue(future.isDone(), "parser must complete synchronously after draining the pipeline");
    return future.join();
  }

  @Test
  @DisplayName("ADR-121 grammar: 'teleport region=default json=true' binds region and json")
  void parserBindsRegionAndJson() {
    Capturing cmd = new Capturing();
    assertTrue(dispatch(cmd, "region=default", "json=true"));
    assertEquals(List.of("default"), cmd.seen.get().get("region"));
    assertEquals(List.of("true"), cmd.seen.get().get("json"));
    assertTrue(ProbeBitReport.isJson(cmd.seen.get()));
  }

  @Test
  @DisplayName("ADR-121 grammar: count=17 is rejected by the validator and never bound")
  void parserRejectsOutOfRangeCount() {
    Capturing cmd = new Capturing();
    dispatch(cmd, "count=17");
    assertTrue(cmd.seen.get() == null || cmd.seen.get().get("count") == null,
        "count=17 must not reach the command body");
    Capturing ok = new Capturing();
    assertTrue(dispatch(ok, "count=4"));
    assertEquals(List.of("4"), ok.seen.get().get("count"));
  }

  @Test
  @DisplayName("ADR-121 grammar: the colon form json:true is an unknown subcommand")
  void parserRejectsColonForm() {
    Capturing cmd = new Capturing();
    assertFalse(dispatch(cmd, "json:true"));
    assertNull(cmd.seen.get());
  }

  @Test
  @DisplayName("ADR-121: teleport codes are registered in the TEST namespace")
  void codesAreRegistered() {
    assertSame(TestTeleportCmd.TEST_TELEPORT_MOCKED, RtpBitCodes.REGISTRY.byId("TEST", 3201).orElseThrow());
    assertSame(TestTeleportCmd.TEST_TELEPORT_TIMEOUT, RtpBitCodes.REGISTRY.byId("TEST", 3207).orElseThrow());
  }
}
