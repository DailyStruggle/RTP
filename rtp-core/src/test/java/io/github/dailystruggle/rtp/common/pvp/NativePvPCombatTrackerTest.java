package io.github.dailystruggle.rtp.common.pvp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.hooks.PvPCombatAction;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for the native PvP combat fallback (ROADMAP Tier 2 PvP gate).
 *
 * <p>Pure logic only - no server bootstrap - so it exercises the combat-tag
 * window, victim/aggressor stamping, expiry pruning, and the {@link PvPCombatAction}
 * config parsing in isolation.
 */
class NativePvPCombatTrackerTest {

  private static final long TAG = 15_000L; // 15s window

  @Test
  @DisplayName("freshly stamped player is in combat within the window")
  void stampedPlayerInCombat() {
    NativePvPCombatTracker t = new NativePvPCombatTracker();
    UUID p = UUID.randomUUID();
    long now = 1_000_000L;
    t.stamp(p, now);
    assertTrue(t.isInCombat(p, now, TAG));
    assertTrue(t.isInCombat(p, now + TAG - 1, TAG));
  }

  @Test
  @DisplayName("combat tag expires at the window boundary and the entry is pruned")
  void tagExpiresAndPrunes() {
    NativePvPCombatTracker t = new NativePvPCombatTracker();
    UUID p = UUID.randomUUID();
    long now = 5_000_000L;
    t.stamp(p, now);
    assertEquals(1, t.trackedCount());
    assertFalse(t.isInCombat(p, now + TAG, TAG), "boundary is exclusive of in-combat");
    assertEquals(0, t.trackedCount(), "expired entry pruned on read");
  }

  @Test
  @DisplayName("unknown / null player and non-positive window are never in combat")
  void edgeCases() {
    NativePvPCombatTracker t = new NativePvPCombatTracker();
    UUID p = UUID.randomUUID();
    long now = 42L;
    assertFalse(t.isInCombat(null, now, TAG));
    assertFalse(t.isInCombat(p, now, TAG), "never stamped");
    t.stamp(p, now);
    assertFalse(t.isInCombat(p, now, 0L), "zero window disables");
    assertFalse(t.isInCombat(p, now, -1L), "negative window disables");
  }

  @Test
  @DisplayName("clear and clearAll forget combat state")
  void clearing() {
    NativePvPCombatTracker t = new NativePvPCombatTracker();
    UUID a = UUID.randomUUID();
    UUID b = UUID.randomUUID();
    long now = 7L;
    t.stamp(a, now);
    t.stamp(b, now);
    t.clear(a);
    assertFalse(t.isInCombat(a, now, TAG));
    assertTrue(t.isInCombat(b, now, TAG));
    t.clearAll();
    assertFalse(t.isInCombat(b, now, TAG));
    assertEquals(0, t.trackedCount());
  }

  @Test
  @DisplayName("remainingMillis decays to zero past the window")
  void remaining() {
    NativePvPCombatTracker t = new NativePvPCombatTracker();
    UUID p = UUID.randomUUID();
    long now = 100L;
    t.stamp(p, now);
    assertEquals(TAG, t.remainingMillis(p, now, TAG));
    assertEquals(TAG - 5000, t.remainingMillis(p, now + 5000, TAG));
    assertEquals(0L, t.remainingMillis(p, now + TAG + 1, TAG));
    assertEquals(0L, t.remainingMillis(UUID.randomUUID(), now, TAG));
  }

  @Test
  @DisplayName("PvPCombatAction.fromConfig is case-insensitive and fails safe to DENY")
  void actionParsing() {
    assertEquals(PvPCombatAction.ALLOW, PvPCombatAction.fromConfig("allow"));
    assertEquals(PvPCombatAction.DENY, PvPCombatAction.fromConfig("DENY"));
    assertEquals(PvPCombatAction.DELAY, PvPCombatAction.fromConfig("  Delay "));
    assertEquals(PvPCombatAction.CANCEL, PvPCombatAction.fromConfig("cancel"));
    assertEquals(PvPCombatAction.DENY, PvPCombatAction.fromConfig(null));
    assertEquals(PvPCombatAction.DENY, PvPCombatAction.fromConfig(""));
    assertEquals(PvPCombatAction.DENY, PvPCombatAction.fromConfig("nonsense"));
  }
}
