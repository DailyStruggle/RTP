package io.github.dailystruggle.rtp.common.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.RTPAPI;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the opt-in threaded ("server topology") model of
 * {@link MockRTPScheduler}: a single main lane plus a small async worker pool,
 * mirroring a native "1 main thread + async pool" server. This enables
 * asserting thread affinity - in particular that teleport-style work is enforced
 * onto the main thread (S-005-adjacent sync enforcement) while blocking waits
 * inside an async task resolve against a separate worker.
 */
class MockRTPSchedulerLaneTest {

  @TempDir File tempDir;
  private MockRTPServerAccessor accessor;
  private MockRTPScheduler scheduler;

  @BeforeEach
  void setUp() {
    accessor = RTPTestSetup.install(tempDir);
    scheduler = accessor.getMockScheduler().enableServerThreads();
  }

  @AfterEach
  void tearDown() {
    scheduler.shutdown();
  }

  @Test
  void syncWork_isEnforcedOntoMainLane() throws Exception {
    // The calling (test) thread is off-lane: the server does not consider it
    // the primary thread once the topology is active.
    assertFalse(accessor.isPrimaryThread(), "test thread is not the main lane");
    assertNull(scheduler.currentLane());

    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<MockRTPScheduler.Lane> lane = new AtomicReference<>();
    AtomicBoolean primary = new AtomicBoolean();

    // runTask models a teleport dispatch: it MUST land on the main lane.
    scheduler.runTask(
        () -> {
          lane.set(scheduler.currentLane());
          primary.set(accessor.isPrimaryThread());
          done.countDown();
        });

    assertTrue(done.await(5, TimeUnit.SECONDS), "main-lane task ran");
    assertEquals(MockRTPScheduler.Lane.MAIN, lane.get());
    assertTrue(primary.get(), "isPrimaryThread() is true on the main lane");
  }

  @Test
  void asyncWork_runsOffTheMainLane() throws Exception {
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<MockRTPScheduler.Lane> lane = new AtomicReference<>();
    AtomicBoolean primary = new AtomicBoolean(true);

    scheduler.runTaskAsynchronously(
        () -> {
          lane.set(scheduler.currentLane());
          primary.set(accessor.isPrimaryThread());
          done.countDown();
        });

    assertTrue(done.await(5, TimeUnit.SECONDS), "async task ran");
    assertEquals(MockRTPScheduler.Lane.ASYNC, lane.get());
    assertFalse(primary.get(), "async worker is not the primary thread");
  }

  @Test
  void nestedSyncFromAsync_hopsBackToMainLane() throws Exception {
    // Mirrors the real teleport pipeline: an async pre-filter that must marshal
    // the actual teleport back onto the main thread before touching the world.
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<MockRTPScheduler.Lane> asyncLane = new AtomicReference<>();
    AtomicReference<MockRTPScheduler.Lane> teleportLane = new AtomicReference<>();

    scheduler.runTaskAsynchronously(
        () -> {
          asyncLane.set(scheduler.currentLane());
          scheduler.runTask(
              () -> {
                teleportLane.set(scheduler.currentLane());
                done.countDown();
              });
        });

    assertTrue(done.await(5, TimeUnit.SECONDS), "nested sync task ran");
    assertEquals(MockRTPScheduler.Lane.ASYNC, asyncLane.get());
    assertEquals(
        MockRTPScheduler.Lane.MAIN, teleportLane.get(), "teleport must hop to the main lane");
  }

  @Test
  void blockingProbeInsideAsync_resolvesAgainstAnotherWorker() throws Exception {
    // The shape of `rtp test scheduler`'s async probe: a task that dispatches a
    // second async task and blocks on its result. The two-worker pool lets the
    // nested task complete on a free worker instead of dead-locking.
    java.util.concurrent.CompletableFuture<MockRTPScheduler.Lane> probe =
        new java.util.concurrent.CompletableFuture<>();

    scheduler.runTaskAsynchronously(
        () -> {
          java.util.concurrent.CompletableFuture<MockRTPScheduler.Lane> inner =
              new java.util.concurrent.CompletableFuture<>();
          scheduler.runTaskAsynchronously(() -> inner.complete(scheduler.currentLane()));
          try {
            probe.complete(inner.get(2, TimeUnit.SECONDS));
          } catch (Exception e) {
            probe.completeExceptionally(e);
          }
        });

    assertEquals(MockRTPScheduler.Lane.ASYNC, probe.get(5, TimeUnit.SECONDS));
  }

  @Test
  void serverIdCaller_stillNotPrimaryFromTestThread() {
    // Sanity: enabling the topology does not accidentally mark arbitrary
    // threads as primary.
    assertFalse(accessor.isPrimaryThread());
    assertTrue(RTPAPI.serverId != null);
  }
}
