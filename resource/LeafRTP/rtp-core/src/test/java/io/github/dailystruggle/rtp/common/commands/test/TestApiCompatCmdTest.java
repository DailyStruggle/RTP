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
    TestApiCompatCmd.clearProbeSuppliers();
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
  void missingOptionalMethodIsWarningButNotAFailure() {
    TestApiCompatCmd.ApiProbe optionalProbe =
        new TestApiCompatCmd.ApiProbe("java.lang.String", "definitelyNotAMethod", List.of(), "optional", true);

    TestApiCompatCmd.ProbeReport report = TestApiCompatCmd.runProbes(List.of(optionalProbe));

    assertEquals(0, report.missingMethod.size());
    assertEquals(1, report.missingOptional.size());
    assertEquals(0, report.failures());
    var finding = TestApiCompatCmd.toBitReport(report).findings().get(0);
    assertEquals(TestApiCompatCmd.MISSING_OPTIONAL_METHOD.key(), finding.code());
    assertEquals(java.lang.System.Logger.Level.WARNING, finding.severity());
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

  @Test
  void platformAwareProbeSelection_coversBukkitPaperFoliaFabricNeoForge() {
    // 1. Spigot / Bukkit: getChunkAtAsync, teleportAsync, and Folia schedulers are optional
    accessor.setPlatform("Spigot");
    List<TestApiCompatCmd.ApiProbe> spigotProbes = TestApiCompatCmd.defaultProbes();
    assertTrue(
        spigotProbes.stream()
            .anyMatch(p -> p.className.equals("org.bukkit.World") && p.methodName.equals("addPluginChunkTicket")));
    assertTrue(
        spigotProbes.stream()
            .anyMatch(p -> p.className.equals("org.bukkit.inventory.InventoryView") && p.methodName.equals("getTopInventory")));
    assertTrue(
        spigotProbes.stream()
            .anyMatch(p -> p.className.equals("org.bukkit.Registry") && p.methodName.equals("BIOME")));
    assertTrue(
        spigotProbes.stream()
            .filter(p -> p.className.equals("org.bukkit.Bukkit") && p.methodName.equals("getRegionScheduler"))
            .allMatch(p -> p.optional),
        "Folia scheduler probes must be optional on Spigot");

    // 2. Paper: getChunkAtAsync and teleportAsync are required; Folia schedulers remain optional
    accessor.setPlatform("Paper");
    List<TestApiCompatCmd.ApiProbe> paperProbes = TestApiCompatCmd.defaultProbes();
    assertTrue(
        paperProbes.stream()
            .filter(p -> p.className.equals("org.bukkit.World") && p.methodName.equals("getChunkAtAsync"))
            .noneMatch(p -> p.optional),
        "World#getChunkAtAsync must be required on Paper");
    assertTrue(
        paperProbes.stream()
            .filter(p -> p.className.equals("org.bukkit.entity.Entity") && p.methodName.equals("teleportAsync"))
            .noneMatch(p -> p.optional),
        "Entity#teleportAsync must be required on Paper");
    assertTrue(
        paperProbes.stream()
            .filter(p -> p.className.equals("org.bukkit.Bukkit") && p.methodName.equals("getRegionScheduler"))
            .allMatch(p -> p.optional),
        "Folia scheduler probes must be optional on Paper");

    // 3. Folia: Folia regional threading surfaces are required (optional == false)
    accessor.setPlatform("Folia");
    List<TestApiCompatCmd.ApiProbe> foliaProbes = TestApiCompatCmd.defaultProbes();
    assertTrue(
        foliaProbes.stream()
            .filter(p -> p.className.equals("org.bukkit.Bukkit") && p.methodName.equals("getRegionScheduler"))
            .noneMatch(p -> p.optional),
        "Bukkit#getRegionScheduler must be required on Folia");
    assertTrue(
        foliaProbes.stream()
            .filter(p -> p.className.equals("org.bukkit.entity.Entity") && p.methodName.equals("getScheduler"))
            .noneMatch(p -> p.optional),
        "Entity#getScheduler must be required on Folia");

    // 4. Fabric: probes FabricVersionAdapter and net.minecraft.server.level.*
    accessor.setPlatform("fabric");
    List<TestApiCompatCmd.ApiProbe> fabricProbes = TestApiCompatCmd.defaultProbes();
    assertTrue(
        fabricProbes.stream()
            .anyMatch(
                p ->
                    p.className.equals("io.github.dailystruggle.rtp.fabric.version.FabricVersionAdapter")
                        && p.methodName.equals("requestFullChunkAsync")));
    assertTrue(
        fabricProbes.stream()
            .anyMatch(
                p ->
                    p.className.equals("net.minecraft.server.level.ServerLevel")
                        && p.methodName.equals("getChunkSource")));
    assertTrue(
        fabricProbes.stream()
            .anyMatch(
                p ->
                    p.className.equals("net.minecraft.core.RegistryAccess")
                        && p.methodName.equals("lookupOrThrow")));

    // 5. NeoForge: probes NeoForgeVersionAdapter and net.minecraft.server.level.*
    accessor.setPlatform("neoforge");
    List<TestApiCompatCmd.ApiProbe> neoForgeProbes = TestApiCompatCmd.defaultProbes();
    assertTrue(
        neoForgeProbes.stream()
            .anyMatch(
                p ->
                    p.className.equals("io.github.dailystruggle.rtp.neoforge.version.NeoForgeVersionAdapter")
                        && p.methodName.equals("requestFullChunkAsync")));
    assertTrue(
        neoForgeProbes.stream()
            .anyMatch(
                p ->
                    p.className.equals("net.minecraft.server.level.ServerChunkCache")
                        && p.methodName.equals("addTicketWithRadius")));
  }

  @Test
  void registerProbeSupplier_overridesAndResolvesStaticFields() {
    TestApiCompatCmd.ApiProbe fieldProbe =
        new TestApiCompatCmd.ApiProbe("java.lang.Integer", "MAX_VALUE", List.of(), "static field probe");
    TestApiCompatCmd.registerProbeSupplier("custom-platform", () -> List.of(fieldProbe));
    accessor.setPlatform("custom-platform");

    List<TestApiCompatCmd.ApiProbe> probes = TestApiCompatCmd.defaultProbes();
    assertEquals(1, probes.size());
    TestApiCompatCmd.ProbeReport report = TestApiCompatCmd.runProbes(probes);
    assertEquals(1, report.ok.size());
    assertEquals(0, report.failures());
    TestApiCompatCmd.clearProbeSuppliers();
  }
}
