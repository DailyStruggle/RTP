package io.github.dailystruggle.rtp.common.playerData;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RTP-71: move-cancel grace opens, expires and clears per player")
class MoveCancelGraceTest {

  @Test
  @DisplayName("a grant is active for its player only, and clear closes it")
  void grantIsPerPlayerAndClears() {
    UUID rifted = UUID.randomUUID();
    UUID other = UUID.randomUUID();

    MoveCancelGrace.grant(rifted, 60_000L);

    assertTrue(MoveCancelGrace.isActive(rifted));
    assertFalse(MoveCancelGrace.isActive(other));
    MoveCancelGrace.clear(rifted);
    assertFalse(MoveCancelGrace.isActive(rifted));
  }

  @Test
  @DisplayName("an expired grant is inactive, so a missed clear cannot disable cancelDistance")
  void expiredGrantIsInactive() throws InterruptedException {
    UUID id = UUID.randomUUID();
    MoveCancelGrace.grant(id, 1L);
    Thread.sleep(5L);
    assertFalse(MoveCancelGrace.isActive(id));
  }

  @Test
  @DisplayName("non-positive durations and null ids grant nothing")
  void invalidGrantsAreIgnored() {
    UUID id = UUID.randomUUID();
    MoveCancelGrace.grant(id, 0L);
    MoveCancelGrace.grant(null, 1_000L);
    assertFalse(MoveCancelGrace.isActive(id));
    assertFalse(MoveCancelGrace.isActive(null));
  }
}
