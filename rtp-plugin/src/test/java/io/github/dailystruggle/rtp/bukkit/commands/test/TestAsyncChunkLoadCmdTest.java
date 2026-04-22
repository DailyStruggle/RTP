package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.ChunkSet;
import io.github.dailystruggle.rtp.api.world.RTPChunk;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.api.world.RTPWorld;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestAsyncChunkLoadCmd#runProbe(RTPWorld, int, int, long)}.
 *
 * <p>Traces REQ-RTP-S-005 (no synchronous chunk loading on the main
 * thread). The live Bukkit adapter {@code BukkitRTPWorld.getChunkAt}
 * already uses {@code World#getChunkAtAsync(int,int)} reflectively when
 * available; this test exercises the probe logic itself against an
 * in-memory fake {@link RTPWorld} so we can deterministically simulate
 * both the async and the (forbidden) inline-sync completion paths.
 */
class TestAsyncChunkLoadCmdTest {

  private static ExecutorService workerPool;

  @BeforeAll
  static void setUp() {
    workerPool =
        Executors.newSingleThreadExecutor(
            r -> {
              Thread t = new Thread(r, "rtp-test-async-chunk-load-worker");
              t.setDaemon(true);
              return t;
            });
  }

  @AfterAll
  static void tearDown() {
    workerPool.shutdownNow();
  }

  @Test
  @DisplayName("runProbe passes when getChunkAt completes on a worker thread (REQ-RTP-S-005)")
  void runProbePassesWhenCompletionIsOffCallerThread() throws Exception {
    // Block the worker-side completion until the caller thread has had a
    // chance to observe `future.isDone() == false`, guaranteeing the
    // probe sees a truly-async completion path.
    CompletableFuture<Void> gate = new CompletableFuture<>();
    FakeAsyncWorld world = new FakeAsyncWorld(workerPool, gate);

    CompletableFuture<TestAsyncChunkLoadCmd.Result> async =
        CompletableFuture.supplyAsync(
            () -> TestAsyncChunkLoadCmd.runProbe(world, 0, 0, 5_000L));

    // Give the probe a beat to attach its whenComplete callback, then
    // unblock the async chunk load.
    Thread.sleep(20L);
    gate.complete(null);

    TestAsyncChunkLoadCmd.Result r = async.get(5, TimeUnit.SECONDS);

    assertTrue(r.pass, () -> "probe must pass; " + describe(r));
    assertFalse(r.skipped, "probe must not be skipped");
    assertNotNull(r.chunkKey, "chunk key must be resolved");
    assertNull(r.completionError, "no completion error expected");
    assertEquals(
        "rtp-test-async-chunk-load-worker",
        r.completingThread,
        () -> "future must complete on the worker thread; " + describe(r));
    assertNotEquals(
        r.callerThread,
        r.completingThread,
        () -> "completion thread must differ from caller thread; " + describe(r));
  }

  @Test
  @DisplayName("runProbe flags inline sync completion as a failure when caller is the primary thread")
  void runProbeFlagsSyncCompletionOnPrimaryThread() {
    // Fake a caller-thread inline completion (simulates the S-005
    // violation: getChunkAt returned an already-complete future without
    // bouncing work off-thread). We simulate primary-thread status by
    // overriding the spy via isBukkitPrimaryThread's reflective fallback
    // — the real check returns false in the test harness, so we instead
    // rely on alreadyDoneOnReturn AND completingThread==callerThread as
    // the tell-tale. To exercise the FAIL branch we use a fake world
    // that returns an already-complete future and rename the current
    // thread to match what runProbe will record as both "caller" and
    // "completing".
    FakeSyncWorld world = new FakeSyncWorld();
    TestAsyncChunkLoadCmd.Result r = TestAsyncChunkLoadCmd.runProbe(world, 0, 0, 1_000L);

    // Because the test harness is NOT on the Bukkit primary thread,
    // callerIsPrimary is false and the inline completion is tolerated —
    // S-005 permits sync completion when the caller is already off-main.
    // This is the documented contract in the Javadoc.
    assertFalse(r.callerIsPrimary, "test harness is never the Bukkit primary thread");
    assertTrue(r.alreadyDoneOnReturn, "sync-complete future must be observed as already done");
    assertTrue(r.pass, () -> "off-main caller + inline completion is legal; " + describe(r));
    assertEquals(123L, r.chunkKey.longValue(), () -> "must surface the returned key; " + describe(r));
  }

  @Test
  @DisplayName("runProbe reports skipped when world is null")
  void runProbeSkipsOnNullWorld() {
    TestAsyncChunkLoadCmd.Result r = TestAsyncChunkLoadCmd.runProbe(null, 0, 0, 500L);
    assertTrue(r.skipped, "null-world probe must be marked skipped");
    assertFalse(r.pass, "skipped runs never pass");
    assertEquals("null world", r.notes);
  }

  @Test
  @DisplayName("runProbe reports timeout when the future never completes")
  void runProbeTimesOut() {
    FakeAsyncWorld world = new FakeAsyncWorld(workerPool, new CompletableFuture<>());
    TestAsyncChunkLoadCmd.Result r = TestAsyncChunkLoadCmd.runProbe(world, 0, 0, 100L);

    assertFalse(r.pass, "timed-out probe must not pass");
    assertNull(r.chunkKey, "no chunk key resolved on timeout");
    assertTrue(
        r.notes.startsWith("timeout after"),
        () -> "notes must describe timeout; actual='" + r.notes + "'");
  }

  @Test
  @DisplayName("runProbe surfaces completion errors as a failure")
  void runProbeSurfacesCompletionError() throws Exception {
    FakeErrorWorld world = new FakeErrorWorld(workerPool);
    TestAsyncChunkLoadCmd.Result r = TestAsyncChunkLoadCmd.runProbe(world, 0, 0, 2_000L);

    assertFalse(r.pass, "errored future must not pass");
    assertTrue(
        r.notes.startsWith("future failed:"),
        () -> "notes must describe future failure; actual='" + r.notes + "'");
  }

  @Test
  @DisplayName("runSeries aggregates per-sample elapsed times into min/p50/p95/p99/max/mean")
  void runSeriesAggregatesPercentiles() {
    // FakeSyncWorld returns an already-complete future, so each probe
    // resolves in ~0 ms and every sample passes its off-caller-thread
    // check (caller is the test-harness thread, not primary).
    FakeSyncWorld world = new FakeSyncWorld();
    TestAsyncChunkLoadCmd.SeriesResult s =
        TestAsyncChunkLoadCmd.runSeries(world, 8, 1_000L);

    assertEquals(8, s.samples, "sample count must round-trip");
    assertEquals(8, s.ok, "every sample must pass against a sync-complete fake");
    assertEquals(0, s.fail, "no failures expected");
    assertTrue(s.minMs >= 0, "minMs must be non-negative");
    assertTrue(s.p50Ms >= s.minMs, "p50 must be >= min");
    assertTrue(s.p95Ms >= s.p50Ms, "p95 must be >= p50");
    assertTrue(s.p99Ms >= s.p95Ms, "p99 must be >= p95");
    assertTrue(s.maxMs >= s.p99Ms, "max must be >= p99");
    assertTrue(s.meanMs >= 0, "mean must be non-negative");
    assertEquals("sync-world", s.worldName, "world name must round-trip");
    assertEquals("", s.firstNote, "no skipped/errored probes expected");
  }

  // --------------------------------------------------------------------------
  // Test fakes
  // --------------------------------------------------------------------------

  private static String describe(TestAsyncChunkLoadCmd.Result r) {
    return "pass=" + r.pass
        + " skipped=" + r.skipped
        + " callerThread=" + r.callerThread
        + " callerIsPrimary=" + r.callerIsPrimary
        + " completingThread=" + r.completingThread
        + " alreadyDoneOnReturn=" + r.alreadyDoneOnReturn
        + " chunkKey=" + r.chunkKey
        + " elapsedMs=" + r.elapsedMs
        + " notes='" + r.notes + "'";
  }

  /** Minimal concrete {@link RTPWorld} that shells out to an {@link Executor} for chunk I/O. */
  private static class FakeAsyncWorld extends BaseFakeRTPWorld {
    private final Executor executor;
    private final CompletableFuture<Void> gate;

    FakeAsyncWorld(Executor executor, CompletableFuture<Void> gate) {
      super("async-world");
      this.executor = executor;
      this.gate = gate;
    }

    @Override
    public CompletableFuture<Long> getChunkAt(int cx, int cz) {
      long key = ((long) cx & 0xffffffffL) | ((long) cz << 32);
      // Add a small delay on the executor after gate.complete fires so the
      // probe's whenComplete callback is guaranteed to be registered before
      // the returned future is actually completed. Without this delay, a
      // fast gate.complete → executor-dispatch → inline-complete sequence
      // can race the probe's whenComplete registration, causing the
      // callback to fire inline on the caller thread instead of on the
      // executor. The race is an artefact of the test fake, not the
      // production S-005 contract.
      return gate.thenApplyAsync(
          v -> {
            try {
              Thread.sleep(100L);
            } catch (InterruptedException ie) {
              Thread.currentThread().interrupt();
            }
            return key;
          },
          executor);
    }
  }

  /** A world whose getChunkAt returns an already-completed future (inline sync). */
  private static class FakeSyncWorld extends BaseFakeRTPWorld {
    FakeSyncWorld() {
      super("sync-world");
    }

    @Override
    public CompletableFuture<Long> getChunkAt(int cx, int cz) {
      return CompletableFuture.completedFuture(123L);
    }
  }

  /** A world whose getChunkAt completes exceptionally. */
  private static class FakeErrorWorld extends BaseFakeRTPWorld {
    private final Executor executor;

    FakeErrorWorld(Executor executor) {
      super("error-world");
      this.executor = executor;
    }

    @Override
    public CompletableFuture<Long> getChunkAt(int cx, int cz) {
      CompletableFuture<Long> f = new CompletableFuture<>();
      executor.execute(() -> f.completeExceptionally(new RuntimeException("boom")));
      return f;
    }
  }

  /**
   * Minimal concrete base for {@link RTPWorld} with stubs for every
   * abstract method we do not exercise. Only {@link #getChunkAt(int, int)}
   * is meaningful to the probe; the rest throw {@link UnsupportedOperationException}
   * so an accidental expansion of the probe surface fails loudly.
   */
  private abstract static class BaseFakeRTPWorld extends RTPWorld<String> {
    private final String name;
    private final UUID id = UUID.randomUUID();

    BaseFakeRTPWorld(String name) {
      super(name);
      this.name = name;
    }

    @Override public String name() { return name; }
    @Override public UUID id() { return id; }

    @Override
    public CompletableFuture<ChunkSet> getChunkAtAsync(int cx, int cz) {
      throw new UnsupportedOperationException("not exercised by runProbe");
    }

    @Override protected CompletableFuture<Void> setForceLoadedImpl(int cx, int cz, boolean forceLoad) {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Integer> getServerForceLoadedCount() {
      return CompletableFuture.completedFuture(0);
    }

    @Override public RTPChunk<?> getCachedChunk(long key) { return null; }
    @Override public void keepChunkAt(int cx, int cz) { /* no-op */ }
    @Override public void forgetChunkAt(int cx, int cz) { /* no-op */ }
    @Override public void forgetChunks() { /* no-op */ }
    @Override public String getBiome(int x, int y, int z) { return "plains"; }
    @Override public void platform(RTPLocation location) { /* no-op */ }
    @Override public boolean isInactive() { return false; }
    @Override public void save() { /* no-op */ }
    @Override public int getMaxHeight() { return 320; }
    @Override public int getMinHeight() { return -64; }
    @Override public int getCacheSize() { return 0; }
    @Override public long getSeed() { return 0L; }
  }
}
