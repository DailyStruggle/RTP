package io.github.dailystruggle.rtp.fabric.commands.test;

import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.test.TestUmbrellaSender;
import io.github.dailystruggle.rtp.fabric.server.FabricServerAccessor;
import io.github.dailystruggle.rtp.fabric.tools.FabricAnsiText;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Fabric implementation of {@link TestUmbrellaSender}.
 *
 * <p>Routes caller-facing log lines through {@link RTP#log(Level, String)}
 * and delivers to any active audit interceptors (REQ-RTP-S-004 audit channel).
 * Formats console text using {@link FabricAnsiText} for ANSI color output.
 * Resolves caller {@link UUID} to a display name via {@link FabricServerAccessor}.
 *
 * <p>Installed onto {@code RTP.testUmbrellaContext} during Fabric server startup.
 */
public final class FabricTestUmbrellaSender implements TestUmbrellaSender {

    private final RTPServerAccessor accessor;
    private final List<Consumer<String>> interceptors = new CopyOnWriteArrayList<>();

    public FabricTestUmbrellaSender() {
        this(null);
    }

    public FabricTestUmbrellaSender(RTPServerAccessor accessor) {
        this.accessor = accessor;
    }

    private RTPServerAccessor getAccessor() {
        if (accessor != null) return accessor;
        return RTP.serverAccessor;
    }

    @Override
    public void log(Level level, String message) {
        if (message == null) return;
        if (level == null) level = Level.INFO;
        for (Consumer<String> interceptor : interceptors) {
            try {
                interceptor.accept(message);
            } catch (Throwable ignored) {
                // best-effort delivery to interceptor
            }
        }
        RTPServerAccessor acc = getAccessor();
        if (acc != null) {
            acc.log(level, message);
        } else {
            String ansi = FabricAnsiText.toAnsiString(message);
            RTP.log(level, ansi);
        }
    }

    @Override
    public String resolveCallerName(UUID callerId) {
        if (callerId == null) {
            return (RTP.serverId != null) ? RTP.serverId.toString() : "CONSOLE";
        }
        try {
            RTPServerAccessor acc = getAccessor();
            if (acc != null) {
                RTPPlayer player = acc.getPlayer(callerId);
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
