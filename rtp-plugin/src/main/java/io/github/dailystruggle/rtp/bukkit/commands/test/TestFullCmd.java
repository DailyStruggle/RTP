package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.TestCancelCmd;
import io.github.dailystruggle.rtp.common.commands.test.TestSchedulerCmd;
import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;
import io.github.dailystruggle.rtp.api.RTPAPI;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
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
 * added to the {@link #SHIPPED_SUBCOMMAND_NAMES} list and dispatched by
 * {@link #runStep(UUID, int, FullAudit)} in the same
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
   * invokes. Mirrors the sequence dispatched by {@link #runStep(UUID, int, FullAudit)}
   * and &sect;4 of {@code RUNTIME_TEST_SUITE_PLAN.md}. Exposed package-private
   * so unit tests can assert parity with {@link TestCmd}'s registration set
   * without having to initialise {@link RTP#serverAccessor}.
   *
   * <p>Deliberately excluded: {@code cancel} (would abort the sweep itself)
   * and {@code reload-safety} (admin-only, intentionally noisy). See the
   * javadoc on {@link #runStep(UUID, int, FullAudit)} for the rationale.
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
              "economy-isolation",
              "queue-starvation",
              "async-reply",
              "disconnect-job",
              "safety-verifier",
              "stress"));

  /**
   * Subcommand names that are registered on the {@code test} parent but
   * deliberately NOT dispatched from the umbrella sweep. See the javadoc
   * on {@link #runStep(UUID, int, FullAudit)} for the rationale.
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

  /**
   * Watchdog tick conversion: the umbrella sweep's drain timeout is
   * specified in millis but the platform's async timer scheduler takes
   * ticks. 50ms/tick is the canonical Bukkit cadence and is also what
   * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler}
   * implementations use, so this is the right unit on every supported
   * platform. The minimum of 1 tick guards against pathological overrides
   * of {@link #DRAIN_TIMEOUT_MILLIS} that would otherwise schedule a
   * zero-tick timer (rejected by some platforms).
   */
  private static final long DRAIN_TIMEOUT_TICKS =
      Math.max(1L, DRAIN_TIMEOUT_MILLIS / 50L);

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;
    if (!isProcessing.compareAndSet(false, true)) return true;
    // The sweep used to park one async-pool worker for its entire wall-clock
    // duration on a polling drain loop. That satisfies REQ-RTP-S-005
    // (main thread untouched) but starves whatever else the platform's
    // async pool wants to run. The chain below now releases its async slot
    // between subcommands: each step runs as a short async task, then arms
    // an event-driven advance via ActiveTestJobs.addOnEmptyListener and a
    // bounded async-timer watchdog (REQ-RTP-S-004 timeout). No thread is
    // parked between subcommands.
    final FullAudit audit = new FullAudit();
    SendMessage.addInterceptor(audit);

    String header = "[RTP test/full] begin (shipped subcommands only)";
    if (!callerId.equals(RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, header);
    }
    RTP.log(Level.INFO, header);

    try {
      RTP.scheduler.runTaskAsynchronously(() -> runStep(callerId, 0, audit));
    } catch (Throwable t) {
      // Async scheduler unavailable (e.g. headless tests). Run the chain
      // inline so we don't silently no-op (S-004). In headless contexts
      // subcommands typically don't register ActiveTestJobs entries, so
      // the listener path resolves synchronously and the chain unwinds
      // without ever needing the watchdog timer.
      runStep(callerId, 0, audit);
    }
    return true;
  }

  /**
   * One step of the umbrella sweep's chain. Runs on an async thread (via
   * {@link io.github.dailystruggle.rtp.api.scheduling.RTPScheduler#runTaskAsynchronously}),
   * and is intentionally short: it dispatches at most one subcommand and
   * either advances the chain inline (if no async jobs were registered) or
   * arms an event-driven advance and returns, releasing its async-pool slot.
   *
   * <p>This is the data-driven equivalent of the for-loop body in
   * {@code runShippedSubcommands}: the loop is unrolled over time so the
   * pool slot is held for microseconds per subcommand instead of for the
   * sweep's full wall-clock duration.
   *
   * <p>{@code index == SHIPPED_SUBCOMMAND_NAMES.size()} is the terminal
   * step: it arms one final drain wait so the footer's audited-warnings
   * count reflects every registered job, then emits the footer and removes
   * the audit interceptor.
   *
   * <p>Deliberately NOT dispatched: {@code cancel} (would abort this very
   * sweep) and {@code reload-safety} (admin-only, intentionally courts
   * failures). See {@link #DELIBERATELY_EXCLUDED_SUBCOMMANDS}.
   */
  private void runStep(UUID callerId, int index, FullAudit audit) {
    try {
      if (index >= SHIPPED_SUBCOMMAND_NAMES.size()) {
        // Final drain: catch any subcommand whose async tail is still in
        // flight after its own per-step drain advanced. The footer must
        // reflect the post-drain state so operators see a stable tally.
        armAdvance(callerId, "final", () -> finishSweep(callerId, audit));
        return;
      }

      String subName = SHIPPED_SUBCOMMAND_NAMES.get(index);
      String skipReason = shouldSkip(subName, callerId);
      if (skipReason != null) {
        String msg = "[RTP test/full] " + subName + " skipped: " + skipReason;
        if (!callerId.equals(RTPAPI.serverId)) {
          RTP.serverAccessor.sendMessage(callerId, msg);
        }
        RTP.log(Level.INFO, msg);
        scheduleNext(callerId, index + 1, audit);
        return;
      }

      // Subcommand-specific operator notice (e.g. commands-live's
      // intentional WARNING burst per REQ-RTP-S-004).
      String notice = noticeFor(subName);
      if (notice != null) {
        if (!callerId.equals(RTPAPI.serverId)) {
          RTP.serverAccessor.sendMessage(callerId, notice);
        }
        RTP.log(Level.INFO, notice);
      }

      dispatchSubcommand(callerId, subName, argsFor(subName, callerId));
      armAdvance(callerId, subName, () -> scheduleNext(callerId, index + 1, audit));
    } catch (Throwable t) {
      // Defensive: an unexpected throwable in the step itself must not
      // strand the sweep with the audit interceptor still attached. Log
      // at WARNING per REQ-RTP-S-004 and force-finish so the interceptor
      // is removed and `isProcessing` is released.
      RTP.log(
          Level.WARNING,
          "[RTP test/full] step " + index + " aborted: " + t.getMessage(),
          t);
      finishSweep(callerId, audit);
    }
  }

  /**
   * Posts the next step on the async scheduler so the current thread (which
   * may be a registry-callback thread inside a subcommand's last
   * {@link ActiveTestJobs} unregister hook) is released immediately and
   * the chain continues on a fresh pool slot. Falls back to inline if the
   * scheduler is unavailable (headless tests).
   */
  private void scheduleNext(UUID callerId, int nextIndex, FullAudit audit) {
    try {
      RTP.scheduler.runTaskAsynchronously(() -> runStep(callerId, nextIndex, audit));
    } catch (Throwable t) {
      runStep(callerId, nextIndex, audit);
    }
  }

  /**
   * Dispatches a shipped subcommand by name through the {@code rtp test}
   * parent's command lookup (i.e. simulates {@code rtp test <subName>}),
   * logging at WARNING per REQ-RTP-S-004 if the subcommand is missing or
   * throws. Returns immediately; the caller arms the cross-subcommand drain
   * wait via {@link #armAdvance}.
   */
  private void dispatchSubcommand(UUID callerId, String subName, Map<String, List<String>> args) {
    CommandsAPICommand sub = findChild(subName);
    if (sub == null) {
      String msg = "[RTP test/full] " + subName + " subcommand not registered; skipping";
      if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.WARNING, msg);
      return;
    }
    try {
      sub.onCommand(callerId, args == null ? new HashMap<>() : args, null);
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP test/full] " + subName + " dispatch failed: " + t.getMessage(),
          t);
    }
  }

  /**
   * Event-driven replacement for the old polling drain loop. Invokes
   * {@code onAdvance} exactly once when {@code callerId} has no outstanding
   * {@link ActiveTestJobs} entries, OR when {@link #DRAIN_TIMEOUT_MILLIS}
   * elapses (in which case a {@code WARNING} is logged per REQ-RTP-S-004).
   *
   * <p>Crucially, this method does NOT block. It returns as soon as the
   * listener and watchdog are armed, releasing the async-pool slot the
   * step was running on. This is the whole reason for the rewrite: the
   * sweep no longer holds a worker thread between subcommands.
   *
   * <p>Listener-vs-watchdog races are resolved by a single
   * {@link java.util.concurrent.atomic.AtomicBoolean} guard: whichever
   * fires first calls {@code onAdvance}; the loser is unregistered or
   * cancelled without invoking {@code onAdvance}.
   */
  private void armAdvance(UUID callerId, String stageLabel, Runnable onAdvance) {
    final java.util.concurrent.atomic.AtomicBoolean advanced =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    // The watchdog timer's task handle, set after the timer is scheduled.
    // Held inside an array so the listener (which is created before the
    // timer) can still cancel it.
    final Object[] watchdogHolder = new Object[1];
    // The listener, held in an array so the watchdog can unregister it
    // without firing it on timeout.
    final Runnable[] listenerHolder = new Runnable[1];

    Runnable listener =
        () -> {
          if (!advanced.compareAndSet(false, true)) return;
          // Cancel the watchdog so it doesn't fire a spurious WARNING.
          Object wd = watchdogHolder[0];
          if (wd != null) {
            try {
              RTP.scheduler.cancelTask(wd);
            } catch (Throwable ignored) {
              // best-effort: a stale cancel can't double-advance because
              // of the `advanced` guard.
            }
          }
          onAdvance.run();
        };
    listenerHolder[0] = listener;

    // Register the listener first. addOnEmptyListener fires inline if the
    // owner is already drained, which is the common case for synchronous
    // subcommands that did not register an ActiveTestJobs entry — so the
    // chain advances without ever scheduling the watchdog timer.
    ActiveTestJobs.addOnEmptyListener(callerId, listener);
    if (advanced.get()) {
      // Listener already fired inline (owner was drained on registration).
      // No watchdog needed.
      return;
    }

    // Listener is parked on the registry; arm the watchdog. Use the
    // async-timer scheduler so neither the watchdog nor its callback
    // touches the main thread (REQ-RTP-S-005). We schedule a one-shot
    // timer by cancelling ourselves on first fire.
    try {
      Object[] selfHolder = new Object[1];
      Runnable watchdogBody =
          () -> {
            // Cancel ourselves so this is effectively a one-shot.
            Object self = selfHolder[0];
            if (self != null) {
              try {
                RTP.scheduler.cancelTask(self);
              } catch (Throwable ignored) {
                // best-effort
              }
            }
            if (!advanced.compareAndSet(false, true)) return;
            // Listener didn't fire in time. Unregister it so a late drain
            // event can't re-fire it after we've already advanced.
            ActiveTestJobs.removeOnEmptyListener(callerId, listenerHolder[0]);
            Collection<ActiveTestJobs.Job> remaining =
                ActiveTestJobs.snapshot().get(callerId);
            int remainingCount = remaining == null ? 0 : remaining.size();
            RTP.log(
                Level.WARNING,
                "[RTP test/full] drain timeout after stage=" + stageLabel
                    + " (remaining=" + remainingCount + "); continuing sweep. "
                    + "Use `rtp test cancel` to abort the stuck job(s).");
            onAdvance.run();
          };
      Object task =
          RTP.scheduler.runTaskTimerAsynchronously(
              watchdogBody, DRAIN_TIMEOUT_TICKS, DRAIN_TIMEOUT_TICKS);
      selfHolder[0] = task;
      watchdogHolder[0] = task;
    } catch (Throwable t) {
      // Async timer scheduler unavailable (headless tests). The listener
      // is already registered; if the owner ever drains it will fire and
      // advance. For tests where no jobs were registered, the inline
      // fast-path above already advanced and we never reached here.
      // If we DID get here with jobs still registered and no scheduler,
      // log a WARNING (S-004) so the regression is visible rather than
      // a silent stall.
      Collection<ActiveTestJobs.Job> remaining =
          ActiveTestJobs.snapshot().get(callerId);
      if (remaining != null && !remaining.isEmpty()) {
        RTP.log(
            Level.WARNING,
            "[RTP test/full] watchdog scheduler unavailable at stage=" + stageLabel
                + " with remaining jobs=" + remaining.size()
                + "; chain will advance only when jobs naturally drain.");
      }
    }
  }

  /**
   * Terminal step of the chain: emits the footer and releases the audit
   * interceptor + the {@link #isProcessing} guard. Idempotent in spirit:
   * the {@link #isProcessing} CAS guarantees only one chain is live at a
   * time, so this runs exactly once per sweep.
   */
  private void finishSweep(UUID callerId, FullAudit audit) {
    try {
      String footer = "[RTP test/full] end (total-audited-warnings=" + audit.warnCount + ")";
      if (!callerId.equals(RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, footer);
      }
      RTP.log(Level.INFO, footer);
    } finally {
      SendMessage.removeInterceptor(audit);
      isProcessing.set(false);
    }
  }

  /**
   * Returns a non-null skip reason if {@code subName} should not be dispatched
   * for {@code callerId} on the current platform, or {@code null} to proceed.
   * Encapsulates the small handful of context-dependent gates the sweep used
   * to inline (Folia-only subcommands, console callers that lack a region or
   * player, etc.).
   */
  private String shouldSkip(String subName, UUID callerId) {
    boolean isConsole = callerId.equals(RTPAPI.serverId);
    switch (subName) {
      case "folia-ownership":
        return RTP.serverAccessor.getPlatform().equalsIgnoreCase("Folia")
            ? null
            : "only meaningful on Folia";
      case "queue-starvation":
        return isConsole ? "console caller has no resolvable region" : null;
      case "async-reply":
        return isConsole ? "console caller has no resolvable player" : null;
      default:
        return null;
    }
  }

  /**
   * Operator-facing notice emitted before {@code subName} is dispatched, or
   * {@code null} if the subcommand needs no preamble.
   */
  private String noticeFor(String subName) {
    return null;
  }

  /**
   * Builds the argument map for {@code subName}. Most subcommands run with
   * defaults (an empty map); a few need a small per-call payload that mirrors
   * what an operator would type after {@code rtp test <sub>}.
   */
  private Map<String, List<String>> argsFor(String subName, UUID callerId) {
    Map<String, List<String>> args = new HashMap<>();
    switch (subName) {
      case "queue-starvation":
        args.put(
            "samples",
            new ArrayList<>(Collections.singletonList(String.valueOf(FULL_ITERATIONS))));
        break;
      case "async-reply":
        args.put(
            "player", new ArrayList<>(Collections.singletonList(resolveName(callerId))));
        break;
      case "stress":
        if (!callerId.equals(RTPAPI.serverId)) {
          args.put(
              "player",
              new ArrayList<>(Collections.singletonList(resolveName(callerId))));
        }
        args.put(
            "iterations",
            new ArrayList<>(Collections.singletonList(String.valueOf(FULL_ITERATIONS))));
        break;
      default:
        // empty map -> equivalent to `rtp test <sub>` with no args
        break;
    }
    return args;
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
