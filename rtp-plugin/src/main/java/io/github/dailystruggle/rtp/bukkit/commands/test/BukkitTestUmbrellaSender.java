package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaSender;
import io.github.dailystruggle.rtp.bukkitplatform.tools.SendMessage;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Bukkit-family implementation of {@link TestUmbrellaSender}. Routes
 * caller-facing log lines through {@link SendMessage#log(Level, String)}
 * so existing color, interceptor, and minimum-level handling is reused
 * unchanged, and resolves a caller {@link UUID} to a display name via
 * {@link RTP#serverAccessor} (the same path
 * {@code TestFullCmd#resolveName(UUID)} used before the Phase 1 SPI
 * extraction).
 *
 * <p>Installed onto {@code RTP.testUmbrellaContext} from
 * {@code RTPBukkitPlugin} during {@code onEnable}.
 */
public final class BukkitTestUmbrellaSender implements TestUmbrellaSender {

  @Override
  public void log(Level level, String message) {
    SendMessage.log(level, message);
  }

  @Override
  public String resolveCallerName(UUID callerId) {
    if (callerId == null) {
      return RTP.serverId.toString();
    }
    try {
      var player = RTP.serverAccessor.getPlayer(callerId);
      if (player != null) {
        String name = player.name();
        if (name != null) return name;
      }
    } catch (Throwable ignored) {
      // fall through to UUID fallback so S-004 unknown-player paths
      // still fire loudly downstream
    }
    return callerId.toString();
  }

  /**
   * Delegates to {@link SendMessage#addInterceptor(Consumer)} so the
   * {@code TestFullCmd} {@code FullAudit} observes every caller-facing
   * line emitted through the Bukkit sender for the duration of an
   * umbrella sweep (REQ-RTP-S-004 audit channel).
   */
  @Override
  public void addAuditInterceptor(Consumer<String> interceptor) {
    SendMessage.addInterceptor(interceptor);
  }

  /**
   * Delegates to {@link SendMessage#removeInterceptor(Consumer)}; paired
   * with {@link #addAuditInterceptor(Consumer)} on every umbrella exit
   * path (normal completion, exception, or final-step early-return).
   */
  @Override
  public void removeAuditInterceptor(Consumer<String> interceptor) {
    SendMessage.removeInterceptor(interceptor);
  }
}
