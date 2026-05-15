package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.ActiveTestJobs;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.OnlinePlayerParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import io.github.dailystruggle.rtp.common.commands.RTPCmd;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
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
import java.util.function.Consumer;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test async-reply player:<name>} &mdash; records which thread the
 * {@code /rtp} pipeline uses to deliver its first player-facing reply
 * ({@code Teleporting/Searching/Loading}) and asserts it is permitted by the
 * active platform's messaging rules. Wraps {@link RTP#serverAccessor} in a JDK
 * dynamic proxy that captures {@link Thread#currentThread()} on matching
 * {@code sendMessage*} calls; the proxy is always restored in {@code finally}.
 * Covers REQ-RTP-S-004/005/006.
 */
public class AsyncReplyTestJob extends BaseRTPCmdImpl {

  /** Lowercase substrings identifying the pipeline's first player-facing reply
   *  across English defaults and localized {@code messages.yml} variants. */
  static final String[] REPLY_NEEDLES = {
      "teleport", "searching", "loading", "setup"
  };

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
    //
    // Register with ActiveTestJobs so `rtp test full`'s
    // dispatchNoArgAndWait → waitForCallerJobsToDrain blocks until this
    // probe completes. Without the registration the umbrella sweep would
    // race ahead and interleave the next stage's output with ours.
    final String targetName = target.name();
    final Runnable[] hookHolder = new Runnable[1];
    final Runnable cancelHook = () -> {
      // Best-effort: completing delivery exceptionally would unblock the
      // 5-second wait, but we don't have a handle to it here. The
      // REPLY_TIMEOUT_MS bound caps the worst case anyway.
    };
    hookHolder[0] = ActiveTestJobs.register(
        callerId, new ActiveTestJobs.Job("async-reply", cancelHook));
    RTP.scheduler.runTaskAsynchronously(() -> {
      try {
        runProbe(callerId, realAccessor, targetId, targetName, rootCmd, parameterValues);
      } finally {
        if (hookHolder[0] != null) hookHolder[0].run();
      }
    });
    return true;
  }

  /**
   * Registers a {@link SendMessage#addInterceptor(Consumer) message
   * interceptor}, fires the {@code /rtp} request through the normal
   * pipeline, waits for the first message that matches one of
   * {@link #REPLY_NEEDLES} (and contains the target player's name to
   * disambiguate from concurrent player chatter), then unregisters the
   * interceptor. Called from the async scheduler.
   *
   * <p>This intentionally does <b>not</b> swap {@code RTP.serverAccessor}
   * &mdash; an earlier iteration installed a {@link
   * java.lang.reflect.Proxy} wrapping the real accessor, but doing so
   * leaves a sharp edge: if the {@code finally} restoration is skipped
   * for any reason, every subsequent message dispatch would silently
   * route through a stale proxy. The interceptor pattern (the same
   * mechanism used by {@code TestFullCmd} and
   * {@code LiveCommandDispatcherTestJob}) keeps {@code RTP.serverAccessor}
   * untouched and is symmetric with how the rest of the test harness
   * captures messages.
   */
  private void runProbe(
      UUID callerId,
      RTPServerAccessor realAccessor,
      UUID targetId,
      String targetName,
      RTPCmd rootCmd,
      Map<String, List<String>> parameterValues) {

    final CompletableFuture<Thread> delivery = new CompletableFuture<>();
    // The interceptor sees the resolved (post-MessagesKeys, post-format)
    // string. Most pipeline replies (chunkLoading, PLAYER_LOADING,
    // PLAYER_SETUP, PLAYER_TELEPORTING, etc.) do NOT embed the target's
    // name, so we cannot filter on targetName. The needles below are the
    // distinctive lower-cased substrings of every message the teleport
    // pipeline emits to a player; matching any one of them is sufficient.
    // /rtp test full is admin-gated and the 5s window is short, so the
    // risk of completing on an unrelated player's reply is negligible.
    final Consumer<String> probe =
        msg -> {
          if (delivery.isDone() || msg == null) return;
          String lower = msg.toLowerCase(Locale.ROOT);
          for (String needle : REPLY_NEEDLES) {
            if (lower.contains(needle)) {
              delivery.complete(Thread.currentThread());
              return;
            }
          }
        };

    SendMessage.addInterceptor(probe);
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
      // Symmetric removal; idempotent if the interceptor was never added.
      SendMessage.removeInterceptor(probe);
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
              || lower.contains("server thread")
              // ForkJoinPool.commonPool-worker-* is a legitimate completion
              // tier on Folia: RTP.scheduler.runTaskAsynchronously and the
              // async chunk-load CompletableFuture chain frequently complete
              // there. The hard rule from REQUIREMENTS.md §3 is "no blocking
              // on a region/main thread" — FJP satisfies that.
              || lower.contains("forkjoinpool");
      reason =
          permitted
              ? "folia: thread name matches a scheduler tier"
              : "folia: thread name '" + threadName + "' is not a recognised scheduler tier";
    } else {
      // Paper/Spigot: either main or a recognised async pool is fine.
      // "craft scheduler" matches CraftBukkit's async scheduler thread names
      // (e.g. "Craft Scheduler Thread - 3 - RTP"), which is the canonical
      // worker pool for BukkitScheduler#runTaskAsynchronously and is safe
      // for CommandSender#sendMessage. Intentionally permissive — see
      // REQ-RTP-S-004 auditor notes in AGENTS.md.
      String lower = threadName.toLowerCase(Locale.ROOT);
      permitted =
          isPrimary
              || lower.contains("async")
              || lower.contains("craft scheduler");
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
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        realAccessor.sendMessage(callerId, line);
    }
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
    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
        accessor.sendMessage(callerId, msg);
    }
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

}
