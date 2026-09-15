package io.github.dailystruggle.rtp.neoforge.commands.test;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaSender;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * NeoForge implementation of {@link TestUmbrellaSender}.
 *
 * <p>Routes caller-facing log lines through {@link RTP#log(Level, String)}
 * and delivers to any active audit interceptors (REQ-RTP-S-004 audit channel).
 * Resolves caller {@link UUID} to a display name via {@link RTP#serverAccessor}.
 *
 * <p>Installed onto {@code RTP.testUmbrellaContext} during NeoForge server setup.
 */
public final class NeoForgeTestUmbrellaSender implements TestUmbrellaSender {

  private final List<Consumer<String>> interceptors = new CopyOnWriteArrayList<>();

  @Override
  public void log(Level level, String message) {
    if (message == null) return;
    for (Consumer<String> interceptor : interceptors) {
      try {
        interceptor.accept(message);
      } catch (Throwable ignored) {
        // best-effort delivery to interceptor
      }
    }
    RTP.log(level, message);
  }

  @Override
  public String resolveCallerName(UUID callerId) {
    if (callerId == null) {
      return RTP.serverId.toString();
    }
    try {
      if (RTP.serverAccessor != null) {
        RTPPlayer player = RTP.serverAccessor.getPlayer(callerId);
        if (player != null) {
          String name = player.name();
          if (name != null) return name;
        }
      }
    } catch (Throwable ignored) {
      // fall through to UUID fallback so S-004 unknown-player paths
      // still fire loudly downstream
    }
    return callerId.toString();
  }

  @Override
  public void addAuditInterceptor(Consumer<String> interceptor) {
    if (interceptor != null) {
      interceptors.add(interceptor);
    }
  }

  @Override
  public void removeAuditInterceptor(Consumer<String> interceptor) {
    if (interceptor != null) {
      interceptors.remove(interceptor);
    }
  }
}
