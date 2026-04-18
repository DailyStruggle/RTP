package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test full} (alias: {@code rtp test all}) &mdash; runs every
 * currently-shipped {@code rtp test *} subcommand in sequence with
 * conservative defaults, emitting a consolidated start/end log line.
 *
 * <p>This class is the <b>intent-of-continuity anchor</b> documented in
 * {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md &sect;3.2}: every time a new
 * subcommand is promoted from <b>Roadmap</b> to <b>Shipped</b>, it must be
 * added to the {@link #runShippedSubcommands(UUID)} sequence in the same
 * change. Today the shipped set is {@link TestSchedulerCmd} and
 * {@link TestStressCmd}; {@link TestCancelCmd} is deliberately excluded
 * (invoking cancel inside the sweep would abort the sweep itself), and
 * {@link TestReloadSafetyCmd} is excluded because it requires
 * {@code rtp.test.admin} and intentionally courts failures that would
 * swamp a default operator run.
 *
 * <p>Permission is {@code rtp.test.full} rather than {@code rtp.test} so
 * operators can grant per-subcommand access without granting the aggregate
 * sweep, which is the most scheduler-expensive of the suite.
 *
 * <p>Safety compliance is inherited: every subcommand we invoke is itself
 * safety-audited against REQ-RTP-S-001/S-002/S-004/S-005, so this umbrella
 * does not need to re-verify them. It MUST, however, continue to log at
 * {@link Level#WARNING} on dispatch failure per S-004.
 */
public class TestFullCmd extends BaseRTPCmdImpl {

  /** Conservative iteration count for embedded {@code stress} runs. */
  static final int FULL_ITERATIONS = 3;

  /**
   * The canonical ordered list of subcommand names this umbrella sweep
   * invokes. Mirrors the sequence in {@link #runShippedSubcommands(UUID)}
   * and &sect;4 of {@code RUNTIME_TEST_SUITE_PLAN.md}. Exposed package-private
   * so unit tests can assert parity with {@link TestCmd}'s registration set
   * without having to initialise {@link RTP#serverAccessor}.
   *
   * <p>Deliberately excluded: {@code cancel} (would abort the sweep itself)
   * and {@code reload-safety} (admin-only, intentionally noisy). See the
   * tail comment in {@link #runShippedSubcommands(UUID)}.
   */
  static final List<String> SHIPPED_SUBCOMMAND_NAMES =
      Collections.unmodifiableList(
          Arrays.asList(
              "commands",
              "api-compat",
              "chunk-ticket",
              "disconnect-midflight",
              "scheduler",
              "folia-ownership",
              "commands-live",
              "economy-isolation",
              "queue-starvation",
              "async-reply",
              "stress"));

  /**
   * Subcommand names that are registered on the {@code test} parent but
   * deliberately NOT dispatched from the umbrella sweep. See the tail
   * comment in {@link #runShippedSubcommands(UUID)} for the rationale.
   */
  static final List<String> DELIBERATELY_EXCLUDED_SUBCOMMANDS =
      Collections.unmodifiableList(Arrays.asList("cancel", "reload-safety", "full", "all"));

  public TestFullCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "full";
  }

  @Override
  public String permission() {
    return "rtp.test.full";
  }

  @Override
  public String description() {
    return "runs every currently-shipped rtp test subcommand in sequence (alias: all)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    runShippedSubcommands(callerId);
    return true;
  }

  /**
   * Invokes every subcommand currently listed in RUNTIME_TEST_SUITE_PLAN.md
   * &sect;3 <b>Shipped</b>, in the order defined in &sect;4. When a new
   * subcommand is promoted, append its invocation here and update the plan.
   */
  private void runShippedSubcommands(UUID callerId) {
    String header = "[RTP test/full] begin (shipped subcommands only)";
    RTP.serverAccessor.sendMessage(callerId, header);
    RTP.log(Level.INFO, header);

    // --- commands audit (read-only, cheapest, runs first) ------------
    dispatchNoArg(callerId, "commands");

    // --- api-compat (read-only reflective probe; safe everywhere) ----
    dispatchNoArg(callerId, "api-compat");

    // --- chunk-ticket (MemoryTracker positive-path; read-only, safe) --
    dispatchNoArg(callerId, "chunk-ticket");

    // --- disconnect-midflight (synthetic UUID; never touches live players) --
    dispatchNoArg(callerId, "disconnect-midflight");

    // --- scheduler (passive, cheap, runs before stress) ---------------
    dispatchNoArg(callerId, "scheduler");

    // --- folia-ownership (Entity-Scheduler region handoff probe) -----
    // Safe on all platforms: reports UNSUPPORTED on Spigot/Paper when
    // Bukkit.isOwnedByCurrentRegion is absent, rather than failing.
    dispatchNoArg(callerId, "folia-ownership");

    // --- commands-live (malformed-input dispatch audit; aborts with a
    // loud WARNING when Bukkit is unavailable in a headless harness).
    dispatchNoArg(callerId, "commands-live");

    // --- economy-isolation (synthetic Vault debit; S-006-guarded and
    // runs entirely on the async tier, so no player context required).
    dispatchNoArg(callerId, "economy-isolation");

    // --- queue-starvation (ADR-006 refill pulse; async sampling only) -
    // Uses a small sample count so the sweep stays conservative. The job
    // resolves the region from the caller's current region when the
    // `region` arg is omitted; on console (serverId) we skip rather than
    // dispatch an unresolvable call.
    CommandsAPICommand queueStarvation = findChild("queue-starvation");
    if (queueStarvation == null) {
      String msg = "[RTP test/full] queue-starvation subcommand not registered; skipping";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
    } else if (callerId.equals(RTPAPI.serverId)) {
      String msg =
          "[RTP test/full] queue-starvation skipped: console caller has no resolvable region";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.INFO, msg);
    } else {
      Map<String, List<String>> qsArgs = new HashMap<>();
      qsArgs.put(
          "samples",
          new ArrayList<>(Collections.singletonList(String.valueOf(FULL_ITERATIONS))));
      try {
        queueStarvation.onCommand(callerId, qsArgs, null);
      } catch (Throwable t) {
        RTP.log(
            Level.WARNING,
            "[RTP test/full] queue-starvation dispatch failed: " + t.getMessage(),
            t);
      }
    }

    // --- async-reply (end-to-end pipeline probe; requires a live player
    // target, so we gate it exactly like `stress`: skip on console, pass
    // the caller's name otherwise). On console callers the subcommand's
    // own S-004 WARNING path would fire; skipping keeps the sweep quiet.
    CommandsAPICommand asyncReply = findChild("async-reply");
    if (asyncReply == null) {
      String msg = "[RTP test/full] async-reply subcommand not registered; skipping";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
    } else if (callerId.equals(RTPAPI.serverId)) {
      String msg =
          "[RTP test/full] async-reply skipped: console caller has no resolvable player";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.INFO, msg);
    } else {
      Map<String, List<String>> arArgs = new HashMap<>();
      arArgs.put(
          "player", new ArrayList<>(Collections.singletonList(resolveName(callerId))));
      try {
        asyncReply.onCommand(callerId, arArgs, null);
      } catch (Throwable t) {
        RTP.log(
            Level.WARNING,
            "[RTP test/full] async-reply dispatch failed: " + t.getMessage(),
            t);
      }
    }

    // --- stress -------------------------------------------------------
    // Defaults: caller is the sole target (operators on console with no
    // player argument produce a loud S-004 warning from TestStressCmd,
    // which is the desired behaviour; we do not substitute a silent no-op).
    CommandsAPICommand stress = findChild("stress");
    if (stress == null) {
      String msg = "[RTP test/full] stress subcommand not registered; skipping";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
    } else {
      Map<String, List<String>> stressArgs = new HashMap<>();
      if (!callerId.equals(RTPAPI.serverId)) {
        // Resolve the caller's display name via the server accessor; fall
        // back to UUID string if the name is unknown.
        String name = resolveName(callerId);
        stressArgs.put("player", new ArrayList<>(Collections.singletonList(name)));
      }
      stressArgs.put(
          "iterations", new ArrayList<>(Collections.singletonList(String.valueOf(FULL_ITERATIONS))));
      try {
        stress.onCommand(callerId, stressArgs, null);
      } catch (Throwable t) {
        RTP.log(Level.WARNING, "[RTP test/full] stress dispatch failed: " + t.getMessage(), t);
      }
    }

    // --- future shipped subcommands go here, in the order documented in
    // RUNTIME_TEST_SUITE_PLAN.md §4. Each addition MUST also move the
    // subcommand's row from Roadmap to Shipped in the plan document.
    //
    // Deliberately NOT dispatched from `full`:
    //   * `cancel` — would abort this very sweep.
    //   * `reload-safety` — admin-only, intentionally courts failures.
    //   Operators who want those must invoke them directly.

    String footer = "[RTP test/full] end";
    RTP.serverAccessor.sendMessage(callerId, footer);
    RTP.log(Level.INFO, footer);
  }

  /**
   * Dispatches a shipped subcommand by name with no arguments, logging at
   * WARNING (per S-004) if the subcommand is missing or throws.
   */
  private void dispatchNoArg(UUID callerId, String subName) {
    CommandsAPICommand sub = findChild(subName);
    if (sub == null) {
      String msg = "[RTP test/full] " + subName + " subcommand not registered; skipping";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      return;
    }
    try {
      sub.onCommand(callerId, new HashMap<>(), null);
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP test/full] " + subName + " dispatch failed: " + t.getMessage(),
          t);
    }
  }

  /** Finds a sibling subcommand by name on our parent (the {@link TestCmd}). */
  private CommandsAPICommand findChild(String subName) {
    CommandsAPICommand par = parent();
    if (!(par instanceof TreeCommand)) return null;
    Map<String, CommandsAPICommand> lookup = ((TreeCommand) par).getCommandLookup();
    if (lookup == null) return null;
    // TreeCommand.addSubCommand stores keys upper-cased (see TreeCommand#27).
    return lookup.get(subName.toUpperCase());
  }

  /**
   * Resolves a caller UUID to a player name for delegation. Returns the
   * UUID string as a safe fallback so the stress command's own S-004
   * unknown-player path fires (loudly) if the lookup fails.
   */
  private String resolveName(UUID callerId) {
    try {
      var player = RTP.serverAccessor.getPlayer(callerId);
      if (player != null) return player.name();
    } catch (Throwable ignored) {
      // fall through
    }
    return callerId.toString();
  }

  /**
   * Pure, side-effect-free audit of a {@link TreeCommand} parent's subcommand
   * lookup against {@link #SHIPPED_SUBCOMMAND_NAMES}. Designed for unit tests:
   * touches no static {@link RTP} state, performs no dispatch, and does not
   * rely on Bukkit being initialised.
   *
   * <p>The returned report lists:
   * <ul>
   *   <li>{@code missingFromParent} &mdash; names in the shipped list that are
   *       not registered on the parent (indicates a drop between
   *       {@link TestCmd} and this sweep &mdash; S-004 would fire a WARNING
   *       at runtime).</li>
   *   <li>{@code unexpectedOnParent} &mdash; names registered on the parent
   *       that are neither in the shipped list nor in
   *       {@link #DELIBERATELY_EXCLUDED_SUBCOMMANDS} (indicates a sibling
   *       subcommand was added to {@link TestCmd} without being wired into
   *       this sweep &mdash; the plan doc's continuity contract was broken).</li>
   * </ul>
   */
  static CoverageReport auditShippedCoverage(@Nullable TreeCommand parent) {
    CoverageReport r = new CoverageReport();
    if (parent == null) {
      r.missingFromParent.addAll(SHIPPED_SUBCOMMAND_NAMES);
      return r;
    }
    Map<String, CommandsAPICommand> lookup = parent.getCommandLookup();
    if (lookup == null) {
      r.missingFromParent.addAll(SHIPPED_SUBCOMMAND_NAMES);
      return r;
    }

    for (String name : SHIPPED_SUBCOMMAND_NAMES) {
      if (!lookup.containsKey(name.toUpperCase())) {
        r.missingFromParent.add(name);
      }
    }

    // Build the set of names we know about (shipped + deliberately excluded)
    // so we can spot stragglers.
    java.util.Set<String> knownUpper = new java.util.HashSet<>();
    for (String s : SHIPPED_SUBCOMMAND_NAMES) knownUpper.add(s.toUpperCase());
    for (String s : DELIBERATELY_EXCLUDED_SUBCOMMANDS) knownUpper.add(s.toUpperCase());

    for (Map.Entry<String, CommandsAPICommand> e : lookup.entrySet()) {
      String key = e.getKey();
      if (key == null) continue;
      if (!knownUpper.contains(key.toUpperCase())) {
        CommandsAPICommand child = e.getValue();
        r.unexpectedOnParent.add(
            key + (child == null ? " (null child)" : " -> " + child.name()));
      }
    }
    return r;
  }

  /** Result of {@link #auditShippedCoverage(TreeCommand)}. */
  static final class CoverageReport {
    final List<String> missingFromParent = new ArrayList<>();
    final List<String> unexpectedOnParent = new ArrayList<>();

    int totalIssues() {
      return missingFromParent.size() + unexpectedOnParent.size();
    }
  }
}
