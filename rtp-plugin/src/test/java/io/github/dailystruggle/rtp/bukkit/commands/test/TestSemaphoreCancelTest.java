package io.github.dailystruggle.rtp.bukkit.commands.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link ActiveTestJobs#cancelOwned(UUID)} releases the
 * per-caller {@link TestSemaphore} permit, so an operator can recover
 * a stuck test by issuing {@code rtp test cancel} (Phase 1.5 of
 * {@code RTP_TEST_ISOLATION_PLAN.md}).
 */
class TestSemaphoreCancelTest {

  @BeforeEach
  void clearBefore() {
    TestSemaphore.clearAllForTesting();
  }

  @AfterEach
  void clearAfter() {
    TestSemaphore.clearAllForTesting();
  }

  @Test
  @DisplayName("cancelOwned releases the permit even when no jobs were registered")
  void cancelOwnedReleasesPermitWithoutJobs() {
    UUID caller = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(caller, "stress"));
    assertNotNull(TestSemaphore.holderOf(caller));
    int cancelled = ActiveTestJobs.cancelOwned(caller);
    // No jobs were registered, so cancelled count is zero.
    assertTrue(cancelled == 0);
    // ...but the permit must be free now so the caller can re-issue.
    assertNull(TestSemaphore.holderOf(caller));
    assertTrue(TestSemaphore.tryAcquire(caller, "full"));
  }

  @Test
  @DisplayName("cancelOwned releases the permit when jobs were registered")
  void cancelOwnedReleasesPermitWithJobs() {
    UUID caller = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(caller, "stress"));
    ActiveTestJobs.register(caller, new ActiveTestJobs.Job("stress", () -> {}));
    int cancelled = ActiveTestJobs.cancelOwned(caller);
    assertTrue(cancelled >= 1);
    assertNull(TestSemaphore.holderOf(caller));
  }

  @Test
  @DisplayName("cancelOwned does not affect a different caller's permit")
  void cancelOwnedIsolatesByCaller() {
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    assertTrue(TestSemaphore.tryAcquire(a, "stress"));
    assertTrue(TestSemaphore.tryAcquire(b, "full"));
    ActiveTestJobs.cancelOwned(a);
    assertNull(TestSemaphore.holderOf(a));
    assertNotNull(TestSemaphore.holderOf(b));
  }
}
