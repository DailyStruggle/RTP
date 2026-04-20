package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.BaseRTPCmdImpl;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import org.jetbrains.annotations.Nullable;

/**
 * {@code rtp test async-chunk-load} &mdash; runtime probe that verifies a
 * single already-generated chunk can be obtained asynchronously through the
 * {@link RTPWorld#getChunkAt(int, int)} abstraction without blocking the
 * main server thread (REQ-RTP-S-005).
 *
 * <p>Motivation. REQ-RTP-S-005 forbids synchronous chunk loading from any
 * main-thread path. The existing unit-test coverage exercises the policy
 * via static analysis ({@code rtp-core} ArchUnit rules) and via the
 * {@code rtp-spigot} {@code AnvilPrefilterTest} suite. Neither of those
 * exercises the live Bukkit adapter on a running server. This probe
 * closes the gap: it calls {@code world.getChunkAt(cx,cz)} from the
 * caller's thread, captures the thread that resolves the future, and
 * asserts that the future did not complete inline on the primary thread.
 *
 * <p>Target chunk selection. The probe targets {@code (cx=0, cz=0)} of the
 * first world returned by {@link io.github.dailystruggle.rtp.api.server.RTPServerAccessor#getRTPWorlds()}.
 * This coordinate is conventionally pre-generated on every Bukkit world
 * (it contains the world spawn), so the probe verifies the
 * <i>async-dispatch</i> invariant &mdash; not generation correctness.
 *
 * <p>Safety compliance.
 * <ul>
 *   <li><b>S-005</b>: the probe dispatches through the platform adapter's
 *       async {@code getChunkAt} and never calls
 *       {@code World#getChunkAt(int,int)} directly. The assertion itself
 *       is the purpose of the probe.</li>
 *   <li><b>S-004</b>: every failure mode (no worlds registered, timeout,
 *       inline completion on the primary thread) produces a
 *       {@link Level#WARNING} log entry and a player-visible message.</li>
 *   <li><b>S-002</b>: the probe does not request a chunk ticket; it only
 *       warms the cache of an already-generated chunk. No ticket to
 *       leak.</li>
 * </ul>
 *
 * <p>See {@code docs/dev/RUNTIME_TEST_SUITE_PLAN.md} and
 * {@code docs/dev/TRACEABILITY.md} (REQ-RTP-S-005).
 */
public class TestAsyncChunkLoadCmd extends BaseRTPCmdImpl {

  /** Default wait for the chunk future to complete. Generous to survive a slow Paper async tick. */
  static final long DEFAULT_TIMEOUT_MS = 5_000L;

  public TestAsyncChunkLoadCmd(@Nullable CommandsAPICommand parent) {
    super(parent);
  }

  @Override
  public String name() {
    return "async-chunk-load";
  }

  @Override
  public String permission() {
    return "rtp.test";
  }

  @Override
  public String description() {
    return "verifies one generated chunk can be loaded asynchronously off the main thread (REQ-RTP-S-005)";
  }

  @Override
  public boolean onCommand(
      UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
    if (nextCommand != null) return true;

    List<RTPWorld<?>> worlds = RTP.serverAccessor.getRTPWorlds();
    if (worlds == null || worlds.isEmpty()) {
      Result r = Result.skipped("no RTPWorlds registered on the server accessor");
      emit(callerId, r);
      return true;
    }

    Result r = runProbe(worlds.get(0), 0, 0, DEFAULT_TIMEOUT_MS);
    emit(callerId, r);
    return true;
  }

