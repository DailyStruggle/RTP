package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSelector;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 platform probe components")
class PluginProbeComponentsTest {
  @BeforeEach
  void initialiseProbeOwners() {
    RtpBitCodes.initialiseOwners(BukkitBitCodeOwners.OWNERS);
  }

  @Test
  void componentsAreRegisteredSafeAndCodesUseTheTestNamespace() throws Exception {
    assertComponent(TestCommandsCmd.class, "commands");
    assertComponent(EconomyIsolationTestJob.class, "economy-isolation");
    assertComponent(DisconnectTestJob.class, "disconnect-job");
    assertComponent(NetworkSimulationTestJob.class, "network");
    assertComponent(SafetyVerifierTestJob.class, "safety-verifier");
  }

  @Test
  @DisplayName("runtime probes register their world/player requirements")
  void runtimeProbesRegisterComponents() {
    new TestStressCmd(null);
    new QueueStarvationTestJob(null);
    new TestDisconnectMidflightCmd(null);
    new FoliaOwnershipTestJob(null);
    new AsyncReplyTestJob(null);
    new TestHologramCmd(null);

    assertComponentSideEffect("stress", BitSideEffect.HEAVY);
    assertComponentSideEffect("stress-player", BitSideEffect.NEEDS_PLAYER);
    assertComponentSideEffect("queue-starvation", BitSideEffect.HEAVY);
    assertComponentSideEffect("disconnect-midflight", BitSideEffect.NEEDS_WORLD);
    assertComponentSideEffect("folia-ownership", BitSideEffect.NEEDS_WORLD);
    assertComponentSideEffect("async-reply", BitSideEffect.HEAVY);
    assertComponentSideEffect("async-reply-player", BitSideEffect.NEEDS_PLAYER);
    assertComponentSideEffect("hologram", BitSideEffect.NEEDS_PLAYER);
  }

  private static void assertComponentSideEffect(String id, BitSideEffect sideEffect) {
    BitComponent component = RtpBit.RUNNER.components().stream()
        .filter(candidate -> candidate.id().equals(id))
        .findFirst()
        .orElse(null);
    assertNotNull(component, id + " must be registered with RtpBit.RUNNER");
    assertEquals(sideEffect, component.sideEffect(), id);
  }

  @Test
  void commandAuditFailuresMapToLocatedRegisteredFindings() throws Exception {
    TestCommandsCmd.AuditReport probe = new TestCommandsCmd.AuditReport();
    probe.missingName.add("root child");
    probe.missingPermission.add("root child");
    probe.keyNameMismatch.add("root key=OLD name=NEW");
    probe.nullChildren.add("root -> key=NULL");
    probe.traversalErrors.add("root: IllegalStateException");

    BitReport report = toBitReport(TestCommandsCmd.class, probe);

    assertEquals(BitHealth.FAILED, report.health());
    assertEquals(
        List.of(
            "TEST_COMMANDS_KEY_NAME_MISMATCH",
            "TEST_COMMANDS_MISSING_NAME",
            "TEST_COMMANDS_MISSING_PERMISSION",
            "TEST_COMMANDS_NULL_CHILD",
            "TEST_COMMANDS_TRAVERSAL_ERROR"),
        report.findings().stream().map(BitFinding::code).sorted().toList());
  }

  @Test
  void disconnectAndSafetyFailuresMapToEveryRegisteredCode() throws Exception {
    assertEveryCodeFails(DisconnectTestJob.class, DisconnectTestJob.Result.class);
    assertEveryCodeFails(SafetyVerifierTestJob.class, SafetyVerifierTestJob.Result.class);
  }

  @Test
  void economyFailuresAndFabricSkipMapToTheirFindings() {
    EconomyIsolationTestJob.Result failed = new EconomyIsolationTestJob.Result();
    failed.noTimeout = false;
    failed.callerThreadFree = false;
    failed.regionThreadFree = false;
    failed.failed = false;
    BitReport failureReport = EconomyIsolationTestJob.toBitReport(failed);

    assertEquals(BitHealth.FAILED, failureReport.health());
    assertEquals(
        List.of(
            "TEST_ECONOMY_ISOLATION_CALLER_THREAD",
            "TEST_ECONOMY_ISOLATION_FAILED",
            "TEST_ECONOMY_ISOLATION_REGION_THREAD",
            "TEST_ECONOMY_ISOLATION_TIMEOUT"),
        failureReport.findings().stream().map(BitFinding::code).sorted().toList());

    EconomyIsolationTestJob.Result skipped = new EconomyIsolationTestJob.Result();
    skipped.notApplicable = true;
    BitReport skippedReport = EconomyIsolationTestJob.toBitReport(skipped);

    assertEquals(BitHealth.HEALTHY, skippedReport.health());
    assertEquals(
        List.of("TEST_ECONOMY_ISOLATION_NOT_APPLICABLE"),
        skippedReport.findings().stream().map(BitFinding::code).toList());
  }

