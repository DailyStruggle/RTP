package io.github.dailystruggle.rtp.common.commands.test;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Platform-agnostic sink for {@code /rtp test ...} caller-facing output and caller UUID resolution.
 * Wired through {@link TestUmbrellaContext}.
 */
public interface TestUmbrellaSender {

  /**
   * Emits one line of caller-facing output at the given log level.
   *
   * @param level   severity level; WARNING+ surfaces to server console for S-004 auditing
   * @param message message line to emit
   */
  void log(Level level, String message);

  /**
   * Resolves a caller UUID to a display name for delegated sub-command argument maps.
   *
   * @param callerId caller UUID; may be null for console
   * @return non-null display name or fallback string
   */
  String resolveCallerName(UUID callerId);

  /**
   * Registers an interceptor observing every caller-facing line emitted during an umbrella sweep.
   *
   * @param interceptor line observer
   */
  default void addAuditInterceptor(Consumer<String> interceptor) {
    // no-op by default; see Javadoc
  }

  /**
   * Removes an interceptor previously registered via {@link #addAuditInterceptor(Consumer)}.
   *
   * @param interceptor observer to remove
   */
  default void removeAuditInterceptor(Consumer<String> interceptor) {
    // no-op by default; see Javadoc on addAuditInterceptor
  }
}
