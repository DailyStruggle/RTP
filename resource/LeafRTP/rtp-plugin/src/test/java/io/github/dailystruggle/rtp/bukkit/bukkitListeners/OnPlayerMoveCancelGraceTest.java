package io.github.dailystruggle.rtp.bukkit.bukkitListeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.world.RTPCoords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Rift addon's fall into its own carved hole tripped {@code cancelDistance} and cancelled
 * every delayed {@code /rtp} (RTP-71). Under move-cancel grace only horizontal movement counts.
 */
@DisplayName("RTP-71: cancelDistance ignores the vertical axis while move-cancel grace is active")
class OnPlayerMoveCancelGraceTest {

  private static final RTPCoords ORIGIN = new RTPCoords("world", 100, 70, -40);
  private static final double CANCEL_DISTANCE_SQUARED = 2.0 * 2.0;

  @Test
  @DisplayName("a 5-block fall cancels without grace and does not cancel with it")
  void fallIsExemptUnderGrace() {
    double without = OnPlayerMove.movedSquared(ORIGIN, "world", 100, 65, -40, false);
    double with = OnPlayerMove.movedSquared(ORIGIN, "world", 100, 65, -40, true);

    assertTrue(without >= CANCEL_DISTANCE_SQUARED, "fall must count when no effect moves the player");
    assertTrue(with < CANCEL_DISTANCE_SQUARED, "rift fall must not cancel");
  }

  @Test
  @DisplayName("walking away still cancels under grace")
  void horizontalMoveStillCancelsUnderGrace() {
    double moved = OnPlayerMove.movedSquared(ORIGIN, "world", 103, 65, -40, true);
    assertEquals(9.0, moved, 1e-9);
    assertTrue(moved >= CANCEL_DISTANCE_SQUARED);
  }

  @Test
  @DisplayName("another world is always out of range")
  void otherWorldIsMaxDistance() {
    assertEquals(Double.MAX_VALUE, OnPlayerMove.movedSquared(ORIGIN, "world_nether", 100, 70, -40, true));
    assertEquals(Double.MAX_VALUE, OnPlayerMove.movedSquared(ORIGIN, null, 100, 70, -40, false));
  }
}
