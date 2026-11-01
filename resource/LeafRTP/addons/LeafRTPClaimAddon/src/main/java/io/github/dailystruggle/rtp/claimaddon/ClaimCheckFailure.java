package io.github.dailystruggle.rtp.claimaddon;

import io.github.dailystruggle.rtp.common.RTP;
import java.lang.reflect.InvocationTargetException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Shared failure policy for claim checkers (REQ-RTP-S-003).
 *
 * <p>Checkers run only while the host plugin is enabled (gated at registration, and per call by
 * the reflective checkers), so every failure means an enabled claim plugin could not answer: the
 * location is rejected (fail closed). An API-shape failure (missing class/method, linkage error)
 * means the installed plugin does not match the API the checker was written against; it is logged
 * once per integration at SEVERE, and destinations stay rejected until the plugin is updated or
 * its {@code integrations.yml} flag is turned off. Other errors are per-call failures, logged at
 * WARNING and throttled per integration.
 */
final class ClaimCheckFailure {
  private ClaimCheckFailure() {}

  static final long LOG_INTERVAL_MILLIS = 60_000L;
  private static final int MAX_UNWRAP = 8;

  private static final Map<String, AtomicLong> lastLogged = new ConcurrentHashMap<>();
  private static final Map<String, AtomicLong> suppressed = new ConcurrentHashMap<>();
  private static final Set<String> incompatibleReported = ConcurrentHashMap.newKeySet();

  /**
   * Resolve a checker failure. Precondition: the host plugin is enabled.
   *
   * @param integration display name of the claim plugin
   * @param t the failure
   * @return the "in a claim" verdict, always {@code true} (location rejected)
   */
  static boolean handle(String integration, Throwable t) {
    if (isIncompatibility(t)) {
      if (incompatibleReported.add(integration)) {
        RTP.log(
            Level.SEVERE,
            "[RTP] " + integration + " is enabled but its API is missing or incompatible with"
                + " this LeafRTPClaimAddon. Rejecting every destination while it is enabled (fail"
                + " closed per REQ-RTP-S-003); update " + integration + " or turn off its"
                + " integrations.yml flag. Reported once until the next reload.",
            t);
      }
      return true;
    }
    logThrottled(integration, t);
    return true;
  }

  /** True once an API incompatibility has been logged for {@code integration}. */
  static boolean incompatibilityReported(String integration) {
    return incompatibleReported.contains(integration);
  }

  /** Re-arms the once-per-integration reports; called on reload. */
  static void resetReports() {
    incompatibleReported.clear();
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
