package io.github.dailystruggle.rtp.common.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test cancel [all]} &mdash; aborts any in-flight {@code rtp test *}
 * job registered with {@link ActiveTestJobs}. Without arguments, cancels jobs
 * owned by the caller; with literal argument {@code all}, cancels every
 * active job (requires {@code rtp.test.admin}).
 *
 * <p>This exists because a bad {@code iterations:1000 intervalTicks:10}
 * invocation currently has no in-band stop switch short of a server
 * restart. See {@code RUNTIME_TEST_SUITE_PLAN.md Â§3.3 (cancel)}.
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> &mdash; every cancel outcome (including
 *       &quot;nothing to cancel&quot;) produces a caller-visible message
 *       and an {@link Level#INFO} or {@link Level#WARNING} log entry.
 *       Permission denial on {@code all} is logged at WARNING.</li>
 *   <li>Cancel itself performs no chunk I/O and no synchronous blocking,
 *       so S-005 is trivially satisfied.</li>
 * </ul>
 */
public class TestCancelCmd extends BaseRTPCmdImpl {

  /** Literal token that switches the command to global-cancel mode. */
  static final String ALL_TOKEN = "all";

  public TestCancelCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "cancel";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "cancel in-flight rtp test jobs (append 'all' to cancel every caller's jobs)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    boolean wantAll = wantsAll(parameterValues);
    if (wantAll) {
      // Gate the global cancel behind a distinct permission so granting
      // `rtp.test` to trusted staff doesn't hand them a server-wide
      // interrupt. hasPermission is checked via the server accessor to
      // stay platform-agnostic.
      if (!callerHasAdmin(callerId)) {
        String denied = "&c[RTP test/cancel] 'all' requires rtp.test.admin";
        if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
          RTP.serverAccessor.sendMessage(callerId, denied);
        }
        RTP.log(Level.WARNING, "[RTP test/cancel] denied 'all' to " + callerId);
        return true;
      }
      int n = ActiveTestJobs.cancelAll();
      String msg = "[RTP test/cancel] cancelled " + n + " job(s) across all owners";
      if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        RTP.serverAccessor.sendMessage(callerId, msg);
      }
      RTP.log(Level.INFO, msg);
      return true;
    }

    int n = ActiveTestJobs.cancelOwned(callerId);
    String msg =
        n == 0
            ? "[RTP test/cancel] no active jobs owned by you"
            : "[RTP test/cancel] cancelled " + n + " job(s)";
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, msg);
    }
    RTP.log(Level.INFO, msg);
    return true;
  }

  /**
   * Resolves the caller UUID to an {@code RTPPlayer} and asks it whether
   * it holds {@code rtp.test.admin}. Console and unknown callers return
   * {@code true} on the assumption that a console sender has implicit
   * admin; otherwise an unreachable permission check would make the
   * {@code all} branch impossible to exercise from the console.
   */
  private static boolean callerHasAdmin(UUID callerId) {
    try {
      var sender = RTP.serverAccessor.getPlayer(callerId);
      if (sender == null) return true; // console path
      return sender.hasPermission("rtp.test.admin");
    } catch (Throwable t) {
      RTP.log(
          Level.WARNING,
          "[RTP test/cancel] permission lookup failed for " + callerId + "; denying 'all'",
          t);
      return false;
    }
  }

  /**
   * Inspects all parameter lists for the literal {@link #ALL_TOKEN}. We
   * avoid declaring a formal parameter because CommandsAPI's parameter
   * model doesn't cleanly express a single positional enum-like flag;
   * scanning the values is simpler and keeps the usage string flat.
   */
  private static boolean wantsAll(Map<String, List<String>> parameterValues) {
    if (parameterValues == null || parameterValues.isEmpty()) return false;
    for (List<String> values : parameterValues.values()) {
      if (values == null) continue;
      for (String v : values) {
        if (v != null && ALL_TOKEN.equals(v.trim().toLowerCase(Locale.ROOT))) return true;
      }
    }
    return false;
  }
}
