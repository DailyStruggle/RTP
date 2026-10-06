package io.github.dailystruggle.rtp.fabric.claims;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-RTP-S-003: mod claim checker failures fail closed unless the mod API is incompatible")
class ModClaimCheckFailureRuleS003Test {

  @Test
  @DisplayName("Errors raised inside the mod reject the location and keep the integration enabled")
  void modErrorsFailClosed() {
    AtomicInteger disabled = new AtomicInteger();
    Throwable[] failures = {
      new InvocationTargetException(new NullPointerException("level missing")),
      new InvocationTargetException(new IllegalArgumentException("bad dimension")),
      new InvocationTargetException(new IllegalStateException("async access")),
      new InvocationTargetException(null),
      new RuntimeException("boom"),
      new StackOverflowError()
    };
    for (Throwable t : failures) {
      assertTrue(ModClaimCheckFailure.handle("Test", t, disabled::incrementAndGet), t.toString());
    }
    assertEquals(0, disabled.get());
  }

  @Test
  @DisplayName("Missing or mismatched mod API disables the integration and reports not claimed")
  void incompatibilityDisables() {
    Throwable[] failures = {
      new NoClassDefFoundError("dev/ftb/mods/ftbchunks/api/FTBChunksAPI"),
      new ClassNotFoundException("xaero.pac.common.claims.player.api.PlayerClaimsApi"),
      new IllegalAccessException("isChunkClaimed"),
      new IllegalArgumentException("argument type mismatch"),
      new InvocationTargetException(new NoSuchMethodError("isChunkClaimed"))
    };
    for (Throwable t : failures) {
      AtomicInteger disabled = new AtomicInteger();
      assertFalse(ModClaimCheckFailure.handle("Test", t, disabled::incrementAndGet), t.toString());
      assertEquals(1, disabled.get(), t.toString());
    }
  }
}
