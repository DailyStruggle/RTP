package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestCommandsCmd}'s walker.
 *
 * <p>The walker is the safety-critical half of {@code rtp test commands}: if it
 * mis-reports a node as fine when it is not (or mis-flags a node that is), the
 * audit delivers false confidence to operators. Dispatch-level behaviour (does
 * a real Bukkit command actually send a message?) is live-server-only and not
 * covered here.
 *
 * <p>Traces REQ-RTP-S-004 via {@link TestCommandsCmd}, which is itself logged
 * in {@code docs/dev/TRACEABILITY.md}.
 */
class TestCommandsCmdAuditTest {

  @Test
  @DisplayName("clean tree with non-blank name + permission reports zero issues")
  void cleanTreeHasNoIssues() {
    FakeNode root = new FakeNode(null, "root", "rtp");
    FakeNode leaf = new FakeNode(root, "leaf", "rtp.leaf");
    root.addChild(leaf);

    TestCommandsCmd.AuditReport r = TestCommandsCmd.auditForTest(root);

    assertEquals(2, r.auditedCount);
    assertEquals(0, r.totalIssues(), () -> "unexpected findings: " + dump(r));
  }

  @Test
  @DisplayName("blank permission is flagged as missing-perm")
  void blankPermissionFlagged() {
    FakeNode root = new FakeNode(null, "root", "rtp");
    FakeNode bad = new FakeNode(root, "bad", "   "); // whitespace-only
    root.addChild(bad);

    TestCommandsCmd.AuditReport r = TestCommandsCmd.auditForTest(root);

    assertEquals(1, r.missingPermission.size(), () -> dump(r));
    assertTrue(r.missingPermission.get(0).endsWith("bad"));
  }

  @Test
  @DisplayName("lookup key not matching child name is flagged as key-name-mismatch")
  void keyNameMismatchFlagged() {
    FakeNode root = new FakeNode(null, "root", "rtp");
    FakeNode child = new FakeNode(root, "scan", "rtp.scan");
    // Deliberately register under a non-matching key to simulate the bug class.
    root.commandLookup.put("SKAN", child);

    TestCommandsCmd.AuditReport r = TestCommandsCmd.auditForTest(root);

    assertEquals(1, r.keyNameMismatch.size(), () -> dump(r));
    String finding = r.keyNameMismatch.get(0);
    assertTrue(finding.contains("key=SKAN"));
    assertTrue(finding.contains("name=scan"));
  }

  @Test
  @DisplayName("null child under a lookup key is flagged without crashing")
  void nullChildFlagged() {
    FakeNode root = new FakeNode(null, "root", "rtp");
    root.commandLookup.put("GHOST", null);

    TestCommandsCmd.AuditReport r = TestCommandsCmd.auditForTest(root);

    assertEquals(1, r.nullChildren.size(), () -> dump(r));
    assertTrue(r.nullChildren.get(0).contains("key=GHOST"));
  }

  @Test
  @DisplayName("walker stops descending into the 'test' subtree (prevents self-dispatch)")
  void skipsTestSubtree() {
    FakeNode root = new FakeNode(null, "root", "rtp");
    FakeNode testSubtree = new FakeNode(root, "test", "rtp.test");
    FakeNode stress = new FakeNode(testSubtree, "stress", "rtp.test");
    testSubtree.addChild(stress);
    root.addChild(testSubtree);

    TestCommandsCmd.AuditReport r = TestCommandsCmd.auditForTest(root);

    // root + test were visited; stress MUST NOT have been descended into.
    assertEquals(2, r.auditedCount);
    assertEquals(0, r.totalIssues());
  }

  @Test
  @DisplayName("findRoot climbs parent() links to the parent-null root")
  void findRootClimbs() {
    FakeNode root = new FakeNode(null, "root", "rtp");
    FakeNode mid = new FakeNode(root, "mid", "rtp.mid");
    FakeNode leaf = new FakeNode(mid, "leaf", "rtp.leaf");

    assertSame(root, TestCommandsCmd.findRoot(leaf));
    assertSame(root, TestCommandsCmd.findRoot(mid));
    assertSame(root, TestCommandsCmd.findRoot(root));
  }

  @Test
  @DisplayName("cycles in the command tree do not cause infinite recursion")
  void cycleTerminates() {
    FakeNode root = new FakeNode(null, "root", "rtp");
    FakeNode a = new FakeNode(root, "a", "rtp.a");
    root.addChild(a);
    // Create a cycle a -> root (identity-based visited set must terminate this).
    a.commandLookup.put("ROOT", root);

    TestCommandsCmd.AuditReport r = TestCommandsCmd.auditForTest(root);

    assertNotNull(r);
    // root visited once, a visited once - the second visit to root is
    // short-circuited by the visited set.
    assertEquals(2, r.auditedCount);
    // The `ROOT` key under `a` legitimately mismatches its child's name `root`
    // only by case - the audit's equalsIgnoreCase check means this is NOT
    // flagged as a mismatch. Assert that explicitly so the test documents it.
    assertFalse(
        r.keyNameMismatch.stream().anyMatch(s -> s.contains("key=ROOT")),
        () -> "ROOT->root case-only difference should not be flagged: " + dump(r));
  }

  // --------------------------------------------------------------------------
  // Fake command node: a minimal TreeCommand that does not depend on any
  // Bukkit/runtime wiring. Only the methods the walker actually calls are
  // meaningfully implemented; everything else throws or returns empty.
  // --------------------------------------------------------------------------

  private static final class FakeNode implements TreeCommand {
    private final CommandsAPICommand parent;
    private final String name;
    private final String permission;
    // HashMap rather than ConcurrentHashMap so tests can inject a null value
    // under a key (to exercise the walker's null-child branch). Production uses
    // ConcurrentHashMap, but the walker treats any Map<String, …> identically.
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
      return java.util.Collections.emptyList();
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
      return java.util.Collections.emptyList();
    }
  }

  private static String dump(TestCommandsCmd.AuditReport r) {
    Map<String, List<String>> m = new HashMap<>();
    m.put("missingName", r.missingName);
    m.put("missingPermission", r.missingPermission);
    m.put("keyNameMismatch", r.keyNameMismatch);
    m.put("nullChildren", r.nullChildren);
    m.put("traversalErrors", r.traversalErrors);
    return m.toString();
  }
}
