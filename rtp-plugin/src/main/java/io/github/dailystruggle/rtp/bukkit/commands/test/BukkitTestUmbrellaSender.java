package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaSender;
import io.github.dailystruggle.rtp.spigot.tools.SendMessage;
import java.util.UUID;
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
 * {@code RTPBukkitPlugin} during {@code onEnable}; see
 * {@code docs/dev/scratch/CHECKLIST-fabric-rtp-test-full.md} Phase 1.4.
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
}
