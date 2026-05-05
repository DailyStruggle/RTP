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
 * {@code rtp test async-chunk-load} — runtime probe for REQ-RTP-S-005.
 * Calls {@link RTPWorld#getChunkAt(int, int)} on chunk {@code (0,0)} of the
 * first registered world, captures the resolving thread, and asserts the
 * future did not complete inline on the primary thread. Targets a chunk
 * conventionally pre-generated (world spawn) so this verifies async-dispatch,
 * not generation. Failure modes (no worlds, timeout, inline completion)
 * each emit a WARNING log and player message (S-004). No chunk ticket is
 * requested (S-002). See {@code RUNTIME_TEST_SUITE_PLAN.md} / {@code TRACEABILITY.md}.
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

    // Optional --samples N for a serial per-chunk latency harness
    // (REQ-RTP-S-005 regression-spotting — isolates per-stage cost from
    // the `scan` command's parallel/batched effective-throughput number).
    // N=1 preserves the original single-probe behaviour and log shape.
    int samples = clampSamples(parseFirstInt(parameterValues.get("samples"), 1));
    if (samples <= 1) {
      Result r = runProbe(worlds.get(0), 0, 0, DEFAULT_TIMEOUT_MS);
      emit(callerId, r);
      return true;
    }

    emitSamples(callerId, runSeries(worlds.get(0), samples, DEFAULT_TIMEOUT_MS));
    return true;
  }

  /**
   * Serially runs {@code samples} single-candidate probes along an
   * Archimedean-ish spiral around {@code (0,0)} and aggregates per-probe
   * elapsed times.
   *
   * <p>Serial — not batched — on purpose. The {@code scan} command's ~cps
   * figure is an aggregate of batched parallel loads; this harness
   * deliberately walks one candidate at a time so each sample's
   * {@code elapsedMs} is a genuine per-chunk wall-clock latency through the
   * full {@link RTPWorld#getChunkAt(int, int)} path (probe → view cache
   * publish → completed future). Each probe targets a distinct
   * {@code (cx,cz)} so repeated runs do not just re-hit a single warm
   * cache entry.
   */
  static SeriesResult runSeries(RTPWorld<?> world, int samples, long timeoutMs) {
    SeriesResult s = new SeriesResult();
    s.worldName = safeName(world);
    s.samples = samples;
    s.callerThread = Thread.currentThread().getName();
    s.callerIsPrimary = isBukkitPrimaryThread();

    long[] elapsed = new long[samples];
    int ok = 0;
    int fail = 0;
    int offPrimary = 0;
    // Simple radial walk — stride 2 chunks so we don't alias the spawn
    // chunk on every sample; kept bounded to ±(samples+4) to avoid
    // straying outside a typical generated region.
    for (int i = 0; i < samples; i++) {
      int cx = ((i & 1) == 0) ? (i / 2) : -((i / 2) + 1);
      int cz = ((i & 2) == 0) ? (i / 2) : -((i / 2) + 1);
      Result r = runProbe(world, cx, cz, timeoutMs);
      elapsed[i] = r.elapsedMs;
      if (r.pass) ok++;
      else fail++;
      if (r.completingThread != null && !r.completingThread.equals(r.callerThread)) offPrimary++;
      if (!r.notes.isEmpty() && s.firstNote.isEmpty()) s.firstNote = r.notes;
    }
    s.ok = ok;
    s.fail = fail;
    s.offCallerThread = offPrimary;
    s.p50Ms = percentile(elapsed, 50);
    s.p95Ms = percentile(elapsed, 95);
    s.p99Ms = percentile(elapsed, 99);
    s.minMs = min(elapsed);
    s.maxMs = max(elapsed);
    s.meanMs = mean(elapsed);
    return s;
  }

  private static int clampSamples(int n) {
    if (n < 1) return 1;
    // Hard cap — per-chunk latency harness is a diagnostic, not a benchmark
    // rig; anything above this belongs in a dedicated performance test.
    if (n > 256) return 256;
    return n;
  }

  private static int parseFirstInt(@Nullable List<String> values, int fallback) {
    if (values == null || values.isEmpty()) return fallback;
    try {
      return Integer.parseInt(values.get(0).trim());
    } catch (NumberFormatException ignored) {
      return fallback;
    }
  }

  private static long percentile(long[] arr, int pct) {
    if (arr.length == 0) return 0L;
    long[] copy = arr.clone();
    java.util.Arrays.sort(copy);
    int idx = (int) Math.ceil((pct / 100.0) * copy.length) - 1;
    if (idx < 0) idx = 0;
    if (idx >= copy.length) idx = copy.length - 1;
    return copy[idx];
  }

  private static long min(long[] arr) {
    long v = Long.MAX_VALUE;
    for (long x : arr) if (x < v) v = x;
    return v == Long.MAX_VALUE ? 0L : v;
  }

  private static long max(long[] arr) {
    long v = Long.MIN_VALUE;
    for (long x : arr) if (x > v) v = x;
    return v == Long.MIN_VALUE ? 0L : v;
  }

  private static long mean(long[] arr) {
    if (arr.length == 0) return 0L;
    long sum = 0L;
    for (long x : arr) sum += x;
    return sum / arr.length;
  }

  private void emitSamples(UUID callerId, SeriesResult s) {
    String summary =
        "[RTP test/async-chunk-load] "
            + (s.fail == 0 ? "ok" : "FAIL")
            + " world=" + s.worldName
            + " samples=" + s.samples
            + " ok=" + s.ok
            + " fail=" + s.fail
            + " offCallerThread=" + s.offCallerThread
            + " callerThread=" + s.callerThread
            + " callerIsPrimary=" + s.callerIsPrimary
            + " minMs=" + s.minMs
            + " p50Ms=" + s.p50Ms
            + " p95Ms=" + s.p95Ms
            + " p99Ms=" + s.p99Ms
            + " maxMs=" + s.maxMs
            + " meanMs=" + s.meanMs
            + (s.firstNote.isEmpty() ? "" : " firstNote=" + s.firstNote);
    RTP.serverAccessor.sendMessage(callerId, summary);
    RTP.log(s.fail == 0 ? Level.INFO : Level.WARNING, summary);
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

    if (!callerId.equals(io.github.dailystruggle.rtp.api.RTPAPI.serverId)) {
      RTP.serverAccessor.sendMessage(callerId, summary);
    }
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

  /**
   * Aggregated result of a serial {@code --samples N} latency harness run.
   * Package-private so the unit test can assert on the percentile values.
   */
  static final class SeriesResult {
    String worldName = "<none>";
    int samples;
    int ok;
    int fail;
    int offCallerThread;
    String callerThread = "<none>";
    boolean callerIsPrimary;
    long minMs;
    long p50Ms;
    long p95Ms;
    long p99Ms;
    long maxMs;
    long meanMs;
    String firstNote = "";
  }
}
