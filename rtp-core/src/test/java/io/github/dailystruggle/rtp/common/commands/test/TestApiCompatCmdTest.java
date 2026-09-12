package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@code rtp test api-compat} - the reflective probe engine
 * ({@code runProbes}) buckets each probe into ok / skipped / missing-method,
 * and {@code onCommand} emits the summary and detail lines. Traces item 14 of
 * ENTERPRISE_READINESS.md. No API method is ever invoked (S-005).
 */
class TestApiCompatCmdTest {

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
  void runProbes_bucketsEachOutcome() {
    List<TestApiCompatCmd.ApiProbe> probes =
        List.of(
            // ok: resolvable no-arg method
            new TestApiCompatCmd.ApiProbe("java.lang.String", "length", List.of(), "hint-ok"),
            // ok: resolvable method with a primitive parameter
            new TestApiCompatCmd.ApiProbe(
                "java.lang.String", "substring", List.of("int"), "hint-substring"),
            // missing-method: class present, signature absent
            new TestApiCompatCmd.ApiProbe(
                "java.lang.String", "definitelyNotAMethod", List.of(), "hint-missing"),
            // skipped: class not on classpath
            new TestApiCompatCmd.ApiProbe(
                "com.example.DoesNotExist", "foo", List.of(), "hint-skip-class"),
            // skipped: parameter type not on classpath
            new TestApiCompatCmd.ApiProbe(
                "java.lang.String", "foo", List.of("com.example.MissingParam"), "hint-skip-param"));

    TestApiCompatCmd.ProbeReport report = TestApiCompatCmd.runProbes(probes);

    assertEquals(2, report.ok.size(), report.ok.toString());
    assertEquals(1, report.missingMethod.size(), report.missingMethod.toString());
    assertEquals(2, report.skipped.size(), report.skipped.toString());
    assertEquals(0, report.errors.size());
    assertEquals(1, report.failures(), "only missing-method + errors count as failures");
  }

  @Test
  void runProbes_resolvesEveryPrimitiveParamType_andDeclaredMethods() {
    List<TestApiCompatCmd.ApiProbe> probes =
        List.of(
            new TestApiCompatCmd.ApiProbe("java.lang.Math", "abs", List.of("double"), "double"),
            new TestApiCompatCmd.ApiProbe("java.lang.Math", "abs", List.of("float"), "float"),
            new TestApiCompatCmd.ApiProbe("java.lang.Math", "abs", List.of("long"), "long"),
            new TestApiCompatCmd.ApiProbe(
                "java.lang.Thread", "setDaemon", List.of("boolean"), "boolean"),
            // Protected, non-public method: getMethod() misses it, forcing the
            // declared-method hierarchy walk in findMethod().
            new TestApiCompatCmd.ApiProbe("java.lang.Object", "finalize", List.of(), "declared"));

    TestApiCompatCmd.ProbeReport report = TestApiCompatCmd.runProbes(probes);
    assertEquals(5, report.ok.size(), report.ok.toString());
    assertEquals(0, report.failures());
  }

  @Test
  void defaultProbes_areAllOffClasspath_soNoFailures() {
    // Every default probe targets a Bukkit/Paper/Folia class, none of which are
    // on the rtp-core classpath - so the sweep must be all-skipped, zero failures.
    TestApiCompatCmd.ProbeReport report =
        TestApiCompatCmd.runProbes(TestApiCompatCmd.defaultProbes());
    assertFalse(TestApiCompatCmd.defaultProbes().isEmpty());
    assertEquals(0, report.failures(), "default probes must not report failures off-platform");
    assertTrue(report.ok.isEmpty(), "no Bukkit class resolves in rtp-core tests");
  }

  @Test
  void onCommand_emitsSummaryToCaller() {
    MockRTPPlayer player = new MockRTPPlayer(UUID.randomUUID(), "p", null);
    accessor.addPlayer(player);

    boolean ret = new TestApiCompatCmd(null).onCommand(player.uuid(), noArgs(), null);

    assertTrue(ret);
    assertTrue(
        player.sentMessages.stream().anyMatch(m -> m.contains("api-compat") && m.contains("ok=")),
        player.sentMessages.toString());
  }

  @Test
  void onCommand_console_logsSummary() {
    boolean ret = new TestApiCompatCmd(null).onCommand(RTPAPI.serverId, noArgs(), null);
    assertTrue(ret);
    assertTrue(
        accessor.logMessages.stream().anyMatch(m -> m.contains("api-compat")),
        accessor.logMessages.toString());
  }

  @Test
  void nextCommandNonNull_shortCircuits() {
    assertTrue(
        new TestApiCompatCmd(null)
            .onCommand(RTPAPI.serverId, noArgs(), new TestApiCompatCmd(null)));
  }

  @Test
  void apiProbe_toString_isReadable() {
    TestApiCompatCmd.ApiProbe p =
        new TestApiCompatCmd.ApiProbe("a.B", "m", List.of("int", "long"), "hint");
    assertEquals("a.B#m(int,long)", p.toString());
    // Null param list normalises to empty.
    assertEquals("a.B#m()", new TestApiCompatCmd.ApiProbe("a.B", "m", null, "h").toString());
  }

  @Test
  void metadata_isStable() {
    TestApiCompatCmd cmd = new TestApiCompatCmd(null);
    assertEquals("api-compat", cmd.name());
    assertEquals("rtp.test", cmd.permission());
    assertTrue(cmd.description().length() > 0);
  }
}
