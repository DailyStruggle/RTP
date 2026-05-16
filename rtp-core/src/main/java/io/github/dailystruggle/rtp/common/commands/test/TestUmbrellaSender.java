package io.github.dailystruggle.rtp.common.commands.test;

import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Platform-agnostic sink for the {@code /rtp test ...} umbrella's caller-facing
 * output and for resolving a caller {@link UUID} to a display name without
 * importing any platform package (e.g. {@code org.bukkit.*}) into
 * {@code rtp-core}.
 *
 * <p>This is the Phase 1 SPI extracted from
 * {@code io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage} and
 * {@code TestFullCmd#resolveName(UUID)} so that the umbrella and its
 * cross-platform leaves can move into {@code rtp-core} unchanged. See
 * {@code docs/dev/scratch/CHECKLIST-fabric-rtp-test-full.md} Phase 1.
 *
 * <p>Implementations are wired through {@link TestUmbrellaContext} and stored
 * statically on {@code RTP.testUmbrellaContext}. Per S-006, callers reaching
 * the SPI before core load must observe an {@link IllegalStateException}
 * rather than a silent no-op &mdash; concrete throwers live on the context
 * accessor (see {@link TestUmbrellaContext}).
 */
public interface TestUmbrellaSender {

  /**
   * Emits one line of caller-facing output at the given log level. Mirrors
   * {@code SendMessage.log(Level, String)} on Bukkit; on Fabric this routes
   * through the platform sender + {@code RTP.log} console fallback.
   *
   * @param level   severity of the line; {@link Level#WARNING} and above must
   *                also surface to the server console for S-004 audit
   *                purposes.
   * @param message the line to emit; must not be {@code null}.
   */
  void log(Level level, String message);

  /**
   * Resolves a caller {@link UUID} to a player display name for use in
   * delegated sub-command argument maps. Implementations must return a
   * non-{@code null} fallback (typically {@code callerId.toString()}) when
   * the lookup fails so downstream S-004 unknown-player paths can fire
   * loudly rather than silently.
   *
   * @param callerId the caller; may be {@code null} (callers may pass the
   *                 console / server UUID).
   * @return a non-{@code null} display name or stable fallback string.
   */
  String resolveCallerName(UUID callerId);

  /**
   * Registers an interceptor that observes every caller-facing line emitted
   * through this sender (regardless of {@link Level}) for the duration of an
   * umbrella sweep. Used by {@code TestFullCmd}'s {@code FullAudit} to count
   * warning-level lines across all dispatched subcommands without coupling
   * {@code rtp-core} to the Bukkit {@code SendMessage} interceptor registry.
   *
   * <p>Implementations shall invoke the consumer on the same thread that
   * produced the line (no marshalling) and shall tolerate concurrent
   * registrations from independent callers. Must be paired with
   * {@link #removeAuditInterceptor(Consumer)} on every exit path of the
   * umbrella sweep (REQ-RTP-S-004 cleanup).
   *
   * <p>Default no-op implementation is provided so platform adapters that
   * do not wire an audit channel (e.g. headless tests, future platforms
   * without a console interceptor surface) need not implement it; in that
   * case the umbrella's footer audited-warnings tally is empty, which is
   * the correct degenerate behavior, not a silent failure.
   *
   * @param interceptor the observer of caller-facing lines; must not be
   *                    {@code null}.
   */
  default void addAuditInterceptor(Consumer<String> interceptor) {
    // no-op by default; see Javadoc
  }

  /**
   * Removes an interceptor previously registered via
   * {@link #addAuditInterceptor(Consumer)}. Idempotent; removing an
   * unregistered interceptor is a no-op.
   *
   * @param interceptor the previously-registered observer; must not be
   *                    {@code null}.
   */
  default void removeAuditInterceptor(Consumer<String> interceptor) {
    // no-op by default; see Javadoc on addAuditInterceptor
  }
}
