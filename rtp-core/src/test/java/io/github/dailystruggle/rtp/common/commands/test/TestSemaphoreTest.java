package io.github.dailystruggle.rtp.common.commands.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the per-caller {@link TestSemaphore} permit that serialises
 * {@code rtp test *} dispatch. Verifies acquire/release matching, force-release,
 * holder lookup, and argument validation.
 */
class TestSemaphoreTest {

  @AfterEach
  void clear() {
    TestSemaphore.clearAllForTesting();
  }

  @Test
  void tryAcquire_isExclusivePerCaller() {
    UUID caller = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(caller, "stress"));
    // A second acquire for the same caller (any subcommand) is refused.
    assertFalse(TestSemaphore.tryAcquire(caller, "scheduler"));
    // A different caller is unaffected.
    assertTrue(TestSemaphore.tryAcquire(UUID.randomUUID(), "stress"));
  }

  @Test
  void release_onlyWhenSubNameMatches() {
    UUID caller = UUID.randomUUID();
    TestSemaphore.tryAcquire(caller, "stress");

    // Wrong subcommand is a no-op.
    assertFalse(TestSemaphore.release(caller, "scheduler"));
    // Matching subcommand releases.
    assertTrue(TestSemaphore.release(caller, "stress"));
    // Already released is a no-op.
    assertFalse(TestSemaphore.release(caller, "stress"));
  }

  @Test
  void release_withNullSubName_releasesRegardlessOfHolder() {
    UUID caller = UUID.randomUUID();
    TestSemaphore.tryAcquire(caller, "stress");
    assertTrue(TestSemaphore.release(caller, null));
  }

  @Test
  void release_nullOwner_isFalse() {
    assertFalse(TestSemaphore.release(null, "stress"));
  }

  @Test
  void releaseOwned_dropsAnyHolder() {
    UUID caller = UUID.randomUUID();
    TestSemaphore.tryAcquire(caller, "stress");
    assertTrue(TestSemaphore.releaseOwned(caller));
    assertFalse(TestSemaphore.releaseOwned(caller));
    assertFalse(TestSemaphore.releaseOwned(null));
  }

  @Test
  void holderOf_reportsCurrentPermit() {
    UUID caller = UUID.randomUUID();
    assertNull(TestSemaphore.holderOf(caller));
    assertNull(TestSemaphore.holderOf(null));

    TestSemaphore.tryAcquire(caller, "scheduler");
    TestSemaphore.Holder h = TestSemaphore.holderOf(caller);
    assertNotNull(h);
    assertEquals(caller, h.ownerCallerId);
    assertEquals("scheduler", h.subName);
    assertTrue(h.acquiredAtNanos > 0L);
  }

  @Test
  void tryAcquire_rejectsNullArguments() {
    assertThrows(IllegalArgumentException.class, () -> TestSemaphore.tryAcquire(null, "x"));
    assertThrows(
        IllegalArgumentException.class, () -> TestSemaphore.tryAcquire(UUID.randomUUID(), null));
  }

  @Test
  void clearAllForTesting_wipesEveryPermit() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    TestSemaphore.tryAcquire(a, "x");
    TestSemaphore.tryAcquire(b, "y");
    TestSemaphore.clearAllForTesting();
    assertNull(TestSemaphore.holderOf(a));
    assertNull(TestSemaphore.holderOf(b));
  }
}
