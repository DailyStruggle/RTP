package io.github.dailystruggle.rtp.common.commands.test;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Platform-agnostic sink for the {@code /rtp test ...} umbrella's caller-facing
 * output and for resolving a caller {@link UUID} to a display name without
 * importing any platform package (e.g. {@code org.bukkit.*}) into
 * {@code rtp-core}.
 *
 * <p>This is the Phase 1 SPI extracted from
 * {@code io.github.dailystruggle.rtp.spigot.tools.SendMessage} and
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
}
