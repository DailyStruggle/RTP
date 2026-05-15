package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.commands.test.TestSemaphore;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TestSemaphore}: per-caller permit, mismatched
 * release no-ops, force-release for cancel hand-off, and per-caller
 * granularity (Phase 1.2 of {@code RTP_TEST_ISOLATION_PLAN.md}).
 */
class TestSemaphoreTest {

  @BeforeEach
  void clearBefore() {
    TestSemaphore.clearAllForTesting();
  }

  @AfterEach
  void clearAfter() {
    TestSemaphore.clearAllForTesting();
  }

  @Test
  @DisplayName("acquire/release round-trip: permit returns to free state")
  void acquireReleaseRoundTrip() {
    UUID caller = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(caller, "stress"));
    TestSemaphore.Holder h = TestSemaphore.holderOf(caller);
    assertNotNull(h);
    assertEquals("stress", h.subName);
    assertEquals(caller, h.ownerCallerId);
    assertTrue(TestSemaphore.release(caller, "stress"));
    assertNull(TestSemaphore.holderOf(caller));
    // re-acquire works after release
    assertTrue(TestSemaphore.tryAcquire(caller, "full"));
  }

  @Test
  @DisplayName("second acquire by same caller while held returns false")
  void secondAcquireSameCallerFails() {
    UUID caller = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(caller, "stress"));
    assertFalse(TestSemaphore.tryAcquire(caller, "full"));
    // holder is still the original
    assertEquals("stress", TestSemaphore.holderOf(caller).subName);
  }

  @Test
  @DisplayName("acquire by a different caller succeeds (per-caller granularity)")
  void differentCallerCanAcquire() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(a, "stress"));
    assertTrue(TestSemaphore.tryAcquire(b, "stress"));
    assertEquals("stress", TestSemaphore.holderOf(a).subName);
    assertEquals("stress", TestSemaphore.holderOf(b).subName);
  }

  @Test
  @DisplayName("release with wrong owner is a no-op")
  void releaseWrongOwnerNoOp() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(a, "stress"));
    assertFalse(TestSemaphore.release(b, "stress"));
    assertNotNull(TestSemaphore.holderOf(a));
  }

  @Test
  @DisplayName("release with wrong subName is a no-op")
  void releaseWrongSubNameNoOp() {
    UUID a = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(a, "stress"));
    assertFalse(TestSemaphore.release(a, "full"));
    assertNotNull(TestSemaphore.holderOf(a));
    // correct subName still works
    assertTrue(TestSemaphore.release(a, "stress"));
  }

  @Test
  @DisplayName("release with null subName matches any holder (force release on cancel path)")
  void releaseNullSubNameMatches() {
    UUID a = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(a, "stress"));
    assertTrue(TestSemaphore.release(a, null));
    assertNull(TestSemaphore.holderOf(a));
  }

  @Test
  @DisplayName("releaseOwned frees regardless of subName")
  void releaseOwnedForceFrees() {
    UUID a = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(a, "stress"));
    assertTrue(TestSemaphore.releaseOwned(a));
    assertNull(TestSemaphore.holderOf(a));
    // double-release is a no-op
    assertFalse(TestSemaphore.releaseOwned(a));
  }

  @Test
  @DisplayName("null arguments are rejected at acquire time")
  void nullArgsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> TestSemaphore.tryAcquire(null, "stress"));
    assertThrows(IllegalArgumentException.class,
        () -> TestSemaphore.tryAcquire(UUID.randomUUID(), null));
  }
}
