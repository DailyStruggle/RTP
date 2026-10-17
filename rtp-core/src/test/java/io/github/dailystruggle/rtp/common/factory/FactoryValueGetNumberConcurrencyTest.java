package io.github.dailystruggle.rtp.common.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for {@link FactoryValue#getNumber} CME race: pre-fix unconditional cache-back races fail-fast iterators
 * on {@code toString}/{@code toYAML}/{@code setData}. Asserts post-fix:
 * Number-typed reads never write back; String-typed reads cache once under
 * {@code synchronized(data)}; {@code getData()} returns a snapshot.
 */
class FactoryValueGetNumberConcurrencyTest {

  private enum K {
    NUMERIC,        // pre-seeded as Long → hot path, never writes back
    STRINGY,        // pre-seeded as String → first read parses + writes
    OTHER_NUMERIC,
    OTHER_STRINGY,
    BOOL_TRUE,      // pre-seeded as Boolean true → coerces to 1
    BOOL_FALSE,     // pre-seeded as Boolean false → coerces to 0
    AUTO_STR        // pre-seeded as "auto"
  }

  /** Concrete subclass exposing only the inherited surface we test. */
  private static class Probe extends FactoryValue<K> {
    Probe() {
      super(K.class, "factoryvalue-cme-test");
      data.put(K.NUMERIC, 42L);
      data.put(K.STRINGY, "3.14");
      data.put(K.OTHER_NUMERIC, 7L);
      data.put(K.OTHER_STRINGY, "1.5");
      data.put(K.BOOL_TRUE, Boolean.TRUE);
      data.put(K.BOOL_FALSE, Boolean.FALSE);
      data.put(K.AUTO_STR, "auto");
    }
  }

  @Test
  void getNumber_onAutoString_resolvesCorrectlyWithoutThrowing() {
    Probe probe = new Probe();
    // generic key with 'auto' falls back to def without throwing or logging SEVERE
    assertEquals(5L, probe.getNumber(K.AUTO_STR, 5L).longValue(),
        "generic key with 'auto' must resolve to default without throwing");
  }

  /**
   * Tolerant boolean -> int coercion: a legacy YAML {@code true}/{@code false}
   * written for a knob that is now numeric (e.g. {@code uniquePlacements})
   * resolves to 1/0 instead of throwing the pre-fix {@code NaN}
   * {@link IllegalArgumentException}.
   */
  @Test
  void getNumber_onBooleanValue_coercesToOneOrZero() {
    Probe probe = new Probe();
    assertEquals(1, probe.getNumber(K.BOOL_TRUE, 0).intValue(),
        "boolean true must coerce to 1");
    assertEquals(0, probe.getNumber(K.BOOL_FALSE, 1).intValue(),
        "boolean false must coerce to 0");
  }

  /**
   * Concurrency test: races readers against {@code toString} and {@code setData(EnumMap)}.
   * Verifies no CME occurs during snapshot reads and synchronized cache-back.
   */
  @Test
  void concurrent_getNumber_and_toString_doNotThrow() throws Exception {
    Probe probe = new Probe();
    int readers = 16;
    int iterations = 5_000;
    ExecutorService pool = Executors.newFixedThreadPool(readers + 2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();

    Runnable readerTask = () -> {
      try {
        start.await();
        for (int i = 0; i < iterations; i++) {
          // Hot-path read (Number-typed): post-fix this performs no write.
          probe.getNumber(K.NUMERIC, 0L);
          probe.getNumber(K.OTHER_NUMERIC, 0L);
          // Cache-back read (String-typed): post-fix the first call
          // parses+writes under synchronized(data); subsequent calls hit the
          // hot path because the cached value is now Number-typed.
          probe.getNumber(K.STRINGY, 0.0);
          probe.getNumber(K.OTHER_STRINGY, 0.0);
        }
      } catch (Throwable t) {
        firstFailure.compareAndSet(null, t);
      }
    };

    Runnable toStringWalker = () -> {
      try {
        start.await();
        for (int i = 0; i < iterations; i++) {
          // toString iterates getData() snapshot, must never throw CME.
          String s = probe.toString();
          // Touch the result so JIT cannot elide.
          if (s == null) throw new AssertionError("toString returned null");
        }
      } catch (Throwable t) {
        firstFailure.compareAndSet(null, t);
      }
    };

    Runnable setDataWriter = () -> {
      try {
        start.await();
        EnumMap<K, Object> swap = new EnumMap<>(K.class);
        swap.put(K.NUMERIC, 42L);
        swap.put(K.STRINGY, "3.14");
        swap.put(K.OTHER_NUMERIC, 7L);
        swap.put(K.OTHER_STRINGY, "1.5");
        for (int i = 0; i < iterations / 4; i++) {
          probe.setData(swap);
        }
      } catch (Throwable t) {
        firstFailure.compareAndSet(null, t);
      }
    };

    for (int i = 0; i < readers; i++) pool.submit(readerTask);
    pool.submit(toStringWalker);
    pool.submit(setDataWriter);
    start.countDown();
    pool.shutdown();
    boolean done = pool.awaitTermination(30, TimeUnit.SECONDS);
    assertTrue(done, "concurrent test did not finish within 30s");
    assertNull(firstFailure.get(),
        "concurrent getNumber/toString/setData must not throw, but observed: "
            + firstFailure.get());
  }

  /**
   * Stress-test stall regression: ~1,400 selection workers queued on the FactoryValue monitor
   * because every knob read took it. Reads must complete while a writer holds the lock.
   */
  @Test
  @Timeout(value = 10, unit = TimeUnit.SECONDS)
  @DisplayName("REQ-RTP-S-004: getNumber/getData do not block while a writer holds the data lock")
  void reads_areLockFree_whileWriterHoldsLock() throws Exception {
    Probe probe = new Probe();
    java.lang.reflect.Field f = FactoryValue.class.getDeclaredField("dataLock");
    f.setAccessible(true);
    Object lock = f.get(probe);

    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Thread writer = new Thread(() -> {
      synchronized (lock) {
        held.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }, "factoryvalue-lock-holder");
    writer.start();
    assertTrue(held.await(5, TimeUnit.SECONDS), "writer failed to take the lock");

    ExecutorService reader = Executors.newSingleThreadExecutor();
    try {
      Number n = reader.submit(() -> probe.getNumber(K.NUMERIC, 0L)).get(2, TimeUnit.SECONDS);
      assertEquals(42L, n.longValue());
      assertEquals(42L, reader.submit(() -> probe.getData().get(K.NUMERIC)).get(2, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      writer.join(5_000);
      reader.shutdownNow();
    }
  }

  /** Copy-on-write: a snapshot taken before {@code set} is never mutated by it. */
  @Test
  void set_publishesNewMap_withoutMutatingPriorSnapshot() {
    Probe probe = new Probe();
    java.util.EnumMap<K, Object> before = probe.data;
    probe.set(K.NUMERIC, 99L);
    assertEquals(42L, before.get(K.NUMERIC), "published maps must be immutable");
    assertEquals(99L, probe.getNumber(K.NUMERIC, 0L).longValue());
  }

  /**
   * Sanity: {@code getNumber} on a Number-typed value returns the same
   * boxed instance and does not mutate the underlying map - pre-fix this
   * write-back was the source of the CME race; post-fix the hot path is a
   * pure read.
   */
  @Test
  void getNumber_onNumberValue_isPureRead() {
    Probe probe = new Probe();
    EnumMap<K, Object> before = probe.getData();
    Number n = probe.getNumber(K.NUMERIC, 0L);
    EnumMap<K, Object> after = probe.getData();
    assertTrue(n instanceof Long && ((Long) n) == 42L,
        "expected the seeded Long value, got " + n);
    assertTrue(before.get(K.NUMERIC).equals(after.get(K.NUMERIC)),
        "Number-typed read must not mutate the cached value");
  }

  /**
   * Sanity: {@code getNumber} on a String-typed value parses to a
   * {@link Number} and caches it back, so a second read takes the hot path.
   * Verifies the post-fix cache-back is still effective for the legitimate
   * String→Number transition (the optimization the original code intended).
   */
  @Test
  void getNumber_onStringValue_cachesBackOnce() {
    Probe probe = new Probe();
    Object raw = probe.getData().get(K.STRINGY);
    assertTrue(raw instanceof String,
        "fixture invariant: STRINGY starts as String, was " + raw);
    Number first = probe.getNumber(K.STRINGY, 0.0);
    Object cached = probe.getData().get(K.STRINGY);
    assertTrue(cached instanceof Number,
        "post-parse value must be cached as Number, got " + cached);
    assertTrue(first.doubleValue() == ((Number) cached).doubleValue(),
        "cached value must equal the parse result");
  }
}
