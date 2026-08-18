package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestFullCmd}'s shipped-coverage audit.
 *
 * <p>{@link TestFullCmd} ensures every new {@code
 * rtp test *} subcommand registered on {@link TestCmd} must either be added
 * to {@link TestFullCmd#SHIPPED_SUBCOMMAND_NAMES} (and dispatched from
 * {@code runShippedSubcommands}) or listed in
 * {@link TestFullCmd#DELIBERATELY_EXCLUDED_SUBCOMMANDS}. These tests guard
 * that contract so a future change cannot silently drop a subcommand from
 * the umbrella sweep.
 *
 * <p>Traces REQ-RTP-S-004 via {@link TestFullCmd}: a missing dispatch would
 * cause the runtime sweep to log a WARNING ("... subcommand not registered;
 * skipping"). We prefer to surface that regression at build time.
 *
 * <p>The test uses the same {@link FakeNode} pattern as {@link
 * TestCommandsCmdAuditTest} so no {@link io.github.dailystruggle.rtp.common.RTP}
 * static state or Bukkit runtime has to be initialised.
 */
class TestFullCmdTest {

  @Test
  @DisplayName("SHIPPED_SUBCOMMAND_NAMES has no duplicates and no overlap with excluded set")
  void shippedListIsWellFormed() {
    Set<String> shipped = new HashSet<>();
    for (String n : TestFullCmd.SHIPPED_SUBCOMMAND_NAMES) {
      assertTrue(shipped.add(n.toUpperCase()), () -> "duplicate in shipped list: " + n);
    }
    for (String excluded : TestFullCmd.DELIBERATELY_EXCLUDED_SUBCOMMANDS) {
      assertFalse(
          shipped.contains(excluded.toUpperCase()),
          () -> "name " + excluded + " is BOTH shipped and excluded; pick one");
    }
  }

  @Test
  @DisplayName("auditShippedCoverage on a parent with every shipped name reports zero issues")
  void cleanCoverageHasNoIssues() {
    FakeNode parent = new FakeNode(null, "test", "rtp.test");
    for (String name : TestFullCmd.SHIPPED_SUBCOMMAND_NAMES) {
      parent.addChild(new FakeNode(parent, name, "rtp.test"));
    }
    // Add the deliberately-excluded siblings too; these must not be flagged
    // as unexpected.
    for (String name : TestFullCmd.DELIBERATELY_EXCLUDED_SUBCOMMANDS) {
      parent.addChild(new FakeNode(parent, name, "rtp.test"));
    }

    TestFullCmd.CoverageReport r = TestFullCmd.auditShippedCoverage(parent);

    assertEquals(0, r.totalIssues(), () -> dump(r));
    assertTrue(r.missingFromParent.isEmpty(), () -> dump(r));
    assertTrue(r.unexpectedOnParent.isEmpty(), () -> dump(r));
  }

  @Test
  @DisplayName("missing shipped subcommand is reported in missingFromParent")
  void missingShippedIsFlagged() {
    FakeNode parent = new FakeNode(null, "test", "rtp.test");
    // Register everything EXCEPT `stress`.
    for (String name : TestFullCmd.SHIPPED_SUBCOMMAND_NAMES) {
      if (name.equals("stress")) continue;
      parent.addChild(new FakeNode(parent, name, "rtp.test"));
    }

    TestFullCmd.CoverageReport r = TestFullCmd.auditShippedCoverage(parent);

    assertEquals(1, r.missingFromParent.size(), () -> dump(r));
    assertEquals("stress", r.missingFromParent.get(0));
    assertTrue(r.unexpectedOnParent.isEmpty(), () -> dump(r));
  }

  @Test
  @DisplayName("sibling not in shipped-or-excluded is reported in unexpectedOnParent")
  void unexpectedSiblingIsFlagged() {
    FakeNode parent = new FakeNode(null, "test", "rtp.test");
    for (String name : TestFullCmd.SHIPPED_SUBCOMMAND_NAMES) {
      parent.addChild(new FakeNode(parent, name, "rtp.test"));
    }
    // A subcommand added to TestCmd without being wired into the sweep.
    parent.addChild(new FakeNode(parent, "newly-invented", "rtp.test"));

    TestFullCmd.CoverageReport r = TestFullCmd.auditShippedCoverage(parent);

    assertTrue(r.missingFromParent.isEmpty(), () -> dump(r));
    assertEquals(1, r.unexpectedOnParent.size(), () -> dump(r));
    assertTrue(
        r.unexpectedOnParent.get(0).toLowerCase().contains("newly-invented"),
        () -> dump(r));
  }

  @Test
  @DisplayName("null parent yields every shipped name as missing (defensive fallback)")
  void nullParentIsAllMissing() {
    TestFullCmd.CoverageReport r = TestFullCmd.auditShippedCoverage(null);
    assertEquals(
        TestFullCmd.SHIPPED_SUBCOMMAND_NAMES.size(),
        r.missingFromParent.size(),
        () -> dump(r));
    assertTrue(r.unexpectedOnParent.isEmpty(), () -> dump(r));
  }

  @Test
  @DisplayName("null child entry under an unknown key is flagged without NPE")
  void nullChildUnderUnknownKeyIsFlagged() {
    FakeNode parent = new FakeNode(null, "test", "rtp.test");
    for (String name : TestFullCmd.SHIPPED_SUBCOMMAND_NAMES) {
      parent.addChild(new FakeNode(parent, name, "rtp.test"));
    }
    parent.commandLookup.put("GHOST", null);

    TestFullCmd.CoverageReport r = TestFullCmd.auditShippedCoverage(parent);

    assertEquals(1, r.unexpectedOnParent.size(), () -> dump(r));
    String finding = r.unexpectedOnParent.get(0);
    assertTrue(finding.contains("GHOST"), () -> finding);
    assertTrue(finding.contains("null child"), () -> finding);
  }

  // -----------------------------------------------------------------
  // Parity test against the real TestCmd registration: any name that
  // TestCmd.addSubCommand registers must be either dispatched from
  // TestFullCmd or deliberately excluded. This is the S-004 continuity
  // contract, asserted at test time.
  // -----------------------------------------------------------------

  @Test
  @DisplayName("every subcommand registered on BukkitTestCmd is either shipped or deliberately excluded")
  void testCmdRegistrationMatchesShippedOrExcluded() {
    // Must use BukkitTestCmd (the Bukkit-flavoured subclass), not the bare
    // TestCmd - TestFullCmd / `stress` / `chunk-probe-perf` / `async-reply`
    // are Bukkit-only and registered by BukkitTestCmd.registerPlatformSpecificChildren().
    // See TestCmd Javadoc and TestCmdPlatformSplitTest for the cross-platform
    // constructor split rationale.
    BukkitTestCmd real = new BukkitTestCmd(null);

    TestFullCmd.CoverageReport r = TestFullCmd.auditShippedCoverage(real);

    // Every shipped name MUST be registered on BukkitTestCmd; if not,
    // the sweep would log a runtime WARNING per S-004 on every invocation.
    assertTrue(
        r.missingFromParent.isEmpty(),
        () -> "shipped names missing from BukkitTestCmd registration: " + r.missingFromParent);

    // No stragglers: if BukkitTestCmd registered a name that is neither
    // shipped nor excluded, the continuity contract is broken.
    assertTrue(
        r.unexpectedOnParent.isEmpty(),
        () ->
            "BukkitTestCmd registered subcommands not covered by SHIPPED_SUBCOMMAND_NAMES "
                + "or DELIBERATELY_EXCLUDED_SUBCOMMANDS: "
                + r.unexpectedOnParent
                + " — add them to the sweep (or to the excluded list) per "
                + "RUNTIME_TEST_SUITE_PLAN.md §3.2");
  }

  @Test
  @DisplayName("TestFullCmd.findRoot-like: constructor does not throw even with a null parent")
  void constructsWithNullParent() {
    TestFullCmd cmd = new TestFullCmd(null);
    assertNotNull(cmd);
    assertEquals("full", cmd.name());
    assertEquals("rtp.test.full", cmd.permission());
    assertSame(null, cmd.parent());
  }

  // --------------------------------------------------------------------------
  // Minimal TreeCommand fake - same pattern as TestCommandsCmdAuditTest so
  // that reviewers can cross-reference the two.
  // --------------------------------------------------------------------------

  private static final class FakeNode implements TreeCommand {
    private final CommandsAPICommand parent;
    private final String name;
    private final String permission;
    final Map<String, CommandsAPICommand> commandLookup = new HashMap<>();
    final Map<String, CommandParameter> parameterLookup = new HashMap<>();

    FakeNode(CommandsAPICommand parent, String name, String permission) {
      this.parent = parent;
      this.name = name;
      this.permission = permission;
    }

    void addChild(FakeNode child) {
      commandLookup.put(child.name.toUpperCase(), child);
    }

    @Override public String name() { return name; }
    @Override public String permission() { return permission; }
    @Override public String description() { return ""; }
    @Override public CommandsAPICommand parent() { return parent; }
    @Override public void msgBadParameter(UUID c, String p, String v) {}
    @Override public long avgTime() { return 0L; }

    @Override public Map<String, CommandParameter> getParameterLookup() { return parameterLookup; }
    @Override public Map<String, CommandsAPICommand> getCommandLookup() { return commandLookup; }

    @Override
    public void addParameter(String name, CommandParameter parameter) {
      parameterLookup.put(name.toUpperCase(), parameter);
    }

    @Override
    public void addSubCommand(CommandsAPICommand command) {
      commandLookup.put(command.name().toUpperCase(), command);
    }

    @Override
    public List<String> onTabComplete(
        UUID callerId, Predicate<String> permissionCheckMethod, String[] args, int i,
        Map<String, CommandParameter> tempParameters) {
      return new ArrayList<>();
    }

    @Override
    public CompletableFuture<Boolean> onCommand(
        UUID callerId, Predicate<String> permissionCheckMethod, Consumer<String> messageMethod,
        String[] args, int i, Map<String, CommandParameter> tempParameters) {
      return CompletableFuture.completedFuture(true);
    }

    @Override
    public boolean onCommand(
        UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
      return true;
    }

    @Override
    public List<String> help(UUID callerId, Predicate<String> permissionCheckMethod) {
      return new ArrayList<>();
    }
  }

  private static String dump(TestFullCmd.CoverageReport r) {
    Map<String, List<String>> m = new HashMap<>();
    m.put("missingFromParent", r.missingFromParent);
    m.put("unexpectedOnParent", r.unexpectedOnParent);
    return m.toString();
  }
}
