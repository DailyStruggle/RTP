package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitHealth;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-API-F-009 runtime probe components")
class ProbeComponentsTest {
  @Test
  void componentsAreRegisteredSafeAndCodesUseTheTestNamespace() throws Exception {
    assertComponent(TestApiCompatCmd.class, "api-compat");
    assertComponent(TestSchedulerCmd.class, "scheduler");
    assertComponent(TestEventsCmd.class, "events");
  }

  @Test
  void apiCompatibilityFailuresMapToRegisteredFindings() throws Exception {
    TestApiCompatCmd.ProbeReport probe = new TestApiCompatCmd.ProbeReport();
    probe.missingMethod.add("java.lang.String#missing()");
    probe.errors.add("java.lang.String#broken() [LinkageError]");

    BitReport report = toBitReport(TestApiCompatCmd.class, probe);

    assertEquals(BitHealth.FAILED, report.health());
    assertEquals(List.of("TEST_API_COMPAT_MISSING_METHOD", "TEST_API_COMPAT_RESOLUTION_ERROR"),
        report.findings().stream().map(finding -> finding.code()).sorted().toList());
  }

  @Test
  void everySchedulerAndEventFailureHasARegisteredFinding() throws Exception {
    assertAllCodesFail(TestSchedulerCmd.class, TestSchedulerCmd.Result.class, "TEST_SCHEDULER_UNAVAILABLE");
    assertAllCodesFail(TestEventsCmd.class, TestEventsCmd.Result.class, "TEST_EVENTS_THREW");
  }

  @Test
  void unavailableSchedulerMapsOnlyItsUnavailableFinding() throws Exception {
    Object probe = failingResult(TestSchedulerCmd.Result.class);
    Field available = probe.getClass().getDeclaredField("available");
    available.setAccessible(true);
    available.setBoolean(probe, false);

    BitReport report = toBitReport(TestSchedulerCmd.class, probe);

    assertEquals(BitHealth.FAILED, report.health());
    assertEquals(List.of("TEST_SCHEDULER_UNAVAILABLE"),
        report.findings().stream().map(finding -> finding.code()).toList());
  }

  @Test
  void tableOutputNeverUsesTheLegacyProbePrefix() throws Exception {
    BitComponent component = component(TestApiCompatCmd.class);
    String output = String.join("\n", BitTableOutput.render(
        BitTableOutput.rows(List.of(component), ignored -> null)));

    assertFalse(output.contains("[RTP test/"), output);
    assertTrue(output.contains(BitTableOutput.PREFIX), output);
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

  private static void assertAllCodesFail(Class<?> owner, Class<?> resultType, String... excludedKeys)
      throws Exception {
    BitReport report = toBitReport(owner, failingResult(resultType));
    List<Integer> expected = codes(owner).stream()
        .filter(code -> Arrays.stream(excludedKeys).noneMatch(key -> key.equals(code.key())))
        .map(BitCode::id)
        .sorted()
        .toList();
    List<Integer> actual = report.findings().stream().map(finding -> finding.codeId()).sorted().toList();
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

  private static List<BitCode> codes(Class<?> owner) throws IllegalAccessException {
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

  private static Object failingResult(Class<?> resultType) throws Exception {
    Constructor<?> constructor = resultType.getDeclaredConstructor();
    constructor.setAccessible(true);
    Object result = constructor.newInstance();
    for (Field field : resultType.getDeclaredFields()) {
      if (field.getType() == boolean.class) {
        field.setAccessible(true);
        field.setBoolean(result, field.getName().equals("available"));
      }
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
