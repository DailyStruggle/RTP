package io.github.dailystruggle.rtp.common.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for {@code /rtp centerx=~} relative-coordinate resolution.
 * Verifies {@link RTPCmd#resolveRelativeCoordinate(String, long)}.
 */
class RelativeCoordinateResolutionTest {

  @Test
  @DisplayName("~ resolves to the player's base coordinate")
  void bareTildeIsBase() {
    assertEquals(123L, RTPCmd.resolveRelativeCoordinate("~", 123L));
    assertEquals(-50L, RTPCmd.resolveRelativeCoordinate("~", -50L));
  }

  @Test
  @DisplayName("~<n> applies a positive or negative offset to the base")
  void tildeWithOffset() {
    assertEquals(110L, RTPCmd.resolveRelativeCoordinate("~10", 100L));
    assertEquals(95L, RTPCmd.resolveRelativeCoordinate("~-5", 100L));
    assertEquals(100L, RTPCmd.resolveRelativeCoordinate("~0", 100L));
  }

  @Test
  @DisplayName("-~ and -~<n> negate the base coordinate")
  void negativeTilde() {
    assertEquals(-100L, RTPCmd.resolveRelativeCoordinate("-~", 100L));
    assertEquals(-90L, RTPCmd.resolveRelativeCoordinate("-~10", 100L));
  }

  @Test
  @DisplayName("an absolute (tilde-free) token parses verbatim")
  void absoluteToken() {
    assertEquals(2000L, RTPCmd.resolveRelativeCoordinate("2000", 100L));
    assertEquals(-7L, RTPCmd.resolveRelativeCoordinate("-7", 100L));
  }

  @Test
  @DisplayName("a malformed offset throws NumberFormatException (caller falls back to base)")
  void malformedOffsetThrows() {
    assertThrows(NumberFormatException.class, () -> RTPCmd.resolveRelativeCoordinate("~abc", 0L));
  }

  @Test
  @DisplayName("the token is never coerced to the boolean false")
  void neverBooleanFalse() {
    Object resolved = RTPCmd.resolveRelativeCoordinate("~", 64L);
    assertEquals(Long.class, resolved.getClass());
    assertEquals(64L, resolved);
  }
}
