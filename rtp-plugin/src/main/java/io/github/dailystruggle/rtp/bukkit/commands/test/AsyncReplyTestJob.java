package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.OnlinePlayerParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test async-reply player:<name>} &mdash; verifies that the
 * asynchronous {@code /rtp} pipeline marshals its final player-facing reply
 * (typically the {@code Teleporting...} or {@code Searching...} message) back
 * to a thread that is permitted by the active platform's messaging rules.
 *
 * <p><b>Why this test exists.</b> The teleport pipeline legitimately runs the
 * bulk of its work off-thread (async chunk loads, claim verifiers, spiral
 * math) but the <i>reply</i> to the caller must land somewhere safe:
 * <ul>
 *   <li>On Paper / Spigot, messages should be delivered on the main server
 *       thread (the classic "sync chat" contract). An async-only reply is
 *       tolerated by the API but is a smell that precedes Adventure-API
 *       breakage.</li>
 *   <li>On Folia, the main-thread rule is replaced by region/global
 *       schedulers; delivering a message from an arbitrary worker thread is
 *       acceptable <i>only</i> because Paper's {@code CommandSender} catches
 *       async messaging and routes it. Delivering from a
 *       {@code Region Thread} that doesn't own the target entity would
 *       throw {@code ThreadAccessException}.</li>
 * </ul>
 * This probe records the actual delivery thread and asserts it is one of
 * the permitted categories for the current platform.
 *
 * <p><b>How it works.</b> We wrap {@link RTP#serverAccessor} in a JDK
 * dynamic proxy that delegates every call to the real accessor but, for
 * {@code sendMessage*} invocations whose textual payload contains one of
 * the tracked needles ({@code "Teleporting"} / {@code "Searching"}) and
 * whose target UUID matches the test player, captures
 * {@link Thread#currentThread()} into a {@link CompletableFuture}. We then
 * delegate a normal {@code /rtp} invocation through
 * {@link RTPCmd#compute(UUID, Map, CommandsAPICommand)} (the same entry
 * point the real command uses), wait up to {@link #REPLY_TIMEOUT_MS} for
 * the first matching reply, and report the thread class / name plus the
 * platform verdict.
 *
 * <p>The accessor is restored in a {@code finally} block regardless of
 * outcome, so a timeout cannot leave the proxy installed and silently
 * break subsequent messaging (which would violate REQ-RTP-S-004 for every
 * later teleport).
 *
 * <p>Safety compliance:
 * <ul>
 *   <li><b>REQ-RTP-S-004</b> &mdash; every outcome (success, timeout,
 *       reject) produces a player-visible line and a
 *       {@link Level#INFO} / {@link Level#WARNING} log entry; the proxy
 *       forwards all delegated {@code sendMessage} calls unchanged.</li>
 *   <li><b>REQ-RTP-S-005</b> &mdash; the probe triggers {@code /rtp}
 *       through the existing pipeline and does not load chunks itself;
 *       the wait happens on the async scheduler, not the main thread.</li>
 *   <li><b>REQ-RTP-S-006</b> &mdash; if {@code RTP.serverAccessor} or
 *       {@code RTP.scheduler} is null (core not yet loaded) the command
 *       logs WARN and returns rather than NPEing.</li>
 * </ul>
 */
public class AsyncReplyTestJob extends BaseRTPCmdImpl {

  /** Case-insensitive substrings that identify the teleport pipeline's final reply. */
  static final String[] REPLY_NEEDLES = {"teleporting", "searching"};

  /** Max wall time to wait for the first tracked reply before timing out. */
  static final long REPLY_TIMEOUT_MS = 5_000L;

  public AsyncReplyTestJob(@Nullable CommandsAPICommand parent) {
    super(parent);
    addParameter(
        "player",
        new OnlinePlayerParameter(
            "rtp.test",
            "target player whose /rtp reply thread will be recorded",
            (sender, s) -> true));
  }

  @Override
  public String name() {
    return "async-reply";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "assert the async /rtp pipeline marshals its reply to a safe messaging thread";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return nextCommand.onCommand(callerId, parameterValues, null);

    // --- S-006: guard against being invoked before rtp-core finishes loading. ---
    final RTPServerAccessor realAccessor = RTP.serverAccessor;
    if (realAccessor == null || RTP.scheduler == null) {
      RTP.log(
          Level.WARNING,
          "[RTP test/async-reply] rejected: rtp-core not loaded (serverAccessor/scheduler null)");
      return true;
    }

    // --- Argument validation (S-004: fail loud, not silent). ---
    List<String> playerNames = parameterValues.get("player");
    if (playerNames == null || playerNames.isEmpty()) {
      String msg = "&c[RTP test/async-reply] missing required player:<name> argument";
      realAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      return true;
    }

    RTPPlayer target = realAccessor.getPlayer(playerNames.getFirst());
    if (target == null) {
      String msg = "&c[RTP test/async-reply] unknown player: " + playerNames.getFirst();
      realAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      return true;
    }
    final UUID targetId = target.uuid();

    final RTPCmd rootCmd = findRtpCmd();
    if (rootCmd == null) {
      String msg = "&c[RTP test/async-reply] root rtp command not found; aborting";
      realAccessor.sendMessage(callerId, msg);
      RTP.log(Level.WARNING, msg);
      return true;
    }

    // The probe body runs on the async tier: we don't want to hold the
    // calling thread (which may be the main server thread on Paper/Spigot)
    // while we wait for the reply. S-005 is thereby preserved.
    RTP.scheduler.runTaskAsynchronously(
        () -> runProbe(callerId, realAccessor, targetId, target.name(), rootCmd, parameterValues));
    return true;
  }

  /**
   * Installs the recording proxy, fires the {@code /rtp} request, waits for
   * the first tracked reply, then restores the real accessor. Called from
   * the async scheduler.
   */
  private void runProbe(
      UUID callerId,
      RTPServerAccessor realAccessor,
      UUID targetId,
      String targetName,
      RTPCmd rootCmd,
      Map<String, List<String>> parameterValues) {

    final CompletableFuture<Thread> delivery = new CompletableFuture<>();

    // The proxy forwards every call to `realAccessor`. The only extra
    // behaviour is: if this is a sendMessage* call targeted at `targetId`
    // whose stringified payload contains one of REPLY_NEEDLES, record the
    // current thread. We forward *before* recording so a throw from the
    // real accessor is surfaced and doesn't count as a recorded delivery.
    RTPServerAccessor recordingAccessor =
        (RTPServerAccessor)
            Proxy.newProxyInstance(
                RTPServerAccessor.class.getClassLoader(),
                new Class<?>[] {RTPServerAccessor.class},
                new RecordingHandler(realAccessor, targetId, delivery));

    RTP.serverAccessor = recordingAccessor;
    try {
      report(callerId, realAccessor, "[RTP test/async-reply] begin for player=" + targetName);

      // Delegate through the normal pipeline so cooldowns, economy, claim
      // verifiers, and the async chunk path all run exactly as a real
      // user command would.
      try {
        Map<String, List<String>> iterArgs = new HashMap<>();
        iterArgs.put("player", Collections.singletonList(targetName));
        List<String> regions = parameterValues.get("region");
        if (regions != null && !regions.isEmpty()) {
          iterArgs.put("region", new ArrayList<>(regions));
        }
        rootCmd.compute(callerId, iterArgs, null);
      } catch (Throwable t) {
        String msg =
            "[RTP test/async-reply] pipeline invocation threw "
                + t.getClass().getSimpleName()
                + ": "
                + t.getMessage();
        realAccessor.sendMessage(callerId, msg);
        RTP.log(Level.WARNING, msg, t);
        return;
      }

      try {
        Thread deliveryThread = delivery.get(REPLY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        evaluate(callerId, realAccessor, deliveryThread);
      } catch (TimeoutException te) {
        String msg =
            "[RTP test/async-reply] TIMEOUT: no tracked reply within "
                + REPLY_TIMEOUT_MS
                + "ms (needles="
                + String.join("/", REPLY_NEEDLES)
                + ")";
        realAccessor.sendMessage(callerId, msg);
        RTP.log(Level.WARNING, msg);
      } catch (Throwable t) {
        String msg =
            "[RTP test/async-reply] wait failed: "
                + t.getClass().getSimpleName()
                + ": "
                + t.getMessage();
        realAccessor.sendMessage(callerId, msg);
        RTP.log(Level.WARNING, msg, t);
      }
    } finally {
      // Restore unconditionally. Leaving the proxy installed would silently
      // break every subsequent message for the lifetime of the JVM and
      // violate S-004 for all future teleports.
      RTP.serverAccessor = realAccessor;
      report(callerId, realAccessor, "[RTP test/async-reply] end");
    }
  }

  /**
   * Classifies the recorded delivery thread against the platform's
   * messaging contract and reports a pass/fail line.
   */
  private void evaluate(UUID callerId, RTPServerAccessor realAccessor, Thread deliveryThread) {
    final String platform = safePlatform(realAccessor);
    final boolean isPrimary = safeIsPrimary(realAccessor, deliveryThread);
    final String threadName = deliveryThread.getName();
    final boolean folia = platform != null && platform.toLowerCase(Locale.ROOT).contains("folia");

    // Permitted categories:
    //   - Paper/Spigot: main server thread is the canonical safe thread;
    //     an async thread that goes through Paper's async-catcher is also
    //     acceptable (Paper wraps CommandSender.sendMessage).
    //   - Folia: any Region/Global/Async scheduler thread is fine; the one
    //     forbidden case is a raw arbitrary thread that doesn't belong to
    //     a scheduler. We approximate "belongs to a scheduler" by the
    //     thread-name prefixes Folia uses.
    boolean permitted;
    String reason;
    if (folia) {
      String lower = threadName.toLowerCase(Locale.ROOT);
      permitted =
          lower.contains("region")
              || lower.contains("global")
              || lower.contains("async")
              || lower.contains("tick")
              || lower.contains("server thread");
      reason =
          permitted
              ? "folia: thread name matches a scheduler tier"
              : "folia: thread name '" + threadName + "' is not a recognised scheduler tier";
    } else {
      // Paper/Spigot: either main or a recognised async pool is fine.
      permitted = isPrimary || threadName.toLowerCase(Locale.ROOT).contains("async");
      reason =
          permitted
              ? (isPrimary ? "paper/spigot: main thread" : "paper/spigot: async (catcher-safe)")
              : "paper/spigot: thread '" + threadName + "' is neither main nor a known async pool";
    }

    String line =
        "[RTP test/async-reply] "
            + (permitted ? "PASS" : "FAIL")
            + " platform="
            + platform
            + " thread='"
            + threadName
            + "' primary="
            + isPrimary
            + " ("
            + reason
            + ")";
    realAccessor.sendMessage(callerId, line);
    RTP.log(permitted ? Level.INFO : Level.WARNING, line);
  }

  private static String safePlatform(RTPServerAccessor accessor) {
    try {
      String p = accessor.getPlatform();
      return p == null ? "unknown" : p;
    } catch (Throwable t) {
      return "unknown";
    }
  }

  /**
   * {@link RTPServerAccessor#isPrimaryThread()} asks about the <i>calling</i>
   * thread, so to classify a recorded thread we briefly pin and inspect.
   * On Folia {@code isPrimaryThread} is effectively a no-concept and we
   * fall back to a name-based heuristic rather than returning a misleading
   * true/false.
   */
  private static boolean safeIsPrimary(RTPServerAccessor accessor, Thread t) {
    // Only the calling thread can be "primary" per the accessor's contract.
    // The delivery thread was captured earlier, so the honest answer is:
    // was the delivering thread the main thread? We approximate via the
    // well-known Bukkit main-thread name "Server thread".
    if (t.getName().equals("Server thread")) return true;
    try {
      // If we *happen* to be running on the delivery thread (rare but
      // possible on single-threaded test runners), consult the accessor.
      if (Thread.currentThread() == t) return accessor.isPrimaryThread();
    } catch (Throwable ignored) {
      // fall through
    }
    return false;
  }

  private void report(UUID callerId, RTPServerAccessor accessor, String msg) {
    accessor.sendMessage(callerId, msg);
    RTP.log(Level.INFO, msg);
  }

  /** Walks the parent chain to locate the root {@link RTPCmd} node. */
  private RTPCmd findRtpCmd() {
    CommandsAPICommand node = parent();
    while (node != null) {
      if (node instanceof RTPCmd) return (RTPCmd) node;
      node = node.parent();
    }
    return null;
  }

  /**
   * {@link InvocationHandler} that forwards every call to the real
   * accessor and, for {@code sendMessage*} calls aimed at the tracked
   * target UUID whose stringified payload contains a tracked needle,
   * records the delivering thread into {@code delivery} exactly once.
   *
   * <p>Package-private for unit-test access (see
   * {@code AsyncReplyTestJobTest}).
   */
  static final class RecordingHandler implements InvocationHandler {
    private final RTPServerAccessor delegate;
    private final UUID targetId;
    private final CompletableFuture<Thread> delivery;

    RecordingHandler(
        RTPServerAccessor delegate, UUID targetId, CompletableFuture<Thread> delivery) {
      this.delegate = delegate;
      this.targetId = targetId;
      this.delivery = delivery;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      // Forward first so exceptions from the real accessor propagate
      // unchanged and do not count as a recorded delivery.
      Object result;
      try {
        result = method.invoke(delegate, args);
      } catch (java.lang.reflect.InvocationTargetException ite) {
        throw ite.getCause() != null ? ite.getCause() : ite;
      }

      if (delivery.isDone()) return result;
      if (!"sendMessage".equals(method.getName())) return result;
      if (args == null || args.length == 0) return result;

      // Find the target UUID slot and a stringified payload among the
      // heterogeneous sendMessage overloads.
      boolean targetsUs = false;
      String payload = null;
      for (Object a : args) {
        if (a instanceof UUID && targetId.equals(a)) targetsUs = true;
        if (a instanceof String && payload == null) payload = (String) a;
        // MessagesKeys / RTPCommandSender: we can't know the rendered text
        // cheaply, so those overloads are best-effort and only match when
        // a String payload is also present.
      }
      if (!targetsUs || payload == null) return result;

      String lower = payload.toLowerCase(Locale.ROOT);
      for (String needle : REPLY_NEEDLES) {
        if (lower.contains(needle)) {
          delivery.complete(Thread.currentThread());
          break;
        }
      }
      return result;
    }
  }
}
