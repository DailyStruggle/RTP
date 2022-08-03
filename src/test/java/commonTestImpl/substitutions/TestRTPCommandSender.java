package commonTestImpl.substitutions;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;

import java.util.UUID;
import java.util.logging.Level;

public class TestRTPCommandSender implements RTPCommandSender {
    private static final UUID id = UUID.randomUUID();

    @Override
    public UUID uuid() {
        return id;
    }

    @Override
    public boolean hasPermission(String permission) {
        return true;
    }

    @Override
    public void sendMessage(String message) {
        RTP.log(Level.INFO, "received: " + message);
    }
}
