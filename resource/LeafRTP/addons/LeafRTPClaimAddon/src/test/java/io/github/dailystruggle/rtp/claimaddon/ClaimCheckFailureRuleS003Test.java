package io.github.dailystruggle.rtp.claimaddon;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.InvocationTargetException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("REQ-RTP-S-003: claim checker failures on an enabled claim plugin fail closed")
class ClaimCheckFailureRuleS003Test {

  @BeforeEach
  @AfterEach
  void resetReports() {
    ClaimCheckFailure.resetReports();
  }

  @Test
  @DisplayName("Per-call runtime errors reject the location without an incompatibility report")
  void runtimeErrorsFailClosed() {
    Throwable[] failures = {
      new NullPointerException("region manager missing"),
      new IllegalStateException("async access"),
      new java.util.ConcurrentModificationException(),
      new InvocationTargetException(new RuntimeException("database down")),
      new InvocationTargetException(null),
      new StackOverflowError()
    };
    for (Throwable t : failures) {
      assertTrue(ClaimCheckFailure.handle("Test", t), t.toString());
    }
    assertFalse(ClaimCheckFailure.incompatibilityReported("Test"));
  }

  @Test
  @DisplayName("Missing or incompatible claim API rejects the location and is reported once")
  void incompatibilityFailsClosed() {
    Throwable[] failures = {
      new NoClassDefFoundError("com/example/Claims"),
      new ClassNotFoundException("com.example.Claims"),
      new NoSuchMethodException("getClaimAt"),
      new NoSuchMethodError("getClaimAt"),
      new InvocationTargetException(new NoSuchFieldError("instance")),
      new InvocationTargetException(new InvocationTargetException(new IncompatibleClassChangeError()))
    };
    for (Throwable t : failures) {
      ClaimCheckFailure.resetReports();
      assertTrue(ClaimCheckFailure.handle("Test", t), t.toString());
      assertTrue(ClaimCheckFailure.incompatibilityReported("Test"), t.toString());
      // Later calls keep rejecting; the report is not repeated.
      assertTrue(ClaimCheckFailure.handle("Test", t), t.toString());
    }
    assertFalse(ClaimCheckFailure.incompatibilityReported("Other"));
  }

  @Test
  @DisplayName("Repeated per-call failures stay fail closed while logging is throttled")
  void repeatedFailuresStayClosed() {
    for (int i = 0; i < 1_000; i++) {
      assertTrue(ClaimCheckFailure.handle("Throttle", new RuntimeException("boom " + i)));
    }
  }
}
