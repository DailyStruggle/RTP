package io.github.dailystruggle.rtp.fabric.claims;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Failure policy for reflective mod claim checkers (REQ-RTP-S-003).
 *
 * <p>An API-shape failure (missing class/method, linkage error, argument mismatch on the reflective
 * call itself) means the mod API is absent or incompatible: the integration disables itself and
 * reports "not claimed". An exception thrown inside the mod is a per-call failure: the location is
 * rejected and the integration stays active. Per-call warnings are throttled per integration.
 */
final class ModClaimCheckFailure {
  private ModClaimCheckFailure() {}

  static final long LOG_INTERVAL_MILLIS = 60_000L;
  private static final int MAX_UNWRAP = 8;
  private static final Map<String, AtomicLong> lastLogged = new ConcurrentHashMap<>();

  /**
   * @return the "in a claim" verdict: {@code false} on incompatibility ({@code disable} run),
   *     {@code true} otherwise (location rejected)
   */
  static boolean handle(String integration, Throwable t, Runnable disable) {
    if (isIncompatibility(t)) {
      disable.run();
      RTP.log(Level.SEVERE, "[RTP] " + integration + " API is missing or incompatible. Disabling the "
          + integration + " claim integration for this session.", t);
      return false;
    }
    long now = System.currentTimeMillis();
    AtomicLong last = lastLogged.computeIfAbsent(integration, k -> new AtomicLong(Long.MIN_VALUE));
    long prev = last.get();
    if ((prev == Long.MIN_VALUE || now - prev >= LOG_INTERVAL_MILLIS) && last.compareAndSet(prev, now)) {
      RTP.log(Level.WARNING, "[RTP] " + integration + " claim check failed; rejecting the location"
          + " (fail closed per REQ-RTP-S-003).", t);
    }
    return true;
  }

  /** True when {@code t} signals an API-shape mismatch rather than an error raised inside the mod. */
  static boolean isIncompatibility(Throwable t) {
    if (!(t instanceof InvocationTargetException)) {
      // Thrown by the reflective call itself (e.g. wrong argument types), not by the mod.
      if (t instanceof IllegalArgumentException) return true;
      return t instanceof LinkageError || t instanceof ReflectiveOperationException;
    }
    Throwable c = t;
    for (int i = 0; i < MAX_UNWRAP && c instanceof InvocationTargetException; i++) {
      Throwable cause = c.getCause();
      if (cause == null) return false;
      c = cause;
    }
    if (c instanceof InvocationTargetException) return false;
    return c instanceof LinkageError;
  }
}
