package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test commands-live} &mdash; live-dispatcher smoke test for malformed
 * input against the real Bukkit/Brigadier command tree rooted at {@code /rtp}.
 *
 * <p>Complements {@link TestCommandsCmd}, which only walks the CommandsAPI tree
 * structure in-memory. This job actually fires commands at the live dispatcher
 * ({@link Bukkit#dispatchCommand(CommandSender, String)}) with deliberately
 * malformed arguments and asserts the dispatch is <em>graceful</em>:
 *
 * <ul>
 *   <li>No {@link Throwable} escapes {@code dispatchCommand} to the console
 *       (e.g. {@link ArrayIndexOutOfBoundsException}, {@link NullPointerException}).
 *       A throwable here is an outright S-004 violation &mdash; the failure
 *       surfaces as a stack trace instead of a player-visible message.</li>
 *   <li>The dispatcher returns control (boolean result) without leaving the
 *       sender with zero output. An empty response on a known-bad input is a
 *       silent failure &mdash; a soft S-004 violation &mdash; and is logged at
 *       {@link Level#WARNING} so operators catch it in review.</li>
 *   <li>A {@link java.util.logging.Handler} is attached to the JUL root logger
 *       for the duration of the job so any {@code WARNING}/{@code SEVERE}
 *       record emitted by the plugin during dispatch is counted. A fully
 *       silent dispatch <em>and</em> a silent log is a hard S-004 finding.</li>
 * </ul>
 *
 * <p>The test cases (see {@link #malformedInputs()}) intentionally exercise
 * the well-known shapes of operator typos:
 * <ul>
 *   <li>Unknown player argument: {@code /rtp player thatdoesnotexist}</li>
 *   <li>Unknown subcommand: {@code /rtp reload invalid_arg}</li>
 *   <li>Unknown region: {@code /rtp region __no_such_region__}</li>
 *   <li>Garbage trailing args: {@code /rtp biome ::: ???}</li>
 *   <li>Completely unknown leaf: {@code /rtp frobnicate}</li>
 * </ul>
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> &mdash; every malformed dispatch that produces
 *       neither a sender message nor a warn-level log line is recorded and
 *       reported to the caller and to {@link RTP#log(Level, String)} at
 *       {@link Level#WARNING}.</li>
 *   <li><b>REQ-RTP-S-005</b> &mdash; dispatch runs on whichever thread the
 *       operator typed the command from. No chunk I/O is performed; this job
 *       only invokes command entry points and counts their side effects.</li>
 * </ul>
 *
 * <p>Traces REQ-RTP-S-004. See {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md
 * &sect;3.6} ({@code rtp test commands-live}).
 */
public class LiveCommandDispatcherTestJob extends BaseRTPCmdImpl {

  /** Marker prepended to every message/log line this job emits. */
  static final String TAG = "[RTP test/commands-live]";

  public LiveCommandDispatcherTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "commands-live";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "dispatches malformed /rtp commands and asserts graceful failure (REQ-RTP-S-004)";
  }

  /**
   * Malformed command lines (without the leading slash). Package-private so the
   * unit test can exercise the same list without a live Bukkit dispatcher.
   */
  static List<String> malformedInputs() {
    return Collections.unmodifiableList(
        Arrays.asList(
            "rtp player thatdoesnotexist",
            "rtp reload invalid_arg",
            "rtp region __no_such_region__",
            "rtp biome ::: ???",
            "rtp frobnicate"));
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    CommandSender sender;
    try {
      sender = Bukkit.getConsoleSender();
    } catch (Throwable t) {
      // Bukkit not initialised (e.g. unit-test harness); abort with a
      // WARNING rather than letting the throwable escape.
      String msg =
          TAG + " aborted: Bukkit console sender unavailable (" + t.getClass().getSimpleName() + ")";
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg, t);
      return true;
    }

    DispatchReport report = runDispatches(sender, malformedInputs());
    emit(callerId, report);
    return true;
  }

  /**
   * Core dispatch loop, extracted so tests can substitute a fake
   * {@link CommandSender} and verify the bookkeeping.
   */
  static DispatchReport runDispatches(CommandSender sender, List<String> inputs) {
    DispatchReport report = new DispatchReport();

    // Attach a JUL handler to the root logger so we can observe whether any
    // WARNING/SEVERE records fire during dispatch. The handler is always
    // detached in the finally block, even if a throwable escapes.
    java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
    CountingHandler handler = new CountingHandler();
    root.addHandler(handler);
    try {
      for (String line : inputs) {
        int warnsBefore = handler.warnCount;
        handler.lastMessage = null;
        report.attempted++;
        boolean threw = false;
        try {
          // Bukkit.dispatchCommand returns a boolean and, per contract,
          // catches plugin-side throwables; any throwable that still escapes
          // here is a hard S-004 violation.
          Bukkit.dispatchCommand(sender, line);
        } catch (Throwable t) {
          threw = true;
          report.threwOnDispatch.add(
              line + " -> " + t.getClass().getSimpleName() + ": " + safeMessage(t));
        }
        int warnsAfter = handler.warnCount;
        boolean producedWarn = warnsAfter > warnsBefore;
        if (!threw && !producedWarn) {
          // No throwable and no warn-level log: either the command genuinely
          // handled the bad input gracefully and emitted a user-visible info
          // message, OR it silently discarded the request. We cannot
          // distinguish perfectly without a sender-side capture, but we can
          // flag the ones that produced neither a log nor a well-known
          // "unknown command" Bukkit response.
          if (!looksLikeBukkitUnknownCommand(handler.lastMessage)) {
            report.possibleSilentFailures.add(line);
          }
        }
      }
    } finally {
      root.removeHandler(handler);
    }
    return report;
  }

  /**
   * Bukkit logs an "Unknown command" message at {@link Level#INFO} when no
   * plugin claims a label. That counts as a visible response, not a silent
   * failure.
   */
  static boolean looksLikeBukkitUnknownCommand(@Nullable String msg) {
    if (msg == null) return false;
    String lc = msg.toLowerCase(java.util.Locale.ROOT);
    return lc.contains("unknown command") || lc.contains("unknown or incomplete");
  }

  private static String safeMessage(Throwable t) {
    String m = t.getMessage();
    return m == null ? "<no message>" : m;
  }

  private void emit(UUID callerId, DispatchReport report) {
    int issues = report.threwOnDispatch.size() + report.possibleSilentFailures.size();
    String summary =
        TAG
            + " attempted="
            + report.attempted
            + " threw="
            + report.threwOnDispatch.size()
            + " possible-silent-failures="
            + report.possibleSilentFailures.size();
    RTP.serverAccessor.sendMessage(callerId, summary);
    if (issues == 0) {
      RTP.log(Level.INFO, summary);
      return;
    }
    RTP.log(Level.WARNING, summary);
    for (String line : report.threwOnDispatch) {
      String msg = TAG + " threw: " + line;
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
    }
    for (String line : report.possibleSilentFailures) {
      String msg = TAG + " silent: " + line;
      RTP.serverAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
    }
  }

  /**
   * Aggregates dispatch findings. Lists are intentionally mutable and
   * package-private so the unit test can assert on them directly.
   */
  static final class DispatchReport {
    int attempted = 0;
    final List<String> threwOnDispatch = new ArrayList<>();
    final List<String> possibleSilentFailures = new ArrayList<>();

    int totalIssues() {
      return threwOnDispatch.size() + possibleSilentFailures.size();
    }
  }

  /**
   * Minimal JUL handler that counts {@link Level#WARNING}+ records and
   * remembers the last formatted message. Intentionally allocation-light
   * because it is attached to the root logger for the duration of a dispatch
   * batch.
   */
  static final class CountingHandler extends java.util.logging.Handler {
    volatile int warnCount = 0;
    volatile @Nullable String lastMessage = null;

    @Override
    public void publish(LogRecord record) {
      if (record == null) return;
      if (record.getLevel() != null
          && record.getLevel().intValue() >= Level.WARNING.intValue()) {
        warnCount++;
      }
      lastMessage = record.getMessage();
    }

    @Override
    public void flush() {}

    @Override
    public void close() throws SecurityException {}
  }
}
