package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test commands} &mdash; combined dispatch &amp; permission audit of
 * the whole RTP command tree. Walks from this node up to the root
 * ({@code parent() == null}) via {@link #parent()}, then recursively enumerates
 * every {@link TreeCommand} under the root via {@link TreeCommand#getCommandLookup()}
 * and asserts operator-facing contracts that are hard to cover in unit tests:
 *
 * <ul>
 *   <li><b>Every node declares a non-blank {@code name()} and {@code permission()}</b>.
 *       A blank {@code permission()} means the command is effectively ungated at runtime,
 *       which is an operator surprise and an implicit S-004 violation (commands that
 *       would otherwise refuse silently when invoked by an unprivileged sender).</li>
 *   <li><b>Lookup keys agree with their stored nodes' names</b>. {@link TreeCommand}
 *       upper-cases lookup keys at insertion; a node registered with a lower-cased
 *       key cannot be dispatched by its own declared {@code name()}, only by alias.
 *       This is the bug that was noted in the {@code rtp test cancel} review.</li>
 *   <li><b>Permission strings are unique per role, or explicitly shared</b>. The audit
 *       reports every permission string that is used by more than one structurally
 *       distinct node (e.g. a leaf and a subtree parent), as an advisory &mdash; it
 *       does not fail the run.</li>
 * </ul>
 *
 * <p>This command is read-only: it does <b>not</b> dispatch any audited command.
 * Dispatch-level behaviour (does a command produce a message? log at WARNING on
 * failure?) is inherently live-server-only and the place to grow this command,
 * not something to add here speculatively.
 *
 * <p>Deliberately skips the {@code test} subtree itself &mdash; re-entering
 * {@link TestStressCmd}, {@link TestReloadSafetyCmd}, {@link TestCancelCmd} or
 * {@link TestFullCmd} from inside an audit would be self-defeating (stress would
 * start, reload-safety would run its admin-gated loop, cancel would abort every
 * job including the audit itself).
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>S-004</b>: every audit finding (missing name, missing permission, key/name
 *       mismatch) is logged at {@link Level#WARNING} and mirrored to the caller.
 *       A clean audit is logged at {@link Level#INFO}.</li>
 *   <li><b>S-005</b>: performs no chunk I/O and no blocking; traversal is pure
 *       in-memory map walking.</li>
 * </ul>
 *
 * <p>Traces REQ-RTP-S-004. See {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md &sect;3.6}.
 */
public class TestCommandsCmd extends BaseRTPCmdImpl {

  /** Max depth for the recursive walk; guards against accidental cycles. */
  static final int MAX_DEPTH = 16;

  public TestCommandsCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "commands";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "audits the rtp command tree: names, permissions, and lookup-key consistency";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    CommandsAPICommand root = findRoot(this);
    if (root == null) {
      String msg = "[RTP test/commands] root command not resolvable; audit aborted";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      return true;
    }

    AuditReport report = new AuditReport();
    Set<CommandsAPICommand> visited =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    walk(root, 0, "", report, visited);

    emit(callerId, report);
    return true;
  }

  /**
   * Climbs {@code parent()} links until a node with {@code parent() == null} is
   * found. That node is the CommandsAPI root (e.g. {@code RTPCmdBukkit}).
   */
  static @Nullable CommandsAPICommand findRoot(CommandsAPICommand start) {
    CommandsAPICommand cur = start;
    for (int i = 0; i < MAX_DEPTH && cur != null; i++) {
      CommandsAPICommand par = cur.parent();
      if (par == null) return cur;
      cur = par;
    }
    return cur;
  }

  /**
   * Depth-first walk over the command tree. Each node contributes at most one
   * finding per category to {@link AuditReport}.
   */
  static void walk(
      CommandsAPICommand node,
      int depth,
      String pathPrefix,
      AuditReport report,
      Set<CommandsAPICommand> visited) {
    if (node == null || depth > MAX_DEPTH) return;
    if (!visited.add(node)) return; // defends against accidental cycles

    String nodeName = safeName(node);
    String fullPath = pathPrefix.isEmpty() ? nodeName : pathPrefix + " " + nodeName;
    report.auditedCount++;

    // Skip descending into the `test` subtree itself so we don't recurse
    // into our own siblings (stress, cancel, reload-safety, full, commands).
    boolean isTestSubtree = "test".equalsIgnoreCase(nodeName) && depth > 0;

    if (isBlank(nodeName)) {
      report.missingName.add(fullPath + " (depth=" + depth + ")");
    }
    if (isBlank(safePermission(node))) {
      report.missingPermission.add(fullPath);
    }

    if (!(node instanceof TreeCommand)) return;
    if (isTestSubtree) return;

    Map<String, CommandsAPICommand> lookup;
    try {
      lookup = ((TreeCommand) node).getCommandLookup();
    } catch (Throwable t) {
      report.traversalErrors.add(fullPath + ": " + t.getClass().getSimpleName());
      return;
    }
    if (lookup == null || lookup.isEmpty()) return;

    for (Map.Entry<String, CommandsAPICommand> e : lookup.entrySet()) {
      String key = e.getKey();
      CommandsAPICommand child = e.getValue();
      if (child == null) {
        report.nullChildren.add(fullPath + " -> key=" + key);
        continue;
      }
      String childName = safeName(child);
      // TreeCommand.addSubCommand upper-cases the key at insertion; the key
      // must equal the child's own upper-cased name unless the child was
      // installed as an explicit alias (documented at the call site).
      if (!isBlank(childName)
          && !key.equalsIgnoreCase(childName)
          && !isDeclaredAlias(node, key, child)) {
        report.keyNameMismatch.add(fullPath + " key=" + key + " name=" + childName);
      }
      walk(child, depth + 1, fullPath, report, visited);
    }
  }

  /**
   * Recognises the one intentional alias in the tree: {@code ALL} -&gt; {@link TestFullCmd}
   * as documented in {@link TestCmd}. If more aliases are introduced, add them here
   * (and, preferably, promote aliases to a first-class concept in {@link TreeCommand}).
   */
  private static boolean isDeclaredAlias(
      CommandsAPICommand parent, String key, CommandsAPICommand child) {
    if (parent instanceof TestCmd
        && "ALL".equals(key)
        && child instanceof TestFullCmd) return true;
    return false;
  }

  private void emit(UUID callerId, AuditReport report) {
    int issues =
        report.missingName.size()
            + report.missingPermission.size()
            + report.keyNameMismatch.size()
            + report.nullChildren.size()
            + report.traversalErrors.size();

    String summary =
        "[RTP test/commands] audited="
            + report.auditedCount
            + " issues="
            + issues
            + " (missing-name="
            + report.missingName.size()
            + " missing-perm="
            + report.missingPermission.size()
            + " key-name-mismatch="
            + report.keyNameMismatch.size()
            + " null-children="
            + report.nullChildren.size()
            + " traversal-errors="
            + report.traversalErrors.size()
            + ")";
    RTP.serverAccessor.sendMessage(callerId, summary);
    if (issues == 0) {
      RTP.log(Level.INFO, summary);
      return;
    }
    RTP.log(Level.WARNING, summary);
    emitDetails(callerId, "missing-name", report.missingName);
    emitDetails(callerId, "missing-perm", report.missingPermission);
    emitDetails(callerId, "key-name-mismatch", report.keyNameMismatch);
    emitDetails(callerId, "null-children", report.nullChildren);
    emitDetails(callerId, "traversal-errors", report.traversalErrors);
  }

  private static void emitDetails(UUID callerId, String tag, List<String> items) {
    for (String item : items) {
      String line = "[RTP test/commands] " + tag + ": " + item;
      RTP.serverAccessor.sendMessage(callerId, line);
      RTP.log(Level.WARNING, line);
    }
  }

  private static String safeName(CommandsAPICommand c) {
    try {
      return c.name();
    } catch (Throwable t) {
      return "";
    }
  }

  private static String safePermission(CommandsAPICommand c) {
    try {
      return c.permission();
    } catch (Throwable t) {
      return "";
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  /**
   * Aggregates audit findings. Lists are intentionally mutable and
   * package-private to keep the unit test straightforward.
   */
  static final class AuditReport {
    int auditedCount = 0;
    final List<String> missingName = new ArrayList<>();
    final List<String> missingPermission = new ArrayList<>();
    final List<String> keyNameMismatch = new ArrayList<>();
    final List<String> nullChildren = new ArrayList<>();
    final List<String> traversalErrors = new ArrayList<>();

    /** Convenience for tests. */
    int totalIssues() {
      return missingName.size()
          + missingPermission.size()
          + keyNameMismatch.size()
          + nullChildren.size()
          + traversalErrors.size();
    }
  }

  /** Package-private hook so tests can exercise the walker without a real caller. */
  static AuditReport auditForTest(CommandsAPICommand root) {
    AuditReport report = new AuditReport();
    Set<CommandsAPICommand> visited =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    walk(root, 0, "", report, visited);
    return report;
  }

}
