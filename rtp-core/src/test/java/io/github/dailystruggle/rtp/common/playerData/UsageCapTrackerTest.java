package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the BetterRTP {@code LockAfter}-parity usage-cap tracker
 * ({@code lockAfterUses} / {@code lockAfterResetSeconds}).
 */
class UsageCapTrackerTest {

  @Test
  @DisplayName("cap of 0 disables the feature entirely")
  void disabledWhenCapZero() {
    UsageCapTracker t = new UsageCapTracker();
    UUID id = UUID.randomUUID();
    for (int i = 0; i < 100; i++) {
      t.recordSuccess(id, 0, 0, 1000L);
    }
    assertFalse(t.isLocked(id, 0, 0, 1000L), "cap 0 must never lock");
  }

  @Test
  @DisplayName("locks once the count reaches the cap")
  void locksAtCap() {
    UsageCapTracker t = new UsageCapTracker();
    UUID id = UUID.randomUUID();
    long cap = 3;
    long now = 1000L;
    assertFalse(t.isLocked(id, cap, 0, now), "fresh player is not locked");
    t.recordSuccess(id, cap, 0, now);
    assertFalse(t.isLocked(id, cap, 0, now), "1 use < cap");
    t.recordSuccess(id, cap, 0, now);
    assertFalse(t.isLocked(id, cap, 0, now), "2 uses < cap");
    t.recordSuccess(id, cap, 0, now);
    assertTrue(t.isLocked(id, cap, 0, now), "3 uses == cap -> locked");
  }

  @Test
  @DisplayName("reset window of 0 is a permanent (lifetime) cap")
  void neverResetsWhenWindowZero() {
    UsageCapTracker t = new UsageCapTracker();
    UUID id = UUID.randomUUID();
    long cap = 2;
    t.recordSuccess(id, cap, 0, 1000L);
    t.recordSuccess(id, cap, 0, 2000L);
    assertTrue(t.isLocked(id, cap, 0, 2000L), "at cap");
    // Far in the future: still locked because the window never resets.
    assertTrue(t.isLocked(id, cap, 0, 999_999_999L), "0 reset window never expires");
  }

  @Test
  @DisplayName("rolling window expiry unlocks and resets the counter")
  void rollingWindowResets() {
    UsageCapTracker t = new UsageCapTracker();
    UUID id = UUID.randomUUID();
    long cap = 2;
    long reset = 10_000L; // 10s window
    t.recordSuccess(id, cap, reset, 1000L); // window starts at 1000
    t.recordSuccess(id, cap, reset, 2000L);
    assertTrue(t.isLocked(id, cap, reset, 2000L), "at cap within window");
    // After the window elapses, the player is unlocked again.
    assertFalse(t.isLocked(id, cap, reset, 1000L + reset), "window elapsed -> unlocked");
    // And the counter restarts: one use after reset is below cap.
    t.recordSuccess(id, cap, reset, 1000L + reset);
    assertFalse(t.isLocked(id, cap, reset, 1000L + reset), "counter reset, 1 use < cap");
  }

  @Test
  @DisplayName("reset(id) clears a single player's usage")
  void resetClearsPlayer() {
    UsageCapTracker t = new UsageCapTracker();
    UUID id = UUID.randomUUID();
    long cap = 1;
    t.recordSuccess(id, cap, 0, 1000L);
    assertTrue(t.isLocked(id, cap, 0, 1000L));
    t.reset(id);
    assertFalse(t.isLocked(id, cap, 0, 1000L), "reset cleared the counter");
  }
}
