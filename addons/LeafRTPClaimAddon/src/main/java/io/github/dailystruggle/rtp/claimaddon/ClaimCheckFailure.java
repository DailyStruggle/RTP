package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Shared failure policy for claim checkers (REQ-RTP-S-003).
 *
 * <p>An API-shape failure (missing class/method, linkage error) means the host plugin is absent
 * or incompatible: the integration disables itself for the session and reports "not claimed".
 * Any other error is a per-call failure: the location is rejected (fail closed) and the
 * integration stays active, so a transient or world-specific error never silently turns off claim
 * protection for the whole server. Per-call warnings are throttled per integration.
 */
final class ClaimCheckFailure {
  private ClaimCheckFailure() {}

  static final long LOG_INTERVAL_MILLIS = 60_000L;
  private static final int MAX_UNWRAP = 8;

  private static final Map<String, AtomicLong> lastLogged = new ConcurrentHashMap<>();
  private static final Map<String, AtomicLong> suppressed = new ConcurrentHashMap<>();

  /**
   * Resolve a checker failure.
   *
   * @param integration display name of the claim plugin
   * @param t the failure
   * @param disable invoked once when the failure is an API incompatibility
   * @return the "in a claim" verdict: {@code false} on incompatibility (integration disabled),
   *     {@code true} otherwise (location rejected)
   */
  static boolean handle(String integration, Throwable t, Runnable disable) {
    if (isIncompatibility(t)) {
      disable.run();
      RTP.log(
          Level.SEVERE,
          "[RTP] " + integration + " API is missing or incompatible. Disabling the "
              + integration + " claim integration for this session.",
          t);
      return false;
    }
    logThrottled(integration, t);
    return true;
  }

  /** True when {@code t} (after unwrapping reflective invocation) signals an API-shape mismatch. */
  static boolean isIncompatibility(Throwable t) {
    Throwable c = t;
    for (int i = 0; i < MAX_UNWRAP && c instanceof InvocationTargetException; i++) {
      Throwable cause = c.getCause();
      if (cause == null) return false;
      c = cause;
    }
    if (c instanceof InvocationTargetException) return false;
    return c instanceof LinkageError || c instanceof ReflectiveOperationException;
  }

  private static void logThrottled(String integration, Throwable t) {
    long now = System.currentTimeMillis();
    AtomicLong last = lastLogged.computeIfAbsent(integration, k -> new AtomicLong(Long.MIN_VALUE));
    AtomicLong skipped = suppressed.computeIfAbsent(integration, k -> new AtomicLong());
    long prev = last.get();
    if (prev != Long.MIN_VALUE && now - prev < LOG_INTERVAL_MILLIS) {
      skipped.incrementAndGet();
      return;
    }
    if (!last.compareAndSet(prev, now)) {
      skipped.incrementAndGet();
      return;
    }
    long n = skipped.getAndSet(0);
    RTP.log(
        Level.WARNING,
        "[RTP] " + integration + " claim check failed; rejecting the location (fail closed per"
            + " REQ-RTP-S-003)."
            + (n > 0 ? " " + n + " similar failure(s) suppressed since the last report." : ""),
        t);
  }
}
