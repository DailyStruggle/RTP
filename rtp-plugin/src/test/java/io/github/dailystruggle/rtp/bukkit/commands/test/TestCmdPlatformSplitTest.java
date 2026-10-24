package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.commands.test.TestAllCmd;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the platform-aware constructor split between {@link TestCmd}
 * (Fabric path) and {@link BukkitTestCmd} (Bukkit path).
 *
 * <p>Justification: four {@code rtp test} subcommands hard-import
 * Bukkit/Spigot symbols ({@link TestStressCmd}, {@link TestChunkProbePerfCmd},
 * {@link TestFullCmd}, {@link AsyncReplyTestJob}). They must be registered
 * by {@link BukkitTestCmd} only, so loading {@link TestCmd} on Fabric does
 * not trigger {@code NoClassDefFoundError}. This test pins that contract;
 * regressions would silently re-introduce the Fabric class-loading hazard.
 *
 * <p>Resolves the Step G2 follow-up flagged in {@code MULTI_PLATFORM_PLAN.md}
 * and the {@code RTPCmdFabricRoot} Javadoc - {@code rtp test} is now reachable
 * on Fabric (every platform-neutral subcommand) without lifting any class out
 * of {@code rtp-plugin}.
 */
class TestCmdPlatformSplitTest {
  @TempDir Path tempDir;

  /** Children that must NEVER be registered by the platform-neutral {@link TestCmd}. */
  private static final Set<String> BUKKIT_ONLY_CHILDREN =
      Set.of("stress", "chunk-probe-perf", "full", "async-reply");

  /** Children that must be present on BOTH Bukkit and Fabric. */
  private static final Set<String> PLATFORM_NEUTRAL_CHILDREN =
      Set.of(
          "cancel",
          "all",
          "scheduler",
          "events",
          "reload-safety",
          "config-set",
          "commands",
          "api-compat",
          "chunk-ticket",
          "disconnect-midflight",
          "anvil-prefilter",
          "biome-source",
          "async-chunk-load",
          "queue-starvation",
          "economy-isolation",
          "folia-ownership",
          "disconnect-job",
          "network",
          "safety-verifier",
          "teleport");

  @BeforeEach
  void setUp() {
    RTPTestSetup.install(tempDir.toFile());
  }

  @Test
  @DisplayName("TestCmd (Fabric path) registers every platform-neutral child")
  void fabricPathHasAllNeutralChildren() {
    TestCmd cmd = new TestCmd(null);
    Map<String, CommandsAPICommand> lookup = cmd.getCommandLookup();
    for (String child : PLATFORM_NEUTRAL_CHILDREN) {
      assertNotNull(
          lookup.get(child.toUpperCase()),
          "TestCmd missing platform-neutral child '" + child + "'; lookup keys=" + lookup.keySet());
    }
  }

  @Test
  @DisplayName("REQ-API-F-009 TestCmd (Fabric path) registers the BIT components of its probe children")
  void fabricPathRegistersChildComponents() {
    Set<String> pluginComponents =
        Set.of("commands", "economy-isolation", "disconnect-job", "network", "safety-verifier");
    pluginComponents.forEach(RtpBit.RUNNER::unregister);

    new TestCmd(null);

    Set<String> registered = RtpBit.RUNNER.components().stream()
        .map(component -> component.id()).collect(java.util.stream.Collectors.toSet());
    assertTrue(registered.containsAll(pluginComponents), "registered components: " + registered);
  }

  @Test
  @DisplayName("TestCmd (Fabric path) excludes every Bukkit-bound child")
  void fabricPathExcludesBukkitOnlyChildren() {
    TestCmd cmd = new TestCmd(null);
    Map<String, CommandsAPICommand> lookup = cmd.getCommandLookup();
    for (String child : BUKKIT_ONLY_CHILDREN) {
      assertNull(
          lookup.get(child.toUpperCase()),
          "TestCmd unexpectedly registered Bukkit-only child '"
              + child
              + "' — would NoClassDefFoundError on Fabric");
    }
    assertTrue(lookup.get("ALL") instanceof TestAllCmd, "SAFE `all` is available cross-platform");
    assertNull(lookup.get("FULL"), "the Bukkit-only `full` sweep must remain absent on Fabric");
  }

  @Test
  @DisplayName("BukkitTestCmd registers Bukkit children and keeps all separate from full")
  void bukkitPathRegistersBukkitOnlyChildren() {
    BukkitTestCmd cmd = new BukkitTestCmd(null);
    Map<String, CommandsAPICommand> lookup = cmd.getCommandLookup();
    for (String child : BUKKIT_ONLY_CHILDREN) {
      assertNotNull(
          lookup.get(child.toUpperCase()),
          "BukkitTestCmd missing Bukkit-only child '" + child + "'");
    }
    assertTrue(lookup.get("ALL") instanceof TestAllCmd, "all must use the SAFE BIT runner");
    assertTrue(lookup.get("FULL") instanceof TestFullCmd, "full must remain the legacy sweep");
    assertFalse(lookup.get("ALL") == lookup.get("FULL"), "all and full are distinct commands");
  }

  @Test
  @DisplayName("BukkitTestCmd inherits every platform-neutral child from TestCmd")
  void bukkitPathInheritsNeutralChildren() {
    BukkitTestCmd cmd = new BukkitTestCmd(null);
    Map<String, CommandsAPICommand> lookup = cmd.getCommandLookup();
    for (String child : PLATFORM_NEUTRAL_CHILDREN) {
      assertNotNull(
          lookup.get(child.toUpperCase()),
          "BukkitTestCmd missing inherited platform-neutral child '" + child + "'");
    }
  }

  @Test
  @DisplayName("`test` is the canonical name on both subclasses")
  void canonicalNameStable() {
    assertFalse(new TestCmd(null).name().isBlank());
    assertTrue("test".equals(new BukkitTestCmd(null).name()), "BukkitTestCmd name must be 'test'");
    assertTrue("test".equals(new TestCmd(null).name()), "TestCmd name must be 'test'");
  }
}