  @Test
  void syntheticNetworkTokensNeedNoRealProxy() {
    assertNull(NetworkSimulationTestJob.resolveTransportOrNull(null));
    NetworkSimulationTestJob.Result probe =
        new NetworkSimulationTestJob.Result(NetworkProbeTarget.ProbeKind.TOKENS);
    probe.notConfigured = true;

    BitReport report = NetworkSimulationTestJob.toBitReport(probe);

    assertEquals(BitHealth.HEALTHY, report.health());
    assertEquals(
        List.of("TEST_NETWORK_NOT_CONFIGURED"),
        report.findings().stream().map(BitFinding::code).toList());
  }

  @Test
  void networkHeartbeatAndTokenFailuresMapToSeparateFindings() {
    NetworkSimulationTestJob.Result tokenFailure =
        new NetworkSimulationTestJob.Result(NetworkProbeTarget.ProbeKind.TOKENS);
    BitReport tokenReport = NetworkSimulationTestJob.toBitReport(tokenFailure);
    assertEquals(BitHealth.FAILED, tokenReport.health());
    assertEquals(List.of("TEST_NETWORK_TOKENS"), tokenReport.findings().stream().map(BitFinding::code).toList());

    NetworkSimulationTestJob.Result heartbeatFailure =
        new NetworkSimulationTestJob.Result(NetworkProbeTarget.ProbeKind.HEARTBEAT);
    BitReport heartbeatReport = NetworkSimulationTestJob.toBitReport(heartbeatFailure);
    assertEquals(BitHealth.FAILED, heartbeatReport.health());
    assertEquals(List.of("TEST_NETWORK_HEARTBEAT"), heartbeatReport.findings().stream().map(BitFinding::code).toList());
  }

  @Test
  void allSelectorSkipsMutatingAndWorldDependentProbes() {
    List<io.github.dailystruggle.bit.BitResult> results =
        RtpBit.RUNNER.run(BitSelector.parse("all"), Runnable::run);
    List<String> componentIds = results.stream().map(result -> result.component().id()).toList();

    assertFalse(componentIds.contains("accessor"));
    assertFalse(componentIds.contains("chunk-ticket"));
  }

  private static void assertComponent(Class<?> owner, String id) throws Exception {
    BitComponent component = component(owner);
    assertEquals(id, component.id());
    assertEquals("test." + id, component.subsystem());
    assertEquals(BitTier.IBIT, component.tier());
    assertEquals(BitSideEffect.SAFE, component.sideEffect());
    assertTrue(RtpBit.RUNNER.components().contains(component), id + " must be in RtpBit.RUNNER");

    List<BitCode> codes = codes(owner);
    assertFalse(codes.isEmpty(), id + " must declare registered finding codes");
    for (BitCode code : codes) {
      assertEquals("TEST", code.namespace(), code.key());
      assertEquals(code, RtpBitCodes.REGISTRY.byId("TEST", code.id()).orElseThrow());
    }
  }

  private static void assertEveryCodeFails(Class<?> owner, Class<?> resultType) throws Exception {
    Object probe = probeResult(resultType);
    BitReport report = toBitReport(owner, probe);
    List<Integer> expected = codes(owner).stream().map(BitCode::id).sorted().toList();
    List<Integer> actual = report.findings().stream().map(BitFinding::codeId).sorted().toList();
    assertEquals(BitHealth.FAILED, report.health(), owner.getSimpleName());
    assertEquals(expected, actual, owner.getSimpleName());
  }

  private static BitComponent component(Class<?> owner) throws Exception {
    Field field = owner.getDeclaredField("COMPONENT");
    field.setAccessible(true);
    Object component = field.get(null);
    assertNotNull(component, owner.getSimpleName() + ".COMPONENT");
    return (BitComponent) component;
  }

  private static List<BitCode> codes(Class<?> owner) {
    return Arrays.stream(owner.getDeclaredFields())
        .filter(field -> field.getType() == BitCode.class)
        .map(
            field -> {
              try {
                field.setAccessible(true);
                return (BitCode) field.get(null);
              } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
              }
            })
        .toList();
  }

  private static Object probeResult(Class<?> resultType) throws Exception {
    Constructor<?> constructor = resultType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object result = constructor.newInstance();
    for (Field field : resultType.getDeclaredFields()) {
      field.setAccessible(true);
      if (field.getType() == boolean.class) field.setBoolean(result, false);
      if (field.getName().equals("threw")) field.set(result, "synthetic failure");
      if (field.getName().equals("residualTicketCount")) field.setInt(result, 1);
    }
    return result;
  }

  private static BitReport toBitReport(Class<?> owner, Object probe) throws Exception {
    Method method = Arrays.stream(owner.getDeclaredMethods())
        .filter(candidate -> candidate.getName().equals("toBitReport"))
        .findFirst()
        .orElseThrow(() -> new NoSuchMethodException(owner.getName() + ".toBitReport"));
    method.setAccessible(true);
    return (BitReport) method.invoke(null, probe);
  }
}
