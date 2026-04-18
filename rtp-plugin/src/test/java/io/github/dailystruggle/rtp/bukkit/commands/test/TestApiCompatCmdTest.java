package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.bukkit.commands.test.TestApiCompatCmd.ApiProbe;
import io.github.dailystruggle.rtp.bukkit.commands.test.TestApiCompatCmd.ProbeReport;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestApiCompatCmd#runProbes(List)}.
 *
 * <p>Uses JDK-only target classes ({@link String}, {@link java.util.List})
 * so the test has no hard dependency on Bukkit/Paper/Folia being on the
 * test classpath &mdash; the production probes point at {@code org.bukkit.*},
 * but the resolver logic is classpath-agnostic by design.
 *
 * <p>Traces to: runtime surfacing of {@link NoSuchMethodError} risk
 * (REQ-RTP-SYS-001; {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md &sect;3.7}).
 */
class TestApiCompatCmdTest {

  @Test
  @DisplayName("probe resolves a real JDK method (java.lang.String#length) → ok")
  void probeResolvesRealMethod() {
    ProbeReport r =
        TestApiCompatCmd.runProbes(
            List.of(
                new ApiProbe(
                    "java.lang.String", "length", Collections.emptyList(), "smoke test")));
    assertEquals(1, r.ok.size(), "expected exactly one ok probe, got report=" + debug(r));
    assertEquals(0, r.failures());
    assertTrue(r.skipped.isEmpty());
  }

  @Test
  @DisplayName("probe with missing class → skipped, not missing-method")
  void missingClassIsSkipped() {
    ProbeReport r =
        TestApiCompatCmd.runProbes(
            List.of(
                new ApiProbe(
                    "com.example.definitely.NotHere",
                    "whatever",
                    Collections.emptyList(),
                    "platform-conditional")));
    assertEquals(1, r.skipped.size(), debug(r));
    assertEquals(0, r.failures(), "missing-class must not count as failure");
  }

  @Test
  @DisplayName("probe with existing class but wrong signature → missing-method (failure)")
  void missingMethodIsFailure() {
    ProbeReport r =
        TestApiCompatCmd.runProbes(
            List.of(
                new ApiProbe(
                    "java.lang.String",
                    "thisMethodDoesNotExist",
                    Collections.emptyList(),
                    "negative test")));
    assertEquals(1, r.missingMethod.size(), debug(r));
    assertEquals(1, r.failures());
    assertTrue(r.ok.isEmpty());
  }

  @Test
  @DisplayName("primitive parameter types resolve (String#substring(int,int))")
  void primitiveParamTypesResolve() {
    ProbeReport r =
        TestApiCompatCmd.runProbes(
            List.of(
                new ApiProbe(
                    "java.lang.String",
                    "substring",
                    List.of("int", "int"),
                    "primitive signature test")));
    assertEquals(1, r.ok.size(), debug(r));
  }

  @Test
  @DisplayName("missing parameter-type class → skipped (can't exist without it)")
  void missingParamTypeIsSkipped() {
    ProbeReport r =
        TestApiCompatCmd.runProbes(
            List.of(
                new ApiProbe(
                    "java.lang.String",
                    "equals",
                    List.of("com.example.Nonexistent"),
                    "param-type-missing test")));
    assertEquals(1, r.skipped.size(), debug(r));
    assertEquals(0, r.failures());
  }

  @Test
  @DisplayName("defaultProbes() list is non-empty and every entry has a hint")
  void defaultProbesWellFormed() {
    List<ApiProbe> probes = TestApiCompatCmd.defaultProbes();
    assertTrue(probes.size() >= 5, "curated list suspiciously small: " + probes.size());
    for (ApiProbe p : probes) {
      assertTrue(p.className != null && !p.className.isBlank(), "blank className in " + p);
      assertTrue(p.methodName != null && !p.methodName.isBlank(), "blank methodName in " + p);
      assertTrue(p.callSiteHint != null && !p.callSiteHint.isBlank(), "blank hint in " + p);
    }
  }

  private static String debug(ProbeReport r) {
    return "ok=" + r.ok + " skipped=" + r.skipped + " missingMethod=" + r.missingMethod
        + " errors=" + r.errors;
  }
}
