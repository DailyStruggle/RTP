package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-RTP-S-003: claim checker failures fail closed unless the claim API is incompatible")
class ClaimCheckFailureRuleS003Test {

  @Test
  @DisplayName("Per-call runtime errors reject the location and keep the integration enabled")
  void runtimeErrorsFailClosed() {
    AtomicInteger disabled = new AtomicInteger();
    Throwable[] failures = {
      new NullPointerException("region manager missing"),
      new IllegalStateException("async access"),
      new java.util.ConcurrentModificationException(),
      new InvocationTargetException(new RuntimeException("database down")),
      new InvocationTargetException(null),
      new StackOverflowError()
    };
    for (Throwable t : failures) {
      assertTrue(ClaimCheckFailure.handle("Test", t, disabled::incrementAndGet), t.toString());
    }
    assertEquals(0, disabled.get());
  }

  @Test
  @DisplayName("Missing or incompatible claim API disables the integration and reports not claimed")
  void incompatibilityDisables() {
    Throwable[] failures = {
      new NoClassDefFoundError("com/example/Claims"),
      new ClassNotFoundException("com.example.Claims"),
      new NoSuchMethodException("getClaimAt"),
      new NoSuchMethodError("getClaimAt"),
      new InvocationTargetException(new NoSuchFieldError("instance")),
      new InvocationTargetException(new InvocationTargetException(new IncompatibleClassChangeError()))
    };
    for (Throwable t : failures) {
      AtomicInteger disabled = new AtomicInteger();
      assertFalse(ClaimCheckFailure.handle("Test", t, disabled::incrementAndGet), t.toString());
      assertEquals(1, disabled.get(), t.toString());
    }
  }

  @Test
  @DisplayName("Repeated per-call failures stay fail closed while logging is throttled")
  void repeatedFailuresStayClosed() {
    for (int i = 0; i < 1_000; i++) {
      assertTrue(ClaimCheckFailure.handle("Throttle", new RuntimeException("boom " + i), () -> fail()));
    }
  }
}
