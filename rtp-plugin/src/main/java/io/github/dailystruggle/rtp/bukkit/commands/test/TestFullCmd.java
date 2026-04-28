package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
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
              "anvil-prefilter",
              "biome-source",
              "async-chunk-load",
              "scheduler",
              "folia-ownership",
              "commands-live",
              "economy-isolation",
              "queue-starvation",
              "async-reply",
              "disconnect-job",
              "safety-verifier",
              "stress"));

  /**
   * Subcommand names that are registered on the {@code test} parent but
   * deliberately NOT dispatched from the umbrella sweep. See the tail
   * comment in {@link #runShippedSubcommands(UUID)} for the rationale.
   */
  static final List<String> DELIBERATELY_EXCLUDED_SUBCOMMANDS =
      Collections.unmodifiableList(
          Arrays.asList(
              "cancel",
              "reload-safety",
              // admin-only; mutates a live ConfigParser value and restores
              // it. Runs on-demand only so the default sweep stays read-only.
              "config-set",
              "full",
              "all",
              // Player-context diagnostic: needs the caller's live world + origin
              // chunk to seed the A/B timing; the umbrella sweep fires under
              // RTPAPI.serverId which has no location. Runs on-demand only.
              "chunk-probe-perf"));

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

  private static final java.util.concurrent.atomic.AtomicBoolean isProcessing = new java.util.concurrent.atomic.AtomicBoolean(false);

  /**
   * Per-step ceiling on how long the sweep will wait for async jobs
   * (stress/queue-starvation/disconnect-midflight/reload-safety) registered
   * in {@link ActiveTestJobs} for the caller to drain before dispatching the
   * next subcommand. Bounded so a stuck job cannot park the umbrella run
   * indefinitely; a breached timeout is logged at {@code WARNING} per
   * REQ-RTP-S-004 and the sweep continues.
   */
  static final long DRAIN_TIMEOUT_MILLIS =
      Long.getLong("rtp.test.full.drainTimeoutMillis", 60_000L);

  /** Polling cadence for the drain wait; kept small to keep shutdown responsive. */
  static final long DRAIN_POLL_MILLIS = 100L;

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (!isProcessing.compareAndSet(false, true)) return true;
    // The sweep polls ActiveTestJobs between subcommands; doing that on the
    // command thread (which on Bukkit is the main server thread) would block
    // ticks and violate REQ-RTP-S-005. Hop onto the async scheduler so the
    // caller returns immediately and the sweep runs sequentially off-tick.
    try {
      RTP.scheduler.runTaskAsynchronously(() -> {
        try {
          runShippedSubcommands(callerId);
        } catch (Throwable t) {
          RTP.log(
              Level.WARNING,
              "[RTP test/full] sweep aborted: " + t.getMessage(),
              t);
        } finally {
          isProcessing.set(false);
        }
      });
    } catch (Throwable t) {
      // If the async scheduler is unavailable (e.g. in headless tests),
      // fall back to running inline so we don't silently no-op (S-004).
      try {
        runShippedSubcommands(callerId);
      } finally {
        isProcessing.set(false);
      }
    }
    return true;
  }

  /**
   * Invokes every subcommand currently listed in RUNTIME_TEST_SUITE_PLAN.md
   * &sect;3 <b>Shipped</b>, in the order defined in &sect;4. When a new
   * subcommand is promoted, append its invocation here and update the plan.
   */
  private void runShippedSubcommands(UUID callerId) {
    String header = "[RTP test/full] begin (shipped subcommands only)";
    if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, header);
    }
    RTP.log(Level.INFO, header);

    FullAudit audit = new FullAudit();
    SendMessage.addInterceptor(audit);
    try {

    // --- commands audit (read-only, cheapest, runs first) ------------
    dispatchNoArgAndWait(callerId, "commands");

    // --- api-compat (read-only reflective probe; safe everywhere) ----
    dispatchNoArgAndWait(callerId, "api-compat");

    // --- chunk-ticket (MemoryTracker positive-path; read-only, safe) --
    dispatchNoArgAndWait(callerId, "chunk-ticket");

    // --- disconnect-midflight (synthetic UUID; never touches live players) --
    dispatchNoArgAndWait(callerId, "disconnect-midflight");

    // --- anvil-prefilter (read-only telemetry readout; ADR-016/017) ---
    // Just reads AnvilPrefilterMetrics atomics; safe on every platform.
    // On Paper/Folia the counters stay at zero because those platforms
    // structurally bypass the pre-filter in BukkitRTPWorld.getChunkAt.
    dispatchNoArgAndWait(callerId, "anvil-prefilter");

    // --- biome-source (read-only biome-tier attribution telemetry; ADR-016 §13.1)
    // Reads BiomeSourceMetrics atomics to verify post-load biome reads came from
    // the Anvil cache vs. the live World#getBiome fallthrough. Safe on all platforms.
    dispatchNoArgAndWait(callerId, "biome-source");

    // --- async-chunk-load (verifies one generated chunk loads off the
    // main thread; REQ-RTP-S-005). Skips gracefully when the server has
    // no RTPWorlds registered (headless harness). Safe on every platform.
    dispatchNoArgAndWait(callerId, "async-chunk-load");

    // --- scheduler (passive, cheap, runs before stress) ---------------
    dispatchNoArgAndWait(callerId, "scheduler");

    // --- folia-ownership (Entity-Scheduler region handoff probe) -----
    // Safe on all platforms, but only meaningful on Folia. We skip it
    // on Spigot/Paper per user request to keep the sweep focused.
    if (RTP.serverAccessor.getPlatform().equalsIgnoreCase("Folia")) {
      dispatchNoArgAndWait(callerId, "folia-ownership");
    }

    // --- commands-live (malformed-input dispatch audit; aborts with a
    // loud WARNING when Bukkit is unavailable in a headless harness).
    String liveWarning = "[RTP test/full] notice: the 'commands-live' phase intentionally "
        + "dispatches malformed inputs and will produce several WARNING logs "
        + "to verify compliance with REQ-RTP-S-004.";
    if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, liveWarning);
    }
    RTP.log(Level.INFO, liveWarning);
    dispatchNoArgAndWait(callerId, "commands-live");

    // --- economy-isolation (synthetic Vault debit; S-006-guarded and
    // runs entirely on the async tier, so no player context required).
    dispatchNoArgAndWait(callerId, "economy-isolation");

    // --- queue-starvation (ADR-006 refill pulse; async sampling only) -
    // Uses a small sample count so the sweep stays conservative. The job
    // resolves the region from the caller's current region when the
    // `region` arg is omitted; on console (serverId) we skip rather than
    // dispatch an unresolvable call.
    CommandsAPICommand queueStarvation = findChild("queue-starvation");
    if (queueStarvation == null) {
      String msg = "[RTP test/full] queue-starvation subcommand not registered; skipping";
      if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
    } else if (callerId.equals(RTPAPI.serverId)) {
      String msg =
          "[RTP test/full] queue-starvation skipped: console caller has no resolvable region";
      if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
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
      waitForCallerJobsToDrain(callerId, "queue-starvation");
    }

    // --- async-reply (end-to-end pipeline probe; requires a live player
    // target, so we gate it exactly like `stress`: skip on console, pass
    // the caller's name otherwise). On console callers the subcommand's
    // own S-004 WARNING path would fire; skipping keeps the sweep quiet.
    CommandsAPICommand asyncReply = findChild("async-reply");
    if (asyncReply == null) {
      String msg = "[RTP test/full] async-reply subcommand not registered; skipping";
      if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
    } else if (callerId.equals(RTPAPI.serverId)) {
      String msg =
          "[RTP test/full] async-reply skipped: console caller has no resolvable player";
      if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
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
      waitForCallerJobsToDrain(callerId, "async-reply");
    }

    // --- disconnect-job (REQ-RTP-S-002 async mid-flight leak canary) --
    // Synthetic UUID caller; no live-player dependency. Complements the
    // `disconnect-midflight` sync-path variant dispatched earlier.
    dispatchNoArgAndWait(callerId, "disconnect-job");

    // --- safety-verifier (REQ-RTP-S-001 block safety + REQ-RTP-S-003 --
    // claim verifiers, bounded by a strict timeout). Read-only; safe on
    // every platform.
    dispatchNoArgAndWait(callerId, "safety-verifier");

    // --- stress -------------------------------------------------------
    // Defaults: caller is the sole target (operators on console with no
    // player argument produce a loud S-004 warning from TestStressCmd,
    // which is the desired behaviour; we do not substitute a silent no-op).
    CommandsAPICommand stress = findChild("stress");
    if (stress == null) {
      String msg = "[RTP test/full] stress subcommand not registered; skipping";
      if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
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
      waitForCallerJobsToDrain(callerId, "stress");
    }

    // --- future shipped subcommands go here, in the order documented in
    // RUNTIME_TEST_SUITE_PLAN.md §4. Each addition MUST also move the
    // subcommand's row from Roadmap to Shipped in the plan document.
    //
    // Deliberately NOT dispatched from `full`:
    //   * `cancel` — would abort this very sweep.
    //   * `reload-safety` — admin-only, intentionally courts failures.
    //   Operators who want those must invoke them directly.

    // Final drain: catch any subcommand whose async tail is still in flight
    // (e.g. a synchronous dispatcher that fans out to ActiveTestJobs jobs
    // we did not explicitly wait on above). The end-state of the sweep --
    // including the audited-warnings count reported in the footer -- must
    // be evaluated AFTER every registered job has completed so operators
    // see a stable final tally rather than a snapshot taken mid-flight.
    waitForCallerJobsToDrain(callerId, "final");

    String footer = "[RTP test/full] end (total-audited-warnings=" + audit.warnCount + ")";
    if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, footer);
    }
    RTP.log(Level.INFO, footer);
  } finally {
    SendMessage.removeInterceptor(audit);
  }
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

  /**
   * Dispatches {@code subName} then blocks (off-main-thread, see {@link
   * #onCommand}) until every {@link ActiveTestJobs} entry owned by
   * {@code callerId} has completed or {@link #DRAIN_TIMEOUT_MILLIS}
   * elapses. This gives the umbrella sweep true sequential semantics:
   * each subcommand finishes (including any async tail it registered in
   * {@link ActiveTestJobs}) before the next one starts, and the final
   * footer reflects the real end-state of the whole run rather than a
   * mid-flight snapshot.
   */
  private void dispatchNoArgAndWait(UUID callerId, String subName) {
    dispatchNoArg(callerId, subName);
    waitForCallerJobsToDrain(callerId, subName);
  }

  /**
   * Polls {@link ActiveTestJobs#snapshot()} until the caller has no
   * outstanding jobs or the drain budget is exhausted. A budget breach is
   * reported at {@code WARNING} (REQ-RTP-S-004) and the sweep is allowed
   * to continue; the stuck job remains registered so {@code rtp test
   * cancel} can still reach it.
   *
   * <p>Must only be called from an async context (the sweep runs on
   * {@code RTP.scheduler.runTaskAsynchronously}); see {@link #onCommand}.
   */
  static void waitForCallerJobsToDrain(UUID callerId, String stageLabel) {
    long deadline = System.nanoTime() + DRAIN_TIMEOUT_MILLIS * 1_000_000L;
    while (true) {
      Collection<ActiveTestJobs.Job> jobs = ActiveTestJobs.snapshot().get(callerId);
      if (jobs == null || jobs.isEmpty()) return;
      if (System.nanoTime() >= deadline) {
        RTP.log(
            Level.WARNING,
            "[RTP test/full] drain timeout after stage=" + stageLabel
                + " (remaining=" + jobs.size() + "); continuing sweep. "
                + "Use `rtp test cancel` to abort the stuck job(s).");
        return;
      }
      try {
        Thread.sleep(DRAIN_POLL_MILLIS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        RTP.log(
            Level.WARNING,
            "[RTP test/full] drain interrupted at stage=" + stageLabel);
        return;
      }
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

  /**
   * Simple interceptor for the duration of the full sweep.
   */
  private static final class FullAudit implements java.util.function.Consumer<String> {
    volatile int warnCount = 0;

    @Override
    public void accept(String s) {
      // We rely on the convention that warnings/errors produced by our plugin
      // include the "[RTP" tag or similar markers from SendMessage.
      if (s != null && s.toUpperCase().contains("RTP")) {
        // We only count it as a "warning" if it's not the start/end/notice logs
        // from the test suite itself.
        if (!s.contains("[RTP test/full]") && !s.contains("notice:")) {
          warnCount++;
        }
      }
    }
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
