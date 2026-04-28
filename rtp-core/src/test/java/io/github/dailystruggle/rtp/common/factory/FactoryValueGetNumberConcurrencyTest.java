package io.github.dailystruggle.rtp.common.factory;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the {@link FactoryValue} concurrent-config-read race
 * that surfaced as a {@link java.util.ConcurrentModificationException} during
 * pregen with the probe-first pipeline (BIOME_LOOKUP_PERF_PLAN.md PR-9+).
 *
 * <p>Pre-fix, {@link FactoryValue#getNumber} unconditionally wrote the parsed
 * value back to the underlying {@link EnumMap} on every call. Multiple pregen
 * workers reading {@code vert.minY()} / {@code vert.maxY()} in parallel raced
 * the resulting {@code data.put} against fail-fast iterators in
 * {@code toString} / {@code toYAML} / {@code setData(EnumMap)}, producing CME
 * in production logs (see commit message and the gauge-line transcript on
 * 2026-04-26).</p>
 *
 * <p>Post-fix invariants asserted here:
 * <ul>
 *   <li>{@code getNumber} on a {@link Number}-typed value never writes back
 *       (hot path), so concurrent readers do not contend at all.</li>
 *   <li>{@code getNumber} on a {@link String}-typed value writes back once,
 *       under {@code synchronized (data)}, so even racing first-parse calls
 *       cannot trip a concurrent {@code toString} / {@code getData} iterator.</li>
 *   <li>{@code setData(EnumMap)} is coherent with concurrent reads: snapshot
 *       semantics are preserved by {@code getData()}'s synchronized clone.</li>
 * </ul>
 *
 * <p>The test does not reference a specific REQ-RTP-* — the race is a
 * cross-cutting concern affecting any safety-critical thread reading config
 * values (relates to S-005 in spirit: a CME on the probe synchronous prefix
 * silently degraded one chunk to UNKNOWN, masking a real thread-safety bug
 * behind the existing UNKNOWN→full-load fallback).</p>
 */
class FactoryValueGetNumberConcurrencyTest {

  private enum K {
    NUMERIC,        // pre-seeded as Long → hot path, never writes back
    STRINGY,        // pre-seeded as String → first read parses + writes
    OTHER_NUMERIC,
    OTHER_STRINGY
  }

  /** Concrete subclass exposing only the inherited surface we test. */
  private static class Probe extends FactoryValue<K> {
    Probe() {
      super(K.class, "factoryvalue-cme-test");
      data.put(K.NUMERIC, 42L);
      data.put(K.STRINGY, "3.14");
      data.put(K.OTHER_NUMERIC, 7L);
      data.put(K.OTHER_STRINGY, "1.5");
    }
  }

  /**
   * Race many readers against {@code toString} (fail-fast iterator on the
   * underlying {@code EnumMap}) plus a writer that invokes
   * {@code setData(EnumMap)} repeatedly. Pre-fix this would intermittently
   * throw CME from the {@code toString} iterator the moment {@code getNumber}
   * wrote back its cached parse on the {@code STRINGY} key. Post-fix the
   * cache-back is conditional and synchronized, and {@code toString} iterates
   * a {@link FactoryValue#getData()} snapshot, so no iterator ever sees a
   * concurrent modification.
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
   * Sanity: {@code getNumber} on a Number-typed value returns the same
   * boxed instance and does not mutate the underlying map — pre-fix this
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
