package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCode;
import io.github.dailystruggle.bit.BitComponent;
import io.github.dailystruggle.bit.BitReport;
import io.github.dailystruggle.bit.BitResult;
import io.github.dailystruggle.bit.BitSideEffect;
import io.github.dailystruggle.bit.BitTier;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter;
import io.github.dailystruggle.rtp.common.bit.RtpBit;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.base.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.test.ProbeBitReport;
import io.github.dailystruggle.rtp.common.runtime.RTPRuntime;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test commands} &mdash; read-only audit of the whole RTP command tree.
 * Walks up to the root, then enumerates every {@link TreeCommand} via
 * {@link TreeCommand#getCommandLookup()} and asserts: each node has a non-blank
 * {@code name()} and {@code permission()}; lookup keys match their stored nodes'
 * names ({@code TreeCommand} upper-cases keys at insertion, so a lower-cased
 * registration is undispatchable by its own name); permission strings reused by
 * structurally distinct nodes are reported as advisory. Skips the {@code test}
 * subtree itself to avoid self-cancel/self-reload loops. Traces REQ-RTP-S-004
 * and REQ-API-F-009.
 */
public class TestCommandsCmd extends BaseRTPCmdImpl {

  public static final BitCode MISSING_NAME = registerCode(3301, "TEST_COMMANDS_MISSING_NAME");
  public static final BitCode MISSING_PERMISSION = registerCode(3302, "TEST_COMMANDS_MISSING_PERMISSION");
  public static final BitCode KEY_NAME_MISMATCH = registerCode(3303, "TEST_COMMANDS_KEY_NAME_MISMATCH");
  public static final BitCode NULL_CHILD = registerCode(3304, "TEST_COMMANDS_NULL_CHILD");
  public static final BitCode TRAVERSAL_ERROR = registerCode(3305, "TEST_COMMANDS_TRAVERSAL_ERROR");
  public static final BitCode NO_TREE = registerCode(3306, "TEST_COMMANDS_NO_TREE");
  public static final BitComponent COMPONENT = registerComponent();
  private static volatile CommandsAPICommand treeAnchor;

  /** Max depth for the recursive walk; guards against accidental cycles. */
  static final int MAX_DEPTH = 16;

  public TestCommandsCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
    treeAnchor = findRoot(parent == null ? this : parent);
    addParameter("json", new BooleanParameter("rtp.test", "emit the BIT report as JSON", (uuid, value) -> true));
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

  private static final java.util.concurrent.atomic.AtomicBoolean isProcessing = new java.util.concurrent.atomic.AtomicBoolean(false);

  @Override
  @SuppressWarnings("java:S3516") // Method returns boolean per CommandsAPICommand contract; false on guard failure, true on audit success
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (isProcessing.get()) return false;
    isProcessing.set(true);
    try {
      CommandsAPICommand root = findRoot(this);
      AuditReport report = new AuditReport();
      report.rootAvailable = root != null;
      Set<CommandsAPICommand> visited =
          java.util.Collections.newSetFromMap(new IdentityHashMap<>());
      walk(root, 0, "", report, visited);

      BitResult bitResult =
          RtpBit.RUNNER.run(COMPONENT.withCheck(() -> toBitReport(report)), Runnable::run);
      emit(callerId, report);
      ProbeBitReport.emit(callerId, parameterValues, COMPONENT, bitResult);
      return true;
    } finally {
      isProcessing.set(false);
    }
  }

  private static BitCode registerCode(int id, String key) {
    return RtpBitCodes.TEST.register(
        id,
        key,
        java.lang.System.Logger.Level.ERROR,
        BitTier.IBIT,
        "test.commands",
        "",
        "Repair the reported command-tree node or registration key.");
  }

  private static BitComponent registerComponent() {
    BitComponent component =
        new BitComponent(
            "commands",
            "test.commands",
            BitTier.IBIT,
            BitSideEffect.SAFE,
            15_000,
            () -> toBitReport(auditForTest(treeAnchor)));
    return component;
  }

  static BitReport toBitReport(AuditReport probe) {
    ProbeBitReport.Builder report = ProbeBitReport.forProbe("commands");
    report.check(NO_TREE, probe.rootAvailable, "commands.root", "The RTP command root is unavailable.");
    for (String item : probe.missingName) {
      report.check(MISSING_NAME, false, item, "The command node has no non-blank name.");
    }
    for (String item : probe.missingPermission) {
      report.check(MISSING_PERMISSION, false, item, "The command node has no non-blank permission.");
    }
    for (String item : probe.keyNameMismatch) {
      report.check(KEY_NAME_MISMATCH, false, item, "The command lookup key does not match its node name.");
    }
    for (String item : probe.nullChildren) {
      report.check(NULL_CHILD, false, item, "The command lookup contains a null child.");
    }
    for (String item : probe.traversalErrors) {
      report.check(TRAVERSAL_ERROR, false, item, "The command tree could not be traversed safely.");
    }
    return report.build();
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
      // Dispatch upper-cases input, so lookup keys must be uppercase. Aliases
      // are valid only while the child's canonical name resolves to that node.
      if (!isBlank(childName)) {
        String canonicalKey = childName.toUpperCase(java.util.Locale.ROOT);
        boolean unreachableKey = key == null || !key.equals(key.toUpperCase(java.util.Locale.ROOT));
        boolean orphanAlias = key != null && !key.equals(canonicalKey) && lookup.get(canonicalKey) != child;
        if (unreachableKey || orphanAlias) {
          report.keyNameMismatch.add(fullPath + " key=" + key + " name=" + childName
              + " (lookup key is unreachable or is an alias without the node's own name)");
        }
      }
      walk(child, depth + 1, fullPath, report, visited);
    }
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
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTPRuntime.serverAccessor().sendMessage(callerId, summary);
    }
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
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTPRuntime.serverAccessor().sendMessage(callerId, line);
      }
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
    return s == null || s.isBlank();
  }

  /**
   * Aggregates audit findings. Lists are intentionally mutable and
   * package-private to keep the unit test straightforward.
   */
  static final class AuditReport {
    int auditedCount = 0;
    boolean rootAvailable = true;
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
    report.rootAvailable = root != null;
    Set<CommandsAPICommand> visited =
        java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    walk(root, 0, "", report, visited);
    return report;
  }

}