  /**
   * Core probe. Extracted for unit testing so callers can inject an
   * in-memory {@link RTPWorld} whose {@code getChunkAt} completes on a
   * controlled executor.
   *
   * <p>Contract:
   * <ol>
   *   <li>Records the caller thread and the thread that resolves the
   *       {@link CompletableFuture} returned by
   *       {@link RTPWorld#getChunkAt(int, int)}.</li>
   *   <li>Waits up to {@code timeoutMs} for the future to complete.</li>
   *   <li>Passes iff the future completes within the timeout AND the
   *       completing thread is either (a) different from the caller
   *       thread, or (b) the future was already complete by the time
   *       {@code whenComplete} was attached but the caller thread is NOT
   *       the Bukkit primary thread (in which case inline completion is
   *       legal). Case (b) cannot violate S-005 because S-005 only
   *       forbids main-thread chunk I/O.</li>
   * </ol>
   *
   * @param world the world adapter to probe (must not be null)
   * @param cx target chunk X
   * @param cz target chunk Z
   * @param timeoutMs maximum time to wait for the future to complete
   * @return a structured {@link Result} describing the outcome
   */
  static Result runProbe(RTPWorld<?> world, int cx, int cz, long timeoutMs) {
    if (world == null) {
      return Result.skipped("null world");
    }

    Result r = new Result();
    r.worldName = safeName(world);
    r.cx = cx;
    r.cz = cz;
    r.callerThread = Thread.currentThread().getName();
    r.callerIsPrimary = isBukkitPrimaryThread();

    AtomicReference<String> completingThread = new AtomicReference<>(null);
    AtomicReference<Throwable> completionError = new AtomicReference<>(null);

    long t0 = System.nanoTime();
    CompletableFuture<Long> future;
    try {
      future = world.getChunkAt(cx, cz);
    } catch (Throwable t) {
      r.notes = "getChunkAt threw synchronously: " + t;
      return r;
    }
    if (future == null) {
      r.notes = "getChunkAt returned null future";
      return r;
    }

    // Capture whether the future was already done before our callback
    // was attached. An implementation that synchronously loaded the
    // chunk on the caller thread would return an already-completed
    // future; this is the tell-tale S-005 violation we care about.
    r.alreadyDoneOnReturn = future.isDone();

    future.whenComplete(
        (key, err) -> {
          completingThread.set(Thread.currentThread().getName());
          if (err != null) completionError.set(err);
        });

    try {
      Long key = future.get(timeoutMs, TimeUnit.MILLISECONDS);
      r.chunkKey = key;
    } catch (TimeoutException te) {
      r.notes = "timeout after " + timeoutMs + "ms";
      return r;
    } catch (Throwable t) {
      r.notes = "future failed: " + t;
      return r;
    } finally {
      r.elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
    }

    r.completingThread = completingThread.get();
    r.completionError = completionError.get();

    // Pass criterion: the future produced a non-null chunk key within the
    // timeout, it did NOT complete synchronously on the caller thread
    // while that caller was the Bukkit primary thread, and no error was
    // reported by whenComplete.
    //
    // If the caller is NOT the primary thread (typical for unit tests or
    // operators invoking the command from an async CommandsAPI
    // dispatch), inline completion is legal — S-005 only forbids
    // main-thread chunk loads. We therefore downgrade the violation
    // check to fire only when caller-is-primary AND completion was
    // observed inline on that same primary thread.
    boolean completedOffPrimaryWhenItMattered =
        !r.callerIsPrimary
            || !r.alreadyDoneOnReturn
            || (r.completingThread != null && !r.completingThread.equals(r.callerThread));

    r.pass =
        r.chunkKey != null
            && r.completionError == null
            && completedOffPrimaryWhenItMattered;
    return r;
  }

  private static boolean isBukkitPrimaryThread() {
    // Reflection so rtp-plugin's own test classpath (which does not always
    // have a running Bukkit) does not blow up — falls back to "not primary"
    // when Bukkit is unavailable, which is the correct answer for the
    // unit-test harness.
    try {
      Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
      Object result = bukkit.getMethod("isPrimaryThread").invoke(null);
      return result instanceof Boolean && (Boolean) result;
    } catch (Throwable ignored) {
      return false;
    }
  }

  private static String safeName(RTPWorld<?> world) {
    try {
      return world.name();
    } catch (Throwable t) {
      return "<unknown>";
    }
  }

  private void emit(UUID callerId, Result r) {
    String summary =
        "[RTP test/async-chunk-load] "
            + (r.skipped ? "skipped" : (r.pass ? "ok" : "FAIL"))
            + " world=" + r.worldName
            + " chunk=(" + r.cx + "," + r.cz + ")"
            + " callerThread=" + r.callerThread
            + " callerIsPrimary=" + r.callerIsPrimary
            + " completingThread=" + r.completingThread
            + " alreadyDoneOnReturn=" + r.alreadyDoneOnReturn
            + " elapsedMs=" + r.elapsedMs
            + (r.notes.isEmpty() ? "" : " notes=" + r.notes);

    RTP.serverAccessor.sendMessage(callerId, summary);
    // S-004: failing / skipped runs log at WARNING; a clean run at INFO.
    RTP.log(r.pass ? Level.INFO : Level.WARNING, summary);
  }

  /** Structured probe result. Package-private so the unit test can assert on it. */
  static final class Result {
    String worldName = "<none>";
    int cx;
    int cz;
    String callerThread = "<none>";
    boolean callerIsPrimary;
    String completingThread;
    boolean alreadyDoneOnReturn;
    Long chunkKey;
    Throwable completionError;
    long elapsedMs;
    boolean pass;
    boolean skipped;
    String notes = "";

    static Result skipped(String reason) {
      Result r = new Result();
      r.skipped = true;
      r.notes = reason;
      return r;
    }
  }
}
